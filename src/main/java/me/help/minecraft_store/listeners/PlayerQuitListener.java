// src/main/java/me/help/minecraft_store/listeners/PlayerQuitListener.java
package me.help.minecraft_store.listeners;

import me.clip.placeholderapi.PlaceholderAPI;
import me.help.minecraft_store.AtlasCoreConnector;
import me.help.minecraft_store.data.PlayerProfileData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Level;

public class PlayerQuitListener implements Listener {

    private final AtlasCoreConnector plugin;

    public PlayerQuitListener(AtlasCoreConnector plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles the PlayerQuitEvent to save the player's profile data to Firebase.
     * This method now waits for the PlayerProfileService to be ready before attempting to save data.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Wait for the PlayerProfileService to signal it's ready before proceeding.
        // This prevents errors if a player quits before Firebase has initialized.
        plugin.getPlayerProfileService().onReady().thenRun(() -> {
            // Run the data collection and saving logic in an asynchronous task to avoid blocking the server.
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        Map<String, String> stats = new HashMap<>();
                        List<String> placeholdersToParse = new ArrayList<>();

                        // Always save the player's current name.
                        stats.put("player_name", player.getName());

                        // Collect Fabled stats if the plugin is enabled.
                        if (plugin.getServer().getPluginManager().isPluginEnabled("Fabled")) {
                            placeholdersToParse.addAll(Arrays.asList(
                                    "%fabled_player_class_mainclass%", "%fabled_default_currentlevel%", "%fabled_player_races_class%",
                                    "%fabled_health%", "%fabled_max_health%", "%fabled_mana%", "%fabled_max_mana%"
                            ));
                        }
                        // Collect AuraSkills stats if the plugin is enabled.
                        if (plugin.getServer().getPluginManager().isPluginEnabled("AuraSkills")) {
                            placeholdersToParse.addAll(Arrays.asList(
                                    "%auraskills_power%", "%auraskills_farming%", "%auraskills_foraging%",
                                    "%auraskills_mining%", "%auraskills_fishing%", "%auraskills_excavation%",
                                    "%auraskills_archery%", "%auraskills_defense%", "%auraskills_fighting%",
                                    "%auraskills_endurance%", "%auraskills_agility%", "%auraskills_alchemy%",
                                    "%auraskills_enchanting%", "%auraskills_sorcery%", "%auraskills_healing%",
                                    "%auraskills_forging%"
                            ));
                        }

                        // Parse all collected placeholders using PlaceholderAPI.
                        for (String placeholder : placeholdersToParse) {
                            String value = PlaceholderAPI.setPlaceholders(player, placeholder);
                            String key = placeholder.replace("%", "").toLowerCase();
                            // Only save if the placeholder returned a meaningful value.
                            if (!value.equals(placeholder)) {
                                stats.put(key, value);
                            }
                        }

                        // Create a new data object with the collected stats.
                        PlayerProfileData profileData = new PlayerProfileData(
                                playerUUID,
                                player.getName(),
                                stats,
                                System.currentTimeMillis()
                        );

                        // Save the profile data using the service.
                        plugin.getPlayerProfileService().savePlayerProfile(profileData);

                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Error saving player data for " + player.getName() + " on quit.", e);
                    }
                }
            }.runTaskAsynchronously(plugin);
        }).exceptionally(ex -> {
            // This block runs if the Firebase initialization fails.
            plugin.getLogger().log(Level.WARNING, "Could not save player profile on quit because Firebase is not initialized.", ex);
            return null;
        });
    }
}
