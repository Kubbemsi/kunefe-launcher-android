package net.kdt.pojavlaunch;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;

/**
 * KünefeSMP ilk kurulum ekranı.
 *
 * Sırasıyla:
 * 1. Depolama iznini ve oyun klasörünü hazırlar
 * 2. Minecraft 1.21.1'i indirir
 * 3. Fabric loader'ı kurar
 * 4. KünefeSMP profilini Fabric sürümüne bağlar
 * 5. Resource pack, FPS ayarları, skin ve modları hazırlar
 *
 * Ardından Amethyst'in normal launcher ekranına geçer.
 */
public class KunefeSetupActivity extends BaseActivity {
    private static final int REQUEST_STORAGE = 1;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mRunning = false;
    private volatile boolean mDestroyed = false;

    private ProgressBar mProgressBar;
    private TextView mPhaseText;
    private TextView mPercentText;
    private TextView mDetailText;
    private TextView mErrorText;
    private TextView mSkipHint;
    private View mActionRow;
    private Button mRetryButton;
    private Button mSkipButton;

    private int mLastPercent = -1;

    private final ProgressListener mMinecraftProgress = new ProgressListener() {
        @Override
        public void onProgressStarted() {
            publish("Minecraft " + KunefeBootstrap.MC_VERSION + " indiriliyor...", 6);
        }

        @Override
        public void onProgressUpdated(int progress, int resid, Object... va) {
            int clamped = Math.max(0, Math.min(100, progress));
            publish("Minecraft " + KunefeBootstrap.MC_VERSION + " indiriliyor...",
                    6 + (clamped * 34 / 100));
        }

        @Override
        public void onProgressEnded() {
            publish("Minecraft dosyaları hazır.", 40);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kunefe_setup);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mProgressBar = findViewById(R.id.setup_progress);
        mPhaseText = findViewById(R.id.phase_text);
        mPercentText = findViewById(R.id.percent_text);
        mDetailText = findViewById(R.id.detail_text);
        mErrorText = findViewById(R.id.error_text);
        mSkipHint = findViewById(R.id.skip_hint);
        mActionRow = findViewById(R.id.action_row);
        mRetryButton = findViewById(R.id.retry_button);
        mSkipButton = findViewById(R.id.skip_button);

        mRetryButton.setOnClickListener(v -> {
            if (!mRunning) begin();
        });
        mSkipButton.setOnClickListener(v -> launchLauncher());

        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, mMinecraftProgress);

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && !TestStorageActivity.isStorageAllowed(this)) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        } else {
            begin();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        }
    }

    private void begin() {
        if (mRunning) return;

        if (!Tools.checkStorageRoot(this)) {
            startActivity(new Intent(this, MissingStorageActivity.class));
            finish();
            return;
        }

        mRunning = true;
        showSetupUi();
        publish("Kurulum başlatılıyor...", 1);

        // Depolama sabitleri ve varsayılan tercihler.
        LauncherPreferences.loadPreferences(this);

        // Amethyst bileşenleri (MioLibPatcher, LWJGL modülleri...) arka planda açılır.
        AsyncAssetManager.unpackComponents(this);
        AsyncAssetManager.unpackSingleFiles(this);

        // En azından vanilla profil bulunsun; Fabric kurulunca güncellenecek.
        KunefeSetup.ensureProfile(this);

        new Thread(this::runSetup, "kunefe-setup").start();
    }

    private void runSetup() {
        try {
            publish("Minecraft " + KunefeBootstrap.MC_VERSION + " kontrol ediliyor...", 4);
            KunefeInstaller.ensureVersion(this, new KunefeBootstrap.ProgressListener() {
                @Override
                public void onProgress(String message, int percent) {
                    publishMessage(message);
                }
            });

            publish("Fabric loader hazırlanıyor...", 42);
            String fabricVersion = KunefeInstaller.ensureFabric(this,
                    new KunefeBootstrap.ProgressListener() {
                        @Override
                        public void onProgress(String message, int percent) {
                            publishMessage(message);
                        }
                    });

            publish("KünefeSMP profili ayarlanıyor...", 52);
            KunefeSetup.ensureProfile(this, fabricVersion);

            publish("İstemci dosyaları hazırlanıyor...", 55);
            KunefeBootstrap.ensure(this, new KunefeBootstrap.ProgressListener() {
                @Override
                public void onProgress(String message, int percent) {
                    int clamped = Math.max(0, Math.min(100, percent));
                    publish(message, 55 + (clamped * 45 / 100));
                }
            });

            publish(getString(R.string.kunefe_setup_done), 100);
            mHandler.postDelayed(this::launchLauncher, 450);
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = throwable.getClass().getSimpleName();
            }
            showError(message);
        }
    }

    private void launchLauncher() {
        if (mDestroyed) return;
        mRunning = false;
        startActivity(new Intent(this, LauncherActivity.class));
        finish();
    }

    private void publish(String message, int percent) {
        mHandler.post(() -> {
            if (mDestroyed) return;
            mPhaseText.setText(message);
            if (percent >= 0 && percent != mLastPercent) {
                mLastPercent = percent;
                mProgressBar.setProgress(percent);
                mPercentText.setText(getString(R.string.kunefe_setup_percent, percent));
            }
        });
    }

    /** Yüzdeyi değiştirmeden yalnızca durum metnini günceller. */
    private void publishMessage(String message) {
        mHandler.post(() -> {
            if (!mDestroyed) mDetailText.setText(message);
        });
    }

    private void showSetupUi() {
        mHandler.post(() -> {
            if (mDestroyed) return;
            mErrorText.setVisibility(View.GONE);
            mActionRow.setVisibility(View.GONE);
            mSkipHint.setVisibility(View.GONE);
            mProgressBar.setProgress(0);
            mLastPercent = -1;
        });
    }

    private void showError(String message) {
        mHandler.post(() -> {
            if (mDestroyed) return;
            mRunning = false;
            mPhaseText.setText(R.string.kunefe_setup_failed);
            mDetailText.setText(message);
            mErrorText.setText(message);
            mErrorText.setVisibility(View.VISIBLE);
            mActionRow.setVisibility(View.VISIBLE);
            mSkipHint.setVisibility(View.VISIBLE);
            mProgressBar.setProgress(0);
            mPercentText.setText(getString(R.string.kunefe_setup_percent, 0));
        });
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mMinecraftProgress);
        super.onDestroy();
    }
}
