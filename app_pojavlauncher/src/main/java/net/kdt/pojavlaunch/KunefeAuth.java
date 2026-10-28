package net.kdt.pojavlaunch;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sunucuya "bu oyuncu launcher'dan geldi" bilgisini gönderir. */
public final class KunefeAuth {
    private static final String AUTH_URL = "http://oyna.kunefesmp.com.tr:7899/kunefe-auth";
    private static final String LAUNCHER_SECRET = "kunefe-gizli-anahtar-degistir";

    private KunefeAuth() {}

    public static void send(String username) {
        if (username == null || username.trim().isEmpty()) return;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(AUTH_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setDoOutput(true);
            connection.setRequestProperty("X-Launcher-Secret", LAUNCHER_SECRET);
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] body = username.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
            // Sunucuya ulaşılamazsa oyun yine açılır; oyuncu /login ile girebilir.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
