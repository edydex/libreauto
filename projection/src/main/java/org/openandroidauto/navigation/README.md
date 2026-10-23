# Lite navigation and saved destinations

This is a phone-side map/navigation view owned by this projection client. It does not embed Google Maps, depend on the official Android Auto host, or capture another application's screen.

`NavigationPanel` uses MapLibre Native 13.7.1 and Android's `LocationManager`, with configurable Photon/Valhalla online services and committed local SQLite/BRouter packages for offline searches and car routes. `RoutePlan` and `RouteTracker` contain Android-independent geometry and guidance progress. The default adaptive30 build raises the map rendering ceiling to 30 FPS for interaction or movement and lowers it to 5 FPS when idle; a fixed15 comparison build remains available with `-PopenAutoMapFrameMode=fixed15`. These are ceilings, not continuously generated frame rates. The map uses a flat camera and hides building extrusion layers. Performance measurements are device/scenario-specific; the packaged testing notes distinguish measured behavior from unverified HiBreak battery and physical-car behavior.

## Integration

Production dependency:

```kotlin
implementation("org.maplibre.gl:android-sdk:13.7.1")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

OkHttp was already a MapLibre runtime dependency. The explicit declaration makes
its API available to the offline downloader, which scopes its concurrency and
request budget to an explicit phone-side download.

Host parser tests additionally need `testImplementation("org.json:json:20240303")`. The app needs Internet and precise location permissions. The parent activity requests permission; the panel never opens permission dialogs itself.

Construct `NavigationPanel(Context, Listener)`, add it to the client's own `Presentation`, then call `start()`, `stop()` and `onDestroy()` with the projection session's lifecycle. Forward low-memory callbacks through `onLowMemory()`. Normal projected touch should be dispatched through the presentation's view hierarchy. A TextureView-backed MapView is used so the native map participates in that window's composition.

Listener callbacks:

- `onNavigationInstruction(String text, double distanceMeters)` emits route-start, turn cues, reroute and arrival instructions. The parent can send these through the car audio channel.
- `onStatus(String text)` reports map/search/route/GPS status without logging location.
- `onDestinationSelected(String name, double latitude, double longitude)` reports a chosen destination.

Public controls include `search(text)`, `navigateTo(name, latitude, longitude)`, `stopNavigation()`, `setSpeechSuppressed(boolean)`, `getRoute()`, `getPosition()` and `getConfig()/setConfig()`. Suppress synthesis while an explicitly requested message dictation owns microphone input. `NavigationConfig.speakInstructions=false` leaves instruction callbacks active for a parent-owned audio implementation.

The panel's search field is disabled when a real GPS fix reports movement above 1.4 m/s. Voice/provider integrations should send a destination through the public methods, rather than manipulate its text fields. Input-mode restrictions still need validation against actual car speed signals.

## Saved and recent places

Map has one-tap **Saved** and **Recent** pickers. Opening a picker never starts a
route or requests a provider. Selecting one destination explicitly starts route
planning when GPS is available. **Save** next to a recent result or the current
destination opens the ordinary phone editor. **Edit on phone** opens the same
editor for adding/editing an address and nickname, removal, Home/Work assignment
and clearing Recent. On the phone, open **Maps & Saved → Add or edit Saved places
→ Add an address**. With validated internet, **Find address** can use Android's
Geocoder and Photon, with local fallback. Offline it uses the committed address
index only. The user must review and confirm a candidate pin before saving. Manual coordinates and a map-pin picker are also available.
Changing the address invalidates the previous pin confirmation. This keeps the
form on the phone and avoids silently saving guessed coordinates.

`DestinationStore` keeps app-private JSON through `DestinationPreferences`:
50 explicitly saved named places, at most one Home and Work shortcut, and 20
most-recent explicit destination selections. Coordinates deduplicate at about
one metre. A full Saved list refuses a new entry instead of evicting an existing
favorite. Reassigning Home/Work keeps the former address as an ordinary saved
place. Clearing Recent preserves Saved. No current-position trail, automatic
geocoding, database, cloud sync, account or polling is used. Removing/uninstalling
this separate Lite app removes its local destination data.

## Shared spoken output

`NavigationSpeech` creates no engine binding until the first map cue or explicit
notification **Read** action. It selects an installed offline English system
voice, uses only `synthesizeToFile`, and hands bounded private WAV output to the
dashboard's existing guidance PCM player. It never calls phone-speaker `speak`
or creates an `AudioTrack`. The same lazy binding serves both maps and messages;
there is no bundled recognizer/model/native speech dependency in Lite.

Each synthesis has a 2,000-character limit, 60-second logical expiry and a 4 MiB
completed-file limit. Cancellation invalidates the generation and deletes pending
files; the dashboard deletes handed-off files after playback. These bounds do
not claim a hard return deadline for a stalled system voice provider. Switching
away from Map pauses MapLibre rendering. A current route keeps tracking while
Messages/Calls/Music are visible and is retained through stop/start of the same
`NavigationPanel` instance. It is not restored into a newly created dashboard or
after process restart. With no route, hidden Map stops location updates.
The adaptive frame policy schedules a decay callback rather than generating a
periodic stream of frames. Location updates are requested at 1 Hz with a 200 ms
minimum interval for fresher fixes supplied by other clients, and duplicate
motion fixes are suppressed.

Host JSON/store tests cover persistence, bounds, deduplication, shortcut
reassignment, invalid data and immutable snapshots. Current Lite UI/shared-voice
runtime acceptance remains separate from the earlier prototype's route/replay
and PCM checks.

## Provider configuration

`NavigationConfig` validates HTTPS URLs without embedded credentials and saves them in private app preferences `navigation-providers`:

| Preference | Default for this research build |
| --- | --- |
| `style_url` | `https://tiles.openfreemap.org/styles/liberty` |
| `route_url` | `https://valhalla1.openstreetmap.de/route` |
| `search_url` | `https://photon.komoot.io/api/` |
| `speak_instructions` | `true` |
| `adaptive_maximum_map_fps` | `30` in the default adaptive30 build |
| `maximum_map_fps` | `15` only when built in fixed15 mode |

These are independent services. The map is free to display using [OpenFreeMap's native mobile integration](https://openfreemap.org/quick_start/). Keep the visible map attribution and its attribution menu, and its provider/data-license links: [OpenFreeMap](https://openfreemap.org/), [OpenMapTiles](https://openmaptiles.org/) and [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).

The FOSSGIS Valhalla endpoint is a community demo with fair-use limits. Requests identify this prototype; each adapter spaces its own calls by at least 1.1 seconds. Route requests occur for a selected destination, confirmed off-route event or one guarded refresh after validated reconnect. Separate foreground/background adapters are not a global server quota. Before publicly distributing an app using that demo, follow the upstream [demo-server policy](https://github.com/valhalla/valhalla#demo-server), including its request to notify the operators. Self-hosting Valhalla or selecting another compatible provider avoids reliance on a demo.

Photon's [public demo policy](https://github.com/komoot/photon#public-demo-server) permits reasonable use but provides no availability/stability guarantee. Search is explicit, not autocomplete or polling. A release should expose endpoint settings and support a self-hosted or otherwise authorized provider. No account or proprietary routing API is required by the adapter.

This module does not use OSMF's standard raster-tile or public Nominatim servers. Their [tile](https://operations.osmfoundation.org/policies/tiles/) and [geocoding](https://operations.osmfoundation.org/policies/nominatim/) policies do not provide an unrestricted production backend or offline source.

## Downloaded map areas and the offline boundary

A complete area now combines MapLibre visuals, shared BRouter RD5 car graphs and
an OSM address/place index. See [offline package behavior](../offline/README.md)
and [address data/import constraints](../offline/address/README.md) for the phone
workflow, limits, privacy, cancellation and deletion rules. Earlier visual-only
areas are retained but stay amber until their navigation stage is resumed.

Without a validated default internet route, new searches, initial routes and
reroutes use committed local data. With internet, Photon/Valhalla are preferred
and failures can fall back locally. Phone Saved-address lookup follows the same
connectivity boundary and does not invoke Android Geocoder while known offline.
OSM address coverage is incomplete, a building/way center can be approximate,
and a zero-entry extract remains a valid completed import. Local queries never
open a network connection or trigger a package download.

`OfflineNavigationStore.available` checks the ready-area union at both endpoints.
After BRouter returns, `coversRoute` requires continuous coverage along the path,
including gaps between vertices. The broader regional graph does not expand
visual coverage. Uncovered paths are refused; existing guidance is preserved on
failure. Download surrounding road space/intersections when choosing areas.
BRouter uses a trusted bundled car profile, per-node travel times, a 32 MiB route
cache budget and a 12-second route watchdog. This is not a bound on total process
memory or a HiBreak performance measurement. Offline posted limits remain unknown.

`RoutePlan.offline` records immutable route provenance; metadata copies preserve
it. Local routes skip the online road-metadata request. A lifecycle-bound
`DefaultInternetMonitor` observes the validated default route, not the car's
local Wi-Fi network. A 1.5-second debounced reconnect can refresh an active local
route from the newest fresh accepted fix. `OnlineRouteRefreshPolicy` allows one
attempt per connection/route revision. A separate worker/provider preserves user
searches and keyboard state; route/destination/network/session identity and a
fresh matching fix gate the commit. Network loss, a new route, stop or destruction
cancel its ownership. Failure keeps the old route; it does not launch a retry loop.

Saved/Recent records, positioning, loaded route progress and installed offline
TTS remain local. An active route is retained through stop/start of the same
panel but is not persisted across a new dashboard/process. Ordinary map cache
lookup and online fallback continue normally. Phone Preview/Verify test map
visuals only; a real offline search/initial route/reroute needs separate phone
acceptance with connectivity off. A successful build or green readiness marker
does not establish those outcomes, and a provider timeout must remain reported.

## Travel times, arrival and posted limits

An online basic route is published before optional metadata work. `RoutePlan` retains
Valhalla's total and maneuver durations, and builds a geometry-aligned timing
profile when the intervals are complete and consistent. One optional
`trace_attributes` request on the configured routing origin may refine segment
times and retrieve posted limits. That request has a four-second budget,
shorter connect/read timeouts, a disconnect watchdog, a 2 MB response bound and a
10,000-route-point cutoff. It is not sent for each GPS update. An unsupported or
failed metadata response leaves the original route usable; it does not add a
second long wait before showing directions.

Remaining time follows provider traversal/transition times along the matched
route. No fixed per-signal delay or assumed driving speed is added. The compact
trip row shows arrival clock, remaining duration and distance when timing and a
fresh, confident road match are available; otherwise it keeps the distance.
The labels do not repeat “Estimated” or a traffic warning, but these are still
provider time estimates without live traffic. A minute callback refreshes the
displayed arrival clock without requesting new provider data.

Local BRouter routes use their own timing profile and leave posted limits unknown.
The speed tile uses GPS speed only while its observation is fresh (up to ten
seconds) and sufficiently accurate. Posted limits use explicit, geometry-aligned
provider fields and a confident route match: usable accuracy, closeness to the
route, compatible moving bearing and no ambiguous nearby route passage. Limits
are unavailable off-route, while rerouting, without a current route, on ambiguous
matches or after freshness expires. Missing/unknown values show `--`; an explicit
unlimited value can show `∞`. New stationary observations can keep a genuinely
unchanged road match fresh; changing motion must be rematched. No GPS speed,
zero sentinel, basemap label or assumed routing speed becomes a posted limit.
Valhalla's [routing speeds and posted limits are distinct attributes](https://valhalla.github.io/valhalla/concepts/speeds/).

**Maps & Saved → Choose speed units** offers Automatic, mph and km/h. Automatic
uses mph for US/GB locales and km/h elsewhere; choosing a unit updates the phone
summary immediately. This changes display conversion only: provider limits stay
in km/h internally and GPS observations stay in metres per second.

## Deterministic replay and checks

For a parked projection test, seed a public location using `injectReplayLocation(47.60889, -122.33748, 5)`, request a real route with `navigateTo("Seattle Center", 47.62051, -122.35087)`, and wait for `getRoute()` to become non-null. Then call `startRouteReplay(10)`. The screen labels simulated positions `REPLAY`. `stopReplay()` resumes the navigator's normal GPS observations. Replay never changes Android's shared location settings or feeds simulated positions to other apps.

The host tests include an actual retained 2.124 km Seattle Valhalla response and actual Photon search response obtained with two identified requests. Tests exercise full geometry replay, turn progression, off-route hysteresis, route reacquisition, poor accuracy, duplicate timestamps, arrival proximity, crossing-route continuity, dateline geometry, malformed polylines, multi-leg indexes and service errors. They do not make network calls.

## Remaining navigation work

This is a functioning research navigation screen, not a mature navigation engine. It lacks live traffic, lane guidance UI, robust dead reckoning/map matching, persistent interrupted-route recovery, multilingual voice selection and destination handling through the selected AI. Full offline-area source support still needs the corresponding real phone/provider/vehicle acceptance. Downloaded map visuals, provider-based time/arrival updates and posted-limit UI do not establish navigation reliability. Real GPS, actual turn-cue timing, route failure recovery, car audio focus and touchscreen/rotary behavior require device and vehicle checks. MapLibre's newer navigation-core library or CoMaps' native offline engine may replace the prototype tracker without replacing the renderer/provider interfaces. Current source/host checks and actual phone/head-unit acceptance must be reported separately.
