// src/main/java/me/help/minecraft_store/data/PlayerProfileData.java
package me.help.minecraft_store.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerProfileData {
    private String uuid;
    private String playerName;
    private Map<String, String> stats; // Stores skills, class, race etc.
    private long lastUpdated; // Timestamp of last update

    public PlayerProfileData() {
        // Default constructor for Firebase deserialization
    }

    public PlayerProfileData(UUID uuid, String playerName, Map<String, String> stats, long lastUpdated) {
        this.uuid = uuid.toString();
        this.playerName = playerName;
        this.stats = new HashMap<>(stats); // Defensive copy
        this.lastUpdated = lastUpdated;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public Map<String, String> getStats() {
        return stats;
    }

    public void setStats(Map<String, String> stats) {
        this.stats = stats;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
