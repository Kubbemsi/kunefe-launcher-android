package net.kdt.pojavlaunch;

import android.app.Activity;
import android.util.Log;

import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeDownloadTask;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minecraft 1.21.1 + Fabric kurulumunu otomatik yapar.
 * Amethyst'in kendi indiricisini kullanır; yerel hesap kontrolünü atlar.
 */
public final class KunefeInstaller {
    private static final String TAG = "KunefeInstaller";
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private KunefeInstaller() {
    }

    public static void ensureVersion(Activity activity, KunefeBootstrap.ProgressListener listener) throws Exception {
        File gameDir = new File(Tools.DIR_GAME_NEW);
        File versionJson = new File(gameDir, "versions/" + KunefeBootstrap.MC_VERSION
                + "/" + KunefeBootstrap.MC_VERSION + ".json");
        File marker = new File(gameDir, ".kunefe-" + KunefeBootstrap.MC_VERSION + "-ready");

        if (marker.isFile() && versionJson.isFile()) {
            publish(listener, "Minecraft " + KunefeBootstrap.MC_VERSION + " hazır.", 38);
            return;
        }

        publish(listener, "Minecraft " + KunefeBootstrap.MC_VERSION + " indiriliyor (ilk kurulum ~300 MB)...", 30);
        JMinecraftVersionList.Version version = fetchVersionInfo();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        new MinecraftDownloader().startForced(activity, version, KunefeBootstrap.MC_VERSION,
                new AsyncMinecraftDownloader.DoneListener() {
                    @Override
                    public void onDownloadDone() {
                        latch.countDown();
                    }

                    @Override
                    public void onDownloadFailed(Throwable throwable) {
                        error.set(throwable);
                        latch.countDown();
                    }
                });

        latch.await();
        if (error.get() != null) {
            throw new IOException("Minecraft indirilemedi: " + error.get().getMessage(), error.get());
        }
        if (!versionJson.isFile()) {
            throw new IOException("Minecraft sürüm dosyası oluşmadı.");
        }

        writeMarker(marker, KunefeBootstrap.MC_VERSION);
        publish(listener, "Minecraft " + KunefeBootstrap.MC_VERSION + " kuruldu.", 55);
    }

    public static String ensureFabric(Activity activity, KunefeBootstrap.ProgressListener listener) throws Exception {
        String existing = findFabricVersion();
        if (existing != null) {
            publish(listener, "Fabric hazır.", 60);
            return existing;
        }

        publish(listener, "Fabric loader sürümü alınıyor...", 58);
        FabricVersion[] loaders = FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(KunefeBootstrap.MC_VERSION);
        if (loaders == null || loaders.length == 0) {
            throw new IOException("Fabric loader sürümü bulunamadı.");
        }

        FabricVersion chosen = null;
        for (FabricVersion loader : loaders) {
            if (loader != null && loader.stable) {
                chosen = loader;
                break;
            }
        }
        if (chosen == null) {
            for (FabricVersion loader : loaders) {
                if (loader != null) {
                    chosen = loader;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new IOException("Uygun Fabric loader bulunamadı.");
        }

        publish(listener, "Fabric " + chosen.version + " kuruluyor...", 62);
        final AtomicReference<Exception> error = new AtomicReference<>();
        FabriclikeDownloadTask task = new FabriclikeDownloadTask(new ModloaderDownloadListener() {
            @Override
            public void onDownloadFinished(File downloadedFile) {
            }

            @Override
            public void onDataNotAvailable() {
                error.set(new IOException("Fabric profili indirilemedi."));
            }

            @Override
            public void onDownloadError(Exception e) {
                error.set(e);
            }
        }, FabriclikeUtils.FABRIC_UTILS, KunefeBootstrap.MC_VERSION, chosen.version, false);
        task.run();

        if (error.get() != null) {
            throw new IOException("Fabric kurulamadı: " + error.get().getMessage(), error.get());
        }

        String installed = findFabricVersion();
        if (installed == null) {
            throw new IOException("Fabric sürüm klasörü oluşmadı.");
        }
        writeMarker(new File(Tools.DIR_GAME_NEW, ".kunefe-fabric-ready"), installed);
        publish(listener, "Fabric " + installed + " kuruldu.", 68);
        return installed;
    }

    private static JMinecraftVersionList.Version fetchVersionInfo() throws Exception {
        JSONObject root = new JSONObject(httpGet(VERSION_MANIFEST));
        JSONArray versions = root.getJSONArray("versions");
        for (int i = 0; i < versions.length(); i++) {
            JSONObject item = versions.getJSONObject(i);
            if (!KunefeBootstrap.MC_VERSION.equals(item.optString("id"))) continue;

            JMinecraftVersionList.Version version = new JMinecraftVersionList.Version();
            version.id = item.optString("id");
            version.url = item.optString("url");
            version.type = item.optString("type");
            version.time = item.optString("time");
            version.releaseTime = item.optString("releaseTime");
            version.sha1 = item.optString("sha1");
            if (version.url.isEmpty()) {
                throw new IOException("Minecraft sürüm URL'si bulunamadı.");
            }
            return version;
        }
        throw new IOException("Minecraft " + KunefeBootstrap.MC_VERSION + " sürüm listesinde yok.");
    }

    private static String findFabricVersion() {
        File versionsDir = new File(Tools.DIR_HOME_VERSION);
        File[] dirs = versionsDir.listFiles();
        if (dirs == null) return null;

        File best = null;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String name = dir.getName();
            if (!name.startsWith("fabric-loader-") || !name.endsWith("-" + KunefeBootstrap.MC_VERSION)) {
                continue;
            }
            File json = new File(dir, name + ".json");
            if (!json.isFile()) continue;
            if (best == null || dir.lastModified() > best.lastModified()) {
                best = dir;
            }
        }
        return best != null ? best.getName() : null;
    }

    private static String httpGet(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setRequestProperty("User-Agent", "KunefeSMP-Launcher/4.6 (Android)");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + urlString);
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void writeMarker(File file, String value) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Marker yazılamadı: " + file, e);
        }
    }

    private static void publish(KunefeBootstrap.ProgressListener listener, String message, int percent) {
        if (listener != null) listener.onProgress(message, percent);
    }
}
