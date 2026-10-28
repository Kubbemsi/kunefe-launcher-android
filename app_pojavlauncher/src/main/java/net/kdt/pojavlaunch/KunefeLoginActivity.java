package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * KunefeSMP hesap girişi. Basarili girişten sonra Amethyst'in normal
 * başlatıcı akışına (KunefeSetupActivity -> LauncherActivity) devam eder.
 */
public class KunefeLoginActivity extends BaseActivity {
    private static final String API_BASE = "http://oyna.kunefesmp.com.tr:7899";
    private static final String LAUNCHER_SECRET = "kunefe-gizli-anahtar-degistir";
    private static final String PREFS = "kunefe_launcher";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private EditText mUserInput;
    private EditText mPassInput;
    private EditText mPass2Input;
    private Button mActionButton;
    private TextView mToggleMode;
    private TextView mStatusText;
    private TextView mServerStatus;
    private ProgressBar mProgress;

    private boolean mRegisterMode = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kunefe_login);

        mUserInput = findViewById(R.id.user_input);
        mPassInput = findViewById(R.id.pass_input);
        mPass2Input = findViewById(R.id.pass2_input);
        mActionButton = findViewById(R.id.action_button);
        mToggleMode = findViewById(R.id.toggle_mode);
        mStatusText = findViewById(R.id.status_text);
        mServerStatus = findViewById(R.id.server_status);
        mProgress = findViewById(R.id.progress);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mUserInput.setText(prefs.getString("last_user", ""));

        // Masaüstündeki "Beni Hatırla" davranışı: varsayılan olarak açık.
        if (prefs.getBoolean("remember_me", true) && hasSavedAccount(prefs.getString("last_user", ""))) {
            startActivity(new Intent(this, LauncherActivity.class));
            finish();
            return;
        }

        mActionButton.setOnClickListener(v -> {
            hideKeyboard();
            submit();
        });
        mToggleMode.setOnClickListener(v -> setRegisterMode(!mRegisterMode));
        mPassInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard();
                submit();
                return true;
            }
            return false;
        });
        mPass2Input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard();
                submit();
                return true;
            }
            return false;
        });

        pingServer();
    }

    private void setRegisterMode(boolean register) {
        mRegisterMode = register;
        mPass2Input.setVisibility(register ? View.VISIBLE : View.GONE);
        mActionButton.setText(register ? R.string.kunefe_register_action : R.string.kunefe_login_action);
        mToggleMode.setText(register ? R.string.kunefe_have_account : R.string.kunefe_no_account);
        mStatusText.setVisibility(View.GONE);
        if (register) mPass2Input.requestFocus();
    }

    private void submit() {
        final String username = mUserInput.getText().toString().trim();
        final String password = mPassInput.getText().toString();
        final String password2 = mPass2Input.getText().toString();
        final boolean register = mRegisterMode;

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            showError("Kullanıcı adı 3-16 karakter olmalı (harf, rakam, _).");
            return;
        }
        if (password.length() < 4) {
            showError("Şifre en az 4 karakter olmalı.");
            return;
        }
        if (register && !password.equals(password2)) {
            showError("Şifreler eşleşmiyor.");
            return;
        }

        setLoading(true);
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("username", username);
                payload.put("password", password);
                String endpoint = register ? "/kunefe-register" : "/kunefe-login";
                HttpResult result = postJson(API_BASE + endpoint, payload.toString());

                JSONObject json = null;
                try {
                    json = new JSONObject(result.body);
                } catch (Exception ignored) {
                }

                boolean success = result.code == 200 && json != null && json.optBoolean("success", false);
                if (!success) {
                    String error = json != null ? json.optString("error", json.optString("message", "")) : "";
                    if (error.isEmpty()) {
                        error = (register ? "Kayıt başarısız" : "Giriş başarısız") + " (HTTP " + result.code + ")";
                    }
                    throw new IOException(error);
                }

                String uuid = json.optString("uuid", "");
                if (uuid.isEmpty()) uuid = UUID.randomUUID().toString();

                saveAccount(username, uuid);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_user", username)
                        .putBoolean("remember_me", true)
                        .apply();

                mHandler.post(() -> {
                    setLoading(false);
                    startActivity(new Intent(KunefeLoginActivity.this, KunefeSetupActivity.class));
                    finish();
                });
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = "Sunucuya ulaşılamadı.";
                final String finalMessage = message;
                mHandler.post(() -> {
                    setLoading(false);
                    showError(finalMessage);
                });
            }
        }, "kunefe-login").start();
    }

    private void saveAccount(String username, String uuid) throws IOException {
        if (Tools.DIR_ACCOUNT_NEW == null) {
            Tools.initEarlyConstants(getApplicationContext());
        }
        File accountDir = new File(Tools.DIR_ACCOUNT_NEW);
        if (!accountDir.exists() && !accountDir.mkdirs()) {
            throw new IOException("Hesap klasörü oluşturulamadı.");
        }

        MinecraftAccount account = new MinecraftAccount();
        account.username = username;
        account.accessToken = "0";
        account.clientToken = "0";
        account.profileId = uuid;
        account.selectedVersion = "1.21.1";
        account.isMicrosoft = false;
        account.save();

        PojavProfile.setCurrentProfile(getApplicationContext(), username);
    }

    /** Kayıtlı hesabın dosyası duruyor mu? Varsa giriş ekranı atlanır. */
    private boolean hasSavedAccount(String username) {
        if (username == null || username.isEmpty()) return false;
        if (Tools.DIR_ACCOUNT_NEW == null) {
            Tools.initEarlyConstants(getApplicationContext());
        }
        File account = new File(Tools.DIR_ACCOUNT_NEW, username + ".json");
        return account.isFile();
    }

    private void setLoading(boolean loading) {
        mProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        mActionButton.setEnabled(!loading);
        mActionButton.setAlpha(loading ? 0.65f : 1f);
        mToggleMode.setEnabled(!loading);
        if (loading) mStatusText.setVisibility(View.GONE);
    }

    private void showError(String message) {
        mStatusText.setText(message);
        mStatusText.setTextColor(ContextCompat.getColor(this, R.color.kunefe_offline));
        mStatusText.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void pingServer() {
        new Thread(() -> {
            boolean online = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("oyna.kunefesmp.com.tr", 25565), 3000);
                online = true;
            } catch (Exception ignored) {
            }
            final boolean isOnline = online;
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                mServerStatus.setText(isOnline ? R.string.kunefe_online : R.string.kunefe_offline);
                mServerStatus.setTextColor(ContextCompat.getColor(
                        this, isOnline ? R.color.kunefe_online : R.color.kunefe_offline));
            });
        }, "kunefe-ping").start();
    }

    private HttpResult postJson(String urlString, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Launcher-Secret", LAUNCHER_SECRET);

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }

            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            HttpResult result = new HttpResult();
            result.code = code;
            result.body = readAll(in);
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString().trim();
    }

    private static final class HttpResult {
        int code;
        String body = "";
    }
}
