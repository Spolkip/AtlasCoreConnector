// src/main/java/me/help/minecraft_store/services/PlayerProfileService.java
package me.help.minecraft_store.services;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import me.help.minecraft_store.AtlasCoreConnector;
import me.help.minecraft_store.data.PlayerProfileData;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

public class PlayerProfileService {

    private final AtlasCoreConnector plugin;
    private Firestore db;
    private final CompletableFuture<Void> initializationFuture = new CompletableFuture<>();

    public PlayerProfileService(AtlasCoreConnector plugin) {
        this.plugin = plugin;
        initializeFirebase();
    }

    private void initializeFirebase() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String serviceAccountFileName = plugin.getConfig().getString("firebase.serviceAccountKeyPath");
                    if (serviceAccountFileName == null || serviceAccountFileName.isEmpty()) {
                        plugin.getLogger().severe("Firebase serviceAccountKeyPath is not set in config.yml.");
                        initializationFuture.completeExceptionally(new IOException("Firebase serviceAccountKeyPath is not set."));
                        return;
                    }

                    File serviceAccountFile = new File(plugin.getDataFolder(), serviceAccountFileName);
                    if (!serviceAccountFile.exists()) {
                        throw new IOException("Firebase service account key file not found at: " + serviceAccountFile.getAbsolutePath());
                    }

                    FileInputStream serviceAccountStream = new FileInputStream(serviceAccountFile);
                    GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream);

                    // Modern way to initialize Firestore
                    FirestoreOptions firestoreOptions = FirestoreOptions.newBuilder()
                            .setCredentials(credentials)
                            .build();
                    db = firestoreOptions.getService();

                    plugin.getLogger().info("Firestore initialized successfully.");
                    initializationFuture.complete(null);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to initialize Firestore.", e);
                    initializationFuture.completeExceptionally(e);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    public CompletableFuture<Void> onReady() {
        return initializationFuture;
    }

    public void savePlayerProfile(PlayerProfileData profileData) {
        onReady().thenRunAsync(() -> {
            try {
                DocumentReference docRef = db.collection("player_profiles").document(profileData.getUuid());
                docRef.set(profileData).get();
            } catch (InterruptedException | ExecutionException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player profile for " + profileData.getPlayerName(), e);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Firestore not ready. Could not save player profile: " + profileData.getPlayerName());
            return null;
        });
    }

    public CompletableFuture<PlayerProfileData> loadPlayerProfile(UUID uuid) {
        return onReady().thenApplyAsync(v -> {
            try {
                DocumentReference docRef = db.collection("player_profiles").document(uuid.toString());
                return docRef.get().get().toObject(PlayerProfileData.class);
            } catch (InterruptedException | ExecutionException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load player profile for " + uuid, e);
                throw new RuntimeException(e);
            }
        });
    }
}