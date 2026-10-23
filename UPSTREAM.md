# LibreAuto upstream software and integration provenance

LibreAuto 0.3.0-preview.1 combines the BluetoothAuto 0.2.0-preview.18 player
foundation with the OpenAuto Lite projection source in one Android package.
The player remains `org.bluetoothauto`; `projection` is an internal library,
not a separately installed app. Original app additions remain GPL-3.0-or-later;
imported GPL code and third-party components retain their own stated terms.
The root GPL license and per-file notices remain authoritative.

## Projection module

The OpenAuto Lite protocol/channel source derives from
[mretallack/AndroidAuto](https://github.com/mretallack/AndroidAuto), commit
`366ee5fee58f7ddeee7f824ff167853b5ea1d0cf`, GPL-3.0. Its original license and
`Open-Auto-UPSTREAM.txt` are retained in the projection module's assets.
Microphone message checks also reference GPL-3.0-or-later
[f1xpl/aasdk](https://github.com/f1xpl/aasdk), commit
`046b3b381595509d0939fa84b14a90978f46ff63`, Copyright 2018 f1x.studio
(Michal Szwaj). This bundle adds no full assistant harness, accessibility driver,
recognition model or desktop Lab.

The optional installed-AA identity reader retains its exact ExtractKey and
AOSP Base64 decoder provenance in
[IDENTITY-PROVENANCE.md](projection/src/main/java/org/openandroidauto/identity/IDENTITY-PROVENANCE.md).
It reads the pinned, separately installed Android Auto 17.8.663874 variant and
fails closed for unsupported packages. No Google APK, extracted identity,
private key or imported upstream credential file is distributed. Acceptance by
a real head unit is a separate result, not established by this source history.

Projection offline routing includes BRouter's unchanged MIT Java core,
release 1.7.10, commit `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`.
[Vendored provenance](third_party/brouter/UPSTREAM.md), source/asset hash
manifests, license and profile resources are retained. Routing graphs and
offline map/address data are downloaded separately; their map-data terms and
attribution remain distinct from the software licenses. See
[offline address data notice](third_party/Offline-Address-Data-NOTICE.txt).
The small AOSP JsonReader host-test copy retains Apache-2.0 attribution in
`third_party/aosp-jsonreader-host/`.

## New integration

Original integration code shares the player library, queue, saved destinations
and units, and arbitrates projection/Bluetooth resource ownership. MapLibre is
pinned to the same 13.7.1 dependency in both modules; Gradle resolves one runtime
dependency rather than requiring two MapLibre applications. Bluetooth output
uses LibVLC; projected output retains the MediaCodec-to-PCM transport adapter.
Their presence in one APK does not require concurrent playback engines.

The optional fixed-package Android Auto enable-state switch uses MIT-licensed
[Shizuku API/provider](https://github.com/RikkaApps/Shizuku-API) 13.1.5,
Copyright 2021 RikkaW. The exact retained MIT text accompanies the APK under
`licenses/Shizuku-API-MIT.txt`. Its official Maven coordinates are
`dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5`, with
their version-matched AIDL/shared support modules. Shizuku itself is separately
installed. This short-lived, fixed-package switch is a new integration, not
the historical full prototype's excluded assistant helper. It contains no
arbitrary shell command interface, performs no root bootstrap and changes
only the separately installed Android Auto package's enable setting.

The player foundation's `licenses/sources.json` is its retained automated text
collection inventory. The subsequently added Shizuku MIT text and AndroidX
CarConnection protocol notice are retained alongside that collection and
documented separately; the old inventory is not the unified app's full
dependency list. See both Gradle module declarations and resolved dependency
reports when changing the build.

The paired-car profile receiver is original code, disabled until explicit
opt-in. It handles selected, already-paired Bluetooth car events without a
Wi-Fi-only heuristic, Bluetooth scanning or an idle foreground polling service.
The source package excludes preferences, playlists, user media, downloaded
areas, car selections, device evidence, backups and developer signing keys.

## Retained BluetoothAuto player provenance

The following component references and implementation history are retained from
the player foundation. They describe that player's pipeline; statements about
offline components not being ported refer to its separate phone map/artwork
pipeline. The optional projection module described above carries its own
offline navigation code. Historical preview test claims are not acceptance
evidence for the unified APK; see the current `TESTED.md`.

The original hint-placement preference selects song-title hints, navigation
artwork, or both. It gates only presentation work: title-only mode bypasses the
map/artwork renderer and Google-mode artwork GPS requests while an active Open
maps route continues to receive the location fixes it needs. Switching modes
cancels obsolete artwork callbacks without discarding the bounded map cache.
Original album tags are preserved separately from navigation instructions.

The local playlist importer and shuffled queue are original app code. They add
no playback dependency and use only read access granted by Android's document
picker. Playlist locations do not grant access to arbitrary storage or streams.
Turn artwork now caches a confirmed junction for the entire maneuver and only
recomposes its bottom instruction/distance strip. Visible map attribution is
retained above that strip.

BluetoothAuto's own source is GPL-3.0-or-later; see `LICENSE`. Third-party code
and data retain their respective licenses. The APK includes the text collection
under `app/src/main/assets/licenses/`. Its `sources.json` records retrieval URLs,
byte counts and SHA-256 hashes for collected upstream text files.

## LibVLC Android 3.7.7

- Maven coordinate: `org.videolan.android:libvlc-all:3.7.7`.
- Official artifact directory, including AAR, POM and Java source JAR:
  <https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/>.
- Official source project: <https://code.videolan.org/videolan/libvlcjni>.
- Versioned JNI source used by the pinned artifact:
  <https://code.videolan.org/videolan/libvlcjni/-/tree/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6>.
- Build entry point at that revision:
  <https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/buildsystem/compile-libvlc.sh>.
- Native source setup and patches:
  <https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/buildsystem/get-vlc.sh>
  and the same revision's `libvlc/patches/` folder.

The pinned POM identifies **GNU LGPL version 2.1**. The accompanying
`libvlc-all-3.7.7-sources.jar` headers for `LibVLC.java` and `MediaPlayer.java`
explicitly permit **version 2.1 or any later version**, so BluetoothAuto's
notices describe the binding as LGPL-2.1-or-later. The exact upstream
`libvlc/COPYING.LIB` and root `LICENSE` texts are included, as is a verbatim
copyright/header excerpt from that Maven source JAR.

That binding-level label does not relicense every native VLC plugin or codec.
VLC and its contrib dependencies retain their own upstream terms. The pinned
native build script supports GPLv3, LGPLv3-plus-advertising-clauses, and
LGPLv2-plus-advertising-clauses contrib modes; its default is the GPLv3 mode.
The collection therefore also retains the native source tree's GPL and LGPL
texts. A custom codec/contrib rebuild must preserve that build's additional
dependency notices and corresponding source; the headline Maven license alone
is not a complete native-code inventory.

The AAR's `res/values/values.xml` records `build_libvlc_revision=0b8dc65` and
`build_vlc_revision=586dd059ac`. The source setup script pins the public native
VLC base `84177b2273abc5c4300234e6e18b55a4d2dd5a03` from
<https://code.videolan.org/videolan/vlc> on branch `3.0.x`, then applies its
bundled patches. The resulting native revision in the AAR is a patched build
revision; it should not be treated as an independently fetchable public VLC
commit. Native `COPYING` and `COPYING.LIB` texts included here come from the
recorded public base revision.

BluetoothAuto uses the published AAR without modifying LibVLC. Its own wrapper
selects audio-only playback and supplies no video surface. The AAR contains
native VLC, JNI and C++ runtime libraries. The supplied preview retains ARM64;
an ARMv7 source build can select `-PbluetoothAutoAbi=armeabi-v7a`. Stock VLC and
its user interface are not bundled or needed. Android's `MediaMetadataRetriever`
reads local tags/embedded covers separately from playback, with bounded decoded
art, a memory-only cache and a UI timeout. Its native operation cannot be safely
force-killed; the sole worker retains resources until that operation returns and
cleanup can run. No new metadata-extraction dependency is added.

To replace/rebuild the library, obtain the source revision above, retain its
native-source setup script and patch set, and follow that revision's build
scripts/help with a suitable Android SDK/NDK and host toolchain. The upstream
README contains background setup instructions, but its older `compile.sh`
example does not match the current `compile-libvlc.sh` entry point. Consult the
pinned script rather than assuming that older command still exists.

For a locally rebuilt AAR, replace the LibVLC Maven dependency in
`app/build.gradle.kts` with a file dependency or your own Maven coordinate, then
rebuild the supplied app source. This allows relinking with a modified library;
the app's source is supplied for that purpose. Library rebuild instructions are
source pointers, not a claim that the native dependency was reproduced here.

## MapLibre and map data

- Renderer: `org.maplibre.gl:android-sdk:13.7.1`, BSD-2-Clause.
- Pinned source:
  <https://github.com/maplibre/maplibre-native/tree/android-v13.7.1>.
- Artifact directory:
  <https://repo.maven.apache.org/maven2/org/maplibre/gl/android-sdk/13.7.1/>.
- Android build documentation:
  <https://maplibre.org/maplibre-native/docs/book/platforms/android/index.html>.
- The pinned tag's root `LICENSE.md`, `LICENSES.core.md`, and Android
  `platform/android/LICENSE.md` are included. The latter two preserve bundled
  native/font/image dependency notices beyond the renderer's headline license.
- Support libraries include MapLibre GeoJSON/Turf 6.0.1 (Maven POMs declare
  Apache-2.0; the upstream 6.0.1 root LICENSE is MIT and is retained as well) and
  MapLibre Android Gestures 0.0.4 (BSD-2-Clause with additional retained notices).
  Sources: <https://github.com/maplibre/maplibre-java/tree/6.0.1> and
  <https://github.com/maplibre/maplibre-gestures-android>.

The public style/tile service is **OpenFreeMap**:
<https://openfreemap.org/>. The renderer uses its Liberty style, separately
from Google Maps. The SDK's bottom attribution remains unobscured in artwork
containing a map; the former duplicate upper attribution strip is removed.
Map source/data information is available at
<https://openmaptiles.org/> and <https://www.openstreetmap.org/copyright>.
OpenStreetMap data is licensed under ODbL 1.0:
<https://opendatacommons.org/licenses/odbl/1-0/>. Rendered images and the software
renderer have distinct attribution/license requirements; keeping software
notices does not replace map-data attribution in the image.

The app uses the published MapLibre AAR unchanged. It renders the phone's Open
maps screen and surrounding-map snapshots for stereo artwork. Original vector
maneuver drawings overlay the artwork; Google notification bitmaps are ignored.
No Google map tiles, SDK, route polyline, or extracted Google route geometry are
part of this map image. Open maps provides its actual upcoming maneuver's
begin-shape junction and a bounded blue approach/departure route slice, only
with fresh matched on-route progress. Google mode can instead display a local
OSM road-path inference after the conservative matching described below.

The camera rotates the incoming road upward into the actual/matched junction
and crops around it. It uses the last25m of the ordered approach rather than
rotating the bitmap or guessing direction from the road name. Map labels and
attribution are rendered by MapLibre in that camera; the position arrow is
relative to the cached camera bearing. A supplied posted limit
sets the preferred scale, otherwise a valid GPS speed or a default scale is
used. Lower speeds prefer a closer view. Neither road category nor provider
travel time is converted into a supposed posted speed limit. The camera may
omit a distant phone position to keep the turn useful at album-art sizes.

Original adaptive-distance code coalesces same-turn title/artwork changes at
1 second within 150 m, 2 seconds within 500 m and 5 seconds beyond that. It uses
actual supplied distances; Google notification cadence can limit freshness.
Location requests follow 1/2/3-second near/approach/far intervals while guidance
needs them. A compatible native snapshot is refreshed no more than once per
15 seconds for the same turn, with up to 45 seconds of reuse during replacement.
An owned 512-pixel static map/blue-path layer is reused for distance-only
composition; current-position and instruction cards are drawn separately.
New-turn identity, view compatibility, projection visibility and fresh-position
checks still gate reuse. These caches are bounded RAM objects, not downloaded
offline areas or a persistent journey history.

The built-in Open maps option uses these online services through original app
adapters, without embedding their server programs:

- Photon place/address search: <https://photon.komoot.io/api/>, upstream source
  <https://github.com/komoot/photon>. Requests contain search text and, when
  available, a nearby location bias.
- Valhalla driving routes: <https://valhalla1.openstreetmap.de/route>, upstream
  source <https://github.com/valhalla/valhalla>. Requests contain the route origin,
  destination and optionally travel bearing. After publishing the basic route,
  one optional bounded request to the same service's `/trace_attributes` sends
  its returned polyline to obtain posted speed limits. It is limited to routes
  with at most 10,000 points, uses a 12-second request deadline and checks exact
  shape/segment alignment before assigning any limits. Missing or changed
  geometry leaves limits unknown and preserves navigation. Shape-level
  `speed_limit` values are km/h, as documented by Valhalla's
  <https://github.com/valhalla/valhalla/blob/master/src/tyr/trace_serializer.cc>.
- Optional Google-mode road geometry: <https://overpass-api.de/api/interpreter>,
  a public Overpass query service returning OpenStreetMap nodes/ways. The query
  contains only a bounded nearby area and road-type filters; notification text,
  contact names and destination names are not sent. The service sees the area
  and IP address. A new lookup has a 30-second minimum interval, a 12-second
  network deadline and a 3 MB response cap. One bounded graph is held only in
  RAM and reused for up to two minutes while it covers the requested area.

Google-mode `TurnMatcher` is original app code and a visual matcher, not a
Google route extractor or a replacement routing engine. It currently accepts
English left/right “Turn … onto [road]” instructions, a valid 5–1,200 m distance,
a recent accurate location and travel course. It follows connected drivable
OSM nodes, road names, one-way direction and turn direction, requiring a unique
matching junction. Unknown schemas, parallel-road ambiguity, missing graph
data or multiple candidate junctions return no blue path. Numeric directional
OSM maxspeed values may supply a limit; conditional/variable/lane-dependent
limits remain unknown. This describes implementation scope, not completed
live Google Maps acceptance; consult `TESTED.md` for the actual evidence.

Local route tracking advances through a returned route without a request for
every GPS fix. New routing/rerouting needs the online service. The offline area,
address-index and local routing engines from OpenAuto Lite have not been ported
to this separate app. Saved/Recent destinations and nicknames are local app
preferences; they are not an offline geocoder or a travelled-path history.

## AndroidX, Kotlin and other runtime notices

The direct AndroidX dependency is `androidx.core:core-ktx:1.13.1`; transitive
AndroidX components retain Apache-2.0 notices. Sources and the license are at
<https://android.googlesource.com/platform/frameworks/support/> and
<https://github.com/androidx/androidx>. AndroidX's own Apache license text is
included alongside the standard Apache-2.0 text.

The Kotlin compiler is pinned to 2.2.20, and the build resolves its standard
library. Kotlin source and Apache-2.0 license are at
<https://github.com/JetBrains/kotlin/tree/v2.2.20>. Kotlin's upstream NOTICE is
retained too; it identifies the compiler distribution and is supplementary to
the runtime license, rather than claiming the compiler runs in the APK.

MapLibre's transitive runtime dependencies include the following projects. Their
collected license texts are included; the Gradle runtime dependency report is
the authority for versions actually resolved by a particular build.

| Project | License | Official source |
| --- | --- | --- |
| OkHttp | Apache-2.0 | <https://github.com/square/okhttp/tree/parent-4.12.0> |
| Okio | Apache-2.0 | <https://github.com/square/okio/tree/parent-3.6.0> |
| Gson | Apache-2.0 | <https://github.com/google/gson/tree/gson-parent-2.10.1> |
| Timber | Apache-2.0 | <https://github.com/JakeWharton/timber/tree/5.0.1> |
| Kotlin coroutines | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.2> |

Generate a current inventory with:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

JUnit is used by host-side tests and is not an app runtime feature. Gradle,
Android Gradle Plugin and the Android SDK are build tools, rather than bundled
Android applications.

## Google Maps and original assets

Google Maps is a separately installed proprietary application; it is not
included in the APK. BluetoothAuto reads supported navigation notifications
after the user enables Android notification access. No Google SDK/API key or
Google account credential is embedded. Maneuver graphics are original vector
drawings selected from supported text/semantic guidance; notification-provided
Google images are ignored. Google notification content remains third-party
content, not app source or a bundled dataset.

Optional message speech reads only fresh incoming notifications from the
supported Messages, Telegram and Signal package allowlist. AndroidX's public
`NotificationCompat.MessagingStyle` extractor reads standard platform message
extras; no messaging app source, account database, attachment or proprietary
network protocol is included. Speech is delegated to Android's installed TTS
engine, preferring an installed offline voice. That engine's own voice/network
configuration controls synthesis; it is not a new bundled assistant or speech
recognition model.

The built-in demo WAV consists of original generated tones released under
CC0-1.0. Its license text is included. The demo position is the public Seattle
test point 47.6062, -122.3321. BluetoothAuto's original vector maneuver
symbols and UI code are part of the GPL-3.0-or-later app source.

## Distribution contents

Keep the APK, this project's source/build files, `LICENSE`, `UPSTREAM.md`, and
the full packaged license collection together when sharing this preview. An
APK alone contains the runtime notice assets, but it does not contain all
editable app source or the complete native library build trees. Preserve the
upstream source references and supply the corresponding source/build materials
when redistributing modified third-party libraries.
