# Artwork renderer

Call `MapArtworkRenderer(context).render(position, guidance, units, geometry, callback)`
on main. Outputs are independent512px images and must not be mutated or recycled.
Unchanged presentation can reuse its prior output without a new callback. The first
successful asynchronous map can upgrade the latest direction card; superseded,
cancelled and closed requests receive no late callbacks.

A map requires confirmed `TurnGeometry` and a fresh usable initial fix. Otherwise
the artwork is a direction card. It never substitutes a current-position map for
an unknown junction. Its blue approach/departure line comes only from the supplied
exact route or validated OSM match. The camera faces the final25m of approach and
fits a bounded junction slice, using the initial posted limit or a fixed default
for scale. The phone position, speed and bearing do not change this diagram, and
there is no moving phone marker.

The first accepted geometry/frame is locked for instruction, source, demo status,
units and geometry identity. Same-key geometry wrappers, a disappearing fix,
elapsed time and distance updates cannot move, expire or refresh it. A changed
geometry key also starts a new preview when two consecutive turns have identical
wording. New instruction/source, cleared guidance and `resetTurn()` discard it.
Use `resetTurn()` for explicit theme/map/session settings changes.

`needsTurnMap(position,guidance,geometry)` admits an initially unavailable geometry
or an unresolved failed map when ready. Initial snapshot work has a12-second
timeout and failed attempts have a30-second backoff. Once a map succeeds, it has
no periodic refresh. Only one native job, snapshot and owned static map/path/credit
layer are retained. Native path projection is validated once on snapshot arrival;
distance updates copy that static layer and draw only the changing footer.

The map occupies the top368px. Below it, a readable translucent credit strip stays
above the compact distance/arrow and bottom-most instruction text. The fixed
OpenFreeMap Liberty style's required OpenMapTiles and OpenStreetMap credit is
rendered once in that strip; the SDK's default band is disabled to avoid duplicate
credit. [OpenFreeMap attribution](https://openfreemap.org/) and
[OSMF guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines)
require credit to remain legible; the final Bluetooth-size image must be checked.
Cards without a map have no map credit. Phone About retains the linked licenses.

Original vector maneuver symbols are shared with `ManeuverIconView`. Unknown text
uses a neutral pin and roundabouts have no invented exit. Notification bitmaps are
not stretched into turn arrows. No GPS/permission request, provider polling or
offline map-download engine is part of this renderer; MapLibre can fetch public
style/tiles while preparing the initial confirmed junction snapshot.

`diagnostics()` reports content-free native snapshot, output composition,
cache-hit and static-layer counters. `cancelPending()` stops obsolete native/deferred
work while retaining the bounded turn cache; `close()` releases owned resources.
