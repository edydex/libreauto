# BRouter embedded Java core

Upstream: https://github.com/abrensch/brouter
Pinned release: v1.7.10
Commit: 4d2639af77ea5ed9c30d3e400764eb6f9e8522da
License: MIT (see LICENSE).

The 101 Java sources are copied unchanged from brouter-core, brouter-mapaccess,
brouter-codec, brouter-expressions, and brouter-util. The source manifest records
the exact imported bytes. No Android app, server, graph creator or native library
is included. Routing graph data is downloaded separately and is OSM-derived/ODbL.

Bundled app assets offline-routing/lookups.dat and car-vario.brf are unmodified
upstream profile resources. car-fast.brf is generated from car-vario.brf using the
upstream generate_profile_variants.sh substitution: vmax becomes 160 km/h.

Add src/main/java to the app Java source set and include proguard-rules.pro.
KinematicModel is loaded by the trusted bundled profile through reflection.
