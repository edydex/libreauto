# Navigation fixtures

These are two bounded, identified research requests made on 2026-10-17 with public Seattle landmark coordinates. They contain no device position or private destination.

- `valhalla-seattle.json`: FOSSGIS Valhalla `/route`, downtown Seattle to Seattle Center, using OpenStreetMap data. [Engine and demo policy](https://github.com/valhalla/valhalla#demo-server).
- `photon-seattle-library.json`: komoot Photon `/api/`, explicit search for Seattle Central Library. [Project and demo policy](https://github.com/komoot/photon).

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under ODbL. The responses are retained as small parser and location-replay fixtures. Tests do not make network calls. Passing replay does not establish real driving behavior, GPS quality, traffic accuracy, or road safety.
