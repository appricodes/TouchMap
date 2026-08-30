#!/usr/bin/env python3

import json
import math
import os
import sqlite3
import sys
import time
import zlib

import msgpack
import osmium as osm
import requests

EARTH_RADIUS = 6378137

name_urls= {
	# Europe
	#"Europe": "https://download.geofabrik.de/europe-latest.osm.pbf",
	"albania": "http://download.geofabrik.de/europe/albania-latest.osm.pbf",
	"andorra": "http://download.geofabrik.de/europe/andorra-latest.osm.pbf",
	"austria": "http://download.geofabrik.de/europe/austria-latest.osm.pbf",
	"azores": "http://download.geofabrik.de/europe/azores-latest.osm.pbf",
	"belarus": "http://download.geofabrik.de/europe/belarus-latest.osm.pbf",
	"belgium": "http://download.geofabrik.de/europe/belgium-latest.osm.pbf",
	"bosnia-herzegovina": "http://download.geofabrik.de/europe/bosnia-herzegovina-latest.osm.pbf",
	"bulgaria": "http://download.geofabrik.de/europe/bulgaria-latest.osm.pbf",
	"croatia": "http://download.geofabrik.de/europe/croatia-latest.osm.pbf",
	"cyprus": "http://download.geofabrik.de/europe/cyprus-latest.osm.pbf",
	"czech-republic": "http://download.geofabrik.de/europe/czech-republic-latest.osm.pbf",
	"denmark": "http://download.geofabrik.de/europe/denmark-latest.osm.pbf",
	"estonia": "http://download.geofabrik.de/europe/estonia-latest.osm.pbf",
	"faroe-islands": "http://download.geofabrik.de/europe/faroe-islands-latest.osm.pbf",
	"finland": "http://download.geofabrik.de/europe/finland-latest.osm.pbf",
	"france": "http://download.geofabrik.de/europe/france-latest.osm.pbf",
	"georgia": "http://download.geofabrik.de/europe/georgia-latest.osm.pbf",
	"germany": "http://download.geofabrik.de/europe/germany-latest.osm.pbf",
	"greece": "http://download.geofabrik.de/europe/greece-latest.osm.pbf",
	"guernsey-and-jersey": "https://download.geofabrik.de/europe/guernsey-jersey-latest.osm.pbf",
	"hungary": "http://download.geofabrik.de/europe/hungary-latest.osm.pbf",
	"iceland": "http://download.geofabrik.de/europe/iceland-latest.osm.pbf",
	"ireland-and-northern-ireland": "http://download.geofabrik.de/europe/ireland-and-northern-ireland-latest.osm.pbf",
	"isle-of-man": "http://download.geofabrik.de/europe/isle-of-man-latest.osm.pbf",
	"italy": "http://download.geofabrik.de/europe/italy-latest.osm.pbf",
	"kosovo": "http://download.geofabrik.de/europe/kosovo-latest.osm.pbf",
	"latvia": "http://download.geofabrik.de/europe/latvia-latest.osm.pbf",
	"liechtenstein": "http://download.geofabrik.de/europe/liechtenstein-latest.osm.pbf",
	"lithuania": "http://download.geofabrik.de/europe/lithuania-latest.osm.pbf",
	"luxembourg": "http://download.geofabrik.de/europe/luxembourg-latest.osm.pbf",
	"macedonia": "http://download.geofabrik.de/europe/macedonia-latest.osm.pbf",
	"malta": "http://download.geofabrik.de/europe/malta-latest.osm.pbf",
	"moldova": "http://download.geofabrik.de/europe/moldova-latest.osm.pbf",
	"monaco": "http://download.geofabrik.de/europe/monaco-latest.osm.pbf",
	"montenegro": "http://download.geofabrik.de/europe/montenegro-latest.osm.pbf",
	"netherlands": "http://download.geofabrik.de/europe/netherlands-latest.osm.pbf",
	"norway": "http://download.geofabrik.de/europe/norway-latest.osm.pbf",
	"poland": "http://download.geofabrik.de/europe/poland-latest.osm.pbf",
	"portugal": "http://download.geofabrik.de/europe/portugal-latest.osm.pbf",
	"romania": "http://download.geofabrik.de/europe/romania-latest.osm.pbf",
	"russian-federation": "https://download.geofabrik.de/russia-latest.osm.pbf",
	"serbia": "http://download.geofabrik.de/europe/serbia-latest.osm.pbf",
	"slovakia": "http://download.geofabrik.de/europe/slovakia-latest.osm.pbf",
	"slovenia": "http://download.geofabrik.de/europe/slovenia-latest.osm.pbf",
	"spain": "http://download.geofabrik.de/europe/spain-latest.osm.pbf",
	"sweden": "http://download.geofabrik.de/europe/sweden-latest.osm.pbf",
	"switzerland": "http://download.geofabrik.de/europe/switzerland-latest.osm.pbf",
	"turkey": "http://download.geofabrik.de/europe/turkey-latest.osm.pbf",
	"ukraine-with-crimea": "http://download.geofabrik.de/europe/ukraine-latest.osm.pbf",
	"united-kingdom": "http://download.geofabrik.de/europe/great-britain-latest.osm.pbf",

	"Africa": "https://download.geofabrik.de/africa-latest.osm.pbf",
	"Antarctica": "https://download.geofabrik.de/antarctica-latest.osm.pbf",
	"Asia": "https://download.geofabrik.de/asia-latest.osm.pbf",
	"Australia-Oceania": "https://download.geofabrik.de/australia-oceania-latest.osm.pbf",
	"Central America": "https://download.geofabrik.de/central-america-latest.osm.pbf",
	"South America": "https://download.geofabrik.de/south-america-latest.osm.pbf",
	#North America
	#"North America": "https://download.geofabrik.de/north-america-latest.osm.pbf",
	"canada": "http://download.geofabrik.de/north-america/canada-latest.osm.pbf",
	"greenland": "http://download.geofabrik.de/north-america/greenland-latest.osm.pbf",
	"mexico": "http://download.geofabrik.de/north-america/mexico-latest.osm.pbf",
	"united-states-of-america": "http://download.geofabrik.de/north-america/us-latest.osm.pbf"
}

# ------------------------------
# Hard-coded configuration
# ------------------------------

tile_size_meters = 1000
#OUTPUT_DIR = "f:/osm/output"
OUTPUT_DIR = "c:/files/osm/output"

PROGRESS_EVERY = 100000
COLLECT_STATS = False
FILTER_TAGS = True

# ------------------------------
# Tag Filtering for Navigation
# ------------------------------
IMPORTANT_NODE_TAGS = {
	"emergency": 5, "healthcare": 5,
	"highway": 10, "railway": 10, "public_transport": 10,
	"barrier": 15, "shop": 20, "amenity": 25,
	"leisure": 30, "tourism": 35, "historic": 35,
	"natural": 50, "waterway": 50,
	"name": 255,
}

IMPORTANT_HIGHWAY_VALUES = {
	"motorway",  # Controlled-access major road (like freeways/autobahns)
	"motorway_link",  # Ramps or connectors to/from motorways
	"trunk",  # High-capacity non-motorway arterial road
	"trunk_link",  # Ramps connecting to trunk roads
	"primary",  # Major roads linking large towns/cities
	"primary_link",  # Ramps connecting to primary roads
	"secondary",  # Roads connecting smaller towns or areas
	"secondary_link",  # Ramps to/from secondary roads
	"tertiary",  # Local connector roads between villages or within towns
	"tertiary_link",  # Ramps connecting to tertiary roads
	"unclassified",  # Minor public roads, typically rural/local
	"residential",  # Roads in residential areas
	"service",  # Access/service roads, driveways, parking aisles
	"living_street",  # Shared spaces with pedestrian priority
	"road",  # Generic or unknown road type; placeholder until classified
	
	# Pedestrian and non-motorized paths
	"pedestrian",  # Pedestrianized streets, e.g., plazas or shared zones
	"footway",  # Dedicated footpaths (walkways), include walking tracks
	"path",  # Generic path for walking, cycling, animals — undefined usage
	"cycleway",  # Dedicated bicycle path
	"steps",  # Flights of stairs on footway
	"corridor",  # Indoor hallway or corridor inside buildings
	"via_ferrata",  # Climbers’ mountain routes with fixed safety equipment
	
	# Special or uncommon road types
	"busway",  # Dedicated bus rapid transit roadway
}

IMPORTANT_TAG_KEYS = {
	# Path properties
	"highway", "footway", "sidewalk",
	# "surface",
	"width", "incline",
	"indoor", "conveying", "ramp", "handrail", "step_count",
	
	# Crossing and curb details
	"crossing", "tactile_paving",
	"sloped_curb", "crossing:island",
	
	# Barriers and obstacles
	"barrier",
	
	# Navigation aids and landmarks
	"name", "amenity", "public_transport", "railway",
	"man_made", "traffic_signals", "traffic_signals:sound",
	"traffic_signals:vibration", "tourism",
	"information", "door", "elevator", "wheelchair",
	"toilet", "toilets:access", "toilets:unisex", "toilets:wheelchair",
}

stats = {}


# ------------------------------ utils
def download_files(files: dict, base_dir: str):
	"""
	Downloads files from URLs and saves them with given names.

	Parameters:
		files (dict): mapping of filename -> url
		base_dir (str): directory to save files

	Downloads a file only if:
		- it does not exist locally, OR
		- its size differs from the remote file
	"""

	if not os.path.exists(base_dir):
		os.makedirs(base_dir)

	for name, url in files.items():
		dest = os.path.join(base_dir, name + ".pbf")

		try:
			# Get remote file size
			head = requests.head(url, allow_redirects=True)
			remote_size = int(head.headers.get("Content-Length", -1))
			
			# Check if file exists and has same size
			if os.path.exists(dest):
				local_size = os.path.getsize(dest)
				print(f"size: {remote_size} / {local_size}")
				if remote_size > 0 and local_size == remote_size:
					print(f"✅ Skipping {name}, already downloaded ({local_size/1e6:.2f} MB)")
					continue

			# Download file
			with requests.get(url, stream=True) as r:
				r.raise_for_status()
				total_size = int(r.headers.get("Content-Length", 0))
				downloaded = 0
				start_time = time.time()

				with open(dest, "wb") as f:
					for chunk in r.iter_content(chunk_size=8192):
						if chunk:
							f.write(chunk)
							downloaded += len(chunk)

							# Progress info
							elapsed = time.time() - start_time
							speed = (downloaded / 1e6) / elapsed if elapsed > 0 else 0
							percent = (downloaded / total_size * 100) if total_size else 0
							print(f"\r⬇️  {name}: {downloaded/1e6:.2f}/{total_size/1e6:.2f} MB "
								  f"({percent:.1f}%) {speed:.2f} MB/s", end="")

				print("\n✅ Download complete:", name)
				if total_size< 100000:
					print("File is too small")
					exit();

		except Exception as e:
			print(f"\n❌ Error downloading {name}: {e}")
def convert_coordinate_to_index(lat, lon):
	lat_rad = math.radians(lat)
	lon_rad = math.radians(lon)
	y_index = int(round(EARTH_RADIUS * lat_rad / tile_size_meters))
	# tile width in radians
	tile_width_rad = tile_size_meters / EARTH_RADIUS / math.cos(lat_rad)
	x_index = int(round(lon_rad / tile_width_rad))
	return x_index, y_index


def get_relative_coordinate(lat, lon, x_index, y_index):
	lat_rad = math.radians(lat)
	lon_rad = math.radians(lon)
	y_rel = int(round(EARTH_RADIUS * lat_rad - y_index * tile_size_meters))
	x_rel = int(round(EARTH_RADIUS * math.cos(lat_rad) * lon_rad - x_index * tile_size_meters))
	return x_rel, y_rel


def add_to_stats(text: str):
	stats[text] = stats.get(text, 0) + 1


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


def filter_navigation_tags(tags: dict, exclude=None) -> dict:
	# Tight loop (no side-effect dict comp)
	if exclude:
		ex = exclude
	else:
		ex = ()
	keep = IMPORTANT_TAG_KEYS
	out = {}
	for k, v in tags.items():
		if (FILTER_TAGS and k in keep and k not in ex) or (not FILTER_TAGS and k not in ex and not k.startswith("addr")):
			out[k] = v
			
			if COLLECT_STATS and k != "highway" and k != "name" :
				add_to_stats(k)
				add_to_stats(v)
	return out


def build_name_and_cat(tags: dict) -> tuple[int, str, str]:
	if "addr:housenumber" in tags or "addr:street" in tags or "addr:place" in tags:
		# order kept compact; join avoids temporaries
		fields = (
			tags.get("addr:housenumber", ""),
			tags.get("addr:street", tags.get("addr:place", "")),
			tags.get("addr:city", ""),
			tags.get("addr:country", ""),
		)
		return 0, "address", "|".join(fields).rstrip("|")
	
	name = tags.get("name", "")
	for tag, cat_id in IMPORTANT_NODE_TAGS.items():
		if tag == "name":
			continue
		if tag in tags:
			return cat_id, tags.get(tag), name
	return -255, "", ""


# ------------------------------ Cache DB (fast)
class CacheDB:
	def __init__(self, db_file: str):
		self.conn = sqlite3.connect(db_file)
		self.cur = self.conn.cursor()
		
		# Speed PRAGMAs (tune to your durability needs)
		self.cur.executescript("""
			PRAGMA journal_mode=WAL;
			PRAGMA synchronous=OFF;         -- set to NORMAL if you want more safety
			PRAGMA temp_store=MEMORY;
			PRAGMA mmap_size=1073741824;     -- 1GB
			PRAGMA cache_size=-2097152;     -- 2GB cache in KiB (negative means KB)
			PRAGMA page_size=4096;
		""")
		
		self.cur.execute("""
			CREATE TABLE IF NOT EXISTS cache (
				id      INTEGER PRIMARY KEY AUTOINCREMENT,
				x_index INTEGER NOT NULL,
				y_index INTEGER NOT NULL,
				data    BLOB NOT NULL
			)
		""")
		self.cur.execute("CREATE INDEX IF NOT EXISTS idx_cache_xy ON cache(x_index, y_index)")
		self.conn.commit()
		
		self.insert_count = 0
		self._insert_stmt = self.cur.execute  # local binding for speed
	
	def insert(self, x_index: int, y_index: int, payload_bytes: bytes):
		# payload is already compressed bytes
		self._insert_stmt(
			"INSERT INTO cache (x_index, y_index, data) VALUES (?, ?, ?)",
			(x_index, y_index, payload_bytes),
		)
		self.insert_count += 1
		if self.insert_count >= 1000000:
			self.conn.commit()
			self.insert_count = 0
	
	def fetch(self, x_index: int, y_index: int):
		self.cur.execute(
			"SELECT data FROM cache WHERE x_index = ? AND y_index = ?",
			(x_index, y_index),
		)
		rows = self.cur.fetchall()
		# Return decompressed msgpack objects
		out = []
		app = out.append
		for (blob,) in rows:
			app(msgpack.unpackb(blob, raw=False))
		return out
	
	def get_all_unique_tiles(self):
		"""Return a list of all unique (x_index, y_index) pairs stored in DB."""
		self.cur.execute("SELECT DISTINCT x_index, y_index FROM cache")
		return self.cur.fetchall()  # list of tuples [(x1, y1), (x2, y2), ...]
	
	def close(self):
		if self.insert_count > 0:
			self.conn.commit()
		self.conn.close()


# ------------------------------ Osmium handler
class NodeWaySink(osm.SimpleHandler):
	"""Osmium handler to process nodes and ways from a PBF file."""
	
	__slots__ = ("seen_nodes", "inserted_nodes", "seen_ways", "inserted_ways",
				 "start_time")
	
	def __init__(self):
		super().__init__()
		mtr = tile_size_meters
		
		self.seen_nodes = 0
		self.inserted_nodes = 0
		self.seen_ways = 0
		self.inserted_ways = 0
		self.start_time = None
	
	def node(self, n):
		self.seen_nodes += 1
		if (self.seen_nodes % (PROGRESS_EVERY * 10)) == 0:
			print(f"\rProcessed nodes: {self.seen_nodes},  Inserted: {self.inserted_nodes}   ", end="")
		
		if not n.tags or not n.location.valid():
			return
		
		lat = n.location.lat
		lon = n.location.lon
		
		# build tags dict once
		tags_dict = {t.k: t.v for t in n.tags}
		self.process_node(n.id, lat, lon, tags_dict)

	
	def process_node(self, id, lat, lon, tags_dict):
		
		x_index, y_index = convert_coordinate_to_index(lat, lon)
		x_rel, y_rel = get_relative_coordinate(lat, lon, x_index, y_index)
		
		cat_id, typ, node_name = build_name_and_cat(tags_dict)
		if cat_id == -255:
			return
		
		filtered_tags = filter_navigation_tags(tags_dict, exclude=IMPORTANT_NODE_TAGS)
		if COLLECT_STATS:
			add_to_stats(typ)
		
		# msgpack (faster & smaller than JSON per row)
		payload = {
			"t": "n",  # node
			"id": id,
			"x": x_rel,
			"y": y_rel,
			"cat_id": cat_id,
			"type": typ,
			"name": node_name,
			"tags": filtered_tags
		}
		packed = msgpack.packb(payload, use_bin_type=True)
		cache_db.insert(x_index, y_index, packed)
		self.inserted_nodes += 1
	
	def way(self, w):
		self.seen_ways += 1
		if (self.seen_ways % PROGRESS_EVERY) == 0:
			print(f"\rProcessed ways: {self.seen_ways}, Inserted: {self.inserted_ways}   ", end="")
		
		if not w.nodes or len(w.nodes) < 2:
			return
		
		tags_dict = {t.k: t.v for t in w.tags}
		typ = get_way_type(tags_dict)
		if not typ:
			return
		
		filtered_tags = filter_navigation_tags(tags_dict)
		
		if not filtered_tags and typ != "address":
			return
		
		# name & address handling
		if typ == "address":
			_, _, way_name = build_name_and_cat(tags_dict)
			if way_name == "":
				return
		else:
			way_name = filtered_tags.get("name", "")
		
		if COLLECT_STATS:
			add_to_stats(typ)
		filtered_tags.pop("highway", None)
		filtered_tags.pop("name", None)
		
		# Collect tiles crossed: one-pass first/last indices
		tile_bounds = {}  # tile_name -> [first_i, last_i, x_index, y_index]
		# Also keep lats/lons to avoid re-accessing NodeRef
		lats = []
		lons = []
		append_lat = lats.append
		append_lon = lons.append
		
		for i, nref in enumerate(w.nodes):
			if not nref.location.valid():
				return
			lat = nref.location.lat
			lon = nref.location.lon
			append_lat(lat)
			append_lon(lon)
			
			xi, yi = convert_coordinate_to_index(lat, lon)
			tname = f"t_{xi}-{yi}"
			rec = tile_bounds.get(tname)
			if rec is None:
				tile_bounds[tname] = [i, i, xi, yi]
			else:
				rec[1] = i  # update last index
		
		if not tile_bounds:
			return
		
		for rec in tile_bounds.values():
			first, last, xi, yi = rec
			if first > 0:
				first -= 1
			if last < len(lats) - 1:
				last += 1
			
			seg_x = []
			seg_y = []
			ax = seg_x.append
			ay = seg_y.append
			for i in range(first, last + 1):
				x, y = get_relative_coordinate(lats[i], lons[i], xi, yi)
				ax(x)
				ay(y)
			
			if typ == "address":
				cat_id = 0
			elif typ.startswith("barrier:"):
				cat_id = 15
			else:
				cat_id = 60
			
			segment = {
				"t": "w",  # way
				"id": w.id,
				"cat_id": cat_id,
				"type": typ,
				"name": way_name,
				"tags": filtered_tags,
				"x": seg_x,
				"y": seg_y
			}
			packed = msgpack.packb(segment, use_bin_type=True)
			cache_db.insert(xi, yi, packed)
		
		self.inserted_ways += 1


# ------------------------------ main
def create_cache(base_dir):
	global cache_db
	os.makedirs(OUTPUT_DIR, exist_ok=True)
	
	# cache DB
	db_file = OUTPUT_DIR + "/cache.db"
	if os.path.exists(db_file):
		os.remove(db_file)
	cache_db = CacheDB(db_file)
	
	
	for name in name_urls:
		print(f"\nProcessing {name}...")
		file_path = os.path.join(base_dir, name + ".pbf")
		
		handler = NodeWaySink()
		handler.apply_file(file_path , locations=True)
	
	cache_db.close()
	
	# write stats to file
	if COLLECT_STATS:
		with open(OUTPUT_DIR+ "/stats.json.txt", "w") as f:
			json.dump(stats, f, indent=1)
	
	print()
	
def create_map():
	# cache DB
	cache_db = CacheDB(OUTPUT_DIR + "/cache.db")
	
	# map.db creation
	db_file_name = OUTPUT_DIR + "/map.db"
	if os.path.exists(db_file_name):
		os.remove(db_file_name)
	
	conn = sqlite3.connect(db_file_name)
	cursor = conn.cursor()
	cursor.execute("""
		CREATE TABLE IF NOT EXISTS map_tiles (
			x_index INTEGER NOT NULL,
			y_index INTEGER NOT NULL,
			data BLOB NOT NULL,
			PRIMARY KEY (x_index, y_index)
		);
	""")
	conn.commit()
	
	print("Table 'map_tiles' is ready.")
	
	json_dir = OUTPUT_DIR + "/json/"
	os.makedirs(json_dir, exist_ok=True)
	
	db_inserts = 0
	
	tile_ids = cache_db.get_all_unique_tiles()
	i = 1
	for tile_id in tile_ids:
		print(f"\rProcessing tile {i} of {len(tile_ids)} ({round(i / len(tile_ids) * 1000) / 10}%)", end="")
		i += 1
		
		x_index = tile_id[0]
		y_index = tile_id[1]
		
		xs = []
		ys = []
		ids = []
		cat_ids = []
		types = []
		names = []
		ns = []
		tags = []
		
		for row in cache_db.fetch(x_index, y_index):
			cat_ids.append(row["cat_id"])
			types.append(row["type"])
			names.append(row["name"])
			tags.append(msgpack.packb(row["tags"], use_bin_type=True))
			
			# reserve ids between -1 billion and 1 billion for manual nodes
			id = row["id"]
			id = (abs(id) + 1000000000) * (1 if id >= 0 else -1)
			if row["t"] == "n":
				# node
				ids.append(id)
				xs.append(row["x"])
				ys.append(row["y"])
				ns.append(1)
			else:
				# way
				ids.append(-id)
				xs.extend(row["x"])
				ys.extend(row["y"])
				ns.append(len(row["x"]))
		
		# FIND INTERSECTIONS
		excluded_types = ["address", "cycleway", "footway", "pedestrian"]
		coord_to_indices = {}
		pos = 0
		for idx, n in enumerate(ns):
			if types[idx] not in excluded_types:
				for j in range(n):
					key = (xs[pos + j], ys[pos + j])
					coord_to_indices.setdefault(key, set()).add(idx)
			pos += n
		
		intersections = []
		for (x, y), element_indices in coord_to_indices.items():
			if len(element_indices) > 1:
				# collect unique names
				unique_names = set(names[i] for i in element_indices if names[i])
				if len(unique_names) > 1:
					# consistent ordering
					name = "|".join(sorted(unique_names))
					intersections.append((x, y, name))
		
		# append intersections
		for inter in intersections:
			x, y, name = inter
			ids.append(-2)  # synthetic negative id
			cat_ids.append(62)  # cat_id for intersections
			types.append("intersection")
			names.append(name)
			tags.append(msgpack.packb({}, use_bin_type=True))
			ns.append(1)
			xs.append(x)
			ys.append(y)
		
		tile = {
			"ids": ids,
			"cat_ids": cat_ids,
			"types": types,
			"names": names,
			"tags": tags,
			"n": ns,
			"xs": xs,
			"ys": ys
		}
		
		# write the tile in db
		data = msgpack.packb(tile, use_bin_type=True)
		compressed = zlib.compress(data, 9)
		
		insert_query = "INSERT OR IGNORE INTO map_tiles (x_index, y_index, data) VALUES (?, ?, ?);"
		cursor.execute(insert_query, (x_index, y_index, compressed))
		db_inserts += 1
		if db_inserts == 10000:
			conn.commit()
			db_inserts = 0
		# temp: save json
		if False:
			tile_name = f"t{x_index}-{y_index}"
			with open(json_dir + "/" + tile_name + ".json.txt", "w") as f:
				json.dump(tile, f, indent=1)
	
	if db_inserts > 0:
		conn.commit()
	conn.close()
	print()
	
	
if __name__ == "__main__":
	#download_files(name_urls, "f:/osm/downloads")
	t0 = time.time()
	#create_cache("f:/osm/downloads")
	print(f"cache database created in {time.time() - t0} seconds")
	
	t0 = time.time()
	create_map()
	print(f"Map database created in {time.time() - t0} seconds")
	