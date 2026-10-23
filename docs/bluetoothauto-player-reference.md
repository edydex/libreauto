# Historical BluetoothAuto player reference

This is the player documentation retained from BluetoothAuto 0.2.0-preview.18.
Use the root LibreAuto README for current installation, modes, switching and acceptance.
Version-specific test claims below describe that earlier preview, not the unified APK.

# BluetoothAuto 0.2.0-preview.18

A local music player that sends navigation hints in the song title, album artwork,
or both to an ordinary Bluetooth stereo. For example: `-> 0.3 mi · Morning drive`.
Choose destinations and settings on the phone; the stereo remains in Bluetooth
Audio mode.

The main screen is the player: album art, title, artist, seek bar, playback
controls, your queue and **Music library**. The **map icon** opens your chosen navigator; the
**gear** opens Settings. Google Maps is the default navigator. Spoken cues are
**Off** by default.

## Install and start

1. Install the test APK on an Android 8.0 or later ARM64 phone. The supplied app
   is separate from OpenAuto Lite and the HiBreak Android Auto scaling fix.
2. Pair the phone with the stereo normally and select **Bluetooth Audio** there.
3. Open **Music library**. Under **Playlists**, create a named playlist and tap
   **Add music**. Or choose **Folders → Add music folder** to browse a folder
   on the phone. Open a playlist or folder and choose ordered or shuffled
   playback. Stock VLC is not needed: the APK contains LibVLC's playback engine.
4. Open **gear → Connection & permissions** to allow playback notifications and
   location. Location is needed for matching turn-map artwork and Open maps.
5. For Google guidance or optional message speech, choose **Allow Google Maps
   guidance** and enable BluetoothAuto in Android's notification-access screen.
6. Tap the **map icon**, choose a destination in the selected navigator, and
   start directions. Return to the player whenever you want.

The player reads local title, artist, album, duration and embedded cover tags
when Android can expose them. Missing tags use the filename; missing covers use
an original record sleeve. Extracted tags and covers stay in bounded memory
caches and are not written back to your files or saved as a separate database.
The selected music queue is saved locally and holds up to 512 entries.

## Music library

**Music library** is always available from the player, including while music is
playing. Its two tabs keep named **Playlists** separate from browsable **Folders**.
When upgrading, your existing queue becomes a **Your music** playlist and
previously linked music folders appear under Folders. Your queue, files and
Android's saved file access are kept.

Under **Playlists**, tap **New playlist**, enter a name, then choose **Add music**.
You can select individual audio files, add a saved or newly chosen folder, or
import a VLC playlist. Open any playlist to choose **Play in order** or
**Shuffle**. Tapping a track starts ordered playback from that track.

**Hold a playlist or track’s ⋮ button and drag** to change its order. The target
row is highlighted, and the list scrolls when you hold near its top or bottom
edge. Releasing outside the list cancels the move. A normal tap still opens
the options menu.

Use a playlist's **⋮** menu to rename it, move it up or down in the library,
or delete it. Each track's **⋮** menu can move it up, down, to the top or to the
bottom; add it to another playlist; or remove it from the current playlist.
Removing tracks or deleting a playlist never deletes the original audio files.

Under **Folders**, choose **Add music folder** once through Android's folder
picker. Open that folder to browse its albums and subfolders; use Back or the
parent-folder button to go up. **Play folder** and **Shuffle** include the
selected folder's subfolders. **Add folder to playlist** adds that selection to
a new or existing playlist. An individual track's **⋮ → Add to playlist** adds
just that track. Folder names can be renamed in the library without renaming
anything on disk; removing a folder from the library leaves its files, playlists
and current playback in place.

Folder playback uses natural filename order: Track 2 comes before Track 10.
Tapping an individual file plays the tracks displayed in that folder, starting
with the chosen file. Only folders and files you select are accessed; browsing
does not start a scan of all the phone's music. If a folder has moved or Android
has lost access to it, choose it again through the folder picker.

The current queue is a separate copy of the music you chose to play. Editing a
saved playlist leaves the current track and queue alone; play the playlist
again when you want its updated contents or order. The queue button shows what
is playing now and lets you jump to another track or clear the queue. Clearing
it stops that music while keeping saved playlists, folders, file access and
the running navigation session.

### VLC playlist files and limits

Use **Import VLC playlist** in Playlists, or **Add music → Import a VLC playlist**
inside an existing playlist. Local **M3U/M3U8, XSPF and PLS** files preserve their
own track order, including repeated tracks. Export or save a playlist file from
VLC first; BluetoothAuto does not read VLC's private saved-playlist database.
For a playlist saved on another computer, select the music folder on this phone
so its local paths can be linked. Missing or unsupported entries are reported;
internet streams are not imported. BluetoothAuto saves the resulting playlist
in its own library; exporting a playlist file is not included in this preview.

The library supports up to **64 playlists**, **32 linked folders**, **512 tracks
per playlist** and **8,192 saved track entries** in total, with a 4 MB saved-library
limit. Repeated entries count toward those limits. Folder reads cover at most
**4,096 items and 12 subfolder levels** at a time. A limited result is reported;
choose a smaller folder or subfolder to reach the remaining music. Cancelling a
read leaves the saved playlists and current queue unchanged.

### Playback controls

The player's **Shuffle** control is saved. Changing it keeps the current track
and playback position. Shuffled playback visits each selected entry once per
cycle; Previous follows that cycle's listening order.

In **gear → Next turn hint in**, choose **Song title only**, **Album art only**,
or **Both** (the default). Title only keeps the track's original cover; album art
only keeps its original song title. The choice is saved and applies immediately
during playback. The phone player and its stereo preview follow the same artwork
choice. Tracks without an embedded cover keep the phone player's record sleeve.
Original album tags stay in the album field.

Navigation and optional spoken cues continue in every mode. Title only skips
navigation artwork composition, map snapshots and Google-mode artwork location
updates. An active Open maps route still uses location for directions and progress.

Previous restarts a track after its first three seconds; otherwise it selects
the preceding track. Natural
playback stops at the queue's end; pressing Next deliberately wraps to its start.

## Choose your navigation

In **gear → Maps app**, select:

- **Google Maps**: installed separately. Start navigation in Google Maps;
  BluetoothAuto reads supported guidance notifications. If guidance is missing,
  check notification access and the presence of a Maps navigation notification.
  On affected versions, disabling Maps' Live Updates notification feature may
  restore the ordinary notification format.
- **Open maps**: the built-in MapLibre/OpenStreetMap navigator. Search for a place
  or address, choose a result, and start directions. Use **Saved**, **Recent**,
  and **Center** above the map. Hold the map to drop a pin. Choose **Save as…** to
  give a result or pin a nickname; saved places can be renamed or removed.

Open maps uses online Photon search and Valhalla driving routes. Once a route
is loaded, progress along it is calculated locally and continues when you
return to music while BluetoothAuto's foreground session runs. New searches,
new routes and off-route recalculation need internet. There is **no downloaded
area manager, offline address index or offline route engine** in this APK.
Cached tiles are not a dependable downloaded-area feature.

Stereo artwork places an original vector turn arrow and distance below the map.
The map shows a confirmed upcoming turn:

- **Open maps** passes the same complete route used by the phone map to
  MapLibre's native blue line layer, so every visible part of that route stays
  highlighted. A separate bounded maneuver window controls camera framing.
  It requires a fresh, confidently matched location on that
  route; it does not guess through an off-route or ambiguous fix.
  Complete curved departures are retained up to the next meaningful maneuver,
  with bounded context (up to 500 m for ordinary turns or 2 km for highway
  ramps/forks). An associated chain of signed highway forks can include the
  actual highway join and a short mainline tail. Its camera can focus on the
  curved connection rather than fitting a long straight entry, while the full
  route remains blue wherever visible. Road speed sizes approach context;
  maneuver geometry determines how closely the picture can zoom.
  The route comes directly from the selected navigator, so this
  mode does not wait for Google notification matching or route reconstruction.
  Uncached map backgrounds can still take time to download and render.
  Departure vertices are kept intact; an unusually dense turn that exceeds
  the artwork's point budget retains the direction card instead of showing
  a shortened curve or drawing shortcuts through it.
- **Google Maps** can try to match a nearby OSM junction for supported English
  instructions such as “Turn right onto James Street.” A usable travel course,
  distance and uniquely connected road match are required. This blue local
  path is inferred from OSM roads; Google does not supply its route geometry.
  Until the upcoming turn is confirmed, its instruction, arrow and distance
  remain visible on a direction card.

Google turn matching uses saved OSM roads first. When road data is missing
or the turn is farther away, it can search the notified road name with Photon
and validate bounded Valhalla route proposals against Google’s turn side, road,
distance and the phone’s course. Street lookup tries the established expanded
spelling first, without inventing an ordinal suffix. For a street turn more than
500 metres away, its first proposal prefers a farther real search result; the
same route checks and fallback still apply. Up to four exact search/rounded-area
keys, with eight results each, can be reused in RAM for five minutes. A new
location still requires fresh route validation; these are search results, not
cached accepted routes. For a highway exit or ramp, a named destination
on Google’s sign (including “ramp to [city]”) can also propose an endpoint; the destination name never
proves which road or turn to take. Nearby road downloads use Overpass. These are
separate services from the driving simulator’s map. Missing or ambiguous data
keeps the authoritative arrow/distance guidance while the turn remains
unconfirmed. Failed requests back off
from 30 seconds to at most five minutes; failures do not keep repainting an
unchanged card. The last reliable travel course
is retained across short stops, with bounds on its age and distance. An explicit
GPS simulator heading also works while stationary. No compass orientation is
substituted for the direction of travel.

Successful road downloads are now saved privately on the phone for seven days,
with a limit of eight areas / 16 MB. A covered saved area is used before any
network request, including after restarting the app. Failed requests therefore
do not prevent matching roads that have already been saved. **Settings →
Prepared maps** shows the saved-area count and can import a BluetoothAuto
map-area file or clear those saved roads. Import preserves the original download
date; it does not extend old data's lifetime. Your music and saved places are
separate. No personal test area is bundled in the APK or source export.

Open maps also prepares the next two confirmed route turns while the current
map is ready. At most three prepared backgrounds are kept in memory, and only
one native map snapshot runs at a time. Advancing to a prepared maneuver reuses
its picture immediately; distance updates still change only the footer. A
reroute, source/settings change or stopped session cancels future work.

### Google next-turn maps

Google integration uses its navigation notifications. A supported notification
provides the current instruction and distance, not a complete route, future
turn list or route shape. BluetoothAuto does not read the Google Maps screen,
open its Directions menu or request accessibility access.

One bounded English maneuver parser supplies the title arrow, phone/artwork
arrow and map matcher. It accepts lane-count forms such as “left 2 lanes,”
“left two lanes,” “two left lanes,” and “any lane,” with mirrored right-lane
forms. The following explicit action wins: “Use the right lane to turn left”
means a left turn. Lane side supplies an exit/ramp/merge side only when the
action does not name one. Unknown or conflicting actions remain neutral.
“Either lane,” “both lanes,” and middle/center/centre lanes also work without
inventing a lane-side preference.
The map additionally needs a usable named road; recognizing an arrow alone
does not establish where that turn is.

Google's instruction and distance are read automatically from its notification.
The map itself is rendered by BluetoothAuto from OpenStreetMap data; it is not
a screenshot or copy of Google's map. Current Android short notification text
is also read when available, so a newer distance-only update can replace an
older title distance. A rerouting/GPS status clears the previous turn distance.

Map matching uses the current hint, an accurate phone location, direction of
travel and connected drivable roads as constraints. It distinguishes turning
**onto** a road from turning **toward** a named street: the latter requires a
short, proved drivable connector reaching that street within 300 metres.
A place name alone does not establish a road. An unambiguous match supplies
**Upcoming turn**; missing data, unsupported wording and competing matches
leave the turn unconfirmed. The app does not invent future Google instructions
or promise to preload an entire Google trip. See **TESTED.md** for the current
phone and Lab acceptance results and limits.

Start navigation normally in Google Maps, keep notification access enabled,
and choose **Album art only** or **Both** to see turn artwork. No extra Google
preparation page, computer, ADB, root or Shizuku is needed for normal use.
Map background tiles can require internet even when nearby road data is saved;
this is not a downloaded offline-navigation area.

The artwork focuses on the upcoming maneuver. Until its location and path can
be confirmed, it keeps Google's current arrow, instruction and distance visible.

A confirmed **Upcoming turn** picture rotates so the final approach enters from
the bottom and the turn lies ahead. It frames the actual junction and replaces
the direction card. The first confirmed map stays fixed for that maneuver: phone
movement, speed and elapsed time do not pan, zoom or refresh it. There is no
moving car marker in this static turn picture. The approach road's posted
limit, when available, chooses the scale. Without a known posted limit, explicit
highway exit, ramp, keep or merge cues use a wider camera view; other turns use
a local-road view.
MapLibre draws the validated blue route itself through native map source and
line layers. It preserves the supplied path, including approach portions outside
the camera window, so every visible part of that path remains blue. The camera
fits the complete supplied departure above the instruction footer; it does not
cut a long ramp at a fixed display distance. Route reconstruction can retain a
curved highway departure up to two kilometres, stopping before the next
unconfirmed decision. If that optional extension exceeds the 512-vertex limit,
it keeps the previous exact 500-metre window instead of losing the map. No
vertices are simplified and no path is extended beyond known geometry. The
junction stays centered in the usable map area above the footer, including
when the departure curves to one side; the camera zooms out to fit that curve.

Distance changes update the selected title/artwork hints about once a second within
150 metres of a turn, once every two seconds within 500 metres, and once every
five seconds farther away. Updates arriving between those times are combined;
the next refresh uses the newest distance. For confirmed turns, only the
instruction/distance footer is recomposed; the map and blue path are reused.
A new turn, reroute, cleared guidance or explicit map-setting change discards the old picture. A first async
map/geometry result can improve an initially plain direction card.
Google mode follows distances its notifications provide; it does not invent a
countdown between them. The stereo may refresh more slowly.

Directions sit at the very bottom of the artwork. Required map credits stay
visible in a smaller translucent strip immediately above them. Prepared map
backgrounds are bounded and are separate from offline-navigation downloads.

Without a usable confirmed-turn map image, artwork keeps the direction card. **gear → Artwork** can disable the map while keeping the arrow.
**Distance units** offers Automatic, Miles / feet, and Kilometers / meters.

## Optional spoken cues

**gear → Audio notifications** offers **Off**, **Directions**, **Messages**, or
**Directions & messages**. Music pauses for a spoken cue and resumes afterward
if it was playing. Changing distance alone does not repeat the same turn.
Google Maps' own spoken directions are controlled separately in Google Maps;
turn one source off if you hear duplicate guidance.

Messages supports fresh incoming notification text from Google/AOSP Messages,
Telegram's official store/direct-download variants, and Signal during a running
BluetoothAuto session. It ignores notification-history refreshes, group
summaries, ongoing/secret notifications and recognized hidden-content
placeholders. Text is bounded and duplicates are suppressed in memory. This
preview reads messages; it does not send replies or start conversations.

Speech uses Android's text-to-speech engine, preferring an installed offline
voice. Voice availability and any network synthesis follow that engine's
settings. No microphone or assistant is involved.

## Current verification

Preview.18 adds the saved music library and hold-to-drag playlist/track ordering.
The final clean APK passed **769 JVM tests** and native Pixel persistence,
folder-access and VLC playback checks. Real phone checks covered creating/editing
playlists, local VLC imports, folder browsing, ordered/shuffled playback and
long-list drag scrolling, including cancellation. Original music and file access
are preserved. The new screens reuse visible rows and scan only when requested.
The mapping sources and native engines remain unchanged; this update is not a
new physical-car or HiBreak performance benchmark. See TESTED.md for detail.

Preview.17 keeps the whole built-in route blue and frames chained highway
connections separately. The final APK passed **708 JVM tests**, its artifact
audit and **65 native Android notification examples / 455 consumer checks**.
On the Pixel, the same captured I-5 route rendered before and after the update:
the entered highway changed from visible-but-grey to blue, and road detail
became **2.53× larger**. All 680 actual route vertices reached MapLibre; visible
mainline beyond the small camera-selection slice also remained highlighted.
Distance updates reused one native picture and changed only its footer.
This is controlled native acceptance, not a new whole-trip or physical-car test.
The packaged turn/player screenshots show the updated built-in map behavior;
the settings screenshot is an earlier example of the unchanged settings UI.

The historical preview.15 record includes centered native-route fixtures and
search-proposal optimization. On the Pixel, the same cold Google-reconstruction
request fell from 12.237 seconds / six service requests to 6.977 seconds /
two requests, with the same 72-point path. Those timings apply to that comparison;
public-service response time varies.

The historical preview.14 record includes native full-curve/cache fixtures,
two normal Google turn cues with recovery, and verified GPS/settings cleanup.
Those results apply to preview.14. See **TESTED.md** for artifact-specific
evidence; complete-trip and physical-car AVRCP verification remain separate.

## Test your stereo while parked

Open **gear → Preview & diagnostics → Play demo**. It plays original generated
tones with clearly labeled synthetic turns at a public Seattle point. Use
**Next demo turn** to check title and artwork refreshes. The short track can be
restarted with Play. Opening the map icon returns to live navigation mode.

Check title changes, artwork changes during the same track, media buttons,
phone screen-off playback and reconnecting. Test real navigation separately:
demo guidance does not validate the installed Google Maps notification format,
live Google/OSM junction matching, or the online routing services. Record the
phone, Android version, stereo model and refresh delay. The accompanying
`TESTED.md` records completed acceptance.

Some stereos support text but no phone-supplied artwork, or refresh artwork
slowly. A touchscreen/album display alone does not establish Bluetooth artwork
support. This preview makes no new physical-car compatibility claim.

The optional Mac Lab can move a simulated GPS position and observe the actual
phone-published title/artwork over USB. Its route coordinates move the test car;
normal phone/car use does not require entering coordinates. Google Maps still
produces the navigation notifications during those tests. The Lab's own map
and BluetoothAuto's artwork use separate map rendering from Google Maps.

## Data and permissions

Android grants broad notification-listener access. BluetoothAuto processes
Google Maps guidance and, only for optional message speech, the supported
messaging packages. It does not read notification history aloud, open message
attachments, or store message bodies. Music and notification content are not
uploaded by BluetoothAuto; optional spoken text is passed to the selected
Android speech engine.

OpenFreeMap receives tile/style requests for the area shown. Open maps sends
search text and an optional nearby location bias to Photon, and sends route
origin/destination coordinates to Valhalla. An optional route-metadata lookup
sends the returned route shape back to Valhalla for posted limits and named road
edges. Those edge labels are used only when the response retains the identical
shape and a complete, ordered edge partition. This metadata lookup is not a
stream of GPS-position updates. Google junction matching can request nearby roads from
Overpass, revealing the queried area. It makes at most one new network request
every 30 seconds and reuses a bounded road graph in RAM for up to two minutes.
Successful road downloads are also saved locally for seven days, within the
eight-area / 16 MB limit; covered areas are checked before another download.
Guidance text and the destination are matched locally, not sent to Overpass.
For Google turn pictures, Photon can receive the upcoming road name, a highway
sign’s named destination, and a nearby location rounded to two decimal degrees
(about 1 km). Exact phone location remains local for ranking those results;
bounded route proposals send the origin and candidate points to Valhalla.
Only a validated first maneuver matching the current Google instruction is
accepted. A proved signed-destination proposal can remain in memory for up to
20 minutes for the same exact highway reference/direction, within 30 km of its
original fix. A new cue still requires a fresh route and the complete maneuver
proof. At most two searches and four candidate endpoints are tried per bounded attempt; failures leave the
turn unconfirmed and retries are throttled.

These services also see the connection's IP address. Saved and Recent
places/nicknames stay on the phone. BluetoothAuto does not store the travelled
path or operate an account/analytics
service. Google Maps follows its own settings and service policies.

The visible foreground session owns playback, optional speech and background
route progress/location work. No accessibility service is included. No root,
ADB, Shizuku, contacts, SMS-reading, microphone or Bluetooth-scanning permission
is needed.

## Stop or remove

Choose **gear → Stop session**, or Stop session in the playback notification.
This ends BluetoothAuto's playback and location work; stop a separate Google
Maps trip in Google Maps if desired.

**Prepared maps → Clear saved road areas** removes road downloads. To erase
all private BluetoothAuto data without uninstalling, use Android **App info →
Storage & cache → Clear storage**; this also resets its playlists, linked folders,
queue, saved places and
preferences. If an earlier experimental build enabled a BluetoothAuto
accessibility entry, disable that entry in Android's accessibility settings.
This revision does not include it.

To remove everything: stop the session, turn BluetoothAuto off in Android's
notification-access settings, then uninstall it. Android removes its saved
playlists, linked folders, queue, saved/recent Open maps places, preferences,
cache and file-access grants.
Your original music and Google Maps stay in place. Restore any Maps Live Updates
setting or APK installer permission you changed if desired. Normal Bluetooth
pairing remains under Android's control.

## Build and licenses

Use JDK 17, SDK platform 36 and build-tools 35.0.1. Set `ANDROID_HOME` or a local
`local.properties` SDK path. Gradle 8.13, Android Gradle Plugin 8.13.0, Kotlin
2.2.20 and runtime library versions are pinned. From the project folder:

```sh
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat`. The default build is ARM64; an ARMv7 build can use
`-PbluetoothAutoAbi=armeabi-v7a`. The debug APK appears at
`app/build/outputs/apk/debug/app-debug.apk`. A local debug key may differ from a
supplied test APK, requiring uninstall before installing your own build. No NDK
is required to use the pinned prebuilt libraries.

App source is GPL-3.0-or-later. See `LICENSE`, `UPSTREAM.md`, and the packaged
`app/src/main/assets/licenses/` collection. This independent app is not affiliated
with Google, VideoLAN or MapLibre.
