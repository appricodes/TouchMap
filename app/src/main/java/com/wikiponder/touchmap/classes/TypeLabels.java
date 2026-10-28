package com.wikiponder.touchmap.classes;

import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * What gets spoken for an unnamed element: the raw OSM value ("service", "platform", "switch",
 * "yes") is often meaningless on its own, so the ones that are ambiguous or cryptic are mapped to a
 * plain phrase here. Looked up by category first (the same value means different things for a road
 * and for a point, e.g. "platform"), then by value alone; anything unmapped just gets its
 * underscores turned into spaces. The types are the ones present in the server-built map boxes
 * (see tools/build_global_cluster_dbs.py), not an exhaustive OSM list.
 */
public final class TypeLabels {
	private TypeLabels() {
	}

	/** Roads, paths and other highway/linear ways (category 60). */
	private static final Map<String, String> WAY = new HashMap<>();
	/** Point features whose bare value is cryptic or ambiguous, keyed by "catId:value". */
	private static final Map<String, String> BY_CATEGORY = new HashMap<>();
	/** Same, for values that mean the same thing in any category. */
	private static final Map<String, String> ANY = new HashMap<>();

	private static void way(String v, String label) { WAY.put(v, label); }
	private static void node(int cat, String v, String label) { BY_CATEGORY.put(cat + ":" + v, label); }
	private static void any(String v, String label) { ANY.put(v, label); }

	static {
		way("service", "service road");
		way("residential", "residential street");
		way("footway", "footpath");
		way("path", "path");
		way("steps", "steps");
		way("cycleway", "cycle path");
		way("pedestrian", "pedestrian street");
		way("living_street", "living street");
		way("unclassified", "minor road");
		way("road", "road of unknown type");
		way("track", "farm or forest track");
		way("bridleway", "bridle path");
		way("busway", "bus-only road");
		way("bus_guideway", "guided bus road");
		way("corridor", "indoor corridor");
		way("platform", "transit platform");
		way("construction", "road under construction");
		way("proposed", "planned road");
		way("raceway", "race track");
		way("rest_area", "rest area");
		way("services", "service area");
		way("elevator", "elevator");
		way("primary", "primary road");
		way("secondary", "secondary road");
		way("tertiary", "tertiary road");
		way("trunk", "trunk road");
		way("motorway", "motorway");
		way("primary_link", "primary road ramp");
		way("secondary_link", "secondary road ramp");
		way("tertiary_link", "tertiary road ramp");
		way("trunk_link", "trunk road ramp");
		way("motorway_link", "motorway ramp");

		// Traffic and rail points
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "crossing", "pedestrian crossing");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "switch", "railway switch");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "signal", "railway signal");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "stop", "stop sign");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "station", "railway station");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "halt", "railway halt");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "platform", "transit platform");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "phone", "public phone");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "give_way", "give way sign");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "traffic_signals", "traffic lights");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "turning_circle", "road turnaround circle");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "turning_loop", "road turnaround loop");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "motorway_junction", "motorway exit");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "stop_position", "transit stop position");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "buffer_stop", "railway buffer stop");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "derail", "railway derailer");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "elevator", "elevator");
		node(OSMDatabaseHelper.CATEGORY_TRAFFIC, "milestone", "milestone marker");
		// Health and emergency
		node(OSMDatabaseHelper.CATEGORY_HEALTH, "phone", "emergency phone");
		node(OSMDatabaseHelper.CATEGORY_HEALTH, "access_point", "emergency access point");
		node(OSMDatabaseHelper.CATEGORY_HEALTH, "suction_point", "fire water suction point");
		node(OSMDatabaseHelper.CATEGORY_HEALTH, "siren", "emergency siren");
		node(OSMDatabaseHelper.CATEGORY_HEALTH, "doctor", "doctor's office");
		node(OSMDatabaseHelper.CATEGORY_HEALTH, "yes", "health facility");
		// Barriers
		node(OSMDatabaseHelper.CATEGORY_BARRIER, "block", "block barrier");
		node(OSMDatabaseHelper.CATEGORY_BARRIER, "entrance", "building entrance");
		node(OSMDatabaseHelper.CATEGORY_BARRIER, "kerb", "kerb");
		node(OSMDatabaseHelper.CATEGORY_BARRIER, "height_restrictor", "height restriction barrier");
		node(OSMDatabaseHelper.CATEGORY_BARRIER, "yes", "barrier");
		// Amenities
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "waste_basket", "waste bin");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "waste_disposal", "waste disposal container");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "recycling", "recycling point");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "parking", "parking lot");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "parking_entrance", "parking garage entrance");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "parking_space", "parking space");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "charging_station", "electric vehicle charging station");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "fast_food", "fast food restaurant");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "taxi", "taxi stand");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "ticket_validator", "ticket validator machine");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "bbq", "barbecue");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "telephone", "public telephone");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "shelter", "shelter");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "compressed_air", "compressed air station");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "hunting_stand", "hunting stand");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "weighbridge", "vehicle weighing scale");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "clock", "public clock");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "social_facility", "social services facility");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "community_centre", "community center");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "post_box", "mailbox");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "pharmacy", "pharmacy");
		node(OSMDatabaseHelper.CATEGORY_AMENITY, "yes", "amenity");
		// Shops
		node(OSMDatabaseHelper.CATEGORY_SHOP, "vacant", "vacant shop");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "yes", "shop");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "bicycle", "bicycle shop");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "car", "car dealership");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "beauty", "beauty salon");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "massage", "massage shop");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "deli", "delicatessen");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "kiosk", "kiosk");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "clothes", "clothing shop");
		node(OSMDatabaseHelper.CATEGORY_SHOP, "hardware", "hardware store");
		// Leisure
		node(OSMDatabaseHelper.CATEGORY_LEISURE, "pitch", "sports pitch");
		node(OSMDatabaseHelper.CATEGORY_LEISURE, "slipway", "boat ramp");
		node(OSMDatabaseHelper.CATEGORY_LEISURE, "outdoor_seating", "outdoor seating area");
		node(OSMDatabaseHelper.CATEGORY_LEISURE, "marina", "boat marina");
		node(OSMDatabaseHelper.CATEGORY_LEISURE, "fitness_station", "outdoor fitness equipment");
		// Tourism and historic
		node(OSMDatabaseHelper.CATEGORY_TOURISM, "information", "tourist information");
		node(OSMDatabaseHelper.CATEGORY_TOURISM, "railway", "historic railway site");
		node(OSMDatabaseHelper.CATEGORY_TOURISM, "yes", "tourist site");
		node(OSMDatabaseHelper.CATEGORY_TOURISM, "boundary_stone", "boundary marker stone");
		node(OSMDatabaseHelper.CATEGORY_TOURISM, "picnic_site", "picnic area");
		node(OSMDatabaseHelper.CATEGORY_TOURISM, "attraction", "tourist attraction");
		// Natural
		node(OSMDatabaseHelper.CATEGORY_NATURAL, "stone", "large rock");
		node(OSMDatabaseHelper.CATEGORY_NATURAL, "cape", "headland");

		any("yes", "unspecified feature");
		any("tree", "tree");
		any("street_lamp", "street lamp");
		any("switch", "railway switch");
		any("signal", "railway signal");
		any("level_crossing", "railway level crossing");
		any("tram_level_crossing", "tram level crossing");
		any("tram_crossing", "tram crossing");
		any("railway_crossing", "railway crossing");
		any("mini_roundabout", "mini roundabout");
		any("emergency_access_point", "emergency access point");
		any("cycle_barrier", "cycle barrier");
		any("lift_gate", "lift gate");
		any("swing_gate", "swing gate");
		any("fire_hydrant", "fire hydrant");
		any("defibrillator", "defibrillator");
		any("subway_entrance", "subway entrance");
	}

	/** Spoken label for an unnamed element of this category and type. */
	public static String label(int catId, String type) {
		if (type == null || type.isEmpty()) return "";
		if (catId == OSMDatabaseHelper.CATEGORY_WAY) {
			String w = WAY.get(type);
			if (w != null) return w;
		}
		// Barrier ways are stored as "barrier: fence" etc.
		if (type.startsWith("barrier: ")) {
			String kind = type.substring("barrier: ".length());
			if (kind.equals("yes") || kind.equals("no")) return "barrier";
			return kind.replace('_', ' ');
		}
		String c = BY_CATEGORY.get(catId + ":" + type);
		if (c != null) return c;
		String a = ANY.get(type);
		if (a != null) return a;
		return type.replace('_', ' ');
	}
}
