#!/usr/bin/env python3
r"""
Builds a global set of app-schema-compatible SQLite map databases covering the whole planet, one db
per adaptive-density BOX rather than one per k-means cluster.

Where build_clustered_country_dbs.py clusters *one country at a time* (so cluster boundaries restart
at every border) and build_region_dbs.py produces one db per Geofabrik leaf, this tool computes a
single, planet-wide partition once, then streams every Geofabrik leaf region into whichever global
box each feature falls in. The result is a set of dbs that tile the earth seamlessly — a way in
Alsace and a way in Baden-Württemberg land in the same box db if they're geographically adjacent.

How the partition is built (no k-means): sample a small percent of every node's (lat, lon), treat
lon/lat as flat Cartesian axes, and recursively cut the whole-planet rectangle across its *longer*
edge — but at the MEDIAN of the sampled coordinates on that edge, not at the geometric midpoint —
recursing on both halves and stopping a branch once its sampled node count drops below --threshold.
Choosing the axis by shape but the cut position by data is what keeps the boxes balanced: a midpoint
cut through, say, a coastal box hands one child the whole city and the other an empty sea, so leaf
sizes ranged from ~0 to --threshold and the db sizes with them. A median cut gives each child half
its parent's sampled nodes by construction, so every leaf lands between --threshold/2 and
--threshold sampled nodes and the resulting dbs come out within ~2x of each other. Dense areas
(cities) still end up as many small boxes and sparse areas (oceans, deserts) as a few huge ones —
that falls out of the counts, not the geometry. This is the same box-tree idea build_planet_boxes.py
uses for its adaptive-density grid, applied here on top of build_global_cluster_dbs.py's own
Geofabrik-region-at-a-time reading pipeline instead of that tool's multi-pass single-planet-file
reading + on-disk coord store.

This file is deliberately SELF-CONTAINED: every helper it needs (tag rules, geometry math, msgpack
packing, db schema, intersection detection, Geofabrik index handling, the box-tree partition) is
copied in rather than imported from the sibling build_*.py tools, so those can be deleted once this
one is proven.

Pipeline (each step resumable — a crashed run continues from the step that broke, and steps 3 and 4
additionally resume item-by-item, skipping regions/dbs already finished):

  Step 1  Sample --sample-percent of every node in the planet file (cached to sampled_nodes.feather
          and reloaded from there on later runs, so changing --threshold never re-reads the planet),
          recursively median-split the sampled points into boxes (stopping each branch once its
          sampled count drops below --threshold, default 50,000), save the box boundaries and the
          split tree that produced them to world_boxes.json, and create one empty db per box.
  Step 2  Walk Geofabrik's region tree, collect every childless region that isn't a Special Sub
          Region, HEAD each one for its pbf size, and save the work list to leaf_regions.json.
  Step 3  Up to --workers regions in parallel (memory-budget aware). Each worker downloads its pbf,
          reads it with osmium location resolution ON so every way arrives with its vertex
          coordinates already attached, routes each useful node through the box tree and writes it
          into its box's db, and routes each useful way into every box db its vertices touch. Then
          deletes its pbf and its location index. Rows are never inserted individually: each worker
          keeps one in-memory row cache per box db and writes it out with a single executemany once
          it reaches RegionImporter.FLUSH_ROWS rows, plus a final flush of every cache when the
          region ends.
  Step 4  Up to --workers box dbs in parallel: verify integrity, detect and persist junction nodes,
          VACUUM to compact, and write a .db.gz beside each db. Finishes by writing
          boxes_manifest.json with every box's boundary, row counts, and db size.

Scratch files never outlive the work that needs them
----------------------------------------------------
A step-3 worker on a large region resolves way geometry through an on-disk node-location index
(locidx_<region>.bin in --scratch-dir), which for the biggest regions runs to tens of GB. pyosmium
gives no way to close one: index.clear() and dropping every Python reference both leave the mmap
open, and it stays open for as long as the process that created it lives — so on Windows, where a
mapped file cannot be unlinked, a pool that reuses its workers can never delete a single index until
the entire run ends. Hence a four-part scheme:

  * the worker truncates its index to zero bytes as soon as apply_file returns (backed by a `finally`
    for the error paths). It cannot unlink it, but truncating reclaims the actual gigabytes, and it
    happens mid-run instead of at exit — this is what bounds the scratch dir's high-water mark;
  * step 3 runs its pool with max_tasks_per_child=1, so the owning process exits after its one
    region. Process exit is the close() pyosmium doesn't offer;
  * the parent unlinks that region's index as soon as its future resolves — for failed regions too;
  * and because none of the above runs when a worker is OOM-killed or segfaults, step 3 sweeps every
    locidx_*.bin in the scratch dir both before it spawns the pool and after that pool drains.

Downloaded pbfs are deleted in a `finally` (so a region that fails partway doesn't strand its
multi-GB extract either) and half-written *.part files are removed on the spot and swept alongside
the indexes. Give concurrent runs of this tool separate --scratch-dirs: the sweeps identify orphans
by filename pattern, not by owner.

What "useful" means, and why tags are no longer trimmed
---------------------------------------------------------
A node/way still only makes it into a box db if it passes the same classification rules
OsmPbfImporter uses (nodes: has one of the IMPORTANT_NODE_TAGS or an addr:* triple; ways: has a
recognized highway/barrier/platform/tram_stop/building type) — that selection is unchanged from
build_global_cluster_dbs.py's k-means predecessor. What changed is what gets written into the `tags`
BLOB for whatever passes that check: the OLD tool trimmed it down to IMPORTANT_TAG_KEYS (a small
curated subset used for turn-by-turn/accessibility rendering); this tool stores the feature's
COMPLETE original tag dict instead — nothing is filtered out of what's saved.

Why way geometry is NOT normalized into a node<->way table
----------------------------------------------------------
Measured on Monaco (4,676 useful ways / 28,945 vertices): the packed `ways.points` msgpack blob the
app already uses costs 12.2 bytes/vertex, while an equivalent normalized nodes+node_ways pair costs
~71 bytes/vertex — 5.9x larger, even after crediting normalization the 1.32x shared-vertex dedup
win. A `nodes` row is simply fat (two REAL coords at 8 bytes each plus nine more columns) and
node_ways adds a row *and* an index entry per vertex. Reading is worse too: rendering a viewport
becomes thousands of ordered joins instead of one blob decode per way. So geometry stays packed, and
osmium's own location resolution (locations=True) supplies the coordinates directly — no all-nodes
insert/prune cycle, and no risk of a way's polyline coming out truncated in a box that happens to
hold only some of its vertices.

The output schema, column names/types, id conventions (node/way id offset, negative intersection
ids, PRAGMA user_version) are bit-for-bit what OSMDatabaseHelper.onCreate() produces — dropping any
single box db in as the app's osm.db is meant to behave exactly like a real on-device import.

Requires: pip install osmium numpy msgpack (pyarrow optional — only needed for the sampled-node
cache; pass --no-sample-cache to run without it)

Usage
-----
    # Full run, all four steps (skips whatever is already done):
    python build_global_cluster_dbs.py --planet-pbf D:\osm\planet-latest.osm.pbf

    # Re-run just one step (e.g. after tweaking intersection logic):
    python build_global_cluster_dbs.py --planet-pbf ... --steps 4

    # Small end-to-end smoke test against a country extract standing in for "the planet":
    python build_global_cluster_dbs.py --planet-pbf tools/geofabrik_cache/monaco.osm.pbf \
        --threshold 2000 --sample-percent 10 --sample-capacity 100000 --only monaco --workers 2 \
        --keep-pbf

Output: tools/out/global_boxes/box_{i:06d}.db(.gz), world_boxes.json, leaf_regions.json,
        boxes_manifest.json
"""
import argparse
import ctypes
import gc
import gzip
import json
import math
import os
import platform
import shutil
import sqlite3
import sys
import time
import urllib.request
from collections import OrderedDict
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, FIRST_COMPLETED, wait

import msgpack
import numpy as np
import osmium

# ---------------------------------------------------------------------------- constants

INDEX_URL = "https://download.geofabrik.de/index-v1.json"
EARTH_RADIUS = 6378137.0                 # matches Contract.EARTH_RADIUS
CELL_SIZE_METERS = 8000.0                # matches Contract.CELL_SIZE_METERS
ID_OFFSET = 1_000_000_000                # matches OsmPbfImporter.ID_OFFSET
DB_VERSION = 47                          # must track OSMDatabaseHelper.DB_VERSION — bump together
REGION_WORLD = 0                         # matches OSMDatabaseHelper.REGION_WORLD
CATEGORY_INTERSECTION = 62               # matches OSMDatabaseHelper.CATEGORY_INTERSECTION

DEFAULT_THRESHOLD = 50_000               # sampled-node count above which a box is split further
DEFAULT_SAMPLE_PERCENT = 1.0
DEFAULT_MEMORY_BUDGET_GB = 32.0
# Firm upper bound on how many sampled nodes step 1 will hold. The planet is ~10 billion nodes, so a
# 1 % sample is ~100 M; 200 M leaves 2x headroom. The buffer is allocated ONCE at this size and
# written by index — never grown by append. (An earlier tool grew an array.array incrementally and
# died with MemoryError at a few hundred million elements: repeated reallocation fragments the heap
# long before the live data itself is large.) 8 bytes/sample => the default reserves ~1.6 GB.
DEFAULT_SAMPLE_CAPACITY = 200_000_000
DEFAULT_WORKERS = 8

# Peak-memory-per-worker heuristic (bytes) as a linear function of a region's pbf size — the only
# size signal available before the file is fetched. Anchored to a real measured data point: the whole
# US (12.0 GB pbf) peaks at ~25 GB resident, i.e. ~2.06 RAM-bytes per pbf byte. The multiplier sits
# just above that (~7 % cushion) so the scheduler slightly over-books rather than under-books, and
# packing to a given --memory-budget-gb keeps *actual* peak a bit under budget. Scheduling heuristic,
# not a hard bound.
ESTIMATED_RAM_PER_PBF_BYTE = 2.2
BASE_WORKER_OVERHEAD_BYTES = 300_000_000

# Above this pbf size a worker resolves way node locations through an on-disk index in --scratch-dir
# instead of osmium's in-RAM flex_mem index. Below it, staying in RAM is markedly faster.
LOCATION_INDEX_DISK_THRESHOLD = 500_000_000

# Geofabrik's own "Special Sub Regions" (see e.g. https://download.geofabrik.de/europe.html): "these
# regions are 'special' because they are outside of the usual administrative hierarchies and may
# duplicate data already contained in the other sub regions" — e.g. Europe's "Alps" spans
# France/Switzerland/Austria/Italy/Slovenia/Germany, "DACH" duplicates Germany+Austria+Switzerland
# whole. index-v1.json carries no machine-readable flag for this (they look like ordinary
# country-tier entries), so this list is hardcoded from cross-referencing every continent's own
# download page. Re-check those pages if Geofabrik adds new ones.
SPECIAL_SUBREGION_IDS = {
    "alps", "britain-and-ireland", "dach", "great-britain",              # europe
    "sea",                                                              # asia (South East Asia)
    "south-africa-and-lesotho",                                         # africa
    "us-midwest", "us-northeast", "us-pacific", "us-south", "us-west",   # north-america
    "kaliningrad",                                                      # russia
}

# Way types excluded from junction detection — matches MapTileManager.INTERSECTION_EXCLUDED_TYPES.
INTERSECTION_EXCLUDED_TYPES = {"address", "cycleway", "footway", "pedestrian"}

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "global_boxes")
CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "geofabrik_cache")
# A dedicated subdirectory, not C:\files itself: that is a real document folder (thousands of
# personal files), and multi-GB scratch indexes have no business being mixed in among them.
DEFAULT_SCRATCH_DIR = (r"C:\files\touchmap_scratch" if platform.system() == "Windows"
                       else "/tmp/touchmap-scratch")

STATE_NAME = "state.json"
# The sampled node coordinates, cached so that re-running step 1 with a different --threshold never
# re-reads the planet. The scan is by far the most expensive part of step 1 (hours over an 80 GB
# pbf) while the box-tree partition itself is seconds, so keeping the sample makes --threshold cheap
# to iterate on.
SAMPLE_FEATHER_NAME = "sampled_nodes.feather"
BOXES_NAME = "world_boxes.json"
REGIONS_NAME = "leaf_regions.json"
MANIFEST_NAME = "boxes_manifest.json"
MISSING_BOXES_NAME = "missing_boxes.json"


def log(code: str, msg: str):
    """Every worker is a separate process printing to the same shared stdout, so each line carries a
    prefix identifying its region/box. flush=True so lines from a long-running worker aren't stuck
    in that process's stdout buffer."""
    print(f"[{code}] {msg}", flush=True)


def estimate_peak_memory_bytes(pbf_size: int) -> int:
    return int(pbf_size * ESTIMATED_RAM_PER_PBF_BYTE) + BASE_WORKER_OVERHEAD_BYTES


def raise_file_limit(want: int) -> int:
    """Best-effort raise of the open-file-handle limit. With hundreds to thousands of box dbs and an
    LRU connection pool per worker, the default 512 handles on Windows / 1024 on POSIX is the binding
    constraint on how many dbs a worker can keep open. Returns the limit actually achieved."""
    try:
        if platform.system() == "Windows":
            crt = ctypes.CDLL("msvcrt")
            got = crt._setmaxstdio(want)
            return got if got > 0 else 512
        import resource
        soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        target = min(want, hard)
        if target > soft:
            resource.setrlimit(resource.RLIMIT_NOFILE, (target, hard))
        return target
    except Exception:
        return 512


# ---------------------------------------------------------------------------- geometry helpers
# Direct ports of Utils.java's coordinate/cell-index math — must stay numerically identical to what
# the app computes, since cell_x/cell_y drive real indexed queries at read time.

def java_round(x):
    """Matches Java's Math.round(double): floor(x + 0.5), not Python's banker's rounding."""
    return math.floor(x + 0.5)


def lat_lon_to_index(lat, lon, cell_size_meters=CELL_SIZE_METERS):
    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)
    y_index = java_round(EARTH_RADIUS * lat_rad / cell_size_meters)
    cell_width_rad = cell_size_meters / EARTH_RADIUS / math.cos(lat_rad)
    x_index = java_round(lon_rad / cell_width_rad)
    return int(x_index), int(y_index)


def index_to_lat_lon(index_x, index_y, cell_size_meters=CELL_SIZE_METERS):
    meters_x = index_x * cell_size_meters
    meters_y = index_y * cell_size_meters
    lat = math.degrees(meters_y / EARTH_RADIUS)
    lon = math.degrees(meters_x / (EARTH_RADIUS * math.cos(math.radians(lat))))
    return lat, lon


def cells_for_bbox(min_lat, max_lat, min_lon, max_lon, cell_size_meters=CELL_SIZE_METERS):
    """Every coarse cell a bbox touches — the same manual R-tree substitute the app's way_cells table
    (and MapTileManager's viewport query) uses. Never misses a touched cell, may over-include one
    only grazed at a boundary."""
    cells = []
    y_min = lat_lon_to_index(min_lat, min_lon, cell_size_meters)[1]
    y_max = lat_lon_to_index(max_lat, min_lon, cell_size_meters)[1]
    for cy in range(y_min, y_max + 1):
        row_lat, _ = index_to_lat_lon(0, cy, cell_size_meters)
        xa = lat_lon_to_index(row_lat, min_lon, cell_size_meters)[0]
        xb = lat_lon_to_index(row_lat, max_lon, cell_size_meters)[0]
        x_min, x_max = min(xa, xb), max(xa, xb)
        for cx in range(x_min, x_max + 1):
            cells.append((cx, cy))
    return cells


def coord_key(lat, lon):
    """Packs a lat/lon into a signed 64-bit int as two E7 halves — matches WayStore.coordKey /
    MapTileManager.coordKey exactly (including Java's fixed-width-shift truncation and sign
    reinterpretation), so a point's key is identical whether computed here or on-device."""
    lat_e7 = java_round(lat * 1e7) & 0xffffffff
    lon_e7 = java_round(lon * 1e7) & 0xffffffff
    combined = (lat_e7 << 32) | lon_e7
    if combined >= 2 ** 63:
        combined -= 2 ** 64
    return combined


# ---------------------------------------------------------------------------- msgpack packing
# Matches Utils.packTags/packWayPoints exactly (both are plain MessagePack — the wire format is
# value-defined, so msgpack-python's default int/map/array encoding already matches msgpack-java's
# byte-for-byte for these integer/string-only structures).

def pack_tags(tags: dict) -> bytes:
    return msgpack.packb(tags, use_bin_type=True)


def pack_way_points(lat_e7, lon_e7, region_id):
    """lat_e7/lon_e7 are already-quantized int32 E7 arrays sourced directly from osmium's own
    Location.y/x, which is bit-identical to java_round(lat*1e7)/java_round(lon*1e7) — the pbf's
    internal representation IS an E7 fixed-point integer at the standard granularity=100. So no
    rounding is needed here at all, unlike Utils.packWayPoints's Java-side float-degree input."""
    triples = [[la, lo, region_id] for la, lo in zip(lat_e7.tolist(), lon_e7.tolist())]
    return msgpack.packb(triples, use_bin_type=True)


# ---------------------------------------------------------------------------- tag rules
# Direct port of ImportTagRules.java — must stay identical so a box db's nodes/ways are classified
# exactly like a real on-device import would classify them. Classification only decides WHICH
# nodes/ways are kept; the tags actually written to the db are the feature's full original dict (see
# the module docstring), not the IMPORTANT_TAG_KEYS-filtered subset the old k-means tool stored.

CAT_SKIP = -255

IMPORTANT_NODE_TAGS = [
    ("emergency", 5), ("healthcare", 5),
    ("highway", 10), ("railway", 10), ("public_transport", 10),
    ("barrier", 15),
    ("shop", 20),
    ("amenity", 25),
    ("leisure", 30),
    ("tourism", 35), ("historic", 35),
    ("natural", 50), ("waterway", 50),
    ("name", 255),
]

IMPORTANT_HIGHWAY_VALUES = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "service", "living_street", "road",
    "pedestrian", "footway", "path", "cycleway", "steps", "corridor", "via_ferrata",
    "busway",
}

IMPORTANT_TAG_KEYS = {
    "highway", "footway", "sidewalk",
    "width", "incline",
    "indoor", "conveying", "ramp", "handrail", "step_count",
    "crossing", "tactile_paving",
    "sloped_curb", "crossing:island",
    "barrier",
    "name", "amenity", "public_transport", "railway",
    "man_made", "traffic_signals", "traffic_signals:sound",
    "traffic_signals:vibration", "tourism",
    "information", "door", "elevator", "wheelchair",
    "toilet", "toilets:access", "toilets:unisex", "toilets:wheelchair",
}


def get_way_type(tags: dict) -> str:
    hv = tags.get("highway")
    if hv in IMPORTANT_HIGHWAY_VALUES:
        return hv
    b = tags.get("barrier")
    if b is not None:
        return "barrier: " + b
    if tags.get("public_transport") == "platform":
        return "platform"
    rv = tags.get("railway")
    if rv == "platform":
        return "platform"
    if rv == "tram_stop":
        return "tram_stop"
    if tags.get("building") == "yes":
        return "address"
    return ""


def _rstrip_pipes(s: str) -> str:
    return s.rstrip("|")


def build_name_and_cat(tags: dict):
    """Returns (cat_id, type, name); cat_id == CAT_SKIP means "don't keep this node"."""
    if "addr:housenumber" in tags or "addr:street" in tags or "addr:place" in tags:
        housenumber = tags.get("addr:housenumber", "")
        street = tags.get("addr:street") if "addr:street" in tags else tags.get("addr:place", "")
        city = tags.get("addr:city", "")
        country = tags.get("addr:country", "")
        name = _rstrip_pipes(f"{housenumber}|{street}|{city}|{country}")
        return 0, "address", name

    name = tags.get("name", "")
    for tag, cat in IMPORTANT_NODE_TAGS:
        if tag == "name":
            continue
        if tag in tags:
            return cat, tags.get(tag, ""), name
    return CAT_SKIP, "", ""


def filter_navigation_tags(tags: dict, exclude: set) -> dict:
    """Used only to DECIDE whether a way carries a tag the app cares about (see way() below) — the
    result is no longer what gets stored; pack_tags() is called on the full tags dict instead."""
    out = {}
    for k, v in tags.items():
        if k in IMPORTANT_TAG_KEYS and (exclude is None or k not in exclude):
            out[k] = v
    return out


# ---------------------------------------------------------------------------- output db schema

def create_box_db(path: str) -> sqlite3.Connection:
    """Creates one empty box db with the exact schema OSMDatabaseHelper.onCreate() produces.

    journal_mode=WAL (rather than the OFF used by the single-writer per-country tool) because step 3
    runs several worker processes concurrently and two adjacent regions can legitimately land in the
    same box db at the same time. WAL is persisted in the file header, so it applies to every later
    connection; each connection additionally sets a long busy_timeout to wait out a peer's write
    transaction rather than failing."""
    if os.path.exists(path):
        os.remove(path)
    conn = sqlite3.connect(path)
    conn.executescript(f"""
        PRAGMA journal_mode=WAL;
        PRAGMA synchronous=OFF;
        PRAGMA user_version={DB_VERSION};

        CREATE TABLE nodes(
          id   INTEGER PRIMARY KEY,
          lat  REAL,
          lon  REAL,
          cell_x  INTEGER,
          cell_y  INTEGER,
          cat_id INTEGER,
          favorite INTEGER NOT NULL DEFAULT 0,
          type TEXT,
          name TEXT,
          tags BLOB,
          region INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS nodes_cell ON nodes (cell_x, cell_y);
        CREATE INDEX IF NOT EXISTS nodes_favorite ON nodes (favorite) WHERE favorite = 1;

        CREATE TABLE ways(
          id   INTEGER PRIMARY KEY,
          cat_id INTEGER,
          type TEXT,
          name TEXT,
          tags BLOB,
          min_lat REAL, max_lat REAL,
          min_lon REAL, max_lon REAL,
          points BLOB
        );
        CREATE INDEX IF NOT EXISTS ways_bbox ON ways(min_lat, max_lat, min_lon, max_lon);

        CREATE TABLE way_cells(
          way_id INTEGER NOT NULL,
          cell_x INTEGER NOT NULL,
          cell_y INTEGER NOT NULL,
          PRIMARY KEY(cell_x, cell_y, way_id)
        );
        CREATE INDEX IF NOT EXISTS way_cells_way ON way_cells(way_id);

        CREATE TABLE boundaries(
          region_id TEXT PRIMARY KEY,
          parent_id TEXT,
          name TEXT NOT NULL,
          depth INTEGER NOT NULL,
          iso2 TEXT,
          pbf_url TEXT,
          min_lat REAL, max_lat REAL,
          min_lon REAL, max_lon REAL,
          rings BLOB NOT NULL,
          imported_at INTEGER
        );
        CREATE INDEX IF NOT EXISTS boundaries_bbox ON boundaries(min_lat, max_lat, min_lon, max_lon);
        CREATE INDEX IF NOT EXISTS boundaries_parent ON boundaries(parent_id);

        CREATE TABLE regions(
          id      INTEGER PRIMARY KEY AUTOINCREMENT,
          country TEXT NOT NULL DEFAULT '',
          admin1  TEXT NOT NULL DEFAULT '',
          admin2  TEXT NOT NULL DEFAULT '',
          UNIQUE(country, admin1, admin2)
        );
        INSERT INTO regions (id, country, admin1, admin2) VALUES ({REGION_WORLD}, 'world', '', '');

        CREATE TABLE way_regions(
          region_id INTEGER NOT NULL,
          way_id    INTEGER NOT NULL,
          PRIMARY KEY(region_id, way_id)
        );
        CREATE INDEX IF NOT EXISTS way_regions_way ON way_regions(way_id);
    """)
    conn.commit()
    return conn


def open_box_db(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, timeout=300.0)
    conn.execute("PRAGMA busy_timeout=300000")
    conn.execute("PRAGMA synchronous=OFF")
    return conn


# ---------------------------------------------------------------------------- intersections

def find_and_persist_intersections(conn: sqlite3.Connection, region_id: int) -> int:
    """Same algorithm as MapTileManager.findIntersections/persistIntersections: group every way's
    points by exact coordinate, keep points touched by >=2 ways with distinct non-empty names
    (excluding types that don't form meaningful street junctions), and insert them with the same
    negative coordinate-derived id — so an intersection here and one the app computes live for the
    same physical point always resolve to the identical row."""
    names_by_coord = {}
    latlon_by_coord = {}
    cur = conn.execute("SELECT type, name, points FROM ways")
    for wtype, name, points_blob in cur:
        if not name or wtype in INTERSECTION_EXCLUDED_TYPES:
            continue
        for lat_e7, lon_e7, _region in msgpack.unpackb(points_blob):
            lat, lon = lat_e7 / 1e7, lon_e7 / 1e7
            key = coord_key(lat, lon)
            names_by_coord.setdefault(key, set()).add(name)
            latlon_by_coord.setdefault(key, (lat, lon))

    rows = []
    for key, names in names_by_coord.items():
        if len(names) < 2:
            continue
        lat, lon = latlon_by_coord[key]
        cell_x, cell_y = lat_lon_to_index(lat, lon)
        node_id = -abs(key) - 1
        rows.append((
            node_id, lat, lon, cell_x, cell_y, CATEGORY_INTERSECTION, 0, "intersection",
            "|".join(sorted(names)), None, region_id,
        ))
    if rows:
        conn.executemany(
            "INSERT OR IGNORE INTO nodes (id,lat,lon,cell_x,cell_y,cat_id,favorite,type,name,tags,region) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
        conn.commit()
    return len(rows)


# ---------------------------------------------------------------------------- Geofabrik index

def download_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "TouchMap-global-cluster-builder"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8")


def load_geofabrik_index() -> dict:
    os.makedirs(CACHE_DIR, exist_ok=True)
    cache_path = os.path.join(CACHE_DIR, "index-v1.json")
    if os.path.exists(cache_path) and (time.time() - os.path.getmtime(cache_path)) < 30 * 86400:
        with open(cache_path, encoding="utf-8") as f:
            text = f.read()
    else:
        text = download_text(INDEX_URL)
        with open(cache_path, "w", encoding="utf-8") as f:
            f.write(text)
    return json.loads(text)


def fetch_leaf_regions(data: dict):
    """Every childless region that has a downloadable pbf and isn't a Special Sub Region. Together
    these tile the planet without overlap."""
    by_id = {}
    for feature in data["features"]:
        props = feature["properties"]
        rid = props.get("id")
        if rid:
            by_id[rid] = props

    # A region "has children" if any other region declares it as `parent` OR is path-nested under its
    # id. The second case matters because Geofabrik represents US state extracts as path-style ids
    # ("us/california", "us/alabama") whose `parent` is the *continent* (north-america), not "us" —
    # so without the path check "us" would look childless and we'd process the whole 12 GB country as
    # one unit. Marking every id-path prefix as a parent makes "us" a non-leaf (skipped) while its
    # states become the leaves; "us/california" (which further splits into norcal/socal) is likewise
    # skipped in favour of those. Contrast Canada/Germany, which use proper `parent=canada`/
    # `germany`, and Iran, which has no descendants at all (a real leaf).
    parents = set()
    for rid, p in by_id.items():
        par = p.get("parent")
        if par:
            parents.add(par)
        if "/" in rid:
            parts = rid.split("/")
            for k in range(1, len(parts)):
                parents.add("/".join(parts[:k]))

    regions = []
    for rid, p in by_id.items():
        if rid in parents:
            continue  # has children — not a leaf
        if rid in SPECIAL_SUBREGION_IDS:
            continue  # overlaps/duplicates the real deepest regions
        pbf_url = (p.get("urls") or {}).get("pbf")
        if not pbf_url:
            continue
        iso_arr = p.get("iso3166-1:alpha2") or []
        regions.append({
            "id": rid,
            "name": p.get("name", rid),
            "iso2": iso_arr[0].upper() if iso_arr else None,
            "pbf_url": pbf_url,
        })
    regions.sort(key=lambda r: r["id"])
    return regions


def region_code(region: dict) -> str:
    """Filesystem-safe key for a region — the Geofabrik id with path separators flattened."""
    return "".join(c if c.isalnum() or c in "-_" else "-" for c in region["id"].lower())


def fetch_pbf_size(region: dict) -> int:
    """HEAD request for a region's pbf size, used to estimate its peak memory for scheduling.
    Returns 0 on any failure (caller decides whether/how to retry)."""
    try:
        req = urllib.request.Request(region["pbf_url"], method="HEAD",
                                     headers={"User-Agent": "TouchMap-global-cluster-builder"})
        with urllib.request.urlopen(req, timeout=30) as r:
            return int(r.headers.get("Content-Length", 0))
    except Exception:
        return 0


def download_pbf(url: str, dest: str, code: str):
    if os.path.exists(dest):
        log(code, f"using cached {os.path.basename(dest)}")
        return
    tmp = dest + ".part"
    log(code, f"downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "TouchMap-global-cluster-builder"})
    last_logged_pct = -1
    try:
        with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
            total = int(r.headers.get("Content-Length", 0))
            done = 0
            start = time.time()
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if total:
                    pct = int(done / total * 100)
                    if pct >= last_logged_pct + 10:  # every ~10%, not every chunk
                        last_logged_pct = pct
                        speed = done / 1e6 / max(1e-6, time.time() - start)
                        log(code, f"downloading: {done / 1e6:.0f}/{total / 1e6:.0f} MB ({pct}%) {speed:.1f} MB/s")
    except BaseException:
        # A partial .part is never resumed — the retry restarts from zero — so keeping it only costs
        # disk. BaseException, not Exception, so a Ctrl-C partway through a 12 GB download doesn't
        # strand it either.
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise
    os.replace(tmp, dest)


# ---------------------------------------------------------------------------- resumable state

class State:
    """Tiny JSON state file recording which steps (and which items within steps 3/4) are finished, so
    an interrupted run picks up where it stopped instead of redoing everything. Written atomically
    via os.replace so a crash mid-write can never leave a truncated state file behind."""

    def __init__(self, path: str):
        self.path = path
        self.data = {"step1_done": False, "step2_done": False,
                     "regions_done": [], "boxes_done": []}
        if os.path.exists(path):
            try:
                with open(path, encoding="utf-8") as f:
                    self.data.update(json.load(f))
            except Exception:
                pass  # unreadable state = start clean rather than crash
        self.regions_done = set(self.data.get("regions_done", []))
        self.boxes_done = set(self.data.get("boxes_done", []))

    def save(self):
        self.data["regions_done"] = sorted(self.regions_done)
        self.data["boxes_done"] = sorted(self.boxes_done)
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2)
        os.replace(tmp, self.path)


def write_json(path: str, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def read_json(path: str):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def box_db_path(out_dir: str, index: int) -> str:
    return os.path.join(out_dir, f"box_{index:06d}.db")


# ---------------------------------------------------------------------------- step 1: box partition

class PlanetSampler(osmium.SimpleHandler):
    """Streams a deterministic 1-in-`step` sample of every node's (lat, lon) — no ids, no tags, just
    enough to fit the global partition. Coordinates are kept as E7 int32 because osmium's own
    Location.y/x already carry exactly that integer, so there's no rounding step and it's half the
    size of float64.

    The buffer is preallocated once at `capacity` and written BY INDEX; it is never appended to or
    grown. Growing one big array incrementally over hundreds of millions of elements fragments the
    heap and raises MemoryError at a live-data size far below actual free RAM. `capacity` must be a
    firm upper bound — exceeding it raises rather than silently dropping samples.

    np.empty() only reserves the address range; the OS backs pages with physical memory lazily on
    first write, which would make a shortfall surface hours into the scan. Filling the buffer
    immediately after allocation forces that commitment up front, so an unaffordable capacity fails
    right here instead."""

    def __init__(self, step: int, capacity: int):
        super().__init__()
        self.step = step
        self.capacity = capacity
        self.lat_buf = np.empty(capacity, dtype=np.int32)
        self.lon_buf = np.empty(capacity, dtype=np.int32)
        self.lat_buf.fill(0)  # force real physical commitment of the whole buffer now, not lazily
        self.lon_buf.fill(0)
        self.count = 0
        self.kept = 0

    def node(self, n):
        self.count += 1
        if self.count % self.step == 0:
            if self.kept >= self.capacity:
                raise RuntimeError(
                    f"PlanetSampler exceeded its capacity of {self.capacity:,} samples — pass a "
                    f"larger --sample-capacity or a lower --sample-percent.")
            loc = n.location
            self.lat_buf[self.kept] = loc.y
            self.lon_buf[self.kept] = loc.x
            self.kept += 1
        if self.count % 50_000_000 == 0:
            log("step1", f"scanned {self.count:,} nodes, sampled {self.kept:,}")

    def trimmed(self):
        """Copies just the used portion into right-sized arrays and drops the (much larger)
        preallocated buffers, so memory shrinks back to the real sample count for the box-tree fit."""
        lat = self.lat_buf[:self.kept].copy()
        lon = self.lon_buf[:self.kept].copy()
        self.lat_buf = self.lon_buf = None
        return lat, lon


def _feather():
    """Imported lazily so the rest of the tool still runs if pyarrow isn't installed — only the
    sample cache needs it."""
    try:
        import pyarrow as pa
        import pyarrow.ipc  # noqa: F401  (registers pa.ipc)
        return pa
    except ImportError as e:
        raise RuntimeError("the sampled-node cache needs pyarrow (pip install pyarrow); pass "
                           "--no-sample-cache to run without it") from e


def save_sample_feather(path: str, lat_e7: np.ndarray, lon_e7: np.ndarray, sample_percent: float,
                        source_pbf: str, scanned: int):
    """Writes the sample as a Feather (Arrow IPC) table of two int32 columns.

    int32 E7 rather than float degrees: that is the sampler's native form (osmium's own Location.y/x),
    so it round-trips exactly and costs half of float64. The settings that produced the sample travel
    with it in the schema metadata, so a later run can tell whether the cache still matches what was
    asked for instead of silently partitioning the wrong data."""
    pa = _feather()
    table = pa.table(
        {"lat_e7": pa.array(lat_e7), "lon_e7": pa.array(lon_e7)},
        metadata={
            b"sample_percent": str(sample_percent).encode(),
            b"source_pbf": os.path.basename(source_pbf).encode(),
            b"scanned_nodes": str(scanned).encode(),
            b"created": time.strftime("%Y-%m-%dT%H:%M:%S").encode(),
        })
    tmp = path + ".tmp"
    # Written through the Arrow IPC file API rather than pyarrow.feather, which is deprecated as of
    # pyarrow 24. Feather V2 *is* the Arrow IPC file format, so this produces a genuine .feather that
    # pandas.read_feather and pyarrow both open normally.
    opts = pa.ipc.IpcWriteOptions(compression="zstd")
    # The sink is opened and closed explicitly: closing only the writer leaves the underlying file
    # handle open, and Windows then refuses the os.replace below with "used by another process".
    with pa.OSFile(tmp, "wb") as sink:
        with pa.ipc.new_file(sink, table.schema, options=opts) as writer:
            writer.write_table(table)
    os.replace(tmp, path)  # atomic: a crash mid-write can't leave a half-written cache in place
    log("step1", f"cached {len(lat_e7):,} sampled nodes -> {os.path.basename(path)} "
                 f"({os.path.getsize(path) / 1e9:.2f} GB)")


def load_sample_feather(path: str, sample_percent):
    """Returns (lat_e7, lon_e7, cached_percent) from the cache, or None if it is absent or unreadable.

    `sample_percent` is None when the user did not explicitly ask for one, in which case whatever the
    cache holds is adopted. That matters: the default would otherwise silently disagree with an
    existing cache and trigger a multi-hour re-scan of the planet just because the flag was not
    repeated. An explicitly requested percentage that differs IS honoured — the cache genuinely no
    longer represents what was asked for, so it is ignored and resampled."""
    if not os.path.exists(path):
        return None
    try:
        pa = _feather()
        with pa.ipc.open_file(path) as reader:
            table = reader.read_all()
        meta = table.schema.metadata or {}
        cached_pct = float(meta.get(b"sample_percent", b"nan"))
        if sample_percent is not None and not math.isclose(cached_pct, sample_percent, rel_tol=1e-9):
            log("step1", f"ignoring {os.path.basename(path)}: it holds a {cached_pct}% sample but "
                         f"{sample_percent}% was explicitly requested — re-sampling")
            return None
        lat = table.column("lat_e7").combine_chunks().to_numpy(zero_copy_only=False)
        lon = table.column("lon_e7").combine_chunks().to_numpy(zero_copy_only=False)
        src = (meta.get(b"source_pbf", b"?")).decode()
        created = (meta.get(b"created", b"?")).decode()
        log("step1", f"loaded {len(lat):,} sampled nodes from {os.path.basename(path)} "
                     f"(from {src}, sampled {created}) — skipping the planet scan")
        return (np.ascontiguousarray(lat, dtype=np.int32),
                np.ascontiguousarray(lon, dtype=np.int32), cached_pct)
    except Exception as e:
        log("step1", f"could not read {os.path.basename(path)} ({type(e).__name__}: {e}) — re-sampling")
        return None


# --- box tree ---------------------------------------------------------------------------------
# No k-means, no clustering library: the planet's whole lat/lon rectangle is recursively cut across
# its *longer* edge at the MEDIAN of the sampled coordinates on that edge, stopping a branch only
# once its sampled node count is below --threshold. Lon/lat are treated as flat Cartesian axes
# throughout (the user's own framing — no spherical distance anywhere here), matching
# build_planet_boxes.py's adaptive-density grid.

class _Leaf:
    __slots__ = ("index",)

    def __init__(self, index):
        self.index = index


class _Split:
    __slots__ = ("axis", "value", "lo", "hi")

    def __init__(self, axis, value, lo, hi):
        self.axis = axis    # 'lat' or 'lon'
        self.value = value  # split point, E7 (degrees * 1e7) — see build_box_tree
        self.lo = lo        # lat<=value (south) or lon<value (west)
        self.hi = hi        # lat>value (north) or lon>=value (east)


MIN_SPLIT_E7 = 10  # ~1e-6 degree (~1cm at the equator) — a box this small can't be split further


def median_split_value(values: np.ndarray, low_inclusive: bool) -> int:
    """The E7 coordinate that cuts `values` as close to in half as a single cut can.

    `low_inclusive` selects the rank to match the axis's own membership test, so that "half" is
    counted the way route_to_box will actually route: the lat axis sends `lat <= v` south, so the
    value at rank (n-1)//2 puts ceil(n/2) points south; the lon axis sends `lon < v` west, so rank
    n//2 is the equivalent there. With distinct coordinates both land on an exact half; ties shift
    the balance by however many points share the cut value, which is the floor for any cut that has
    to be a single coordinate.

    np.partition is O(n) introselect and returns a REORDERED COPY — it deliberately does not sort
    `values` in place, because lat and lon are parallel arrays whose pairing by index is the only
    thing tying a sample's two coordinates together. That copy is one int32 array the size of the
    box's own subset, transient, and never larger than the caller's existing arrays."""
    n = len(values)
    k = (n - 1) // 2 if low_inclusive else n // 2
    return int(np.partition(values, k)[k])


def build_box_tree(lat_e7: np.ndarray, lon_e7: np.ndarray, threshold: int):
    """Recursively splits the whole-planet box until every leaf holds fewer than `threshold` sampled
    nodes. A box's *shape* picks which axis to cut (always the longer edge); the sampled *data* picks
    where on that axis to cut (the median), so each child receives half its parent's sampled nodes.
    Returns (tree_root, boxes) where boxes is a flat list of (lat_min, lat_max, lon_min, lon_max) *in
    degrees* in leaf-visit order, tree_root's _Leaf.index values matching that list's positions.

    Why the median and not the midpoint: a midpoint cut divides area, and node density across that
    area varies by orders of magnitude, so one child routinely inherited nearly all of the parent's
    nodes while the other inherited almost none. Both children then recursed on their own — the dense
    one splitting several more times, the sparse one immediately becoming a leaf — which produced
    leaves anywhere from empty to just-under-threshold, and box dbs whose sizes varied just as
    wildly. Cutting at the median makes the halving a property of the construction rather than a hope
    about the data: every leaf ends up holding between threshold/2 and threshold sampled nodes (ties
    on a single coordinate aside), so the dbs come out comparable in size, and tree depth is bounded
    at ~log2(total_samples / threshold) instead of by how deep the densest city happens to sit in a
    fixed quadtree.

    Works entirely in E7 integers (degrees * 1e7, matching lat_e7/lon_e7's own units) rather than
    converting the whole sample to float64 degrees up front — that conversion would itself be a
    single large allocation the same size as the (potentially hundreds-of-millions-of-element) input.
    Only the four numbers per finished leaf get converted to degrees, a negligible cost. Split values
    are themselves sampled coordinates, so they are exact E7 integers needing no rounding.

    Boundary convention (so every point maps to exactly one box, no gaps/overlaps): each split's
    "south"/"west" child owns its edge inclusively, the "north"/"east" child exclusively at that same
    edge — i.e. a box owns lat in (lat_min, lat_max] and lon in [lon_min, lon_max). Matches
    route_to_box below: lat<=split goes south (that edge is the south child's own top, inclusive);
    the equivalent for longitude routes lon<split west. Note the children still inherit the parent's
    full extent on the un-cut side and tile it exactly — the cut moves off-centre, it does not shrink
    a box to its data's bounding box, so points absent from the sample still route somewhere.

    Passing the actual point *subset* down the recursion (rather than literally re-filtering the full
    sample array at every node) is mathematically identical — same counts, same splits, same final
    boxes — just without repeatedly rescanning already-excluded points; explicit `del`s below keep
    peak memory at O(total samples) rather than growing with recursion depth.

    Also returns each leaf's sampled-node count (parallel to `boxes`), which step 1 reports as a
    spread — the one number that shows at a glance whether the partition actually came out balanced."""
    boxes = []
    counts = []

    def leaf(n, lat_min, lat_max, lon_min, lon_max):
        idx = len(boxes)
        boxes.append((lat_min / 1e7, lat_max / 1e7, lon_min / 1e7, lon_max / 1e7))
        counts.append(n)
        return _Leaf(idx)

    def recurse(lat, lon, lat_min, lat_max, lon_min, lon_max):
        n = len(lat)
        if n < threshold or ((lat_max - lat_min) < MIN_SPLIT_E7 and (lon_max - lon_min) < MIN_SPLIT_E7):
            return leaf(n, lat_min, lat_max, lon_min, lon_max)

        # Longer edge first. The shorter edge is tried only as a fallback, for the case where every
        # sampled point in this box shares one coordinate on the longer edge — then no cut there can
        # separate anything, and cutting the other way is the only way to make progress.
        axes = ("lat", "lon") if (lat_max - lat_min) >= (lon_max - lon_min) else ("lon", "lat")
        for axis in axes:
            on_lat = axis == "lat"
            values = lat if on_lat else lon
            split = median_split_value(values, low_inclusive=on_lat)
            lo_mask = (values <= split) if on_lat else (values < split)
            n_lo = int(np.count_nonzero(lo_mask))
            if n_lo == 0 or n_lo == n:
                # Every sampled point lands on one side, so this cut would recurse on an identical
                # subset forever. (Only reachable when the points tie on this axis: the median is a
                # point's own coordinate, so with any two distinct values both sides are non-empty.)
                # Rejecting it here is also what guarantees the recursion terminates — every cut that
                # IS accepted leaves both children with strictly fewer than n points.
                del lo_mask
                continue

            lo_lat, lo_lon = lat[lo_mask], lon[lo_mask]
            hi_mask = ~lo_mask
            del lo_mask
            hi_lat, hi_lon = lat[hi_mask], lon[hi_mask]
            del lat, lon, values, hi_mask

            if on_lat:
                lo_box = (lat_min, split, lon_min, lon_max)
                hi_box = (split, lat_max, lon_min, lon_max)
            else:
                lo_box = (lat_min, lat_max, lon_min, split)
                hi_box = (lat_min, lat_max, split, lon_max)

            lo = recurse(lo_lat, lo_lon, *lo_box)
            del lo_lat, lo_lon
            hi = recurse(hi_lat, hi_lon, *hi_box)
            del hi_lat, hi_lon
            return _Split(axis, split, lo, hi)

        # Neither axis separated the points: they are all at the identical coordinate, so no cut of
        # any kind divides them. The box stays whole despite being over threshold.
        return leaf(n, lat_min, lat_max, lon_min, lon_max)

    root = recurse(lat_e7, lon_e7, -900_000_000, 900_000_000, -1_800_000_000, 1_800_000_000)
    return root, boxes, counts


def route_to_box(node, lat_e7: int, lon_e7: int) -> int:
    """Walks the tree built by build_box_tree for a single point (E7 ints — same units the tree's
    split values are in, so no per-call degree conversion). O(log(#boxes))."""
    while isinstance(node, _Split):
        if node.axis == "lat":
            node = node.lo if lat_e7 <= node.value else node.hi
        else:
            node = node.lo if lon_e7 < node.value else node.hi
    return node.index


def tree_to_json(node):
    """Serializes the box tree into a plain JSON-safe nested dict so it can live inside world_boxes.json
    and cross process boundaries (ProcessPoolExecutor pickles its arguments — a plain dict of ints/
    strs needs no special pickling support, unlike passing _Split/_Leaf objects directly)."""
    if isinstance(node, _Leaf):
        return {"leaf": node.index}
    return {"axis": node.axis, "value": node.value,
            "lo": tree_to_json(node.lo), "hi": tree_to_json(node.hi)}


def tree_from_json(d):
    if "leaf" in d:
        return _Leaf(d["leaf"])
    return _Split(d["axis"], d["value"], tree_from_json(d["lo"]), tree_from_json(d["hi"]))


def step1_build_boxes(planet_pbf: str, threshold: int, sample_percent: float, sample_capacity: int,
                      out_dir: str, resample: bool = False, use_sample_cache: bool = True):
    cache_path = os.path.join(out_dir, SAMPLE_FEATHER_NAME)
    cached = None if (resample or not use_sample_cache) else load_sample_feather(cache_path,
                                                                                sample_percent)
    if cached is not None:
        lat_e7, lon_e7, sample_percent = cached  # adopt the cache's percentage when none was given
    else:
        if sample_percent is None:
            sample_percent = DEFAULT_SAMPLE_PERCENT
        if not planet_pbf:
            raise RuntimeError(f"no cached sample at {cache_path} and no --planet-pbf given")
        step = max(1, round(100.0 / sample_percent))
        reserve_gb = sample_capacity * 4 / 1e9  # one int32 array; the sampler allocates two
        log("step1", f"reserving sample buffers (capacity {sample_capacity:,}, "
                     f"~{reserve_gb * 2:.1f} GB) ...")
        t0 = time.time()
        sampler = PlanetSampler(step, sample_capacity)
        log("step1", f"buffers committed in {time.time() - t0:.1f}s — if this hung or failed, the "
                     f"machine cannot back ~{reserve_gb * 2:.1f} GB; lower --sample-capacity")

        log("step1", f"sampling ~1/{step} of nodes ({sample_percent}%) from {planet_pbf} ...")
        with osmium.io.Reader(planet_pbf, osmium.osm.NODE) as reader:
            osmium.apply(reader, sampler)
        log("step1", f"sampled {sampler.kept:,} of {sampler.count:,} nodes")
        lat_e7, lon_e7 = sampler.trimmed()
        scanned = sampler.count
        del sampler
        if use_sample_cache:
            # Written before partitioning, so an expensive scan is never lost to a failure in the
            # (comparatively quick) box-tree split that follows.
            save_sample_feather(cache_path, lat_e7, lon_e7, sample_percent, planet_pbf, scanned)

    log("step1", f"partitioning {len(lat_e7):,} sampled points into boxes by median split "
                 f"(threshold={threshold:,} sampled nodes/box) ...")
    t0 = time.time()
    tree_root, box_bounds, box_counts = build_box_tree(lat_e7, lon_e7, threshold)
    del lat_e7, lon_e7
    log("step1", f"partitioned into {len(box_bounds)} boxes in {time.time() - t0:.0f}s")
    if box_counts:
        # The median split's whole point is that these three numbers sit close together — a min far
        # below threshold/2 would mean the cut is not actually halving the sample.
        ordered = sorted(box_counts)
        log("step1", f"sampled nodes per box: min {ordered[0]:,}, median "
                     f"{ordered[len(ordered) // 2]:,}, max {ordered[-1]:,} "
                     f"(threshold {threshold:,})")

    boxes = [
        {"index": i, "lat_min": lat_min, "lat_max": lat_max, "lon_min": lon_min, "lon_max": lon_max,
         "sampled_nodes": box_counts[i]}
        for i, (lat_min, lat_max, lon_min, lon_max) in enumerate(box_bounds)
    ]
    write_json(os.path.join(out_dir, BOXES_NAME), {
        "sample_percent": sample_percent,
        "threshold": threshold,
        "num_boxes": len(boxes),
        "boxes": boxes,
        # Part of the partition rule, not diagnostics — step 3 reconstructs this exact tree (see
        # tree_from_json) to route every feature. A box's min/max alone can't be routed against
        # directly (adjacent boxes' rectangles don't encode which one wins on a shared edge); the
        # split tree is what step1's own inclusive/exclusive boundary convention actually is.
        "tree": tree_to_json(tree_root),
    })
    log("step1", f"wrote {BOXES_NAME} with {len(boxes)} boxes + split tree")

    log("step1", f"creating {len(boxes)} empty box dbs...")
    for i in range(len(boxes)):
        conn = create_box_db(box_db_path(out_dir, i))
        conn.close()
    # Confirmed present, not just "the loop ran": a real run once ended up 93 box dbs short of the
    # planned 4096 with nothing in this function's own log to show for it (this loop has no per-
    # iteration error handling, so the *cause* wasn't here — something outside this tool removed them
    # between step 1 and step 4, see step4_finalize's own guard/warning) — but step 1 finishing without
    # verifying its own output let that gap ride silently through steps 2-4 for hours before a user
    # hit it in the app. Checking right here means any future disappearance (whatever the cause) is
    # caught at the earliest possible point instead of hours/days later.
    missing_after_create = [i for i in range(len(boxes)) if not os.path.exists(box_db_path(out_dir, i))]
    if missing_after_create:
        raise RuntimeError(
            f"{len(missing_after_create)}/{len(boxes)} box dbs are missing immediately after creating "
            f"them: {missing_after_create[:20]}{'...' if len(missing_after_create) > 20 else ''} — "
            f"something is deleting or failing to create them (disk space? antivirus/controlled-folder "
            f"access quarantining new .db files in {out_dir}? a cleanup script watching this folder?). "
            f"Investigate before continuing — steps 2-4 will otherwise silently build an incomplete "
            f"planet.")
    log("step1", f"created {len(boxes)} box dbs in {out_dir}")


# ---------------------------------------------------------------------------- step 2: leaf regions

def step2_leaf_regions(out_dir: str):
    log("step2", "loading Geofabrik index...")
    data = load_geofabrik_index()
    regions = fetch_leaf_regions(data)
    log("step2", f"found {len(regions)} leaf regions (childless, non-special, with a pbf)")

    log("step2", f"fetching pbf sizes for {len(regions)} regions...")
    with ThreadPoolExecutor(max_workers=16) as pool:
        sizes = list(pool.map(fetch_pbf_size, regions))
    unknown = 0
    for region, size in zip(regions, sizes):
        region["pbf_size"] = size
        if size <= 0:
            unknown += 1
    total = sum(r["pbf_size"] for r in regions)
    if unknown:
        log("step2", f"warning: {unknown} regions returned no size (they'll be scheduled last)")
    log("step2", f"total pbf volume: {total / 1e9:.1f} GB")
    write_json(os.path.join(out_dir, REGIONS_NAME), {"regions": regions})
    log("step2", f"wrote {REGIONS_NAME}")


# ---------------------------------------------------------------------------- step 3: import regions

class DbPool:
    """Bounded LRU cache of open box-db connections. With hundreds to thousands of boxes a worker
    can't hold every connection open at once, and the pbf stream offers no geographic locality to
    exploit (nodes arrive in id order), so least-recently-used eviction — committing and closing the
    evicted connection, reopening on demand — is the simplest thing that stays within the handle
    limit."""

    def __init__(self, out_dir: str, max_open: int):
        self.out_dir = out_dir
        self.max_open = max_open
        self.conns = OrderedDict()

    def get(self, box_index: int) -> sqlite3.Connection:
        conn = self.conns.pop(box_index, None)
        if conn is not None:
            self.conns[box_index] = conn  # refresh recency
            return conn
        path = box_db_path(self.out_dir, box_index)
        if not os.path.exists(path):
            # Step 1 created every box db up front. A missing file here means the run is being
            # driven out of order (step 3 without step 1), and creating one now would silently
            # produce a db the manifest never accounts for — better to fail loudly.
            raise RuntimeError(f"box db missing: {path} — run step 1 first")
        conn = open_box_db(path)
        self.conns[box_index] = conn
        if len(self.conns) > self.max_open:
            _, evicted = self.conns.popitem(last=False)
            evicted.commit()
            evicted.close()
        return conn

    def close_all(self):
        for conn in self.conns.values():
            conn.commit()
            conn.close()
        self.conns.clear()


class RegionImporter(osmium.SimpleHandler):
    """Reads one region's pbf with node locations already resolved onto ways, classifies nodes/ways
    with the same rules OsmPbfImporter uses, and routes each into its box db(s) via the box tree.

    Unlike the balanced k-means predecessor, routing a point needs no batched nearest-neighbour
    query — walking the box tree is already O(log #boxes) per point and cheap enough to do inline as
    each node/way streams in, so there is no pending-batch queue here."""

    # Rows are never inserted one at a time. Each box db gets its own in-memory row cache, and a
    # cache is written out with a single executemany once it holds FLUSH_ROWS rows — and again for
    # every non-empty cache when the region finishes (flush_all).
    FLUSH_ROWS = 5_000
    # A region touching very many boxes could otherwise hold FLUSH_ROWS rows for each of them at
    # once. This caps total rows held across all caches; crossing it flushes everything, keeping a
    # worker's buffer memory bounded regardless of how many boxes the region spans.
    MAX_BUFFERED_ROWS = 400_000

    def __init__(self, tree_root, pool: DbPool, code: str):
        super().__init__()
        self.tree_root = tree_root
        self.pool = pool
        self.code = code
        self.buffered = 0

        self.node_buf = {}      # box_index -> list of node row tuples
        self.way_buf = {}
        self.way_cell_buf = {}
        self.way_region_buf = {}

        self.nodes_seen = 0
        self.nodes_kept = 0
        self.ways_seen = 0
        self.ways_kept = 0

    # -- nodes ---------------------------------------------------------------

    def node(self, n):
        self.nodes_seen += 1
        if self.nodes_seen % 5_000_000 == 0:
            log(self.code, f"nodes: {self.nodes_seen:,} seen, {self.nodes_kept:,} kept")
        tags = dict(n.tags)
        if not tags:
            return
        cat_id, ntype, name = build_name_and_cat(tags)
        if cat_id == CAT_SKIP:
            return  # not a useful node — addresses, shops, benches etc. all pass this check

        loc = n.location
        if not loc.valid():
            return
        lat, lon = loc.lat, loc.lon
        box_index = route_to_box(self.tree_root, loc.y, loc.x)
        cell_x, cell_y = lat_lon_to_index(lat, lon)
        row = (
            abs(n.id) + ID_OFFSET, lat, lon, cell_x, cell_y, cat_id, 0, ntype, name,
            pack_tags(tags), REGION_WORLD,  # full tags — not filtered, see module docstring
        )
        rows = self.node_buf.setdefault(box_index, [])
        rows.append(row)
        self.buffered += 1
        self.nodes_kept += 1
        if len(rows) >= self.FLUSH_ROWS:
            self._flush(box_index)
        if self.buffered >= self.MAX_BUFFERED_ROWS:
            self._flush_all_buffers()

    # -- ways ----------------------------------------------------------------

    def way(self, w):
        self.ways_seen += 1
        if self.ways_seen % 500_000 == 0:
            log(self.code, f"ways: {self.ways_seen:,} seen, {self.ways_kept:,} kept")
        if len(w.nodes) < 2:
            return
        # Classify from tags before touching the node list — most ways are rejected here, and
        # skipping the per-vertex work for them is a real win on dense extracts.
        tags = dict(w.tags)
        wtype = get_way_type(tags)
        if not wtype:
            return
        # filter_navigation_tags is used here ONLY to decide whether this way carries a tag the app
        # cares about — its result is not what gets stored (see tags_blob below, which packs the
        # full `tags` dict).
        filtered = filter_navigation_tags(tags, None)
        if not filtered and wtype != "address":
            return
        if wtype == "address":
            _, _, way_name = build_name_and_cat(tags)
            if not way_name:
                return
        else:
            way_name = tags.get("name", "")

        # Locations were resolved by osmium during the read, so every vertex carries its coordinate
        # here. A way with any unresolvable vertex is skipped outright, matching
        # OsmPbfImporter.processWay's strict incomplete-geometry rule. (At a region border this is
        # normal: the neighbouring vertex simply isn't in this extract.)
        lats = []
        lons = []
        for nd in w.nodes:
            loc = nd.location
            if not loc.valid():
                return
            lats.append(loc.y)
            lons.append(loc.x)
        lat_e7 = np.asarray(lats, dtype=np.int32)
        lon_e7 = np.asarray(lons, dtype=np.int32)

        boxes_touched = {route_to_box(self.tree_root, la, lo)
                         for la, lo in zip(lat_e7.tolist(), lon_e7.tolist())}

        if wtype == "address":
            cat_id = 0
        elif wtype.startswith("barrier:"):
            cat_id = 15
        else:
            cat_id = 60

        way_id = abs(w.id) + ID_OFFSET
        min_lat, max_lat = float(lat_e7.min()) / 1e7, float(lat_e7.max()) / 1e7
        min_lon, max_lon = float(lon_e7.min()) / 1e7, float(lon_e7.max()) / 1e7
        points_blob = pack_way_points(lat_e7, lon_e7, REGION_WORLD)
        tags_blob = pack_tags(tags)  # full tags — not filtered, see module docstring
        cells = cells_for_bbox(min_lat, max_lat, min_lon, max_lon)

        # The full geometry goes into every box the way touches, so each box db is self-contained
        # for anything it renders — no cross-db lookup is ever needed at read time.
        for box_index in boxes_touched:
            self.way_buf.setdefault(box_index, []).append((
                way_id, cat_id, wtype, way_name, tags_blob,
                min_lat, max_lat, min_lon, max_lon, points_blob,
            ))
            cell_buf = self.way_cell_buf.setdefault(box_index, [])
            for cx, cy in cells:
                cell_buf.append((way_id, cx, cy))
            self.way_region_buf.setdefault(box_index, []).append((REGION_WORLD, way_id))
            self.buffered += 1
            if len(self.way_buf[box_index]) >= self.FLUSH_ROWS:
                self._flush(box_index)
        self.ways_kept += 1
        if self.buffered >= self.MAX_BUFFERED_ROWS:
            self._flush_all_buffers()

    # -- writing -------------------------------------------------------------

    def _flush(self, box_index: int):
        """Writes one box's cached rows with a single executemany per table, then commits."""
        conn = self.pool.get(box_index)
        self.buffered -= len(self.node_buf.get(box_index) or ()) + len(self.way_buf.get(box_index) or ())
        rows = self.node_buf.get(box_index)
        if rows:
            conn.executemany(
                "INSERT OR IGNORE INTO nodes (id,lat,lon,cell_x,cell_y,cat_id,favorite,type,name,tags,region) "
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
            self.node_buf[box_index] = []
        rows = self.way_buf.get(box_index)
        if rows:
            conn.executemany(
                "INSERT OR IGNORE INTO ways (id,cat_id,type,name,tags,min_lat,max_lat,min_lon,max_lon,points) "
                "VALUES (?,?,?,?,?,?,?,?,?,?)", rows)
            self.way_buf[box_index] = []
        rows = self.way_cell_buf.get(box_index)
        if rows:
            conn.executemany(
                "INSERT OR IGNORE INTO way_cells (way_id,cell_x,cell_y) VALUES (?,?,?)", rows)
            self.way_cell_buf[box_index] = []
        rows = self.way_region_buf.get(box_index)
        if rows:
            conn.executemany(
                "INSERT OR IGNORE INTO way_regions (region_id,way_id) VALUES (?,?)", rows)
            self.way_region_buf[box_index] = []
        conn.commit()

    def _flush_all_buffers(self):
        boxes = set(self.node_buf) | set(self.way_buf) | set(self.way_cell_buf) | set(self.way_region_buf)
        for box_index in sorted(boxes):
            self._flush(box_index)
        self.buffered = 0

    def flush_all(self):
        """End of a region: write out every non-empty cache."""
        self._flush_all_buffers()


LOCATION_INDEX_PREFIX = "locidx_"
LOCATION_INDEX_SUFFIX = ".bin"


def location_index_path(scratch_dir: str, code: str) -> str:
    return os.path.join(scratch_dir, f"{LOCATION_INDEX_PREFIX}{code}{LOCATION_INDEX_SUFFIX}")


def remove_location_index(idx_path, code: str) -> bool:
    """Deletes a finished region's node-location index (~1-2 GB each, and up to tens of GB for the
    largest regions). Returns True once the file is gone.

    Called from two places with different odds of success. From the PARENT after the owning worker
    process has exited, the unlink simply works — that is the path that actually removes these files
    (see step3_import_regions on why the worker has to exit first). From inside the WORKER it is
    best-effort: pyosmium holds the sparse_file_array mmap open for the rest of that process's life,
    and Windows refuses to unlink a mapped file, so the retries below usually run out. They are still
    worth doing — on POSIX unlink-while-mapped succeeds immediately, and the gc/backoff loop catches
    the cases where the handle is merely in the middle of being released.

    When the retries do run out, the file is TRUNCATED to zero bytes. A name left in the scratch dir
    costs nothing; the gigabytes behind it are the actual problem, and truncation reclaims them right
    away even while the mapping pins the directory entry — which is what keeps the scratch dir's
    high-water mark down mid-run rather than only at process exit. A final failure at even that is
    LOGGED, never swallowed: silently ignoring it is exactly how ~47 GB of dead indexes accumulated
    unnoticed, one per large region, in a directory nobody was watching."""
    if not idx_path:
        return True
    for attempt in range(8):
        if not os.path.exists(idx_path):
            return True
        try:
            os.remove(idx_path)
            return True
        except OSError:
            gc.collect()
            time.sleep(0.5 * (attempt + 1))
    try:
        size = os.path.getsize(idx_path)
        with open(idx_path, "r+b") as f:
            f.truncate(0)
        log(code, f"could not unlink location index {idx_path} (still mapped) — truncated it "
                  f"instead, reclaiming ~{size / 1e9:.1f} GB; the empty file is swept next run")
    except OSError as e:
        log(code, f"WARNING: could not delete or truncate location index {idx_path} ({e}) — it is "
                  f"scratch data, safe to delete by hand once this run finishes")
    return False


def sweep_location_indexes(scratch_dir: str) -> int:
    """Deletes EVERY locidx_*.bin in the scratch dir, returning the bytes reclaimed.

    These files only ever live for the duration of one worker's apply_file call, so any that exist
    when this runs belong to a run that is already over — one that was hard-killed, or whose worker
    died in a way no `finally` could catch. Sweeping by pattern rather than by "which regions does
    state.json say finished" is the difference between reclaiming all of the leaked scratch and
    reclaiming only the part that leaked from successful regions: a region that crashed or was killed
    is precisely the one that leaks its index AND never gets recorded as done, so the old
    done-regions-only sweep could never reach it.

    Call this only when no worker of THIS process is running (step 3 does so before spawning its pool
    and after it drains). The one thing it is not safe against is two copies of this tool sharing a
    --scratch-dir; give concurrent runs separate scratch dirs."""
    if not os.path.isdir(scratch_dir):
        return 0
    freed = 0
    for name in os.listdir(scratch_dir):
        if not (name.startswith(LOCATION_INDEX_PREFIX) and name.endswith(LOCATION_INDEX_SUFFIX)):
            continue
        path = os.path.join(scratch_dir, name)
        try:
            size = os.path.getsize(path)
        except OSError:
            continue
        if remove_location_index(path, "sweep"):
            freed += size
    return freed


def sweep_partial_downloads() -> int:
    """Removes half-downloaded *.osm.pbf.part files. download_pbf writes through a .part and renames
    on success, so a leftover one is always from an interrupted download and is never resumed — the
    retry restarts the file from zero. Same reasoning as the index sweep: only safe with no download
    of this run in flight, which is why step 3 calls it before starting its pool."""
    if not os.path.isdir(CACHE_DIR):
        return 0
    freed = 0
    for name in os.listdir(CACHE_DIR):
        if not name.endswith(".part"):
            continue
        path = os.path.join(CACHE_DIR, name)
        try:
            size = os.path.getsize(path)
            os.remove(path)
            freed += size
        except OSError:
            pass
    return freed


def _import_region(region: dict, out_dir: str, scratch_dir: str, tree_root, max_open: int,
                   keep_pbf: bool):
    code = region_code(region)
    pbf_size = region.get("pbf_size") or fetch_pbf_size(region)
    pbf_path = os.path.join(CACHE_DIR, f"{code}.osm.pbf")
    os.makedirs(CACHE_DIR, exist_ok=True)
    download_pbf(region["pbf_url"], pbf_path, code)

    pool = DbPool(out_dir, max_open)

    # osmium needs a node-location index to attach coordinates to way vertices. Small extracts are
    # fastest with the in-RAM flex_mem index; past LOCATION_INDEX_DISK_THRESHOLD that index would
    # dominate the worker's footprint, so it goes to disk in the scratch dir instead. Each worker
    # gets its own index file, since several run concurrently.
    idx_path = None
    if pbf_size and pbf_size > LOCATION_INDEX_DISK_THRESHOLD:
        os.makedirs(scratch_dir, exist_ok=True)
        idx_path = location_index_path(scratch_dir, code)
        if os.path.exists(idx_path):
            os.remove(idx_path)  # stale file from a crashed run
        idx_spec = f"sparse_file_array,{idx_path}"
        log(code, f"{pbf_size / 1e6:.0f} MB pbf — resolving locations via on-disk index {idx_path}")
    else:
        idx_spec = "flex_mem"

    importer = RegionImporter(tree_root, pool, code)
    try:
        importer.apply_file(pbf_path, locations=True, idx=idx_spec)
        # The index is dead the instant apply_file returns — nothing past this line reads it — so it
        # is released here rather than after the final flush. flush_all can take minutes on a region
        # that touches many boxes, and holding a multi-GB index across that window for no reason is
        # what drives the scratch dir's high-water mark up: it multiplies by the live worker count.
        # This process cannot unlink the file (pyosmium still has it mapped), but it can and does
        # truncate it, which is what reclaims the space; the parent unlinks the husk once this
        # process exits.
        remove_location_index(idx_path, code)
        idx_path = None
        importer.flush_all()
    finally:
        pool.close_all()
        # Backstop for every path that skips the release above: an exception inside apply_file, or
        # one raised between it and the release.
        remove_location_index(idx_path, code)
        # In the finally, not after it: a region that fails partway has still downloaded its pbf, and
        # those are the biggest files this tool touches. Leaving them behind on the error path is the
        # same storage leak as the index, just larger. A retry re-downloads, which is the cost the
        # tool already accepts for every successful region; --keep-pbf opts out of both.
        if not keep_pbf:
            try:
                os.remove(pbf_path)
            except OSError:
                pass

    log(code, f"done: {importer.nodes_kept:,} useful nodes, {importer.ways_kept:,} ways "
              f"(of {importer.nodes_seen:,} nodes / {importer.ways_seen:,} ways)")
    return {"nodes": importer.nodes_kept, "ways": importer.ways_kept}


def _region_worker(region: dict, out_dir: str, scratch_dir: str, tree_dict: dict, max_open: int,
                   keep_pbf: bool):
    """Top-level (picklable) worker entry point. Never lets an exception cross the process boundary
    as a raw traceback — returns it as data instead, so one region's failure can't take down the
    whole pool."""
    rid = region["id"]
    try:
        tree_root = tree_from_json(tree_dict)
        stats = _import_region(region, out_dir, scratch_dir, tree_root, max_open, keep_pbf)
        return rid, stats, None
    except Exception as e:
        return rid, None, f"{type(e).__name__}: {e}"


def step3_import_regions(out_dir: str, scratch_dir: str, state: State, workers: int,
                         budget_bytes: int, keep_pbf: bool, only: set):
    boxes_doc = read_json(os.path.join(out_dir, BOXES_NAME))
    tree_dict = boxes_doc["tree"]
    regions = read_json(os.path.join(out_dir, REGIONS_NAME))["regions"]
    if only:
        regions = [r for r in regions if r["id"] in only or region_code(r) in only]

    # Before anything of this run is in flight, so every locidx_*.bin / *.part present is by
    # definition an earlier run's orphan.
    freed = sweep_location_indexes(scratch_dir) + sweep_partial_downloads()
    if freed:
        log("step3", f"reclaimed {freed / 1e9:.1f} GB of scratch left by earlier runs")

    todo = [r for r in regions if r["id"] not in state.regions_done]
    log("step3", f"{len(todo)} regions to import ({len(regions) - len(todo)} already done)")
    if not todo:
        return

    for r in todo:
        r["mem_estimate"] = estimate_peak_memory_bytes(r.get("pbf_size") or 0)
    # Largest-first, so the first-fit packing below gets big regions running while there's still
    # budget slack, rather than stranding them behind a long tail of small ones.
    todo.sort(key=lambda r: -r["mem_estimate"])

    limit = raise_file_limit(4096)
    max_open = max(16, min(512, limit - 64) // max(1, workers))
    log("step3", f"file handle limit {limit}, up to {max_open} open dbs per worker")

    pending = list(todo)
    in_flight = {}
    used_memory = 0
    total = len(pending)
    done_count = 0

    try:
        # max_tasks_per_child=1 — one region per worker process, then the process exits and a fresh
        # one replaces it. This is not tuning, it is the only thing that makes the location index
        # deletable at all: pyosmium's sparse_file_array keeps its mmap open for the lifetime of the
        # process that created it, with no API to close it (index.clear() and dropping every Python
        # reference both leave the handle open). On Windows that mapping blocks unlink outright, so
        # in a pool that REUSES workers the index of every large region stays locked until the whole
        # run ends — which is exactly how tens of GB of dead .bin files piled up in the scratch dir.
        # Letting the process exit is the close() that pyosmium doesn't expose; the parent then
        # unlinks the file below. The per-region process spawn costs a couple of seconds against
        # region imports measured in minutes, and it throws in a clean heap per region for free.
        with ProcessPoolExecutor(max_workers=workers, max_tasks_per_child=1) as pool:
            while pending or in_flight:
                i = 0
                while i < len(pending) and len(in_flight) < workers:
                    region = pending[i]
                    est = region["mem_estimate"]
                    # A job always starts when the pool is otherwise idle, regardless of its own size,
                    # so one huge region (Russia, say) can't starve forever waiting for a budget it
                    # will never fit under alone.
                    if used_memory + est <= budget_bytes or not in_flight:
                        future = pool.submit(_region_worker, region, out_dir, scratch_dir, tree_dict,
                                             max_open, keep_pbf)
                        in_flight[future] = (est, region_code(region))
                        used_memory += est
                        pending.pop(i)
                    else:
                        i += 1  # doesn't fit right now — try the next (smaller) candidate
                if not in_flight:
                    break
                done_set, _ = wait(in_flight.keys(), return_when=FIRST_COMPLETED)
                for future in done_set:
                    est, code = in_flight.pop(future)
                    used_memory -= est
                    done_count += 1
                    rid, stats, error = future.result()
                    # Targeted at the finished region's own index only, never a blanket sweep: other
                    # workers are still running and their indexes are live. The worker that owned this
                    # one has returned its result and is on its way out, so remove_location_index's
                    # retry/backoff is what rides out the last moments of its teardown. Runs for
                    # failed regions too — a crash mid-import leaves the same file behind.
                    remove_location_index(location_index_path(scratch_dir, code), code)
                    if error is not None:
                        log("step3", f"FAILED {rid} ({done_count}/{total}): {error}")
                        continue
                    state.regions_done.add(rid)
                    state.save()  # incremental — an interrupted run resumes from exactly here
                    log("step3", f"{rid} complete ({done_count}/{total}) — "
                                 f"{stats['nodes']:,} nodes, {stats['ways']:,} ways")
    finally:
        # The pool has drained (the `with` joins its workers, including on the Ctrl-C path), so any
        # index still on disk belongs to a worker that died without running its own cleanup — an OOM
        # kill, a segfault in osmium, a hard interrupt. This is the sweep that keeps those from
        # surviving the run that created them.
        freed = sweep_location_indexes(scratch_dir) + sweep_partial_downloads()
        if freed:
            log("step3", f"reclaimed {freed / 1e9:.1f} GB of scratch from workers that did not "
                         f"clean up after themselves")


# ---------------------------------------------------------------------------- step 4: finalize

def _finalize_box(index: int, out_dir: str):
    path = box_db_path(out_dir, index)
    if not os.path.exists(path):
        # sqlite3.connect() on a missing path silently CREATES an empty file rather than raising —
        # without this check, that empty stub goes on to fail on the very next line with a confusing
        # "no such table: nodes", indistinguishable in the log from any other corrupt-db failure. A
        # missing box db here means step 3 never wrote (or something outside this tool deleted) that
        # box's data entirely; DbPool.get() in step 3 already fails loudly for exactly this same
        # situation (see its docstring) — this mirrors that so step 4 does too, instead of quietly
        # producing an incomplete manifest that only surfaces once a user hits the gap in the app. This
        # exact failure mode (93 boxes silently missing, one of them covering Tokyo) is why this check
        # exists — see the "missing box dbs" summary step4_finalize prints/writes after the pool drains.
        raise RuntimeError(f"box db file does not exist: {path} (step 3 never wrote it, or it was "
                           f"deleted after step 3 ran)")
    conn = open_box_db(path)
    try:
        # Duplicate suppression is structural rather than a cleanup pass: nodes.id and ways.id are
        # INTEGER PRIMARY KEY, and way_cells/way_regions have composite primary keys, so the
        # INSERT OR IGNORE used throughout step 3 makes a duplicate row impossible to create even
        # when two regions (or a re-run of a partially-finished region) submit the same feature.
        # This counts what's actually there so the manifest can be checked against expectations.
        n_nodes = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
        n_ways = conn.execute("SELECT COUNT(*) FROM ways").fetchone()[0]

        n_inter = find_and_persist_intersections(conn, REGION_WORLD)

        # WAL pages have to be folded back into the main db before VACUUM can actually shrink it.
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    db_size = os.path.getsize(path)
    gz_path = path + ".gz"
    with open(path, "rb") as src, gzip.open(gz_path, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst, length=1 << 20)
    gz_size = os.path.getsize(gz_path)

    # The .gz is the only artifact anything downstream (the manifest, the app's download) ever reads
    # — the plain .db was only ever scratch space for this function. With thousands of boxes this adds
    # up to real disk (the whole planet's worth, uncompressed, left lying around after every run
    # otherwise had no way to reclaim it — exactly what happened before: every leftover .db across a
    # full run is where "someone/something cleaned them up by hand afterward" was likely headed, see
    # [[box-manifest-data-gap]]). Deleting only reaches here once the .gz write above has fully
    # completed without raising and its size was read back successfully, so a box that fails partway
    # (this function raising, caught by _finalize_worker) always leaves its source .db intact for a
    # retry — never deletes the one copy of a box's data before its replacement is confirmed to exist.
    deleted = _delete_box_db_files(path)
    if not deleted:
        log("step4", f"box_{index:06d}: gzip'd but could not delete source .db (left in place, "
                     f"harmless — just wastes disk until removed by hand)")

    return {"index": index, "nodes": n_nodes, "ways": n_ways, "intersections": n_inter,
            "db_size_mb": round(db_size / 1e6, 3), "gz_size_mb": round(gz_size / 1e6, 3)}


def _delete_box_db_files(path: str) -> bool:
    """Removes a finalized box's plain .db and any leftover WAL sidecar files. Best-effort: a failure
    here (e.g. an antivirus/indexer transiently holding the file open) only means a bit of disk isn't
    reclaimed yet, never data loss — the .gz this is called after producing is already complete and is
    the artifact everything downstream uses. Returns whether the primary .db was actually removed."""
    ok = True
    for suffix in ("", "-wal", "-shm", "-journal"):
        p = path + suffix
        if not os.path.exists(p):
            continue
        try:
            os.remove(p)
        except OSError as e:
            if suffix == "":
                ok = False
            log("step4", f"could not delete {p}: {e}")
    return ok


def _finalize_worker(args):
    index, out_dir = args
    try:
        return _finalize_box(index, out_dir), None
    except Exception as e:
        return {"index": index}, f"{type(e).__name__}: {e}"


def step4_finalize(out_dir: str, state: State, workers: int):
    boxes_doc = read_json(os.path.join(out_dir, BOXES_NAME))
    boxes = boxes_doc["boxes"]
    k = len(boxes)

    todo = [i for i in range(k) if i not in state.boxes_done]
    log("step4", f"{len(todo)} box dbs to finalize ({k - len(todo)} already done)")

    results = {}
    manifest_path = os.path.join(out_dir, MANIFEST_NAME)
    if os.path.exists(manifest_path):
        try:
            for entry in read_json(manifest_path)["boxes"]:
                results[entry["index"]] = entry
        except Exception:
            pass  # unreadable/partial manifest just gets rebuilt from scratch below

    if todo:
        with ProcessPoolExecutor(max_workers=workers) as pool:
            for stats, error in pool.map(_finalize_worker, [(i, out_dir) for i in todo]):
                idx = stats["index"]
                if error is not None:
                    log("step4", f"FAILED box_{idx:06d}: {error}")
                    continue
                b = boxes[idx]
                stats["lat_min"] = b["lat_min"]
                stats["lat_max"] = b["lat_max"]
                stats["lon_min"] = b["lon_min"]
                stats["lon_max"] = b["lon_max"]
                # What step 1's partition predicted this box would hold, next to what it actually
                # holds — the pair is how you tell a bad --sample-percent from a bad --threshold.
                stats["sampled_nodes"] = b.get("sampled_nodes")
                results[idx] = stats
                state.boxes_done.add(idx)
                state.save()
                log("step4", f"box_{idx:06d}: {stats['nodes']:,} nodes, {stats['ways']:,} ways, "
                             f"{stats['intersections']:,} intersections, "
                             f"{stats['db_size_mb']:.1f} MB -> {stats['gz_size_mb']:.1f} MB gz")

    boxes_out = [results[i] for i in sorted(results)]
    write_json(manifest_path, {
        "num_boxes": k,
        "num_boxes_finalized": len(boxes_out),
        "total_db_size_mb": round(sum(b.get("db_size_mb", 0) for b in boxes_out), 2),
        "total_gz_size_mb": round(sum(b.get("gz_size_mb", 0) for b in boxes_out), 2),
        "boxes": boxes_out,
    })
    log("step4", f"wrote {MANIFEST_NAME} with {len(boxes_out)} boxes")

    # A box silently missing from the manifest (its .gz was never produced) means anyone whose
    # location happens to fall in it gets "no map data available" with no indication why — this is
    # exactly what happened for a real user in Tokyo, discovered only after the fact. num_boxes vs.
    # len(boxes) diverging is the same signal, but nothing forced a human to actually notice the
    # mismatch; this makes it impossible to miss and gives the exact indices + geographic bounds
    # needed to act on it (re-run step 3 for whatever region(s) cover them, or step 4 alone once their
    # box db exists again).
    missing = sorted(set(range(k)) - set(results.keys()))
    if missing:
        log("step4", f"WARNING: {len(missing)}/{k} boxes are MISSING from the manifest — "
                     f"anyone whose location falls in one of these gets \"no map data available\" "
                     f"in the app with no other warning. See {MISSING_BOXES_NAME}.")
        write_json(os.path.join(out_dir, MISSING_BOXES_NAME), {
            "missing_count": len(missing),
            "total_boxes": k,
            "boxes": [
                {"index": i, "lat_min": boxes[i]["lat_min"], "lat_max": boxes[i]["lat_max"],
                 "lon_min": boxes[i]["lon_min"], "lon_max": boxes[i]["lon_max"],
                 "sampled_nodes": boxes[i].get("sampled_nodes")}
                for i in missing
            ],
        })
    else:
        # Explicit, not just the absence of a warning — a clean run should say so, so a quiet log
        # doesn't get mistaken for "didn't check."
        log("step4", f"all {k} boxes present in the manifest — no gaps.")


# ---------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(
        description="Build a global grid of adaptive-density-boxed, app-schema map databases for "
                    "the planet.")
    ap.add_argument("--planet-pbf", help="Path to planet-latest.osm.pbf (required for step 1).")
    ap.add_argument("--threshold", type=int, default=DEFAULT_THRESHOLD,
                    help=f"Max sampled-node count a box may hold before it's split further "
                         f"(default {DEFAULT_THRESHOLD:,}). Lower = more, smaller boxes. Splits cut "
                         f"at the sample's median, so leaves land between half this and this.")
    ap.add_argument("--sample-percent", type=float, default=None,
                    help=f"Percent of planet nodes to sample (default {DEFAULT_SAMPLE_PERCENT}). If "
                         f"{SAMPLE_FEATHER_NAME} already exists and this is not given, the cached "
                         f"sample's own percentage is reused; passing a different value explicitly "
                         f"forces a fresh scan of the planet.")
    ap.add_argument("--sample-capacity", type=int, default=DEFAULT_SAMPLE_CAPACITY,
                    help=f"Firm upper bound on sampled nodes, preallocated once as a fixed-size "
                         f"buffer (default {DEFAULT_SAMPLE_CAPACITY:,} = "
                         f"~{DEFAULT_SAMPLE_CAPACITY * 8 / 1e9:.1f} GB). Lower it for test runs.")
    ap.add_argument("--steps", default="1,2,3,4",
                    help="Comma-separated steps to run (default 1,2,3,4). Completed steps are "
                         "skipped unless --force.")
    ap.add_argument("--force", action="store_true",
                    help="Re-run the selected steps even if state.json marks them complete.")
    ap.add_argument("--only", help="Step 3 only: restrict to these Geofabrik region ids "
                                   "(comma-separated), e.g. monaco,alsace")
    ap.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                    help=f"Max parallel workers for steps 3 and 4 (default {DEFAULT_WORKERS}). An "
                         f"upper bound, not a target: step 3 also honors --memory-budget-gb.")
    ap.add_argument("--resample", action="store_true",
                    help=f"Re-scan the planet even if {SAMPLE_FEATHER_NAME} exists, replacing it. "
                         f"Only needed when the planet file itself has changed — changing "
                         f"--threshold reuses the cached sample automatically.")
    ap.add_argument("--no-sample-cache", action="store_true",
                    help=f"Neither read nor write {SAMPLE_FEATHER_NAME}.")
    ap.add_argument("--memory-budget-gb", type=float, default=DEFAULT_MEMORY_BUDGET_GB,
                    help=f"Rough cap (GB, default {DEFAULT_MEMORY_BUDGET_GB:.0f}) on total "
                         "estimated peak memory summed across "
                         "every currently running step-3 worker — not per-worker. Each region's "
                         "estimate is derived from its pbf size (calibrated so a 12 GB pbf ~= 25 GB "
                         "peak), so a large region is held back until enough budget frees up.")
    ap.add_argument("--out-dir", default=OUT_DIR, help=f"Output directory (default {OUT_DIR}).")
    ap.add_argument("--scratch-dir", default=DEFAULT_SCRATCH_DIR,
                    help=f"Disk buffer for osmium's node-location index on large regions "
                         f"(default {DEFAULT_SCRATCH_DIR}).")
    ap.add_argument("--keep-pbf", action="store_true",
                    help="Don't delete each region's .osm.pbf after importing it.")
    args = ap.parse_args()

    steps = {int(s.strip()) for s in args.steps.split(",") if s.strip()}
    os.makedirs(args.out_dir, exist_ok=True)
    state = State(os.path.join(args.out_dir, STATE_NAME))
    only = {s.strip() for s in args.only.split(",")} if args.only else set()

    if 1 in steps:
        if state.data.get("step1_done") and not args.force:
            log("step1", "already done, skipping (use --force to redo)")
        else:
            have_cache = (not args.resample and not args.no_sample_cache
                          and os.path.exists(os.path.join(args.out_dir, SAMPLE_FEATHER_NAME)))
            if not args.planet_pbf and not have_cache:
                ap.error("--planet-pbf is required for step 1 (no cached sample to fall back on)")
            if args.planet_pbf and not os.path.exists(args.planet_pbf):
                ap.error(f"planet pbf not found: {args.planet_pbf}")
            # Step 1 recreates every box db from scratch, so any partial step-3/4 progress against
            # the old partition is meaningless — clear it rather than let stale region markers
            # silently skip work that now needs redoing.
            state.regions_done.clear()
            state.boxes_done.clear()
            step1_build_boxes(args.planet_pbf, args.threshold, args.sample_percent,
                              args.sample_capacity, args.out_dir, resample=args.resample,
                              use_sample_cache=not args.no_sample_cache)
            state.data["step1_done"] = True
            state.save()

    if 2 in steps:
        if state.data.get("step2_done") and not args.force:
            log("step2", "already done, skipping (use --force to redo)")
        else:
            step2_leaf_regions(args.out_dir)
            state.data["step2_done"] = True
            state.save()

    if 3 in steps:
        budget = int(max(1.0, args.memory_budget_gb) * (1024 ** 3))
        step3_import_regions(args.out_dir, args.scratch_dir, state, max(1, args.workers),
                             budget, args.keep_pbf, only)

    if 4 in steps:
        if args.force:
            state.boxes_done.clear()
        step4_finalize(args.out_dir, state, max(1, args.workers))

    print(f"Done. Output: {args.out_dir}", flush=True)


if __name__ == "__main__":
    main()
