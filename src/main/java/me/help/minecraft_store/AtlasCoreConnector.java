package me.help.minecraft_store;

import me.help.minecraft_store.listeners.PlayerListener;
import me.help.minecraft_store.tasks.StatsTask;
import me.help.minecraft_store.web.WebServer;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasCoreConnector extends JavaPlugin {

    private final Map<UUID, String> verificationCodes = new ConcurrentHashMap<>();
    private final AtomicInteger newPlayersToday = new AtomicInteger(0);
    private WebServer webServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Initialize and start the web server
        webServer = new WebServer(this);
        webServer.start();

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Schedule repeating tasks
        // The interval is now 5 minutes (6000 ticks) by default.
        new StatsTask(this).runTaskTimerAsynchronously(this, 0, getConfig().getLong("stats.interval", 6000));

        getLogger().info("AtlasCoreConnector has been enabled!");
    }

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
}