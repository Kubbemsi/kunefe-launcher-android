package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.dialogOnUiThread;
import static net.kdt.pojavlaunch.Tools.hasMods;
import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.runOnUiThread;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.KunefeBootstrap;
import net.kdt.pojavlaunch.KunefeSettingsActivity;
import net.kdt.pojavlaunch.KunefeUpdater;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.List;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    private TextView mUsernameView;
    private TextView mServerStatusView;
    private boolean mPreparingLaunch = false;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);
        Button mSettingsButton = view.findViewById(R.id.kunefe_settings_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);
        mUsernameView = view.findViewById(R.id.kunefe_username);
        mServerStatusView = view.findViewById(R.id.kunefe_server_status);

        // Bazi layout varyantlarinda (orn. eski yatay layout) bu ogeler bulunmayabilir.
        if (mSettingsButton != null) {
            mSettingsButton.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), KunefeSettingsActivity.class)));
        }

        if (mNewsButton != null) {
            mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
            mNewsButton.setOnLongClickListener((v) -> {
                Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
                return true;
            });
        }
        if (mDiscordButton != null) {
            mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        }
        if (mCustomControlButton != null) {
            mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        }
        if (mInstallJarButton != null) {
            if (hasOnlineProfile()) {
                mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
                mInstallJarButton.setOnLongClickListener(v -> {
                    runInstallerWithConfirmation(true);
                    return true;
                });
            } else mInstallJarButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));
        }
        if (mEditProfileButton != null) {
            mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));
        }

        if (mPlayButton != null) {
            mPlayButton.setOnClickListener(v -> {
                if (Tools.hasMods("sodium") && !(LauncherPreferences.DEFAULT_PREF.getBoolean("sodium_override", false))) {
                    AlertDialog sodiumWarningDialog = new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.sodium_warning_title)
                            .setMessage(R.string.sodium_warning_message)
                            .setNeutralButton(R.string.delete_sodium, (d, w) -> {
                                Tools.deleteSodiumMods();
                                ensureModsAndLaunch();
                            })
                            .create();
                    sodiumWarningDialog.show();
                } else ensureModsAndLaunch();
            });
        }

        if (mShareLogsButton != null) {
            mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));
        }

        if (mOpenDirectoryButton != null) {
            mOpenDirectoryButton.setOnClickListener((v) -> {
                if (Tools.isDemoProfile(v.getContext())) { // Say a different message when on demo profile since they might see the hidden demo folder
                    hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
                } else if (!hasOnlineProfile()) { // Otherwise display the generic pop-up to log in
                    hasNoOnlineProfileDialog(requireActivity());
                } else openPath(v.getContext(), getCurrentProfileDirectory(), false);
            });
        }
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
        refreshUser();
        checkServer();
        KunefeUpdater.checkInBackground(requireActivity());
    }

    /** Oyna'ya basıldığında modları doğrular, sonra oyunu başlatır. */
    private void ensureModsAndLaunch() {
        if (mPreparingLaunch) return;
        mPreparingLaunch = true;
        Toast.makeText(requireContext(), R.string.kunefe_mods_checking, Toast.LENGTH_SHORT).show();

        final android.content.Context context = requireContext();
        new Thread(() -> {
            try {
                KunefeBootstrap.ensureMods(context);
            } catch (Throwable ignored) {
            }
            if (!isAdded()) {
                mPreparingLaunch = false;
                return;
            }
            requireActivity().runOnUiThread(() -> {
                mPreparingLaunch = false;
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
            });
        }, "kunefe-mod-check").start();
    }

    private void refreshUser() {
        if (mUsernameView == null || getContext() == null) return;
        String username = requireContext()
                .getSharedPreferences("kunefe_launcher", android.content.Context.MODE_PRIVATE)
                .getString("username", "");
        if (username == null || username.isEmpty()) {
            mUsernameView.setText(R.string.kunefe_home_no_user);
        } else {
            mUsernameView.setText(getString(R.string.kunefe_home_welcome, username));
        }
    }

    /** Sunucuya TCP ile baÄŸlanÄ±p Ã§evrimiÃ§i durumunu gÃ¶sterir. */
    private void checkServer() {
        if (mServerStatusView == null) return;
        mServerStatusView.setText(R.string.kunefe_server_checking);
        mServerStatusView.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.kunefe_dim));

        new Thread(() -> {
            boolean online = false;
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(
                        KunefeBootstrap.SERVER_HOST, KunefeBootstrap.SERVER_PORT), 3000);
                online = true;
            } catch (Exception ignored) {
            }

            final boolean result = online;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (mServerStatusView == null || !isAdded()) return;
                mServerStatusView.setText(result ? R.string.kunefe_online : R.string.kunefe_offline);
                mServerStatusView.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), 
                        result ? R.color.kunefe_online : R.color.kunefe_offline));
            });
        }, "kunefe-server-ping").start();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
