package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

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
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KünefeSMP istemci dosyalarını hazırlar:
 * - resource pack'i APK assets'ten çıkarır ve options.txt'te etkinleştirir
 * - CustomSkinLoader yapılandırmasını yazar
 * - servers.dat'ı sadece KunefeSMP'ye kilitler
 * - zorunlu modları Modrinth'ten indirir
 *
 * Bu sınıf ağ işlemleri yaptığı için asla UI thread'inde çağrılmamalıdır.
 */
public final class KunefeBootstrap {
    private static final String TAG = "KunefeBootstrap";

    public static final String MC_VERSION = "1.21.1";
    public static final String SERVER_HOST = "oyna.kunefesmp.com.tr";
    public static final int SERVER_PORT = 25565;
    public static final String API_BASE = "http://oyna.kunefesmp.com.tr:7899";
    public static final String RESOURCE_PACK = "KunefeClient.zip";

    private static final String PREFS = "kunefe_launcher";
    private static final String MODS_MARKER = ".kunefe-mods.json";

    public interface ProgressListener {
        void onProgress(String message, int percent);
    }

    private KunefeBootstrap() {
    }

    /**
     * Tüm istemci dosyalarını hazırlar. Zaten hazır olanları atlar.
     */
    public static void ensure(Context context, ProgressListener listener) throws Exception {
        LauncherPreferences.loadPreferences(context);
        if (Tools.DIR_GAME_NEW == null) {
            throw new IOException("Oyun klasörü bulunamadı.");
        }

        File gameDir = new File(Tools.DIR_GAME_NEW);
        if (!gameDir.exists() && !gameDir.mkdirs()) {
            throw new IOException("Oyun klasörü oluşturulamadı: " + gameDir);
        }

        publish(listener, "Künefe paketi hazırlanıyor...", 5);
        copyResourcePack(context, gameDir);

        publish(listener, "Grafik ayarları uygulanıyor...", 12);
        applyOptions(context, gameDir);

        publish(listener, "Skin ayarları hazırlanıyor...", 20);
        setupCustomSkinLoader(context, gameDir);

        publish(listener, "Sunucu listesi kilitleniyor...", 28);
        writeServersDat(gameDir);

        publish(listener, "Modlar kontrol ediliyor...", 35);
        downloadMods(context, gameDir, listener);

        publish(listener, "KünefeSMP hazır!", 100);
    }

    /** Modları (zorunlu + seçili isteğe bağlı) kurar; diğer dosyalara dokunmaz. */
    public static void ensureMods(Context context) {
        LauncherPreferences.loadPreferences(context);
        if (Tools.DIR_GAME_NEW == null) return;
        File gameDir = new File(Tools.DIR_GAME_NEW);
        if (!gameDir.exists()) return;
        downloadMods(context, gameDir, null);
    }

    /** FPS ayarını yalnızca options.txt'ye uygular (oyun çalışmıyorken). */
    public static void applyFpsPreset(Context context) {
        LauncherPreferences.loadPreferences(context);
        if (Tools.DIR_GAME_NEW == null) return;
        File gameDir = new File(Tools.DIR_GAME_NEW);
        if (!gameDir.exists()) return;
        try {
            applyOptions(context, gameDir);
        } catch (Exception e) {
            Log.w(TAG, "FPS ayarları uygulanamadı", e);
        }
    }

    /** Seçili skini CustomSkinLoader'a kopyalar. */
    public static void applySkin(Context context) {
        LauncherPreferences.loadPreferences(context);
        if (Tools.DIR_GAME_NEW == null) return;
        File gameDir = new File(Tools.DIR_GAME_NEW);
        if (!gameDir.exists()) return;
        try {
            setupCustomSkinLoader(context, gameDir);
        } catch (Exception e) {
            Log.w(TAG, "Skin ayarları uygulanamadı", e);
        }
    }

    private static void publish(ProgressListener listener, String message, int percent) {
        if (listener != null) {
            listener.onProgress(message, percent);
        }
    }

    private static void copyResourcePack(Context context, File gameDir) throws IOException {
        File rpDir = new File(gameDir, "resourcepacks");
        if (!rpDir.exists() && !rpDir.mkdirs()) {
            throw new IOException("resourcepacks klasörü oluşturulamadı.");
        }
        File target = new File(rpDir, RESOURCE_PACK);
        File temp = new File(rpDir, RESOURCE_PACK + ".part");

        try (InputStream in = context.getAssets().open("kunefe/" + RESOURCE_PACK);
             OutputStream out = new FileOutputStream(temp)) {
            copy(in, out);
        }
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Eski resource pack silinemedi: " + target);
        }
        if (!temp.renameTo(target)) {
            // Bazı dosya sistemlerinde rename çalışmaz; doğrudan kopyala.
            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void applyOptions(Context context, File gameDir) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String presetName = prefs.getString("fps_preset", "Dengeli");
        Map<String, String> preset = fpsPreset(presetName);

        Map<String, String> settings = new LinkedHashMap<>();
        File optionsFile = new File(gameDir, "options.txt");
        if (optionsFile.exists()) {
            for (String line : readLines(optionsFile)) {
                int split = line.indexOf(':');
                if (split > 0) {
                    settings.put(line.substring(0, split), line.substring(split + 1));
                }
            }
        }

        settings.putAll(preset);
        settings.put("resourcePacks", "[\"file/" + RESOURCE_PACK + "\",\"vanilla\"]");
        settings.put("incompatibleResourcePacks", "[]");

        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(optionsFile), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                writer.write(entry.getKey());
                writer.write(':');
                writer.write(entry.getValue());
                writer.write('\n');
            }
        }
    }

    private static Map<String, String> fpsPreset(String name) {
        Map<String, String> map = new LinkedHashMap<>();
        if ("Performans".equals(name)) {
            map.put("renderDistance", "6");
            map.put("simulationDistance", "5");
            map.put("fpsLimit", "120");
            map.put("graphics", "0");
            map.put("ao", "0");
            map.put("entityShadows", "false");
            map.put("particles", "2");
        } else if ("Ultra".equals(name)) {
            map.put("renderDistance", "16");
            map.put("simulationDistance", "12");
            map.put("fpsLimit", "260");
            map.put("graphics", "1");
            map.put("ao", "2");
            map.put("entityShadows", "true");
            map.put("particles", "0");
        } else {
            map.put("renderDistance", "10");
            map.put("simulationDistance", "8");
            map.put("fpsLimit", "60");
            map.put("graphics", "1");
            map.put("ao", "1");
            map.put("entityShadows", "true");
            map.put("particles", "1");
        }
        return map;
    }

    private static void setupCustomSkinLoader(Context context, File gameDir) throws Exception {
        File cslDir = new File(gameDir, "CustomSkinLoader");
        File localSkinsDir = new File(cslDir, "LocalSkin/skins");
        if (!localSkinsDir.exists() && !localSkinsDir.mkdirs()) {
            throw new IOException("CustomSkinLoader klasörü oluşturulamadı.");
        }

        // Eski önbelleği temizle; yeni skin hemen görünsün.
        deleteRecursive(new File(cslDir, "caches"));

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String username = prefs.getString("username",
                prefs.getString("last_user", ""));
        if (!username.isEmpty()) {
            File selectedSkin = new File(context.getFilesDir(), "kunefe/skin.png");
            File localSkin = new File(localSkinsDir, username + ".png");
            if (selectedSkin.exists()) {
                copyFile(selectedSkin, localSkin);
            } else {
                File gameSkin = new File(gameDir, "skins/" + username + ".png");
                if (gameSkin.exists()) {
                    copyFile(gameSkin, localSkin);
                }
            }
        }

        JSONArray loadlist = new JSONArray();
        loadlist.put(new JSONObject()
                .put("name", "LocalSkin")
                .put("type", "Legacy")
                .put("checkPNG", false)
                .put("skin", "LocalSkin/skins/{USERNAME}.png")
                .put("model", "auto"));
        loadlist.put(new JSONObject()
                .put("name", "KunefeSMP")
                .put("type", "Legacy")
                .put("checkPNG", false)
                .put("skin", API_BASE + "/skins/{USERNAME}.png")
                .put("model", "auto"));
        loadlist.put(new JSONObject().put("name", "Mojang").put("type", "MojangAPI"));
        loadlist.put(new JSONObject().put("name", "ElyBy").put("type", "ElyByAPI"));

        JSONObject root = new JSONObject();
        root.put("enable", true);
        root.put("loadlist", loadlist);

        File configFile = new File(cslDir, "CustomSkinLoader.json");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            writer.write(root.toString(2));
        }
    }

    private static void writeServersDat(File gameDir) throws IOException {
        String name = "KünefeSMP";
        String ip = SERVER_HOST + ":" + SERVER_PORT;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(0x0a); // root compound
        writeNbtString(out, "");
        out.write(0x09); // list
        writeNbtString(out, "servers");
        out.write(0x0a); // list of compounds
        writeInt(out, 1);
        out.write(0x08); // string
        writeNbtString(out, "name");
        writeNbtString(out, name);
        out.write(0x08);
        writeNbtString(out, "ip");
        writeNbtString(out, ip);
        out.write(0x00); // end of compound
        out.write(0x00); // end of root

        File target = new File(gameDir, "servers.dat");
        try (OutputStream fileOut = new FileOutputStream(target)) {
            out.writeTo(fileOut);
        }
    }

    /**
     * Zorunlu modları ve kullanıcının seçtiği isteğe bağlı modları kurar.
     * Seçimden çıkarılan modların dosyaları da silinir.
     */
    private static void downloadMods(Context context, File gameDir, ProgressListener listener) {
        File modsDir = new File(gameDir, "mods");
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            Log.w(TAG, "mods klasörü oluşturulamadı.");
            return;
        }

        List<KunefeModCatalog.Mod> desired = new ArrayList<>(KunefeModCatalog.required());
        Set<String> optional = KunefeModCatalog.selectedOptional(context);
        for (KunefeModCatalog.Mod mod : KunefeModCatalog.all()) {
            if (!mod.required && optional.contains(mod.slug)) desired.add(mod);
        }

        JSONObject marker = readJson(new File(gameDir, MODS_MARKER));
        JSONObject installed = marker != null ? marker.optJSONObject("mods") : null;
        if (installed == null) installed = new JSONObject();

        // Artık istenmeyen isteğe bağlı modları kaldır.
        Set<String> desiredSlugs = new HashSet<>();
        for (KunefeModCatalog.Mod mod : desired) desiredSlugs.add(mod.slug);

        List<String> stale = new ArrayList<>();
        Iterator<String> keys = installed.keys();
        while (keys.hasNext()) {
            String slug = keys.next();
            if (desiredSlugs.contains(slug)) continue;
            KunefeModCatalog.Mod mod = KunefeModCatalog.bySlug(slug);
            // Zorunlu modlar hiçbir zaman kaldırılmaz.
            if (mod != null && mod.required) continue;
            stale.add(slug);
        }
        for (String slug : stale) {
            String filename = installed.optString(slug, "");
            if (!filename.isEmpty()) {
                removeByPrefix(modsDir, filename);
            }
            installed.remove(slug);
        }
        if (!stale.isEmpty()) writeMarker(gameDir, installed);

        for (int i = 0; i < desired.size(); i++) {
            KunefeModCatalog.Mod mod = desired.get(i);
            int percent = 38 + (int) (((i + 1) / (float) desired.size()) * 52);
            publish(listener, mod.name + " kontrol ediliyor...", percent);

            try {
                String knownFile = installed.optString(mod.slug, "");
                if (!knownFile.isEmpty()) {
                    File existing = new File(modsDir, knownFile);
                    if (existing.isFile() && existing.length() > 1024) {
                        continue;
                    }
                }

                String filename = downloadLatestMod(mod.slug, modsDir);
                if (filename != null) {
                    installed.put(mod.slug, filename);
                    writeMarker(gameDir, installed);
                }
            } catch (Exception e) {
                Log.w(TAG, "Mod indirilemedi: " + mod.slug, e);
            }
        }
    }

    private static void writeMarker(File gameDir, JSONObject installed) {
        try {
            JSONObject newMarker = new JSONObject();
            newMarker.put("mc_version", MC_VERSION);
            newMarker.put("mods", installed);
            writeJson(new File(gameDir, MODS_MARKER), newMarker);
        } catch (Exception e) {
            Log.w(TAG, "Mod işaretçisi yazılamadı", e);
        }
    }

    /** Modrinth dosya adları sürüm içerebilir; eski adı da temizler. */
    private static void removeByPrefix(File modsDir, String filename) {
        File target = new File(modsDir, filename);
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Mod silinemedi: " + target);
        }
        File[] files = modsDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".jar") && name.startsWith(filename.replace(".jar", ""))) {
                if (!file.equals(target) && !file.delete()) {
                    Log.w(TAG, "Eski mod silinemedi: " + file);
                }
            }
        }
    }

    private static String downloadLatestMod(String slug, File modsDir) throws Exception {
        String query = "?game_versions=" + URLEncoder.encode("[\"" + MC_VERSION + "\"]", "UTF-8")
                + "&loaders=" + URLEncoder.encode("[\"fabric\"]", "UTF-8");
        String body = httpGet("https://api.modrinth.com/v2/project/" + slug + "/version" + query);
        if (body == null) return null;

        JSONArray versions = new JSONArray(body);
        for (int v = 0; v < Math.min(versions.length(), 4); v++) {
            JSONObject version = versions.getJSONObject(v);
            JSONArray files = version.optJSONArray("files");
            if (files == null || files.length() == 0) continue;

            JSONObject chosen = null;
            for (int f = 0; f < files.length(); f++) {
                JSONObject file = files.getJSONObject(f);
                if (file.optBoolean("primary", false)) {
                    chosen = file;
                    break;
                }
            }
            if (chosen == null) chosen = files.getJSONObject(0);

            String url = chosen.optString("url", "");
            String filename = chosen.optString("filename", "");
            if (url.isEmpty() || filename.isEmpty()) continue;

            File target = new File(modsDir, filename);
            if (target.isFile() && target.length() > 1024) return filename;

            File temp = new File(modsDir, filename + ".part");
            if (!downloadFile(url, temp)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                continue;
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Eski mod silinemedi: " + target);
            }
            if (!temp.renameTo(target)) {
                copyFile(temp, target);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            if (target.length() > 1024) return filename;
        }
        return null;
    }

    private static String httpGet(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "KunefeSMP-Launcher/4.6 (Android)");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                return readAll(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean downloadFile(String urlString, File target) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "KunefeSMP-Launcher/4.6 (Android)");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return false;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
            return target.length() > 1024;
        } catch (Exception e) {
            Log.w(TAG, "Dosya indirilemedi: " + urlString, e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return new JSONObject(readAll(in));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJson(File file, JSONObject object) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(object.toString());
        } catch (Exception e) {
            Log.w(TAG, "JSON yazılamadı: " + file, e);
        }
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in = new FileInputStream(file)) {
            String content = readAll(in);
            for (String line : content.split("\n", -1)) {
                lines.add(line.replace("\r", ""));
            }
        }
        return lines;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    private static void writeNbtString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >> 8) & 0xff);
        out.write(bytes.length & 0xff);
        out.write(bytes);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
