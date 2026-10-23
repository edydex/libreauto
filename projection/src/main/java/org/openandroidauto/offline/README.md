# Complete phone offline areas

Open **Maps & Saved → Manage offline maps**, pan/zoom to the desired rectangle,
then choose **Download this view** and a name. Saved/current-location shortcuts
center the map; coordinates are an optional overflow action. Rotation and tilt
are disabled. The naming prompt snapshots the exact visible extent.

Each area combines three independently checked stages: MapLibre visual resources,
shared BRouter road graphs, and an OSM address/place extract in SQLite. Green
requires all three; missing or incomplete data stays amber. A completed empty
address extract is valid but cannot supply missing places. Earlier visual-only
packages remain available and resume into the navigation-data stage.

Keep the foreground workflow open. Pause/leaving cancels the current stage and
waits for its worker/SDK ownership to finish before reopening the picker. A new
car connection interrupts setup. Road downloads can resume, valid graph files
are reused, and completed address replacements are transactional. There is no
background download service, provider polling or hidden location upload.

Up to 64 rectangles are supported, at most 21 km per side. The visual stage uses
the default OpenFreeMap style at zooms 0–14 and density variants 1/2. It requires
precise positive SDK completion counts and saved tiles; unknown `0/0` is not
complete. It has a 2,400-tile conservative preflight, durable 2,700-network-
exchange allowance, four-request concurrency and a 45 MB soft resource pause.
These are visual limits; a shared regional RD5 road file may be up to 250 MB.
Road files are downloaded sequentially over HTTPS with bounded response sizes,
validated range resumes, disk-space checks, graph validation and atomic rename.
The address stage has separate response/time/element bounds described in
[its module notes](address/README.md).

An atomic private readiness manifest is committed only after verified graphs and
a complete address extract exist. A per-operation revision rejects late commits
after replacement or deletion. A shared pipeline lease serializes road/address
downloads and deletion. Picker readiness checks run on a worker. TLS/socket
teardown also runs off the Android main thread.

Offline route eligibility requires both endpoints inside the ready-area union;
the computed path is then checked continuously, including gaps between shape
vertices. A route leaving this union is refused. Download surrounding road space
and intersections, not just endpoints. The broader RD5 region does not extend
the chosen visual coverage. BRouter car routes carry their own travel-time
profile; posted limits remain unknown. Online navigation is preferred when a
validated internet route exists, with local fallback and a guarded reconnect
refresh; see [navigation](../navigation/README.md).

Tap inside an area to resume, preview, verify or delete it. Overlapping areas
remain separate and have a chooser. Confirmed deletion first removes readiness,
then the area's index membership and unshared graph files, followed by its owned
MapLibre region references. Saved addresses and other packages remain. Both
complete and incomplete neighbors retain their shared road data. Legacy square
and current rectangle metadata must pass owner/style/zoom/density/bounds checks
before their native resources can be managed.

**Preview without internet** disables SDK map networking for a new phone map.
**Verify downloaded area** explicitly asks to clear only temporary browsing cache,
preserving pinned areas and Saved places, before that preview. Neither action
tests address search or routing. Test a fresh local search, initial route and
off-route recalculation separately with phone connectivity off, then restore it.
Ordinary driving uses normal SDK cache lookup and online fallback; it is not
globally forced offline. Downloaded resources persist after restart, while an
active route is still memory-only.

Status markers, source tests and a build do not establish rendered-device or
physical-car acceptance. The public address provider can time out; a partial
package must remain amber and offer explicit Resume. No graph/address database
is bundled in the APK. Required OpenMapTiles/OpenStreetMap credit remains visible.
See [OpenFreeMap terms](https://openfreemap.org/tos/), the vendored BRouter MIT
notice, and the address module's OSM/ODbL source and privacy notices.
