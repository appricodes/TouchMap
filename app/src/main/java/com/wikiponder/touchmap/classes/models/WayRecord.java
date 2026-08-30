package com.wikiponder.touchmap.classes.models;

/**
 * An in-memory line/area feature loaded from the normalized {@code ways} table for the current
 * viewport. Geometry is the full absolute-coordinate point list (stored once, never clipped), so a
 * long way is a single record. Tags stay packed until the way is actually selected/announced.
 */
public class WayRecord {
	public final long id;
	public final int catId;
	public final String type;
	public final String name;
	public final byte[] tags;
	public final double[] lats;
	public final double[] lons;
	public final double minLat;
	public final double maxLat;
	public final double minLon;
	public final double maxLon;

	public WayRecord(long id, int catId, String type, String name, byte[] tags,
					 double[] lats, double[] lons,
					 double minLat, double maxLat, double minLon, double maxLon) {
		this.id = id;
		this.catId = catId;
		this.type = type;
		this.name = name;
		this.tags = tags;
		this.lats = lats;
		this.lons = lons;
		this.minLat = minLat;
		this.maxLat = maxLat;
		this.minLon = minLon;
		this.maxLon = maxLon;
	}
}
