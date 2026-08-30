#!/usr/bin/env python3
"""
Builds `world_places.db` — a compact SQLite gazetteer of the world's populated places
for TouchMap, used to show something on the map in areas where no detailed map has been
imported yet.

Contents
--------
* Cities, towns and villages (one midpoint each) from GeoNames feature class ``P``.
* City sub-regions ("sections of populated place", GeoNames code ``PPLX``) for large cities —
  so a big city shows the midpoints of its districts rather than a single dot.
* A couple of famous landmarks near each large city (GeoNames class ``S``/``L``/``T`` with a
  curated set of "notable" feature codes).
* Airports, major train stations and hospitals worldwide (GeoNames AIRP / RSTN / HSP / HSPC).
  Fire stations are not in GeoNames; they come from the OSM import (amenity=fire_station) once a
  local map is downloaded.

The output table is named and shaped exactly like the app's own normalized `nodes` table
(id, lat, lon, cell_x, cell_y, cat_id, favorite, type, name, tags, region) — see OSMDatabaseHelper and
MapTileManager — so the app can `ATTACH DATABASE` this file and query it with the identical SQL
shape used for real imported data, no separate query path needed. cell_x/cell_y use the same
coarse cell size as the app's spatial index (Contract.CELL_SIZE_METERS); cat_id is
CATEGORY_WORLD_CITY + kind (see OSMDatabaseHelper.isWorldPlaceCategory); type is the
already-resolved label the app speaks ("city"/"town"/"village"/"district"/... — the
population-dependent city/town/village decision is made here at generation time, not at read
time in the app).

Data source: https://download.geofabrik.de is NOT used here; this uses GeoNames
(https://www.geonames.org/, CC BY 4.0). Please keep the attribution in the app.

Usage
-----
    # Light build (towns/villages down to population 500; quick, ~10 MB download):
    python build_world_places.py --source cities500

    # Full build (adds PPLX city sections + landmarks near big cities; ~1.9 GB download):
    python build_world_places.py --source allCountries

Output: tools/out/world_places.db
"""
import argparse
import io
import math
import os
import sqlite3
import time
import urllib.request
import zipfile

EARTH_RADIUS = 6378137.0
CELL_SIZE_METERS = 8000.0  # matches Contract.CELL_SIZE_METERS

GEONAMES_BASE = "https://download.geonames.org/export/dump/"

# --- place classification -------------------------------------------------

# Populated places we treat as "cities/towns/villages" (a single midpoint each).
CITY_CODES = {
    "PPLC",   # national capital
    "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5",  # seats of admin divisions
    "PPL",    # populated place (generic)
    "PPLL",   # locality / small village
    "PPLF",   # farm village
    "PPLG",   # seat of government
    "PPLS",   # populated places
    "PPLR",   # religious populated place
    "PPLW",   # destroyed populated place (kept: often still named on the ground)
}
# Historical / abandoned / questionable — skipped.
SKIP_CITY_CODES = {"PPLH", "PPLCH", "PPLQ", "PPLX"}  # PPLX handled separately as a section

SECTION_CODE = "PPLX"  # section of a populated place == city sub-region

# "Famous places" — a curated subset of notable feature codes across classes S/L/T.
LANDMARK_CODES = {
    # class S — spots / buildings
    "MNMT", "CSTL", "PAL", "MUS", "HSTS", "TOWR", "ARCH", "PYR", "PYRS", "RUIN",
    "CH", "MSQE", "TMPL", "SHRN", "THTR", "OPRA", "STDM", "ANS", "AMTH", "OBS",
    "LTHSE", "BDG", "GHAT",
    # class L — parks / areas
    "PRK", "RESN", "RESW", "GDN", "AMUS", "RECG",
    # class T — natural landmarks
    "MT", "PK", "VLC", "BCH", "CAPE", "ISL", "FLLS", "CLF",
}

# kind values (kept for internal classification only; the app never sees "kind" — it sees the
# already-resolved cat_id/type columns below).
KIND_CITY = 0
KIND_SECTION = 1
KIND_LANDMARK = 2
KIND_AIRPORT = 3
KIND_STATION = 4       # major train station
KIND_HOSPITAL = 5

# Matches OSMDatabaseHelper.CATEGORY_WORLD_CITY (100..105, consecutive with KIND_* above).
CATEGORY_WORLD_CITY = 100

# Globally-included points of interest (not just near big cities), by GeoNames feature code.
# NOTE: GeoNames has no fire-station feature code; fire stations are captured from OSM
# (amenity=fire_station) when a local map is imported, so they appear once an area is downloaded.
GLOBAL_POI_CODES = {
    "AIRP": KIND_AIRPORT,   # airport
    "RSTN": KIND_STATION,   # railroad station
    "HSP": KIND_HOSPITAL,   # hospital
    "HSPC": KIND_HOSPITAL,  # clinic
}

# A "large city" gets sections + landmarks attached.
LARGE_CITY_POP = 100_000
LANDMARKS_PER_CITY = 3
LANDMARK_RADIUS_M = 15_000.0


def convert_coordinate_to_cell(lat, lon):
    """Identical maths to the app's Utils.latLonToIndex, parameterized by CELL_SIZE_METERS."""
    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)
    y_index = int(round(EARTH_RADIUS * lat_rad / CELL_SIZE_METERS))
    cell_width_rad = CELL_SIZE_METERS / EARTH_RADIUS / math.cos(lat_rad)
    x_index = int(round(lon_rad / cell_width_rad))
    return x_index, y_index


def kind_to_type(kind, population):
    """Mirrors the app's (retired) WorldPlace.kindLabel() — resolved here at generation time."""
    if kind == KIND_CITY:
        if population >= 100_000:
            return "city"
        if population >= 10_000:
            return "town"
        return "village"
    if kind == KIND_SECTION:
        return "district"
    if kind == KIND_LANDMARK:
        return "landmark"
    if kind == KIND_AIRPORT:
        return "airport"
    if kind == KIND_STATION:
        return "railway station"
    if kind == KIND_HOSPITAL:
        return "hospital"
    return "place"


def download(url, dest):
    if os.path.exists(dest):
        print(f"  using cached {os.path.basename(dest)}")
        return dest
    print(f"  downloading {url}")
    tmp = dest + ".part"
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        done = 0
        start = time.time()
        while True:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                pct = done / total * 100
                speed = done / 1e6 / max(1e-6, time.time() - start)
                print(f"\r    {done/1e6:.1f}/{total/1e6:.1f} MB ({pct:.0f}%) {speed:.1f} MB/s", end="")
        print()
    os.replace(tmp, dest)
    return dest


def iter_geonames_rows(source, base_dir):
    """Yields parsed GeoNames rows (as tuples of fields) from cities500/1000/... or allCountries."""
    fname = source + ".zip"
    path = download(GEONAMES_BASE + fname, os.path.join(base_dir, fname))
    inner = source + ".txt"
    with zipfile.ZipFile(path) as z:
        with z.open(inner) as raw:
            for line in io.TextIOWrapper(raw, encoding="utf-8"):
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 19:
                    continue
                yield parts


def haversine_m(lat1, lon1, lat2, lon2):
    r = EARTH_RADIUS
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


INSERT_SQL = (
    "INSERT OR IGNORE INTO nodes (id,lat,lon,cell_x,cell_y,cat_id,favorite,type,name,tags,region) "
    "VALUES (?,?,?,?,?,?,0,?,?,X'',0)"
)


def build(source, out_path):
    base_dir = os.path.join(os.path.dirname(__file__), "geonames_cache")
    os.makedirs(base_dir, exist_ok=True)

    if os.path.exists(out_path):
        os.remove(out_path)
    conn = sqlite3.connect(out_path)
    cur = conn.cursor()
    cur.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        CREATE TABLE nodes (
            id       INTEGER PRIMARY KEY,   -- geonameid
            lat      REAL NOT NULL,
            lon      REAL NOT NULL,
            cell_x   INTEGER NOT NULL,
            cell_y   INTEGER NOT NULL,
            cat_id   INTEGER NOT NULL,      -- CATEGORY_WORLD_CITY + kind (100..105)
            favorite INTEGER NOT NULL DEFAULT 0,
            type     TEXT,                  -- resolved label: "city"/"town"/"village"/"district"/...
            name     TEXT NOT NULL,
            tags     BLOB,
            region   INTEGER NOT NULL DEFAULT 0  -- matches OSMDatabaseHelper.REGION_WORLD (regions.id=0)
        );
    """)

    large_cities = []  # (lat, lon) of big cities, to attach landmarks
    rows_batch = []
    seen = 0
    inserted = 0

    def flush():
        cur.executemany(INSERT_SQL, rows_batch)
        rows_batch.clear()

    print(f"Parsing {source} (pass 1: places + sections)...")
    for p in iter_geonames_rows(source, base_dir):
        seen += 1
        if seen % 500000 == 0:
            print(f"\r  {seen} rows, {inserted} kept", end="")
        feature_class = p[6]
        feature_code = p[7]
        if feature_class != "P":
            continue
        if feature_code in SKIP_CITY_CODES and feature_code != SECTION_CODE:
            continue

        try:
            lat = float(p[4]); lon = float(p[5])
        except ValueError:
            continue
        name = p[1]
        try:
            population = int(p[14]) if p[14] else 0
        except ValueError:
            population = 0

        if feature_code == SECTION_CODE:
            kind = KIND_SECTION
        elif feature_code in CITY_CODES:
            kind = KIND_CITY
        else:
            continue

        cx, cy = convert_coordinate_to_cell(lat, lon)
        rows_batch.append((
            int(p[0]), lat, lon, cx, cy, CATEGORY_WORLD_CITY + kind,
            kind_to_type(kind, population), name,
        ))
        inserted += 1
        if kind == KIND_CITY and population >= LARGE_CITY_POP:
            large_cities.append((lat, lon))
        if len(rows_batch) >= 50000:
            flush()
    flush()
    print(f"\n  places+sections inserted: {inserted}; large cities: {len(large_cities)}")

    # Landmarks and global POIs are only present in the full allCountries dump.
    if source == "allCountries":
        if large_cities:
            attach_landmarks(cur, base_dir, source, large_cities)
        attach_global_pois(cur, base_dir, source)

    print("Creating indexes...")
    cur.execute("CREATE INDEX idx_nodes_cell ON nodes(cell_x, cell_y)")
    conn.commit()
    cur.execute("VACUUM")
    conn.close()

    size_mb = os.path.getsize(out_path) / 1e6
    print(f"Done -> {out_path} ({size_mb:.1f} MB)")


def attach_landmarks(cur, base_dir, source, large_cities):
    """Second pass: keep up to LANDMARKS_PER_CITY nearest landmarks around each large city."""
    print("Parsing (pass 2: landmarks near large cities)...")
    # Bucket big cities by cell for fast proximity checks.
    from collections import defaultdict
    city_buckets = defaultdict(list)
    for lat, lon in large_cities:
        cx, cy = convert_coordinate_to_cell(lat, lon)
        city_buckets[(cx, cy)].append((lat, lon))

    radius_cells = int(LANDMARK_RADIUS_M / CELL_SIZE_METERS) + 1
    # keyed by nearest city -> list of (dist, row); we keep the closest few per city.
    kept = defaultdict(list)
    seen = 0
    for p in iter_geonames_rows(source, base_dir):
        seen += 1
        if seen % 1000000 == 0:
            print(f"\r  {seen} rows scanned", end="")
        if p[6] not in ("S", "L", "T"):
            continue
        if p[7] not in LANDMARK_CODES:
            continue
        try:
            lat = float(p[4]); lon = float(p[5])
        except ValueError:
            continue
        cx, cy = convert_coordinate_to_cell(lat, lon)
        # find a nearby large city
        best = None
        for dx in range(-radius_cells, radius_cells + 1):
            for dy in range(-radius_cells, radius_cells + 1):
                for clat, clon in city_buckets.get((cx + dx, cy + dy), ()):
                    d = haversine_m(lat, lon, clat, clon)
                    if d <= LANDMARK_RADIUS_M and (best is None or d < best[0]):
                        best = (d, clat, clon)
        if best is None:
            continue
        city_key = (best[1], best[2])
        row = (
            int(p[0]), lat, lon, cx, cy, CATEGORY_WORLD_CITY + KIND_LANDMARK,
            kind_to_type(KIND_LANDMARK, 0), p[1],
        )
        lst = kept[city_key]
        lst.append((best[0], row))
        lst.sort(key=lambda t: t[0])
        del lst[LANDMARKS_PER_CITY:]

    landmark_rows = [row for lst in kept.values() for _, row in lst]
    cur.executemany(INSERT_SQL, landmark_rows)
    print(f"\n  landmarks inserted: {len(landmark_rows)}")


def attach_global_pois(cur, base_dir, source):
    """Include airports, major train stations and hospitals everywhere (each with a name)."""
    print("Parsing (global POIs: airports, stations, hospitals)...")
    rows = []
    seen = 0
    kept = 0
    for p in iter_geonames_rows(source, base_dir):
        seen += 1
        if seen % 1000000 == 0:
            print(f"\r  {seen} rows scanned, {kept} POIs kept", end="")
        kind = GLOBAL_POI_CODES.get(p[7])
        if kind is None:
            continue
        name = p[1]
        if not name:
            continue
        try:
            lat = float(p[4]); lon = float(p[5])
        except ValueError:
            continue
        cx, cy = convert_coordinate_to_cell(lat, lon)
        rows.append((
            int(p[0]), lat, lon, cx, cy, CATEGORY_WORLD_CITY + kind,
            kind_to_type(kind, 0), name,
        ))
        kept += 1
        if len(rows) >= 50000:
            cur.executemany(INSERT_SQL, rows)
            rows.clear()
    if rows:
        cur.executemany(INSERT_SQL, rows)
    print(f"\n  global POIs inserted: {kept}")


def main():
    ap = argparse.ArgumentParser(description="Build the TouchMap world-places SQLite database.")
    ap.add_argument("--source", default="cities500",
                    choices=["cities500", "cities1000", "cities5000", "cities15000", "allCountries"],
                    help="GeoNames dump to use. allCountries adds PPLX sections + landmarks but is ~1.9 GB.")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "out", "world_places.db"))
    args = ap.parse_args()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    build(args.source, args.out)


if __name__ == "__main__":
    main()
