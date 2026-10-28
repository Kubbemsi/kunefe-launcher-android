package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Masaüstü KunefeLauncher'daki mod listesinin Android karşılığı.
 *
 * Sodium Android'de desteklenmediği için zorunlu listeden çıkarılmıştır;
 * diğer modlar ve varsayılan seçimler aynıdır.
 */
public final class KunefeModCatalog {
    public static final String PREFS = "kunefe_launcher";
    public static final String PREF_OPTIONAL_MODS = "optional_mods";

    public static final class Mod {
        public final String slug;
        public final String name;
        public final String description;
        public final boolean required;
        public final boolean defaultEnabled;
        public final String category;

        Mod(String slug, String name, String description, boolean required,
            boolean defaultEnabled, String category) {
            this.slug = slug;
            this.name = name;
            this.description = description;
            this.required = required;
            this.defaultEnabled = defaultEnabled;
            this.category = category;
        }
    }

    private static final List<Mod> MODS = Arrays.asList(
            // Zorunlu modlar
            new Mod("appleskin", "AppleSkin", "Açlık ve doygunluk bilgisi", true, true, "Zorunlu"),
            new Mod("fabric-api", "Fabric API", "Fabric mod altyapısı", true, true, "Zorunlu"),
            new Mod("libipn", "libIPN", "Envanter profili kütüphanesi", true, true, "Zorunlu"),
            new Mod("cloth-config", "Cloth Config API", "Mod ayar ekranı altyapısı", true, true, "Zorunlu"),
            new Mod("fabric-language-kotlin", "Fabric Language Kotlin", "Kotlin mod desteği", true, true, "Zorunlu"),
            new Mod("audioplayer", "Audio Player", "Sunucu müzik sistemi", true, true, "Zorunlu"),
            new Mod("modmenu", "Mod Menu", "Mod listesi ekranı", true, true, "Zorunlu"),
            new Mod("simple-voice-chat", "Simple Voice Chat", "Yakın sesli sohbet", true, true, "Zorunlu"),
            new Mod("crosshair-indicator", "Crosshair Indicator", "Nişangah göstergesi", true, true, "Zorunlu"),
            new Mod("customskinloader", "CustomSkinLoader", "Skin yükleme altyapısı", true, true, "Zorunlu"),

            // FPS ve performans
            new Mod("lithium", "Lithium", "Oyun mantığı optimizasyonu", false, true, "FPS & Performans"),
            new Mod("ferritecore", "FerriteCore", "RAM kullanımını azaltır", false, true, "FPS & Performans"),
            new Mod("entityculling", "Entity Culling", "Görünmeyen entityleri çizmez", false, true, "FPS & Performans"),
            new Mod("immediatelyfast", "ImmediatelyFast", "Render hızını artırır", false, false, "FPS & Performans"),
            new Mod("krypton", "Krypton", "Ağ paketi optimizasyonu", false, false, "FPS & Performans"),
            new Mod("moreculling", "More Culling", "Ekstra render optimizasyonu", false, false, "FPS & Performans"),
            new Mod("lazydfu", "LazyDFU", "Oyun başlangıcını hızlandırır", false, false, "FPS & Performans"),

            // PVP
            new Mod("betterhitboxes", "Better Hitboxes", "Düzeltilmiş hitbox görünümü", false, false, "PVP"),
            new Mod("no-chat-reports", "No Chat Reports", "Sohbet raporlamayı engeller", false, true, "PVP"),
            new Mod("clear-skies", "Clear Skies", "Sis efektini kaldırır", false, false, "PVP"),
            new Mod("dynamic-crosshair", "Dynamic Crosshair", "Dinamik nişangah", false, false, "PVP"),

            // Kalite
            new Mod("shulkerboxtooltip", "Shulker Box Tooltip", "Sandık içeriğini önizler", false, false, "Kalite (QoL)"),
            new Mod("jade", "Jade", "Blok ve mob bilgisi gösterir", false, false, "Kalite (QoL)"),
            new Mod("roughly-enough-items", "REI", "Tarif ve item görüntüleyici", false, false, "Kalite (QoL)"),
            new Mod("continuity", "Continuity", "Bağlı doku (CTM) desteği", false, false, "Kalite (QoL)")
    );

    private KunefeModCatalog() {}

    public static List<Mod> all() {
        return MODS;
    }

    public static List<Mod> required() {
        List<Mod> list = new ArrayList<>();
        for (Mod mod : MODS) if (mod.required) list.add(mod);
        return list;
    }

    /** Kategorilere göre isteğe bağlı modlar (başlık sırası korunur). */
    public static List<String> categories() {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (Mod mod : MODS) {
            if (!mod.required) categories.add(mod.category);
        }
        return new ArrayList<>(categories);
    }

    public static List<Mod> optionalInCategory(String category) {
        List<Mod> list = new ArrayList<>();
        for (Mod mod : MODS) {
            if (!mod.required && category.equals(mod.category)) list.add(mod);
        }
        return list;
    }

    /** Kayıtlı seçim yoksa masaüstündeki varsayılanlar kullanılır. */
    public static Set<String> selectedOptional(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> stored = prefs.getStringSet(PREF_OPTIONAL_MODS, null);
        if (stored != null) return new HashSet<>(stored);

        Set<String> defaults = new HashSet<>();
        for (Mod mod : MODS) {
            if (!mod.required && mod.defaultEnabled) defaults.add(mod.slug);
        }
        return defaults;
    }

    public static void saveOptional(Context context, Set<String> slugs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PREF_OPTIONAL_MODS, new HashSet<>(slugs))
                .apply();
    }

    public static Mod bySlug(String slug) {
        for (Mod mod : MODS) if (mod.slug.equals(slug)) return mod;
        return null;
    }
}
