# Runtime notices and provenance

The unchanged license texts and upstream notice aggregates are shipped in
`app/src/main/assets/licenses/`, so they accompany both the APK and source ZIP.
The app's source and modifications remain GPL-3.0; dependency notices do not
replace [project provenance](../UPSTREAM.md) or the root GPL license.

| Component | Version/reference | Retained notice |
| --- | --- | --- |
| MapLibre Native Android | `org.maplibre.gl:android-sdk:13.7.1`, upstream tag `android-v13.7.1` | BSD2 license plus the tag's complete native and Android notice aggregates |
| Kotlin standard library | `2.2.20`, source tag `v2.2.20` | Exact upstream Apache2 license |
| Kotlin coroutines Android/core | Resolved `1.10.2` (MapLibre transitively raises the declared `1.9.0` floor), source tag `1.10.2` | Exact upstream Apache2 license and copyright appendix |
| AndroidX core/core-ktx | `1.13.1` | Apache2 terms and copyright lines from both exact published source JARs |
| BRouter embedded Java core | `v1.7.10`, commit `4d2639af77ea5ed9c30d3e400764eb6f9e8522da` | MIT license; unchanged source manifest and profile provenance in `brouter/` |

The Gradle-distributed OkHttp public-suffix NOTICE and other artifact resources
are retained. MapLibre's aggregates also preserve its native/transitive library
notices; the files are copied intact rather than rewritten from a current web
page. Map data attribution displayed by MapLibre remains a separate requirement.
These notices describe software bundled here, not ownership of map data or the
user's installed assistant applications.

Exact primary sources:

- `https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSE.md`
- `https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSES.core.md`
- `https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/platform/android/LICENSE.md`
- `https://raw.githubusercontent.com/JetBrains/kotlin/v2.2.20/license/LICENSE.txt`
- `https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/LICENSE.txt`
- `https://dl.google.com/dl/android/maven2/androidx/core/core/1.13.1/core-1.13.1-sources.jar`
- `https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.13.1/core-ktx-1.13.1-sources.jar`

Kotlin's top-level `license/NOTICE.txt` identifies its compiler distribution, not
the runtime library shipped in the APK; it is not mislabeled as a runtime notice.
The runtime license text is retained in full. Build-only tools and test-only
dependencies are obtained by Gradle and are not bundled as application code.


The Lite source includes the production installed-AA identity relationship reader. Its exact upstream ExtractKey/AOSP decoder attribution is retained in app/src/main/java/org/openandroidauto/identity/IDENTITY-PROVENANCE.md. It bundles no installed Android Auto APK, certificate or private key.

This project does not compile or bundle the full app's voice-recognition, assistant or shell-helper components.
