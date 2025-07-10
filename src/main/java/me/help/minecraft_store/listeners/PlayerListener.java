package me.help.minecraft_store.listeners;

import me.help.minecraft_store.AtlasCoreConnector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerListener implements Listener {

    private final AtlasCoreConnector plugin;

    public PlayerListener(AtlasCoreConnector plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPlayedBefore()) {
            plugin.getNewPlayersToday().incrementAndGet();
        }
    }
}
