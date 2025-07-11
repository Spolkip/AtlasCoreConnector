package me.help.minecraft_store.web;

import com.google.gson.Gson;
import me.clip.placeholderapi.PlaceholderAPI;
import me.help.minecraft_store.AtlasCoreConnector;
import me.help.minecraft_store.payloads.CommandPayload;
import me.help.minecraft_store.payloads.VerificationPayload;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import spark.Spark;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class WebServer {

    private final AtlasCoreConnector plugin;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();

    public WebServer(AtlasCoreConnector plugin) {
        this.plugin = plugin;
    }

    public void start() {
        try {
            int port = plugin.getConfig().getInt("webhook.port", 4567);
            String secret = plugin.getConfig().getString("webhook.secret");

            if (secret == null || secret.isEmpty() || secret.equals("YOUR_SECRET_KEY_HERE")) {
                plugin.getLogger().severe("CRITICAL: webhook.secret is not set. Web server is disabled for security.");
                return;
            }

            Spark.port(port);
            plugin.getLogger().info("Internal web server starting on port " + port);

            // --- CORS Handling ---
            Spark.options("/*", (request, response) -> {
                String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
                if (accessControlRequestHeaders != null) {
                    response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
                }
                String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
                if (accessControlRequestMethod != null) {
                    response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
                }
                return "OK";
            });

            Spark.before((request, response) -> {
                response.header("Access-Control-Allow-Origin", "*");
            });

            // --- Authentication Filter ---
            Spark.before((req, res) -> {
                if (req.pathInfo().equals("/") || req.requestMethod().equals("OPTIONS")) {
                    return;
                }
                String authHeader = req.headers("Authorization");
                String expectedHeader = "Bearer " + secret;
                if (!expectedHeader.equals(authHeader)) {
                    plugin.getLogger().warning("Unauthorized request to " + req.pathInfo() + " from IP: " + req.ip());
                    Spark.halt(401, gson.toJson(Map.of("success", false, "message", "Unauthorized.")));
                }
            });

            Spark.get("/", (req, res) -> "AtlasCoreConnector is running");

            // --- Command Execution Endpoint ---
            Spark.post("/execute-command", (req, res) -> {
                res.type("application/json");
                CommandPayload payload = gson.fromJson(req.body(), CommandPayload.class);

                if (payload == null || payload.getCommand() == null || payload.getCommand().trim().isEmpty()) {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "message", "Invalid payload: Missing command."));
                }

                String commandToExecute = payload.getCommand();
                Map<String, String> playerContext = payload.getPlayerContext();

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        String finalCommand = commandToExecute;

                        // --- START OF SIMPLIFIED FIX ---
                        // Directly replace {player} with the name from the context.
                        // This removes the dependency on PlaceholderAPI for the most critical part.
                        if (playerContext != null && playerContext.containsKey("playerName")) {
                            String playerName = playerContext.get("playerName");
                            if (playerName != null && !playerName.isEmpty()) {
                                finalCommand = finalCommand.replace("{player}", playerName);
                            } else {
                                plugin.getLogger().warning("Command execution skipped: Player name was empty in the context.");
                                return; // Stop if there's no player name to insert.
                            }
                        } else {
                            plugin.getLogger().warning("Command execution skipped: Player context did not contain a playerName.");
                            return;
                        }
                        // --- END OF SIMPLIFIED FIX ---

                        // You can still use PlaceholderAPI for other, more complex placeholders if needed.
                        // This part will now run on a command that already has the player's name in it.
                        if (playerContext.containsKey("uuid")) {
                            try {
                                UUID playerUUID = UUID.fromString(playerContext.get("uuid"));
                                OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
                                if (player.hasPlayedBefore() || player.isOnline()) {
                                    finalCommand = PlaceholderAPI.setPlaceholders(player, finalCommand);
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("Could not parse UUID for additional placeholder replacements: " + playerContext.get("uuid"));
                            }
                        }

                        plugin.getLogger().info("Dispatching final command: " + finalCommand);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                    }
                }.runTask(plugin);

                return gson.toJson(Map.of("success", true, "message", "Command dispatched for execution."));
            });

            // --- Other Endpoints (Verification, Stats, etc.) ---

            Spark.post("/generate-and-send-code", (req, res) -> {
                res.type("application/json");
                VerificationPayload payload = gson.fromJson(req.body(), VerificationPayload.class);

                if (payload == null || payload.getUsername() == null) {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "status", 400, "message", "Invalid payload: Missing username"));
                }

                CompletableFuture<String> future = new CompletableFuture<>();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Player player = Bukkit.getPlayerExact(payload.getUsername());
                        if (player == null || !player.isOnline()) {
                            future.complete(gson.toJson(Map.of("success", false, "status", 404, "message", "Player not found or not online")));
                            return;
                        }
                        String code = String.format("%06d", random.nextInt(999999));
                        plugin.getVerificationCodes().put(player.getUniqueId(), code);
                        player.sendMessage(ChatColor.GREEN + "Your AtlasCore verification code is: " + ChatColor.GOLD + code);
                        player.sendMessage(ChatColor.GRAY + "This code will expire in 5 minutes.");
                        future.complete(gson.toJson(Map.of("success", true, "status", 200, "message", "Verification code sent to player.")));
                    }
                }.runTask(plugin);

                String resultJson = future.get();
                Map<String, Object> resultMap = gson.fromJson(resultJson, Map.class);
                res.status(((Double) resultMap.get("status")).intValue());
                return resultJson;
            });

            Spark.post("/verify-code", (req, res) -> {
                res.type("application/json");
                VerificationPayload payload = gson.fromJson(req.body(), VerificationPayload.class);

                if (payload == null || payload.getUsername() == null || payload.getCode() == null) {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "message", "Invalid payload: Missing username or code"));
                }

                OfflinePlayer player = Bukkit.getOfflinePlayer(payload.getUsername());
                if (!player.hasPlayedBefore() && !player.isOnline()) {
                    res.status(404);
                    return gson.toJson(Map.of("success", false, "message", "Player has never played on this server"));
                }

                UUID playerUUID = player.getUniqueId();
                String storedCode = plugin.getVerificationCodes().get(playerUUID);

                if (storedCode != null && storedCode.equals(payload.getCode())) {
                    plugin.getVerificationCodes().remove(playerUUID);
                    return gson.toJson(Map.of("success", true, "uuid", playerUUID.toString(), "message", "Verification successful!"));
                } else {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "message", "Invalid or expired verification code"));
                }
            });

            Spark.post("/player-stats", (req, res) -> {
                res.type("application/json");
                Map<String, String> payload = gson.fromJson(req.body(), Map.class);
                String playerUUIDString = payload != null ? payload.get("uuid") : null;

                if (playerUUIDString == null || playerUUIDString.isEmpty()) {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "message", "Missing player UUID."));
                }

                CompletableFuture<String> future = new CompletableFuture<>();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            UUID playerUUID = UUID.fromString(playerUUIDString);
                            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);

                            if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                                future.complete(gson.toJson(Map.of("success", false, "message", "PlaceholderAPI is not enabled.")));
                                return;
                            }

                            Map<String, String> stats = new HashMap<>();
                            List<String> placeholdersToParse = new ArrayList<>();

                            if (offlinePlayer.getName() != null) {
                                stats.put("player_name", offlinePlayer.getName());
                            }

                            if (Bukkit.getPluginManager().isPluginEnabled("Fabled")) {
                                placeholdersToParse.addAll(Arrays.asList(
                                        "%fabled_player_class_mainclass%", "%fabled_default_currentlevel%", "%fabled_player_races_class%",
                                        "%fabled_health%", "%fabled_max_health%", "%fabled_mana%", "%fabled_max_mana%"
                                ));
                            }
                            if (Bukkit.getPluginManager().isPluginEnabled("AuraSkills")) {
                                placeholdersToParse.addAll(Arrays.asList(
                                        "%auraskills_power%", "%auraskills_farming%", "%auraskills_foraging%",
                                        "%auraskills_mining%", "%auraskills_fishing%", "%auraskills_excavation%",
                                        "%auraskills_archery%", "%auraskills_defense%", "%auraskills_fighting%",
                                        "%auraskills_endurance%", "%auraskills_agility%", "%auraskills_alchemy%",
                                        "%auraskills_enchanting%", "%auraskills_sorcery%", "%auraskills_healing%",
                                        "%auraskills_forging%"
                                ));
                            }

                            for (String placeholder : placeholdersToParse) {
                                String value = PlaceholderAPI.setPlaceholders(offlinePlayer, placeholder);
                                String key = placeholder.replace("%", "").toLowerCase();
                                stats.put(key, value.equals(placeholder) ? "N/A" : value);
                            }

                            future.complete(gson.toJson(Map.of("success", true, "stats", stats)));

                        } catch (Exception e) {
                            plugin.getLogger().log(Level.SEVERE, "Error fetching player stats", e);
                            future.complete(gson.toJson(Map.of("success", false, "message", "Internal plugin error.")));
                        }
                    }
                }.runTask(plugin);
                return future.get();
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start internal web server", e);
        }
    }

    public void stop() {
        Spark.stop();
        plugin.getLogger().info("Internal web server stopped.");
    }
}
