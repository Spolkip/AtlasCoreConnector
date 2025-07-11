package me.help.minecraft_store;

import me.help.minecraft_store.listeners.PlayerListener;
import me.help.minecraft_store.tasks.StatsTask;
import me.help.minecraft_store.web.WebServer;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();

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

        // Schedule repeating tasks
        new StatsTask(this).runTaskTimerAsynchronously(this, 0, getConfig().getLong("stats.interval", 6000));

        getLogger().info("AtlasCoreConnector has been enabled! Web server will start in 10 seconds.");
    }
    public Map<UUID, String> getVerificationCodes() {
        return verificationCodes;
    }

    public AtomicInteger getNewPlayersToday() {
        return newPlayersToday;
    }
}