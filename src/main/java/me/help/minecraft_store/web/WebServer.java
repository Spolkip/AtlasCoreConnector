package me.help.minecraft_store.web;

import com.google.gson.Gson;
import me.help.minecraft_store.AtlasCoreConnector;
import me.help.minecraft_store.payloads.CommandPayload; // Import CommandPayload
import me.help.minecraft_store.payloads.VerificationPayload;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import spark.Spark;

import java.security.SecureRandom;
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

            if (secret == null || secret.equals("YOUR_SECRET_KEY_HERE") || secret.isEmpty()) {
                plugin.getLogger().severe("CRITICAL: webhook.secret is not set in config.yml. The plugin will not be secure. Disabling web server.");
                return;
            }

            Spark.port(port);
            plugin.getLogger().info("Internal web server starting on port " + port);

            // General exception handler for Spark
            Spark.exception(Exception.class, (exception, request, response) -> {
                plugin.getLogger().log(Level.SEVERE, "An error occurred while processing a web request", exception);
                response.status(500);
                response.type("application/json");
                response.body(gson.toJson(Map.of("success", false, "message", "An internal plugin error occurred.")));
            });

            // Authentication filter
            Spark.before((req, res) -> {
                // Don't protect the root path if you want a status check endpoint
                if (req.pathInfo().equals("/") || req.pathInfo().equals("/execute-command")) { // Allow /execute-command to be authenticated by its own payload secret
                    return;
                }

                String authHeader = req.headers("Authorization");
                String expectedHeader = "Bearer " + secret;

                if (!expectedHeader.equals(authHeader)) {
                    plugin.getLogger().severe("--- AUTHORIZATION FAILED ---");
                    plugin.getLogger().warning("Request from IP: " + req.ip() + " to " + req.pathInfo());
                    plugin.getLogger().warning("Reason: Invalid 'Authorization' header.");
                    plugin.getLogger().warning("Expected a secret matching the one in config.yml.");
                    plugin.getLogger().warning("Received Header: " + (authHeader != null ? authHeader : "null"));
                    Spark.halt(401, gson.toJson(Map.of("success", false, "message", "Unauthorized. Check that backend's WEBHOOK_SECRET matches the plugin's config.yml.")));
                }
            });

            // Root path for health checks
            Spark.get("/", (req, res) -> {
                res.type("application/json");
                return gson.toJson(Map.of("status", "AtlasCoreConnector is running"));
            });

            // Endpoint to generate and send a verification code
            Spark.post("/generate-and-send-code", (req, res) -> {
                res.type("application/json");
                VerificationPayload payload = gson.fromJson(req.body(), VerificationPayload.class);

                if (payload == null || payload.getUsername() == null) {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "status", 400, "message", "Invalid payload: Missing username"));
                }

                // CompletableFuture allows us to work with the async Bukkit scheduler
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

                // Wait for the Bukkit task to complete and return its result
                String resultJson = future.get(); // This blocks until the task is done
                Map<String, Object> resultMap = gson.fromJson(resultJson, Map.class);
                res.status(((Double) resultMap.get("status")).intValue());
                return resultJson;
            });

            // Endpoint to verify the code
            Spark.post("/verify-code", (req, res) -> {
                res.type("application/json");
                VerificationPayload payload = gson.fromJson(req.body(), VerificationPayload.class);

                if (payload == null || payload.getUsername() == null || payload.getCode() == null) {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "message", "Invalid payload: Missing username or code"));
                }

                // Can use OfflinePlayer here since we don't need to message them
                OfflinePlayer player = Bukkit.getOfflinePlayer(payload.getUsername());
                if (!player.hasPlayedBefore() && !player.isOnline()) {
                    res.status(404);
                    return gson.toJson(Map.of("success", false, "message", "Player has never played on this server"));
                }

                UUID playerUUID = player.getUniqueId();
                String storedCode = plugin.getVerificationCodes().get(playerUUID);

                if (storedCode != null && storedCode.equals(payload.getCode())) {
                    plugin.getVerificationCodes().remove(playerUUID); // Code is one-time use
                    return gson.toJson(Map.of("success", true, "uuid", playerUUID.toString(), "message", "Verification successful!"));
                } else {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "message", "Invalid or expired verification code"));
                }
            });

            // Endpoint to provide server statistics
            Spark.post("/server-stats", (req, res) -> {
                res.type("application/json");
                // The backend sends a secret in the body, verify it
                Map<String, String> payload = gson.fromJson(req.body(), Map.class);
                if (payload == null || !secret.equals(payload.get("secret"))) {
                    res.status(401);
                    return gson.toJson(Map.of("success", false, "message", "Unauthorized access to server stats."));
                }

                // Run on main thread to get accurate player count
                CompletableFuture<String> future = new CompletableFuture<>();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        int onlinePlayers = Bukkit.getOnlinePlayers().size();
                        int maxPlayers = Bukkit.getMaxPlayers();
                        int newPlayersToday = plugin.getNewPlayersToday().get(); // Get current count, don't reset here
                        future.complete(gson.toJson(Map.of(
                                "success", true,
                                "onlinePlayers", onlinePlayers,
                                "maxPlayers", maxPlayers,
                                "newPlayersToday", newPlayersToday
                        )));
                    }
                }.runTask(plugin);
                return future.get(); // Blocks until the data is ready
            });

            // NEW: Endpoint to execute commands from the backend with placeholder support
            Spark.post("/execute-command", (req, res) -> {
                res.type("application/json");
                CommandPayload payload = gson.fromJson(req.body(), CommandPayload.class);

                // Authenticate using the secret from the Authorization header
                String receivedSecret = req.headers("Authorization") != null ? req.headers("Authorization").replace("Bearer ", "") : null;
                if (receivedSecret == null || !receivedSecret.equals(secret)) {
                    plugin.getLogger().severe("--- COMMAND EXECUTION AUTHORIZATION FAILED ---");
                    plugin.getLogger().warning("Request from IP: " + req.ip() + " to /execute-command");
                    plugin.getLogger().warning("Reason: Invalid 'Authorization' header for command execution.");
                    Spark.halt(401, gson.toJson(Map.of("success", false, "message", "Unauthorized to execute command.")));
                }

                if (payload == null || payload.getCommand() == null || payload.getCommand().trim().isEmpty()) {
                    res.status(400);
                    return gson.toJson(Map.of("success", false, "message", "Invalid payload: Missing command."));
                }

                String commandToExecute = payload.getCommand();
                Map<String, String> playerContext = payload.getPlayerContext(); // Get player context

                // Run on main thread to get real-time data for placeholders and execute command
                CompletableFuture<String> future = new CompletableFuture<>();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            String finalCommand = commandToExecute;
                            String targetPlayerName = null;
                            UUID targetPlayerUUID = null;
                            String targetWorldName = "world"; // Default world name

                            if (playerContext != null) {
                                if (playerContext.containsKey("uuid") && playerContext.get("uuid") != null && !playerContext.get("uuid").isEmpty()) {
                                    try {
                                        targetPlayerUUID = UUID.fromString(playerContext.get("uuid"));
                                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetPlayerUUID);
                                        // Use the player's actual name if available, especially if online
                                        if (offlinePlayer.isOnline()) {
                                            targetPlayerName = offlinePlayer.getName();
                                            targetWorldName = ((Player) offlinePlayer).getWorld().getName();
                                        } else {
                                            targetPlayerName = offlinePlayer.getName(); // Get name even if offline
                                        }
                                    } catch (IllegalArgumentException e) {
                                        plugin.getLogger().warning("Invalid UUID provided in playerContext: " + playerContext.get("uuid"));
                                    }
                                } else if (playerContext.containsKey("playerName") && playerContext.get("playerName") != null && !playerContext.get("playerName").isEmpty()) {
                                    targetPlayerName = playerContext.get("playerName");
                                    Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
                                    if (onlinePlayer != null) {
                                        targetWorldName = onlinePlayer.getWorld().getName();
                                    }
                                }
                            }

                            // Replace {player} with the determined player name
                            if (targetPlayerName != null) {
                                finalCommand = finalCommand.replace("{player}", targetPlayerName);
                            } else {
                                // If player name couldn't be determined, log a warning and remove placeholder
                                plugin.getLogger().warning("Could not resolve {player} placeholder for command: " + commandToExecute);
                                finalCommand = finalCommand.replace("{player}", "UNKNOWN_PLAYER"); // Replace with a safe fallback
                            }

                            // Replace {world}
                            finalCommand = finalCommand.replace("{world}", targetWorldName);

                            // Replace {uuid}
                            if (targetPlayerUUID != null) {
                                finalCommand = finalCommand.replace("{uuid}", targetPlayerUUID.toString());
                            } else {
                                finalCommand = finalCommand.replace("{uuid}", "UNKNOWN_UUID"); // Safe fallback
                            }

                            // Replace {username} (web username)
                            if (playerContext != null && playerContext.containsKey("username")) {
                                finalCommand = finalCommand.replace("{username}", playerContext.get("username"));
                            } else {
                                finalCommand = finalCommand.replace("{username}", "UNKNOWN_WEB_USERNAME"); // Safe fallback
                            }

                            // Replace server-wide placeholders
                            finalCommand = finalCommand.replace("{onlinePlayers}", String.valueOf(Bukkit.getOnlinePlayers().size()));
                            finalCommand = finalCommand.replace("{maxPlayers}", String.valueOf(Bukkit.getMaxPlayers()));
                            finalCommand = finalCommand.replace("{newPlayersToday}", String.valueOf(plugin.getNewPlayersToday().get()));


                            plugin.getLogger().info("Executing command: " + finalCommand);
                            // Execute the command as console
                            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                            if (success) {
                                future.complete(gson.toJson(Map.of("success", true, "message", "Command executed successfully.")));
                            } else {
                                future.complete(gson.toJson(Map.of("success", false, "message", "Command dispatch failed. Check server logs for details.")));
                            }
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.SEVERE, "Error executing command: " + commandToExecute, e);
                            future.complete(gson.toJson(Map.of("success", false, "message", "Internal plugin error during command execution: " + e.getMessage())));
                        }
                    }
                }.runTask(plugin); // Run on the main thread

                return future.get(); // Blocks until the command is executed
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
