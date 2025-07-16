// src/main/java/me/help/minecraft_store/web/WebServer.java
package me.help.minecraft_store.web;

import com.google.gson.Gson;
import me.clip.placeholderapi.PlaceholderAPI;
import me.help.minecraft_store.AtlasCoreConnector;
import me.help.minecraft_store.data.PlayerProfileData;
import me.help.minecraft_store.payloads.CommandPayload;
import me.help.minecraft_store.payloads.VerificationPayload;
import org.bukkit.Bukkit;
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
        // Ensure Spark runs with the correct class loader
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        try {
            int port = plugin.getConfig().getInt("webhook.port", 4567);
            String secret = plugin.getConfig().getString("webhook.secret");

            if (secret == null || secret.isEmpty() || secret.equals("YOUR_SECRET_KEY_HERE")) {
                plugin.getLogger().severe("CRITICAL: webhook.secret is not set in config.yml. Web server is disabled for security.");
                return;
            }

            Spark.port(port);
            plugin.getLogger().info("Internal web server starting on port " + port);

            setupMiddleware(secret);

            // --- Define All API Endpoints ---
            Spark.get("/", (req, res) -> "AtlasCoreConnector is running");
            Spark.post("/execute-command", this::handleExecuteCommand);
            Spark.post("/player-stats", this::handlePlayerStats);

            // FIX: Added missing endpoints for account verification
            Spark.post("/generate-and-send-code", this::handleGenerateAndSendCode);
            Spark.post("/verify-code", this::handleVerifyCode);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start internal web server", e);
        }
    }

    /**
     * Sets up Spark middleware for CORS and authentication.
     */
    private void setupMiddleware(String secret) {
        // Enable CORS
        Spark.options("/*", (request, response) -> {
            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }
            String accessControlRequestMethod = request.headers("Access-Control-Request-Methods");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }
            return "OK";
        });

        Spark.before((request, response) -> response.header("Access-Control-Allow-Origin", "*"));

        // Authentication middleware to protect endpoints
        Spark.before((req, res) -> {
            // Don't protect the root or OPTIONS requests
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
    }

    /**
     * Handles requests to generate and send a verification code to an online player.
     */
    private String handleGenerateAndSendCode(spark.Request req, spark.Response res) {
        res.type("application/json");
        VerificationPayload payload = gson.fromJson(req.body(), VerificationPayload.class);
        String username = payload != null ? payload.getUsername() : null;

        if (username == null || username.trim().isEmpty()) {
            res.status(400);
            return gson.toJson(Map.of("success", false, "message", "Username is required."));
        }

        Player player = Bukkit.getPlayerExact(username);
        if (player == null || !player.isOnline()) {
            res.status(404);
            return gson.toJson(Map.of("success", false, "message", "Player is not online."));
        }

        // Generate a 6-digit code
        String code = String.format("%06d", random.nextInt(999999));
        plugin.getVerificationCodes().put(player.getUniqueId(), code);

        // Send the code to the player in-game (must be on the main thread)
        new BukkitRunnable() {
            @Override
            public void run() {
                player.sendMessage("§e[AtlasCore] §fYour verification code is: §a§l" + code);
                player.sendMessage("§e[AtlasCore] §fEnter this code on the website to link your account.");
            }
        }.runTask(plugin);

        return gson.toJson(Map.of("success", true, "message", "Code sent to player in-game."));
    }

    /**
     * Handles requests to verify a code and link a Minecraft account.
     */
    private String handleVerifyCode(spark.Request req, spark.Response res) {
        res.type("application/json");
        VerificationPayload payload = gson.fromJson(req.body(), VerificationPayload.class);
        String username = payload != null ? payload.getUsername() : null;
        String code = payload != null ? payload.getCode() : null;

        if (username == null || code == null) {
            res.status(400);
            return gson.toJson(Map.of("success", false, "message", "Username and code are required."));
        }

        // Use OfflinePlayer to get UUID without requiring the player to be online
        OfflinePlayer player = Bukkit.getOfflinePlayer(username);
        UUID playerUUID = player.getUniqueId();

        if (playerUUID == null) {
            res.status(404);
            return gson.toJson(Map.of("success", false, "message", "Player not found."));
        }

        String storedCode = plugin.getVerificationCodes().get(playerUUID);

        if (storedCode != null && storedCode.equals(code)) {
            plugin.getVerificationCodes().remove(playerUUID); // Code is valid, remove it
            res.status(200);
            return gson.toJson(Map.of("success", true, "message", "Verification successful.", "uuid", playerUUID.toString()));
        } else {
            res.status(400);
            return gson.toJson(Map.of("success", false, "message", "Invalid or expired verification code."));
        }
    }

    /**
     * Handles requests to execute a command from the web store.
     */
    private String handleExecuteCommand(spark.Request req, spark.Response res) {
        res.type("application/json");
        CommandPayload payload = gson.fromJson(req.body(), CommandPayload.class);

        if (payload == null || payload.getCommand() == null || payload.getCommand().trim().isEmpty()) {
            res.status(400);
            return gson.toJson(Map.of("success", false, "message", "Invalid payload: Missing command."));
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                String finalCommand = payload.getCommand();
                Map<String, String> playerContext = payload.getPlayerContext();
                if (playerContext != null && playerContext.containsKey("playerName")) {
                    finalCommand = finalCommand.replace("{player}", playerContext.get("playerName"));
                }
                plugin.getLogger().info("Dispatching command: " + finalCommand);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            }
        }.runTask(plugin);

        return gson.toJson(Map.of("success", true, "message", "Command dispatched."));
    }

    /**
     * Handles requests to fetch a player's profile stats.
     */
    private String handlePlayerStats(spark.Request req, spark.Response res) {
        res.type("application/json");
        Map<String, String> payload = gson.fromJson(req.body(), Map.class);
        String playerUUIDString = payload != null ? payload.get("uuid") : null;

        if (playerUUIDString == null || playerUUIDString.isEmpty()) {
            res.status(400);
            return gson.toJson(Map.of("success", false, "message", "Missing player UUID."));
        }

        try {
            UUID playerUUID = UUID.fromString(playerUUIDString);
            CompletableFuture<String> futureResult = new CompletableFuture<>();

            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
                        if (!offlinePlayer.hasPlayedBefore() && offlinePlayer.getName() == null) {
                            futureResult.complete(gson.toJson(Map.of("success", false, "message", "Player with UUID " + playerUUID + " has not played on this server.")));
                            return;
                        }

                        Map<String, String> liveStats = new HashMap<>();
                        String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown";
                        liveStats.put("player_name", playerName);

                        List<String> placeholders = getPlaceholders();
                        if (!placeholders.isEmpty()) {
                            List<String> parsedValues = PlaceholderAPI.setPlaceholders(offlinePlayer, placeholders);
                            for (int i = 0; i < placeholders.size(); i++) {
                                String key = placeholders.get(i).replace("%", "").toLowerCase();
                                String value = parsedValues.get(i);
                                // FIX: Only save if the placeholder returned a meaningful value, it's not the placeholder itself, AND it's not empty
                                if (!value.equals(placeholders.get(i)) && !value.isEmpty()) {
                                    liveStats.put(key, value);
                                }
                            }
                        }

                        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                            String balancePlaceholder = "%vault_eco_balance%";
                            String rawBalance = PlaceholderAPI.setPlaceholders(offlinePlayer, balancePlaceholder);
                            // FIX: Only save if the placeholder returned a meaningful value, it's not the placeholder itself, AND it's not empty
                            if (rawBalance != null && !rawBalance.equals(balancePlaceholder) && !rawBalance.isEmpty()) {
                                liveStats.put("vault_eco_balance", rawBalance.replace(",", ""));
                            }
                        }

                        PlayerProfileData cachedProfile = null;
                        try {
                            cachedProfile = plugin.getPlayerProfileService().loadPlayerProfile(playerUUID).get();
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not load cached profile for " + playerName + ". Error: " + e.getMessage());
                        }

                        Map<String, String> finalStats = new HashMap<>();
                        if (cachedProfile != null && cachedProfile.getStats() != null) {
                            finalStats.putAll(cachedProfile.getStats());
                        }
                        finalStats.putAll(liveStats); // <---- Live stats are put AFTER cached stats

                        if (offlinePlayer.isOnline()) {
                            PlayerProfileData profileToSave = new PlayerProfileData(playerUUID, playerName, finalStats, System.currentTimeMillis());
                            plugin.getPlayerProfileService().savePlayerProfile(profileToSave);
                        }

                        futureResult.complete(gson.toJson(Map.of("success", true, "stats", finalStats)));

                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Error processing player stats for UUID: " + playerUUID, e);
                        futureResult.complete(gson.toJson(Map.of("success", false, "message", "Internal plugin error during stat processing.")));
                    }
                }
            }.runTask(plugin);

            return futureResult.get();

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to handle /player-stats request: " + e.getMessage(), e);
            return gson.toJson(Map.of("success", false, "message", "Invalid UUID or fatal server error."));
        }
    }

    /**
     * Gathers all relevant PlaceholderAPI placeholders.
     */
    private List<String> getPlaceholders() {
        List<String> placeholders = new ArrayList<>();
        if (Bukkit.getPluginManager().isPluginEnabled("Fabled")) {
            placeholders.addAll(Arrays.asList(
                    "%fabled_player_class_mainclass%", "%fabled_default_currentlevel%", "%fabled_player_races_class%",
                    "%fabled_health%", "%fabled_max_health%", "%fabled_mana%", "%fabled_max_mana%"
            ));
        }
        if (Bukkit.getPluginManager().isPluginEnabled("AuraSkills")) {
            placeholders.addAll(Arrays.asList(
                    "%auraskills_power%", "%auraskills_farming%", "%auraskills_foraging%",
                    "%auraskills_mining%", "%auraskills_fishing%", "%auraskills_excavation%",
                    "%auraskills_archery%", "%auraskills_defense%", "%auraskills_fighting%",
                    "%auraskills_endurance%", "%auraskills_agility%", "%auraskills_alchemy%",
                    "%auraskills_enchanting%", "%auraskills_sorcery%", "%auraskills_healing%",
                    "%auraskills_forging%"
            ));
        }
        placeholders.add("%statistic_player_kills%");
        placeholders.add("%statistic_deaths%");
        return placeholders;
    }

    /**
     * Stops the Spark web server.
     */
    public void stop() {
        Spark.stop();
        plugin.getLogger().info("Internal web server stopped.");
    }
}