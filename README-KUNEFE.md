# Künefe Launcher — Android

KünefeSMP sunucusu için **Android istemcisi**. Masaüstü Künefe Launcher 4.6'nın mekaniklerini (giriş, sürüm kurulumu, modlar, doku paketi, skin, otomatik sunucu bağlantısı, otomatik güncelleme) Android'e taşıyan bir launcher.

Depo: <https://github.com/Kubbemsi/kunefe-launcher-android> — uygulamanın Ayarlar ekranında da bu bağlantı bulunur.

Bu depo, [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) projesinin (PojavLauncher tabanlı, **LGPLv3**) bir fork'udur. LGPLv3 gereği, dağıtılan APK'nın kaynak kodu bu depoda yayınlanmaktadır.

- Üst proje: `AngelAuraMC/Amethyst-Android`, fork tabanı commit `28d6e12`
- Lisans: **GNU Lesser General Public License v3.0** (`LICENSE`)
- Paket adı: `com.kunefesmp.launcher`
- Sürüm: `4.6-android` (versionCode `4600`)
- Desteklenen mimari: **arm64-v8a** (Android 5.0 / API 21+)

## İndirme

- APK: <https://kunefesmp.com.tr/kunefe-android.apk>
- Sürüm bilgisi (JSON): <https://kunefesmp.com.tr/kunefe-android-version>

## Bu fork'ta yapılan değişiklikler

Amethyst çekirdeği (JRE, Fabric/Forge, renderer, giriş katmanı) korunmuştur. Üzerine eklenenler:

| Alan | Dosya / sınıf | Açıklama |
| --- | --- | --- |
| Giriş / kayıt | `KunefeLoginActivity` | `/kunefe-login`, `/kunefe-register` uçları; "Beni Hatırla" varsayılan açık, kayıtlı hesap varsa giriş ekranı atlanır |
| İlk kurulum | `KunefeSetupActivity`, `KunefeInstaller` | Depolama izni, MC 1.21.1 indirme, Fabric kurulumu, profil oluşturma |
| İstemci dosyaları | `KunefeBootstrap` | `options.txt` (FPS ayarları), sunucu listesi, skin/CSL, doku paketi, mod indirme/kaldırma |
| Mod kataloğu | `KunefeModCatalog` | Modrinth üzerinden çalışma anında indirme; zorunlu + isteğe bağlı mod ayrımı |
| Ana ekran | `fragments/MainMenuFragment`, `fragment_launcher.xml` | Künefe teması, kullanıcı adı, TCP sunucu ping'i, Oyna akışı |
| Ayarlar | `KunefeSettingsActivity` | FPS ön ayarları, skin yükleme, isteğe bağlı modlar, güncelleme denetimi, çıkış |
| Kimlik doğrulama | `KunefeAuth`, `Tools` | Oyun açılmadan önce `/kunefe-auth` çağrısı, sunucuya `--server` argümanları |
| Güncelleme | `KunefeUpdater` | `GET /kunefe-android-version`, uygulama içi indirme + kurulum |
| Paketleme | `build.gradle` | Yalnızca arm64-v8a paket, diğer ABI varlıklarının dışlanması, kendi imzalama yapılandırması |

Sunucu tarafı (Künefe eklentisi, uçlar) bu depoda yer almaz.

## Derleme

Gereksinimler:

- JDK 17
- Android SDK (compileSdk 37, build-tools kurulu)
- Android NDK `27.3.13750724`
- Git alt modülleri

```bash
git submodule update --init --recursive
```

İmzalama için `app_pojavlauncher/keystore.properties` dosyasını kendiniz oluşturun (depoda **yoktur**):

```properties
storePassword=...
keyPassword=...
keyAlias=...
```

ve `kunefe-release.jks` dosyasını aynı klasöre koyun. Alternatif olarak `KUNEFE_KEYSTORE_PASSWORD` / `KUNEFE_KEY_PASSWORD` ortam değişkenleri kullanılabilir.

```bash
./gradlew :app_pojavlauncher:assembleRelease
```

Çıktı: `app_pojavlauncher/build/outputs/apk/release/app_pojavlauncher-arm64-v8a-release.apk`

> Not: Depoda imzalama anahtarı ve parolaları bilinçli olarak yoktur.

## Lisans ve kaynak kodu

Bu proje LGPLv3 altında dağıtılır. Değiştirilmiş kaynak kodu bu depoda mevcuttur; APK'yı dağıtan herkes aynı haklara sahiptir. Amethyst ve PojavLauncher'a ait özgün telif hakları ilgili sahiplerine aittir.

Minecraft, Mojang Studios'un tescilli yazılımıdır. Bu proje Mojang, Microsoft veya Amethyst geliştiricileriyle bağlantılı değildir.
