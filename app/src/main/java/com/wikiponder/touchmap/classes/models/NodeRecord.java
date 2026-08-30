package com.wikiponder.touchmap.classes.models;

/**
 * An in-memory point feature loaded from the normalized {@code nodes} table for the current
 * viewport. Absolute lat/lon — no tile-relative coordinates. Tags are kept packed and only unpacked
 * (into a {@link MapElement}) for the element actually selected/announced.
 */
public class NodeRecord {
	public final long id;
	public final double lat;
	public final double lon;
	public final int catId;
	public final String type;
	public final String name;
	public final byte[] tags;
	public final boolean favorite;

	public NodeRecord(long id, double lat, double lon, int catId, String type, String name,
					  byte[] tags, boolean favorite) {
		this.id = id;
		this.lat = lat;
		this.lon = lon;
		this.catId = catId;
		this.type = type;
		this.name = name;
		this.tags = tags;
		this.favorite = favorite;
	}
}
