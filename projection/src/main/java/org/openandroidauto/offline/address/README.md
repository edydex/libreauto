# Local address and place index

This package stores an explicitly downloaded area's mapped OSM addresses, named
streets and selected named places in Android SQLite. It contains no Android
Geocoder, online search or periodic network code. `OfflineAddressDownloader` is a
separate, explicit foreground download operation; the phone coordinator owns its
lifecycle and combines search readiness with independent visual/routing status.

The default data service is [Private.coffee Overpass](https://overpass.private.coffee/),
whose terms dated 10 April 2026 expressly permit use in applications. Its public
service is best effort. Each operation makes one POST, with a meaningful project
User-Agent and no automatic retries or alternate-provider fallback. Downloads are
serialized. Only the user-selected bounding rectangle is sent; no search text,
Saved nickname, contact, recorded voice or live GPS updates are uploaded. The
provider receives the chosen region and ordinary connection metadata; see its
[privacy policy](https://overpass.private.coffee/#privacy-policy). A selected
region may itself reveal the area a user intends to visit.

Bounds must pass the existing at-most-21-km-per-side geometry policy. The query
selects mapped house numbers, named highways and named amenity/shop/tourism/
leisure/office/healthcare/place features. `out body center` retains node
coordinates and adds representative way/relation centers. An independent final
`out count` detects truncated output. Client limits are 90 seconds, 32 MiB of
decompressed JSON, and 100,000 returned OSM elements. A 64 MiB server RAM
expectation and 60-second server timeout bound the query. Oversized responses,
server remarks/timeouts, missing/mismatched counts, malformed UTF-8/JSON and
cancellation fail the operation. They never commit a partial replacement.

The production importer streams `android.util.JsonReader` directly into a single
SQLite transaction. Individual JSON values/tokens and nesting are bounded before
the reader can accumulate them. Prepared statements avoid per-row SQL compilation.
WAL permits readers to retain the old completed snapshot during replacement.
Global OSM entries are deduplicated by object type/id; package memberships retain
the references needed by overlapping downloads. Deletion removes only the
selected area's references and unreferenced entries. Insert/update/delete triggers
keep FTS4 synchronized. Disk searches copy results into an immutable list before
closing the cursor, so subsequent deletion cannot invalidate returned candidates.

Search normalizes common English street abbreviations and diacritics on both
sides. FTS4 receives bounded, quoted literal terms separated by spaces (implicit
conjunction), which works with both standard and enhanced SQLite FTS parsers. An
explicit `AND` is a literal word in Android builds using the standard parser. User
query operators never enter the expression as syntax. House numbers match actual mapped tokens exactly, with no numeric prefix
or address interpolation. For house-number searches, a comma-delimited city and
an explicit apartment/unit suffix are ignored for matching the mapped building;
a unit is not claimed to be verified. Up to 20 candidates are returned, biased
by approximate distance when a position is supplied. The UI must still require
the normal reviewed destination/route confirmation.

OSM coverage is incomplete. A completed extract is not proof that every address
or place exists in the index. No street-number interpolation, typo correction,
Google Saved import, entrance resolution or global address hierarchy is supplied.
Named way/relation results use their bounding-box center and are marked
`approximate`; centers outside the selected rectangle are omitted rather than
clamped or invented. Saved places and coordinates remain useful fallbacks. An
empty completed extract reports zero entries. Visual-tile readiness does not
establish address or route readiness.

## Integration API

All methods below are synchronous worker operations. `open` retains only the
application context. `hasEntries` returns false on unavailable storage; the other
store operations report storage/import failures with `IOException`.

```java
OfflineAddressIndex index = OfflineAddressIndex.open(context);
index.search(query, nullableBiasLatitude, nullableBiasLongitude, 20);
index.status(areaId);       // null absent; entryCount can be zero
index.areas();
index.hasEntries();
index.removeArea(areaId);
OfflineAddressDownloader.fetchAndReplace(context, areaId, bounds,
    cancellationSignal, (bytes, entries) -> { /* worker callback */ });
```

A download owns its cancellation signal listener for that synchronous phase.
Cancellation socket teardown is queued on a short-lived owned worker, avoiding
TLS close operations on the Android UI thread. Results contain `name`, coordinates,
`osmKey`, `kind` and `approximate`. Status contains bounds, actual membership count,
fetch time and the provider's OSM base timestamp. No provider data is shipped in
the APK.

## Source and checks

[Overpass output documentation](https://dev.overpass-api.de/overpass-doc/en/targets/formats.html)
explains that way/relation centers are bounding-box centers rather than entrances.
[OSM address tagging](https://wiki.openstreetmap.org/wiki/Addresses) explains node,
building and interpolation representations. [SQLite FTS4](https://www.sqlite.org/fts3.html)
is the local full-text engine.

`python3 tools/check-offline-addresses.py` compiles these actual sources
against API 35, runs parser/search-boundary tests with the pinned AOSP JSON reader
in `third_party/aosp-jsonreader-host`, and executes the production schema/query contracts
against host SQLite. An optional public JSON fixture path exercises the importer
and local searches against that real extract. Build/host tests do not establish
phone rendering, a successful provider download, HiBreak performance or physical
car navigation. Provider timeouts must be reported separately from source checks.

OSM data is © OpenStreetMap contributors and licensed under the ODbL; see
[OSM copyright](https://www.openstreetmap.org/copyright). Any distributed derived
database must retain its data-license/source obligations. This app's GPL code
license does not relicense downloaded OSM data.
