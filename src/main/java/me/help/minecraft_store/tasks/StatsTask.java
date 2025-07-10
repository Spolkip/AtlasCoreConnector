package me.help.minecraft_store.tasks;

import com.google.gson.Gson;
import me.help.minecraft_store.AtlasCoreConnector;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class StatsTask extends BukkitRunnable {

    private final AtlasCoreConnector plugin;
    private final Gson gson = new Gson();
    private int consecutiveFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    public StatsTask(AtlasCoreConnector plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        String urlString = plugin.getConfig().getString("stats.url");
        String secret = plugin.getConfig().getString("stats.secret");

        if (!validateConfig(urlString, secret)) {
            return;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            configureConnection(connection);

            String jsonPayload = createPayload(secret);
            sendRequest(connection, jsonPayload);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                plugin.getLogger().info("Successfully sent server stats to backend");
                consecutiveFailures = 0;
            } else {
                handleErrorResponse(connection, responseCode);
            }

        } catch (Exception e) {
            handleException(e, urlString);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean validateConfig(String urlString, String secret) {
        if (urlString == null || urlString.isEmpty()) {
            plugin.getLogger().warning("stats.url is not configured in config.yml. Stats will not be sent.");
            this.cancel();
            return false;
        }

        if (secret == null || secret.isEmpty()) {
            plugin.getLogger().warning("stats.secret is not configured in config.yml. Stats will not be sent.");
            this.cancel();
            return false;
        }
        return true;
    }

    private void configureConnection(HttpURLConnection connection) throws Exception {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");
    }

    private String createPayload(String secret) {
        Map<String, Object> statsData = new HashMap<>();
        statsData.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        statsData.put("maxPlayers", Bukkit.getMaxPlayers());
        statsData.put("newPlayersToday", plugin.getNewPlayersToday().getAndSet(0));
        statsData.put("secret", secret);
        return gson.toJson(statsData);
    }

    private void sendRequest(HttpURLConnection connection, String jsonPayload) throws Exception {
        byte[] postData = jsonPayload.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(postData.length);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(postData);
            os.flush();
        }
    }

    private void handleErrorResponse(HttpURLConnection connection, int responseCode) {
        StringBuilder errorResponse = new StringBuilder();
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        errorResponse.append(responseLine.trim());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read error response", e);
        }

        plugin.getLogger().warning(String.format(
                "Failed to send stats. Code: %d, Response: %s",
                responseCode,
                errorResponse.length() > 0 ? errorResponse.toString() : "No error response"
        ));

        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            plugin.getLogger().severe("Max consecutive failures reached. Disabling stats reporting.");
            this.cancel();
        }
    }

    private void handleException(Exception e, String urlString) {
        plugin.getLogger().log(Level.SEVERE, "Failed to send server stats: " + e.getMessage(), e);

        if (e instanceof java.net.ConnectException) {
            plugin.getLogger().warning("Could not connect to backend. Will retry next interval.");
            consecutiveFailures++;
        } else if (e instanceof java.net.MalformedURLException) {
            plugin.getLogger().severe("Invalid stats.url in config.yml: " + urlString);
            this.cancel();
            return;
        }

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            plugin.getLogger().severe("Max consecutive failures reached. Disabling stats reporting.");
            this.cancel();
        }
    }
}