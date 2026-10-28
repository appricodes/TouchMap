package com.wikiponder.touchmap.classes;

import android.graphics.Point;
import android.util.Log;

import com.wikiponder.touchmap.classes.models.LatLon;

import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Inflater;

public class Utils {

	public static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
	static byte[] toMsgpack(Value v) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var packer = MessagePack.newDefaultPacker(out)) {
			v.writeTo(packer); // write back as MsgPack
		}
		return out.toByteArray();
	}
	
	public static byte[] decompress(byte[] compressedData) throws Exception {
		Inflater inflater = new Inflater();
		inflater.setInput(compressedData);
		
		byte[] buffer = new byte[1024];
		int len;
		try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
			while (!inflater.finished()) {
				len = inflater.inflate(buffer);
				if (len == 0 && inflater.needsInput()) break; // avoid infinite loop
				baos.write(buffer, 0, len);
			}
			return baos.toByteArray();
		}
	}
	public static double getDistanceSquareToLineSegment(double px, double py, double x1, double y1, double x2, double y2, Point nearestPoint) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		
		// If the length of the line is zero (two points are the same)
		if (dx == 0 && dy == 0) {
			dx = px - x1;
			dy = py - y1;
			nearestPoint.x = (int) x1;
			nearestPoint.y = (int) y1;
			return dx * dx + dy * dy;
		}

		// Calculate t (scaller projection)
		double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
		
		if (t < 0) {
			// is the nearest point A
			dx = px - x1;
			dy = py - y1;
			nearestPoint.x = (int) x1;
			nearestPoint.y = (int) y1;
		} else if (t > 1) {
			// is the nearest point B
			dx = px - x2;
			dy = py - y2;
			nearestPoint.x = (int) x2;
			nearestPoint.y = (int) y2;
		} else {
			// nearest point on the fragmented self
			double projX = x1 + t * dx;
			double projY = y1 + t * dy;
			dx = px - projX;
			dy = py - projY;
			nearestPoint.x = (int) projX;
			nearestPoint.y = (int) projY;
		}
		
		return dx * dx + dy * dy;
	}
	
	/**
	 * Returns the distance from point (x, y) to the intersection of:
	 * - a ray starting at (x, y) with direction {@code bearingDeg} (degrees, 0° = north, 90° = east),
	 * - and the line segment (x0, y0)–(x1, y1).
	 *
	 * The ray is one-sided (only forward from (x, y)), and the segment is finite.
	 * If no intersection exists, returns -1.
	 *
	 * @param x          ray origin x
	 * @param y          ray origin y
	 * @param bearingDeg bearing angle in degrees (0° = north, 90° = east)
	 * @param x0         segment start x
	 * @param y0         segment start y
	 * @param x1         segment end x
	 * @param y1         segment end y
	 * @param intersection An output parameter; this Point object will be modified to hold the
	 * rounded integer coordinates of the intersection point.
	 * @return distance to intersection, or -1 if none
	 */
	public static double getIntersectionDistance(
			double x, double y, double bearingDeg,
			double x0, double y0, double x1, double y1,
			Point intersection) {
		
		// Convert bearing to radians
		double bearingRad = Math.toRadians(bearingDeg);
		
		// **CHANGE**: Create a unit vector for a 0°=North, 90°=East system.
		// Math.sin(angle) now corresponds to the x-component (East).
		// Math.cos(angle) now corresponds to the y-component (North).
		double dx = Math.sin(bearingRad);
		double dy = Math.cos(bearingRad);
		
		// Define the line segment as a vector
		double sx = x1 - x0;
		double sy = y1 - y0;
		
		// The determinant of the system of equations for the intersection of the two lines
		double det = sx * dy - sy * dx;
		
		// Check if the lines are parallel (determinant is close to zero)
		if (Math.abs(det) < 1e-9) {
			return -1; // Parallel lines, no unique intersection
		}
		
		// Solve for parameters t (for the ray) and u (for the segment)
		double t = (sx * (y0 - y) - sy * (x0 - x)) / det;
		double u = (dx * (y0 - y) - dy * (x0 - x)) / det;
		
		// An intersection exists only if:
		// 1. t >= 0: The intersection is in the forward direction of the ray.
		// 2. 0 <= u <= 1: The intersection is within the finite line segment.
		if (t >= 0 && u >= 0 && u <= 1) {
			// Calculate the precise intersection point
			double ix = x + t * dx;
			double iy = y + t * dy;
			
			// Update the output parameter, rounding to the nearest integer
			if (intersection != null) {
				intersection.x = (int) Math.round(ix);
				intersection.y = (int) Math.round(iy);
			}
			
			// 't' is the exact distance from the ray's origin to the intersection
			return t;
		}
		
		return -1; // No valid intersection found
	}
	public static int[] latLonToIndex(double lat, double lon) {
		return latLonToIndex(lat, lon, Contract.TILE_SIZE_METERS);
	}
	public static int[] latLonToIndex(double lat, double lon, double cellSizeMeters) {
		double[] indexes = latLonToIndexDouble(lat, lon, cellSizeMeters);
		return new int[] {(int)indexes[0], (int)indexes[1]};
	}
	public static double[] latLonToIndexDouble(double lat, double lon, double cellSizeMeters) {
		double latRad = Math.toRadians(lat);
		double lonRad = Math.toRadians(lon);

		double yIndex = Math.round(Contract.EARTH_RADIUS * latRad / cellSizeMeters);

		// cell width in radians
		double cellWidthRad = cellSizeMeters / Contract.EARTH_RADIUS / Math.cos(latRad);

		double xIndex = Math.round(lonRad / cellWidthRad);

		return new double[]{xIndex, yIndex};
	}
	/**
	 * Enumerates the coarse cells (Contract.CELL_SIZE_METERS) that a lat/lon bounding box touches —
	 * the manual R-tree substitute shared by the importer (populating way_cells) and the reader
	 * (querying by viewport). Correctness-safe: it may over-include a cell the box only grazes, never
	 * misses one it truly overlaps. Returns a list of {cell_x, cell_y} pairs.
	 */
	public static java.util.List<int[]> cellsForBbox(double minLat, double maxLat, double minLon, double maxLon) {
		return cellsForBbox(minLat, maxLat, minLon, maxLon, Contract.CELL_SIZE_METERS);
	}

	/** Same as {@link #cellsForBbox(double, double, double, double)}, parameterized by cell size —
	 * used with a much finer size for the in-memory nearest-element index (see MapTileManager). */
	public static java.util.List<int[]> cellsForBbox(double minLat, double maxLat, double minLon, double maxLon, double cellSizeMeters) {
		java.util.List<int[]> cells = new java.util.ArrayList<>();
		int yMin = latLonToIndex(minLat, minLon, cellSizeMeters)[1];
		int yMax = latLonToIndex(maxLat, minLon, cellSizeMeters)[1];
		for (int cy = yMin; cy <= yMax; cy++) {
			// Cell x depends on latitude (equirectangular), so compute the x-range at this row's own
			// latitude — accurate within the row and never off by more than a boundary sliver.
			double rowLat = indexToLatLon(0, cy, cellSizeMeters).lat;
			int xa = latLonToIndex(rowLat, minLon, cellSizeMeters)[0];
			int xb = latLonToIndex(rowLat, maxLon, cellSizeMeters)[0];
			int xMin = Math.min(xa, xb);
			int xMax = Math.max(xa, xb);
			for (int cx = xMin; cx <= xMax; cx++) {
				cells.add(new int[]{cx, cy});
			}
		}
		return cells;
	}

	/**
	 * Standard ray-casting point-in-polygon test, one outer ring. Point and ring coordinates are
	 * (x=lon, y=lat) — matches GeofabrikIndex's in-memory geometry and the packed boundaries.rings
	 * format. Shared so both the in-memory GeofabrikIndex scan and the DB-backed boundaries query use
	 * the exact same, already-proven algorithm.
	 */
	public static boolean ringContains(float[][] ring, double x, double y) {
		boolean inside = false;
		for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
			double xi = ring[i][0], yi = ring[i][1];
			double xj = ring[j][0], yj = ring[j][1];
			boolean intersect = ((yi > y) != (yj > y))
					&& (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
			if (intersect) inside = !inside;
		}
		return inside;
	}

	/** True if (lat,lon) falls inside any of the given rings (a region can be a MultiPolygon). */
	public static boolean containsPoint(java.util.List<float[][]> rings, double lat, double lon) {
		for (float[][] ring : rings) {
			if (ringContains(ring, lon, lat)) return true;
		}
		return false;
	}

	/** Packs a tag map as a MessagePack map of string->string. Shared by any import pipeline. */
	public static byte[] packTags(java.util.Map<String, String> tags) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (org.msgpack.core.MessagePacker packer = MessagePack.newDefaultPacker(bos)) {
			packer.packMapHeader(tags.size());
			for (java.util.Map.Entry<String, String> e : tags.entrySet()) {
				packer.packString(e.getKey());
				packer.packString(e.getValue() == null ? "" : e.getValue());
			}
		}
		return bos.toByteArray();
	}

	/** A way's unpacked geometry: parallel lat/lon/region arrays, one entry per point. */
	public static final class WayPoints {
		public final double[] lats;
		public final double[] lons;
		public final int[] regions;

		public WayPoints(double[] lats, double[] lons, int[] regions) {
			this.lats = lats;
			this.lons = lons;
			this.regions = regions;
		}
	}

	/**
	 * Packs a way's geometry as a MessagePack array of [lat_e7, lon_e7, region_id] integer triples —
	 * region is per-point (not per-way) because a way can straddle two regions; see
	 * OSMDatabaseHelper's {@code regions}/{@code way_regions} tables and RegionResolver.
	 */
	public static byte[] packWayPoints(double[] lats, double[] lons, int[] regions) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (org.msgpack.core.MessagePacker packer = MessagePack.newDefaultPacker(bos)) {
			packer.packArrayHeader(lats.length);
			for (int i = 0; i < lats.length; i++) {
				packer.packArrayHeader(3);
				packer.packInt((int) Math.round(lats[i] * 1e7));
				packer.packInt((int) Math.round(lons[i] * 1e7));
				packer.packInt(regions[i]);
			}
		}
		return bos.toByteArray();
	}

	/** Unpacks a {@code ways.points} blob (see {@link #packWayPoints}) into parallel arrays. */
	public static WayPoints unpackWayPoints(byte[] blob) {
		if (blob == null) return new WayPoints(new double[0], new double[0], new int[0]);
		try (org.msgpack.core.MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(blob)) {
			org.msgpack.value.ArrayValue arr = unpacker.unpackValue().asArrayValue();
			int n = arr.size();
			double[] lats = new double[n];
			double[] lons = new double[n];
			int[] regions = new int[n];
			for (int i = 0; i < n; i++) {
				org.msgpack.value.ArrayValue p = arr.get(i).asArrayValue();
				lats[i] = p.get(0).asIntegerValue().asInt() / 1e7;
				lons[i] = p.get(1).asIntegerValue().asInt() / 1e7;
				regions[i] = p.size() > 2 ? p.get(2).asIntegerValue().asInt() : 0;
			}
			return new WayPoints(lats, lons, regions);
		} catch (Exception e) {
			Log.e("Utils", "unpackWayPoints failed", e);
			return new WayPoints(new double[0], new double[0], new int[0]);
		}
	}

	/**
	 * Unpacks a {@code boundaries.rings} blob (see GeofabrikIndex.packRings) into rings of [lon,lat]
	 * pairs. Shared by any code doing a point-in-region lookup against the boundaries table (the
	 * "is this imported" check, and region resolution for the region/deletion feature).
	 */
	public static java.util.List<float[][]> unpackRings(byte[] blob) {
		java.util.List<float[][]> rings = new java.util.ArrayList<>();
		if (blob == null) return rings;
		try (org.msgpack.core.MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(blob)) {
			org.msgpack.value.ArrayValue ringsArr = unpacker.unpackValue().asArrayValue();
			for (int i = 0; i < ringsArr.size(); i++) {
				org.msgpack.value.ArrayValue ring = ringsArr.get(i).asArrayValue();
				float[][] pts = new float[ring.size()][2];
				for (int j = 0; j < ring.size(); j++) {
					org.msgpack.value.ArrayValue pt = ring.get(j).asArrayValue();
					pts[j][0] = pt.get(0).asFloatValue().toFloat();
					pts[j][1] = pt.get(1).asFloatValue().toFloat();
				}
				rings.add(pts);
			}
		} catch (Exception e) {
			Log.e("Utils", "unpackRings failed", e);
		}
		return rings;
	}

	// Converts indexX, indexY to lat/lon of tile/cell center
	public static LatLon indexToLatLon(int indexX, int indexY, double cellSizeMeters) {
		double metersX = indexX * cellSizeMeters;
		double metersY = indexY * cellSizeMeters;

		double lat = Math.toDegrees(metersY / Contract.EARTH_RADIUS);
	double lon = Math.toDegrees(metersX / (Contract.EARTH_RADIUS * Math.cos(Math.toRadians(lat)))); // adjust with latitude later


		return new LatLon(lat, lon);
	}
	
	/**
	 * Computes the cosine of the angle between a bearing direction vector
	 * and the vector from (x1, y1) to (x2, y2).
	 *
	 * @param bearingRad bearing in degrees (0 = north, 90 = east)
	 * @param x1 start x
	 * @param y1 start y
	 * @param x2 end x
	 * @param y2 end y
	 * @return cosine of angle between vectors (-1..1), NaN if degenerate
	 */
	public static double cosineBetween(double bearingRad, double x1, double y1, double x2, double y2) {
		// Unit vector in bearing direction
		double ax = Math.sin(bearingRad);
		double ay = Math.cos(bearingRad);
		
		// Displacement vector
		double bx = x2 - x1;
		double by = y2 - y1;
		
		// Norm of displacement vector
		double normB = Math.sqrt(bx * bx + by * by);
		if (normB == 0) return Double.NaN; // undefined
		
		// Dot product / |B|
		return (ax * bx + ay * by) / normB;
	}
	
	public static int bearingToClockDirection(double bearing) {
		// Normalize bearing to 0–360°
		bearing = (bearing + 2*360) % 360;
		
		// Each hour on the clock represents 30 degrees
		long hour = Math.round(bearing / 30f) % 12;
		
		// Convert 0 to 12 for "12 o'clock"
		if (hour == 0) hour = 12;
		
		return (int) hour;
	}
	
	public static String formatAddress(String name, String format) {
		String[] parts = (name + "| | | |").split("\\|");
		String houseNumber = parts[0];
		String street = parts[1];
		String city = parts[2];
		String country = parts[3];
		
		// auto detection
		if (format.equals("auto")) {
			Set<String> streetFirstCountries = new HashSet<>();
			streetFirstCountries.add("AR"); // Argentina
			streetFirstCountries.add("AT"); // Austria
			streetFirstCountries.add("BE"); // Belgium
			streetFirstCountries.add("BR"); // Brazil
			streetFirstCountries.add("BG"); // Bulgaria
			streetFirstCountries.add("CL"); // Chile
			streetFirstCountries.add("HR"); // Croatia
			streetFirstCountries.add("CZ"); // Czech Republic
			streetFirstCountries.add("DK"); // Denmark
			streetFirstCountries.add("EE"); // Estonia
			streetFirstCountries.add("FI"); // Finland
			streetFirstCountries.add("DE"); // Germany
			streetFirstCountries.add("GR"); // Greece
			streetFirstCountries.add("HU"); // Hungary
			streetFirstCountries.add("IS"); // Iceland
			streetFirstCountries.add("IT"); // Italy
			streetFirstCountries.add("LV"); // Latvia
			streetFirstCountries.add("LT"); // Lithuania
			streetFirstCountries.add("LU"); // Luxembourg
			streetFirstCountries.add("NL"); // Netherlands
			streetFirstCountries.add("NO"); // Norway
			streetFirstCountries.add("PL"); // Poland
			streetFirstCountries.add("PT"); // Portugal
			streetFirstCountries.add("RO"); // Romania
			streetFirstCountries.add("RS"); // Serbia
			streetFirstCountries.add("SK"); // Slovakia
			streetFirstCountries.add("SI"); // Slovenia
			streetFirstCountries.add("ES"); // Spain
			streetFirstCountries.add("SE"); // Sweden
			streetFirstCountries.add("CH"); // Switzerland
			streetFirstCountries.add("TR"); // Turkey
			streetFirstCountries.add("UA"); // Ukraine
			if (streetFirstCountries.contains(country.toUpperCase(Locale.ROOT)))
				format = "street_first";
			else
				format = "number_first";
		}
		if (format.equals("street_first"))
			return street + " " + houseNumber;
		else
			return houseNumber+ " " + street;
	}
}
