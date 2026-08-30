#!/usr/bin/env python3
"""
Builds `geofabrik_index.db` — a compact SQLite copy of Geofabrik's `index-v1.json` region index,
bundled in the app (app/src/main/assets/geofabrik_index.db) so the download screen's country list
and location-based defaulting work instantly and offline, instead of downloading and parsing the
~4 MB JSON at runtime.

Mirrors tools/build_world_places.py's structure/CLI shape. Re-run and re-upload to
https://wikiponder.com/navia/geofabrik_index.db whenever Geofabrik's index changes meaningfully;
bump the "regions" version in tools/out/updates.json to match so installed apps pick it up.

Row shape mirrors what GeofabrikIndex.java already parses from the JSON at runtime (see
parseOuterRings/addRing there) plus the pbf download URL, so GeofabrikIndex.parseFromDb() can
populate the exact same in-memory model it used to build from JSON.

Usage
-----
    python build_geofabrik_index.py

Output: tools/out/geofabrik_index.db
"""
import json
import os
import sqlite3
import urllib.request

import msgpack

INDEX_URL = "https://download.geofabrik.de/index-v1.json"


def download_json(url, cache_path):
    if os.path.exists(cache_path):
        print(f"  using cached {os.path.basename(cache_path)}")
        with open(cache_path, encoding="utf-8") as f:
            return f.read()
    print(f"  downloading {url}")
    with urllib.request.urlopen(url) as r:
        text = r.read().decode("utf-8")
    with open(cache_path, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def parse_outer_rings(geometry):
    """Polygon -> its one outer ring; MultiPolygon -> each part's outer ring. Holes ignored —
    matches GeofabrikIndex.parseOuterRings exactly, so the two stay pixel-for-pixel identical."""
    if geometry is None:
        return []
    gtype = geometry.get("type")
    coords = geometry.get("coordinates")
    if coords is None:
        return []
    rings = []
    if gtype == "Polygon":
        if coords and coords[0]:
            rings.append(coords[0])
    elif gtype == "MultiPolygon":
        for poly in coords:
            if poly and poly[0]:
                rings.append(poly[0])
    return rings


def pack_rings(rings):
    """[[ [lon,lat], ... ], ...] -> msgpack bytes, same shape GeofabrikIndex.packRings/
    Utils.unpackRings already read (nested arrays; unpackRings' asFloatValue().toFloat() accepts
    either float32 or float64 wire format, so use_single_float just keeps the file smaller)."""
    packer = msgpack.Packer(use_single_float=True, use_bin_type=True)
    out = bytearray()
    out += packer.pack_array_header(len(rings))
    for ring in rings:
        out += packer.pack_array_header(len(ring))
        for pt in ring:
            out += packer.pack_array_header(2)
            out += packer.pack(float(pt[0]))
            out += packer.pack(float(pt[1]))
    return bytes(out)


def build(out_path):
    base_dir = os.path.join(os.path.dirname(__file__), "geofabrik_cache")
    os.makedirs(base_dir, exist_ok=True)
    cache_path = os.path.join(base_dir, "index-v1.json")

    print("Fetching Geofabrik index...")
    text = download_json(INDEX_URL, cache_path)
    data = json.loads(text)
    features = data["features"]
    print(f"  {len(features)} features")

    if os.path.exists(out_path):
        os.remove(out_path)
    conn = sqlite3.connect(out_path)
    cur = conn.cursor()
    cur.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        CREATE TABLE regions (
            id      TEXT PRIMARY KEY,   -- Geofabrik id, e.g. "bayern"
            parent  TEXT,                -- Geofabrik parent id, NULL for continents
            name    TEXT NOT NULL,
            iso2    TEXT,                 -- only set on countries
            pbf_url TEXT,                 -- NULL if this region has no downloadable PBF
            min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL,
            rings   BLOB                  -- NULL if this region has no geometry; msgpack-encoded
        );
    """)

    rows = []
    skipped_no_id = 0
    no_geometry = 0
    for feature in features:
        props = feature.get("properties", {})
        region_id = props.get("id")
        if not region_id:
            skipped_no_id += 1
            continue
        name = props.get("name", region_id)
        parent = props.get("parent")
        pbf_url = (props.get("urls") or {}).get("pbf")
        iso_arr = props.get("iso3166-1:alpha2") or []
        iso2 = iso_arr[0].upper() if iso_arr else None

        rings = parse_outer_rings(feature.get("geometry"))
        if not rings:
            no_geometry += 1
            rows.append((region_id, parent, name, iso2, pbf_url, None, None, None, None, None))
            continue

        min_lat = min(pt[1] for ring in rings for pt in ring)
        max_lat = max(pt[1] for ring in rings for pt in ring)
        min_lon = min(pt[0] for ring in rings for pt in ring)
        max_lon = max(pt[0] for ring in rings for pt in ring)
        rings_blob = pack_rings(rings)
        rows.append((region_id, parent, name, iso2, pbf_url,
                     min_lat, max_lat, min_lon, max_lon, rings_blob))

    cur.executemany(
        "INSERT INTO regions (id, parent, name, iso2, pbf_url, min_lat, max_lat, min_lon, max_lon, rings) "
        "VALUES (?,?,?,?,?,?,?,?,?,?)",
        rows,
    )
    conn.commit()
    cur.execute("VACUUM")
    conn.close()

    print(f"  regions inserted: {len(rows)} (no geometry: {no_geometry}, skipped no-id: {skipped_no_id})")
    size_mb = os.path.getsize(out_path) / 1e6
    print(f"Done -> {out_path} ({size_mb:.1f} MB)")


def main():
    out = os.path.join(os.path.dirname(__file__), "out", "geofabrik_index.db")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    build(out)


if __name__ == "__main__":
    main()
