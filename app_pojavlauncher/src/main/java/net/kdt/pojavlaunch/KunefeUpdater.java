package net.kdt.pojavlaunch;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * KÃ¼nefe Launcher Android gÃ¼ncelleme denetimi.
 *
 * Sunucudaki sÃ¼rÃ¼m dosyasÄ± ÅŸu biÃ§imde olmalÄ±:
 * {
 *   "versionCode": 4700,
 *   "versionName": "4.7-android",
 *   "url": "http://oyna.kunefesmp.com.tr:7899/kunefe-android.apk",
 *   "notes": "DeÄŸiÅŸiklikler..."
 * }
 *
 * Denetim sessizdir: sunucuya ulaÅŸÄ±lamazsa hiÃ§bir ÅŸey gÃ¶sterilmez.
 */
public final class KunefeUpdater {
    private static final String TAG = "KunefeUpdater";
    private static final String VERSION_URL = "https://kunefesmp.com.tr/kunefe-android-version";
    private static final String PREFS = "kunefe_launcher";
    private static final String PREF_LAST_CHECK = "update_last_check";
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L; // 6 saat

    private KunefeUpdater() {}

    /** Uygulama aÃ§Ä±lÄ±ÅŸÄ±nda Ã§aÄŸrÄ±lÄ±r; 6 saatte bir sessizce denetler. */
    public static void checkInBackground(Activity activity) {
        check(activity, false);
    }

    /** Ayarlar ekranÄ±ndan elle denetim. */
    public static void checkManually(Activity activity) {
        check(activity, true);
    }

    private static void check(Activity activity, boolean manual) {
        if (activity == null || activity.isFinishing()) return;

        Context context = activity.getApplicationContext();
        long last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(PREF_LAST_CHECK, 0L);
        if (!manual && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;

        new Thread(() -> {
            JSONObject info = null;
            try {
                info = fetchVersion();
            } catch (Exception e) {
                Log.w(TAG, "GÃ¼ncelleme bilgisi alÄ±namadÄ±", e);
            }

            if (info == null) {
                if (manual) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            R.string.kunefe_update_check_failed, Toast.LENGTH_LONG).show());
                }
                return;
            }

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();

            int remoteCode = info.optInt("versionCode", 0);
            String remoteName = info.optString("versionName", "");
            String url = info.optString("url", "");
            String notes = info.optString("notes", "");

            if (remoteCode <= BuildConfig.VERSION_CODE || url.isEmpty()) {
                if (manual) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            R.string.kunefe_update_latest, Toast.LENGTH_LONG).show());
                }
                return;
            }

            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                showUpdateDialog(activity, remoteName, notes, url);
            });
        }, "kunefe-update-check").start();
    }

    private static JSONObject fetchVersion() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(VERSION_URL).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "KunefeSMP-Launcher/4.6 (Android)");
        try {
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                StringBuilder sb = new StringBuilder();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) sb.append(new String(buffer, 0, read, "UTF-8"));
                return new JSONObject(sb.toString());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void showUpdateDialog(Activity activity, String versionName,
                                         String notes, String url) {
        String message = activity.getString(R.string.kunefe_update_message,
                versionName != null && !versionName.isEmpty() ? versionName : "?");
        if (notes != null && !notes.trim().isEmpty()) {
            message += "\n\n" + notes.trim();
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.kunefe_update_title)
                .setMessage(message)
                .setPositiveButton(R.string.kunefe_update_now, (d, w) -> downloadAndInstall(activity, url))
                .setNegativeButton(R.string.kunefe_update_later, null)
                .show();
    }

    private static void downloadAndInstall(Activity activity, String url) {
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                Toast.makeText(activity, R.string.kunefe_update_allow_install,
                        Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
                Toast.makeText(activity, R.string.kunefe_update_install_failed,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(R.string.kunefe_update_downloading)
                .setMessage("0%")
                .setCancelable(false)
                .create();
        progress.show();

        final Handler handler = new Handler(Looper.getMainLooper());
        final Context context = activity.getApplicationContext();

        new Thread(() -> {
            File apk = new File(context.getCacheDir(), "kunefe-update.apk");
            boolean ok = false;
            try {
                ok = download(url, apk, percent ->
                        handler.post(() -> {
                            if (progress.isShowing()) {
                                progress.setMessage(percent + "%");
                            }
                        }));
            } catch (Exception e) {
                Log.w(TAG, "GÃ¼ncelleme indirilemedi", e);
            }

            final boolean success = ok;
            handler.post(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (!activity.isFinishing()) {
                    if (success) install(activity, apk);
                    else Toast.makeText(activity, R.string.kunefe_update_install_failed,
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "kunefe-update-download").start();
    }

    private static boolean download(String urlString, File target, ProgressCallback callback)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "KunefeSMP-Launcher/4.6 (Android)");
        try {
            if (connection.getResponseCode() != 200) return false;
            int total = connection.getContentLength();
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                long written = 0;
                int read;
                int lastPercent = -1;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (total > 0) {
                        int percent = (int) (written * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            callback.onProgress(percent);
                        }
                    }
                }
            }
            return target.length() > 1024 * 1024;
        } finally {
            connection.disconnect();
        }
    }

    private static void install(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".kunefeupdates", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Kurulum baÅŸlatÄ±lamadÄ±", e);
            Toast.makeText(activity, R.string.kunefe_update_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    private interface ProgressCallback {
        void onProgress(int percent);
    }
}
