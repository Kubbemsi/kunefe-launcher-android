package net.kdt.pojavlaunch;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * KÃ¼nefeSMP ayarlarÄ±: FPS profili, skin ve isteÄŸe baÄŸlÄ± modlar.
 *
 * MasaÃ¼stÃ¼ launcher'daki sekmelerin Android karÅŸÄ±lÄ±ÄŸÄ±dÄ±r. Kaydet'e
 * basÄ±ldÄ±ÄŸÄ±nda options.txt, CustomSkinLoader ve mod klasÃ¶rÃ¼ gÃ¼ncellenir.
 */
public class KunefeSettingsActivity extends BaseActivity {
    private static final String PREFS = "kunefe_launcher";
    private static final String PREF_FPS = "fps_preset";
    private static final String SKIN_UPLOAD_URL = KunefeBootstrap.API_BASE + "/kunefe-skin";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Switch> mModSwitches = new LinkedHashMap<>();

    private RadioGroup mFpsGroup;
    private ImageView mSkinPreview;
    private TextView mSkinStatus;
    private TextView mSaveStatus;
    private Button mSaveButton;

    private File mSkinFile;
    private volatile boolean mSaving = false;

    private final ActivityResultLauncher<String> mSkinPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onSkinPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kunefe_settings);

        LauncherPreferences.loadPreferences(this);

        mFpsGroup = findViewById(R.id.fps_group);
        mSkinPreview = findViewById(R.id.skin_preview);
        mSkinStatus = findViewById(R.id.skin_status);
        mSaveStatus = findViewById(R.id.save_status);
        mSaveButton = findViewById(R.id.save_button);

        mSkinFile = new File(getFilesDir(), "kunefe/skin.png");

        findViewById(R.id.skin_pick_button).setOnClickListener(v -> mSkinPicker.launch("image/png"));
        findViewById(R.id.update_button).setOnClickListener(v -> KunefeUpdater.checkManually(this));
        findViewById(R.id.logout_button).setOnClickListener(v -> confirmLogout());
        findViewById(R.id.source_code_link).setOnClickListener(v -> openSourceCode());
        mSaveButton.setOnClickListener(v -> save());

        loadFps();
        loadSkin();
        buildModList();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void loadFps() {
        String preset = prefs().getString(PREF_FPS, "Dengeli");
        int id = R.id.fps_balanced;
        if ("Performans".equals(preset)) id = R.id.fps_performance;
        else if ("Ultra".equals(preset)) id = R.id.fps_ultra;
        mFpsGroup.check(id);
    }

    private String selectedFpsName() {
        int checked = mFpsGroup.getCheckedRadioButtonId();
        if (checked == R.id.fps_performance) return "Performans";
        if (checked == R.id.fps_ultra) return "Ultra";
        return "Dengeli";
    }

    private void loadSkin() {
        if (mSkinFile.isFile()) {
            showSkinPreview();
        } else {
            mSkinPreview.setImageDrawable(null);
            mSkinStatus.setText(R.string.kunefe_settings_skin_none);
        }
    }

    private void showSkinPreview() {
        Bitmap bitmap = BitmapFactory.decodeFile(mSkinFile.getAbsolutePath());
        if (bitmap != null) {
            mSkinPreview.setImageBitmap(bitmap);
            mSkinStatus.setText(getString(R.string.kunefe_settings_skin_selected, mSkinFile.getName()));
        } else {
            mSkinPreview.setImageDrawable(null);
            mSkinStatus.setText(R.string.kunefe_settings_skin_none);
        }
    }

    private void onSkinPicked(Uri uri) {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("AkÄ±ÅŸ aÃ§Ä±lamadÄ±");

            File parent = mSkinFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new Exception("KlasÃ¶r oluÅŸturulamadÄ±");
            }

            File temp = new File(mSkinFile.getAbsolutePath() + ".part");
            try (OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temp.getAbsolutePath(), options);
            int width = options.outWidth;
            int height = options.outHeight;
            boolean valid = width > 0 && height > 0
                    && width % 64 == 0 && (height == width || height * 2 == width);
            if (!valid) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                Toast.makeText(this, R.string.kunefe_settings_skin_invalid, Toast.LENGTH_LONG).show();
                return;
            }

            if (mSkinFile.exists() && !mSkinFile.delete()) {
                throw new Exception("Eski skin silinemedi");
            }
            if (!temp.renameTo(mSkinFile)) {
                throw new Exception("Skin kaydedilemedi");
            }

            showSkinPreview();
            uploadSkin();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Skini sunucuya yÃ¼kler; baÅŸarÄ±sÄ±z olursa yalnÄ±zca cihazda kalÄ±r. */
    private void uploadSkin() {
        String username = prefs().getString("username", prefs().getString("last_user", ""));
        if (username.isEmpty() || !mSkinFile.isFile()) return;

        new Thread(() -> {
            boolean ok = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(SKIN_UPLOAD_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "image/png");
                connection.setRequestProperty("X-Username", username);
                connection.setRequestProperty("X-Launcher-Secret", "kunefe-gizli-anahtar-degistir");
                try (OutputStream out = connection.getOutputStream();
                     InputStream in = new java.io.FileInputStream(mSkinFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                ok = connection.getResponseCode() == 200;
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }

            final boolean success = ok;
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this,
                        success ? R.string.kunefe_settings_skin_uploaded
                                : R.string.kunefe_settings_skin_upload_failed,
                        Toast.LENGTH_LONG).show();
            });
        }, "kunefe-skin-upload").start();
    }

    private void buildModList() {
        LinearLayout container = findViewById(R.id.mods_container);
        container.removeAllViews();

        Set<String> selected = KunefeModCatalog.selectedOptional(this);

        TextView requiredLabel = new TextView(this);
        requiredLabel.setText(R.string.kunefe_settings_required);
        requiredLabel.setTextColor(ContextCompat.getColor(this, R.color.kunefe_dim));
        requiredLabel.setTextSize(12);
        requiredLabel.setPadding(0, dp(10), 0, dp(4));
        container.addView(requiredLabel);

        StringBuilder requiredNames = new StringBuilder();
        for (KunefeModCatalog.Mod mod : KunefeModCatalog.required()) {
            if (requiredNames.length() > 0) requiredNames.append(", ");
            requiredNames.append(mod.name);
        }
        TextView requiredList = new TextView(this);
        requiredList.setText(requiredNames.toString());
        requiredList.setTextColor(ContextCompat.getColor(this, R.color.kunefe_accent));
        requiredList.setTextSize(12);
        requiredList.setPadding(0, 0, 0, dp(6));
        container.addView(requiredList);

        for (String category : KunefeModCatalog.categories()) {
            TextView header = new TextView(this);
            header.setText(category);
            header.setTextColor(ContextCompat.getColor(this, R.color.kunefe_text));
            header.setTextSize(13);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            header.setPadding(0, dp(12), 0, dp(2));
            container.addView(header);

            for (KunefeModCatalog.Mod mod : KunefeModCatalog.optionalInCategory(category)) {
                Switch toggle = new Switch(this);
                toggle.setText(mod.name);
                toggle.setTextColor(ContextCompat.getColor(this, R.color.kunefe_text));
                toggle.setTextSize(14);
                toggle.setChecked(selected.contains(mod.slug));
                toggle.setPadding(0, 0, 0, dp(2));
                toggle.setThumbTintList(ContextCompat.getColorStateList(this, R.color.kunefe_accent));
                toggle.setTrackTintList(ContextCompat.getColorStateList(this, R.color.kunefe_border));
                container.addView(toggle, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                mModSwitches.put(mod.slug, toggle);

                if (mod.description != null && !mod.description.isEmpty()) {
                    TextView description = new TextView(this);
                    description.setText(mod.description);
                    description.setTextColor(ContextCompat.getColor(this, R.color.kunefe_dim));
                    description.setTextSize(11);
                    description.setPadding(dp(4), 0, 0, dp(4));
                    container.addView(description);
                }
            }
        }
    }

    /** LGPLv3 gereği kaynak koduna erişim. */
    private void openSourceCode() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Kubbemsi/kunefe-launcher-android")));
        } catch (Exception e) {
            Toast.makeText(this, "Tarayıcı açılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmLogout() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.kunefe_settings_logout_title)
                .setMessage(R.string.kunefe_settings_logout_message)
                .setPositiveButton(R.string.kunefe_settings_logout_confirm, (d, w) -> logout())
                .setNegativeButton(R.string.kunefe_settings_logout_cancel, null)
                .show();
    }

    /** Masaüstündeki "Çıkış Yap" ile aynı: hatırlamayı kapat, hesabı kaldır. */
    private void logout() {
        String username = prefs().getString("username",
                prefs().getString("last_user", ""));
        prefs().edit().putBoolean("remember_me", false).apply();

        try {
            if (Tools.DIR_ACCOUNT_NEW == null) {
                Tools.initEarlyConstants(getApplicationContext());
            }
            if (username != null && !username.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                new File(Tools.DIR_ACCOUNT_NEW, username + ".json").delete();
            }
        } catch (Throwable ignored) {
        }

        Intent intent = new Intent(this, KunefeLoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private Set<String> selectedMods() {        Set<String> slugs = new HashSet<>();
        for (Map.Entry<String, Switch> entry : mModSwitches.entrySet()) {
            if (entry.getValue().isChecked()) slugs.add(entry.getKey());
        }
        return slugs;
    }

    private void save() {
        if (mSaving) return;
        mSaving = true;
        mSaveButton.setEnabled(false);
        setStatus(R.string.kunefe_settings_saving, true);

        String preset = selectedFpsName();
        Set<String> mods = selectedMods();
        prefs().edit()
                .putString(PREF_FPS, preset)
                .putStringSet(KunefeModCatalog.PREF_OPTIONAL_MODS, new HashSet<>(mods))
                .apply();

        new Thread(() -> {
            try {
                KunefeBootstrap.applyFpsPreset(this);
                KunefeBootstrap.applySkin(this);

                mHandler.post(() -> setStatus(R.string.kunefe_settings_mods_applying, true));
                KunefeBootstrap.ensure(this, (message, percent) ->
                        mHandler.post(() -> setStatus(message, true)));

                mHandler.post(() -> {
                    mSaving = false;
                    mSaveButton.setEnabled(true);
                    setStatus(R.string.kunefe_settings_saved, false);
                });
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                mHandler.post(() -> {
                    mSaving = false;
                    mSaveButton.setEnabled(true);
                    setStatus(getString(R.string.kunefe_settings_error, message), false);
                });
            }
        }, "kunefe-settings-save").start();
    }

    private void setStatus(int stringRes, boolean neutral) {
        setStatus(getString(stringRes), neutral);
    }

    private void setStatus(String text, boolean neutral) {
        mSaveStatus.setVisibility(View.VISIBLE);
        mSaveStatus.setText(text);
        mSaveStatus.setTextColor(ContextCompat.getColor(this, neutral ? R.color.kunefe_dim : R.color.kunefe_online));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSkin();
    }
}
