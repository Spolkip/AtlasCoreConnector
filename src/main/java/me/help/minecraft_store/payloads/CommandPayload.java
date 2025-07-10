// src/main/java/me/help/minecraft_store/payloads/CommandPayload.java
package me.help.minecraft_store.payloads;

import java.util.Map; // Import Map

public class CommandPayload {
    private String command;
    private Map<String, String> playerContext; // NEW: Add playerContext field

    public String getCommand() {
        return command;
    }

    // NEW: Getter for playerContext
    public Map<String, String> getPlayerContext() {
        return playerContext;
    }
}
