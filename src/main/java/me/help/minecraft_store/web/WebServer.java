// src/main/java/me/help/minecraft_store/web/WebServer.java
package me.help.minecraft_store.web;

import com.google.gson.Gson;
import me.clip.placeholderapi.PlaceholderAPI;
import me.help.minecraft_store.AtlasCoreConnector;
import me.help.minecraft_store.data.PlayerProfileData;
import me.help.minecraft_store.payloads.CommandPayload;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        try {
            int port = plugin.getConfig().getInt("webhook.port", 4567);
            String secret = plugin.getConfig().getString("webhook.secret");

            if (secret == null || secret.isEmpty() || secret.equals("YOUR_SECRET_KEY_HERE")) {
                plugin.getLogger().severe("CRITICAL: webhook.secret is not set. Web server is disabled for security.");
                return;
            }

            Spark.port(port);
            plugin.getLogger().info("Internal web server starting on port " + port);

            setupMiddleware(secret);

            Spark.get("/", (req, res) -> "AtlasCoreConnector is running");

            Spark.post("/execute-command", this::handleExecuteCommand);

            // **REWRITTEN ENDPOINT LOGIC**
            Spark.post("/player-stats", this::handlePlayerStats);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start internal web server", e);
        }
    }

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

            // Create a CompletableFuture to handle the result from the Bukkit thread.
            CompletableFuture<String> futureResult = new CompletableFuture<>();

            // Run the entire logic on the main server thread to ensure API safety.
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
                        if (!offlinePlayer.hasPlayedBefore() && offlinePlayer.getName() == null) {
                            futureResult.complete(gson.toJson(Map.of("success", false, "message", "Player with UUID " + playerUUID + " has not played on this server.")));
                            return;
                        }

                        // STEP 1: Get live data from the game. This is the priority.
                        Map<String, String> liveStats = new HashMap<>();
                        String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown";
                        liveStats.put("player_name", playerName);

                        // Build and parse all placeholders
                        List<String> placeholders = getPlaceholders();
                        if (!placeholders.isEmpty()) {
                            List<String> parsedValues = PlaceholderAPI.setPlaceholders(offlinePlayer, placeholders);
                            for (int i = 0; i < placeholders.size(); i++) {
                                String key = placeholders.get(i).replace("%", "").toLowerCase();
                                String value = parsedValues.get(i);
                                if (!value.equals(placeholders.get(i))) { // Check if placeholder was replaced
                                    liveStats.put(key, value);
                                }
                            }
                        }

                        // Handle Vault balance separately
                        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                            String balancePlaceholder = "%vault_eco_balance%";
                            String rawBalance = PlaceholderAPI.setPlaceholders(offlinePlayer, balancePlaceholder);
                            if (rawBalance != null && !rawBalance.equals(balancePlaceholder)) {
                                liveStats.put("vault_eco_balance", rawBalance.replace(",", ""));
                            }
                        }

                        // STEP 2: Attempt to load the cached profile to enrich the data.
                        PlayerProfileData cachedProfile = null;
                        try {
                            cachedProfile = plugin.getPlayerProfileService().loadPlayerProfile(playerUUID).get(); // .get() is safe here inside a Bukkit task
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not load cached profile for " + playerName + ". Proceeding without it. Error: " + e.getMessage());
                        }

                        // STEP 3: Combine the data, prioritizing live data.
                        Map<String, String> finalStats = new HashMap<>();
                        if (cachedProfile != null && cachedProfile.getStats() != null) {
                            finalStats.putAll(cachedProfile.getStats());
                        }
                        finalStats.putAll(liveStats); // Live stats always overwrite cached stats.

                        // STEP 4: Asynchronously save the updated profile back to Firebase if the player is online.
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

            // Wait for the Bukkit task to complete and return its result.
            return futureResult.get();

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to handle /player-stats request: " + e.getMessage(), e);
            return gson.toJson(Map.of("success", false, "message", "Invalid UUID or fatal server error."));
        }
    }

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

    private void setupMiddleware(String secret) {
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
    }

    public void stop() {
        Spark.stop();
        plugin.getLogger().info("Internal web server stopped.");
    }
}