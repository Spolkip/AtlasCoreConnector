// src/main/java/me/help/minecraft_store/AtlasCoreConnector.java
package me.help.minecraft_store;

import me.help.minecraft_store.listeners.PlayerListener;
import me.help.minecraft_store.listeners.PlayerQuitListener; // NEW: Import PlayerQuitListener
import me.help.minecraft_store.tasks.StatsTask;
import me.help.minecraft_store.web.WebServer;
import me.help.minecraft_store.services.PlayerProfileService; // NEW: Import PlayerProfileService

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasCoreConnector extends JavaPlugin {

    private final Map<UUID, String> verificationCodes = new ConcurrentHashMap<>();
    private final AtomicInteger newPlayersToday = new AtomicInteger(0);
    private WebServer webServer;
    private PlayerProfileService playerProfileService; // NEW: Declare PlayerProfileService

    @Override
    public void onEnable() {
        saveDefaultConfig(); // Ensures config.yml is loaded

        // NEW: Initialize PlayerProfileService
        this.playerProfileService = new PlayerProfileService(this);

        // Schedule the web server to start after a delay
        new BukkitRunnable() {
            @Override
            public void run() {
                // Initialize and start the web server
                webServer = new WebServer(AtlasCoreConnector.this);
                webServer.start();
            }
        }.runTaskLater(this, 200L); // 200 ticks = 10 seconds

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this); // NEW: Register PlayerQuitListener

        // Schedule repeating tasks
        new StatsTask(this).runTaskTimerAsynchronously(this, 0, getConfig().getLong("stats.interval", 6000));

        getLogger().info("AtlasCoreConnector has been enabled! Web server will start in 10 seconds.");
    }

    // NEW: Add onDisable to stop Spark cleanly
    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        getLogger().info("AtlasCoreConnector has been disabled!");
    }

    public Map<UUID, String> getVerificationCodes() {
        return verificationCodes;
    }

    public AtomicInteger getNewPlayersToday() {
        return newPlayersToday;
    }

    // NEW: Getter for PlayerProfileService
    public PlayerProfileService getPlayerProfileService() {
        return playerProfileService;
    }
}
