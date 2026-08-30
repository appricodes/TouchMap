package com.wikiponder.touchmap.classes.geofabrik;

/**
 * A single downloadable region from the Geofabrik index
 * (https://download.geofabrik.de/index-v1-nogeom.json).
 *
 * The Geofabrik hierarchy is: continent (no parent) -> country -> sub-region (province/state) -> ...
 */
public class GeofabrikRegion {
	/** Stable Geofabrik id, e.g. "germany", "bayern". */
	public final String id;
	/** Human-readable name, e.g. "Germany", "Bayern". */
	public final String name;
	/** Parent region id, or null for continents. */
	public final String parent;
	/** URL of the .osm.pbf download, or null if this region has no pbf. */
	public final String pbfUrl;
	/** ISO 3166-1 alpha-2 country code (upper case), or null. Only set on countries. */
	public final String iso2;

	public GeofabrikRegion(String id, String name, String parent, String pbfUrl, String iso2) {
		this.id = id;
		this.name = name;
		this.parent = parent;
		this.pbfUrl = pbfUrl;
		this.iso2 = iso2;
	}

	@Override
	public String toString() {
		return name;
	}
}
