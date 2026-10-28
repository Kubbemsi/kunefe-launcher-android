package net.kdt.pojavlaunch;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.Date;
import java.util.Map;

/** KunefeSMP için hazır profil oluşturur ve seçer. */
public final class KunefeSetup {
    private static final String PROFILE_NAME = "KunefeSMP";
    private static final String VERSION = "1.21.1";

    private KunefeSetup() {}

    /** Var olan sürümü koruyarak profili hazırlar. */
    public static void ensureProfile(Context context) {
        ensureProfile(context, null);
    }

    /**
     * Profili hazırlar ve sürümünü ayarlar.
     *
     * @param versionId Fabric sürüm kimliği (ör. fabric-loader-0.16.0-1.21.1).
     *                  null ise mevcut sürüm korunur; hiç yoksa 1.21.1 yazılır.
     */
    public static void ensureProfile(Context context, String versionId) {
        try {
            LauncherProfiles.load();

            String key = null;
            for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
                MinecraftProfile profile = entry.getValue();
                if (profile != null && PROFILE_NAME.equals(profile.name)) {
                    key = entry.getKey();
                    if (versionId != null && !versionId.isEmpty()) {
                        profile.lastVersionId = versionId;
                    } else if (profile.lastVersionId == null || profile.lastVersionId.isEmpty()) {
                        profile.lastVersionId = VERSION;
                    }
                    break;
                }
            }

            if (key == null) {
                MinecraftProfile profile = new MinecraftProfile();
                profile.name = PROFILE_NAME;
                profile.type = "custom";
                profile.lastVersionId = (versionId == null || versionId.isEmpty()) ? VERSION : versionId;
                profile.created = new Date().toString();
                profile.lastUsed = profile.created;
                key = LauncherProfiles.getFreeProfileKey();
                LauncherProfiles.mainProfileJson.profiles.put(key, profile);
            }

            LauncherProfiles.write();
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key)
                    .apply();
        } catch (Throwable throwable) {
            Log.w("KunefeSetup", "Kunefe profili hazırlanamadı", throwable);
        }
    }
}
