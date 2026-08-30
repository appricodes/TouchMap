package com.wikiponder.touchmap.classes.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.wikiponder.touchmap.classes.Utils;

/**
 * Resolves a (country, admin1, admin2) triple to a compact {@code regions.id} from a raw lat/lon, by
 * point-in-polygon against the {@code boundaries} table (admin geometry from {@code GeofabrikIndex} —
 * used only for naming/lookup now, not for downloading, see {@code BoxImporter}). See
 * {@link OSMDatabaseHelper}'s {@code regions} / {@code way_regions} tables.
 */
public class RegionResolver {

	private RegionResolver() {
	}

	/** Resolves the region id containing (lat, lon) — currently only manually-added nodes go through
	 * this; box-downloaded data is tagged {@code OSMDatabaseHelper.REGION_WORLD} at the source. */
	public static int resolveByLocation(SQLiteDatabase db, double lat, double lon) {
		String deepestId = deepestRegionIdContaining(db, lat, lon);
		if (deepestId == null) return upsertRegion(db, "", "", "");
		String[] parts = resolveTriple(db, deepestId);
		if (parts == null) return upsertRegion(db, "", "", "");
		return upsertRegion(db, parts[0], parts[1], parts[2]);
	}

	/** The deepest {@code boundaries} region_id whose polygon contains (lat, lon), or null. */
	private static String deepestRegionIdContaining(SQLiteDatabase db, double lat, double lon) {
		try (Cursor c = db.query("boundaries", new String[]{"region_id", "rings"},
				"min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
				new String[]{String.valueOf(lat), String.valueOf(lat), String.valueOf(lon), String.valueOf(lon)},
				null, null, "depth DESC")) {
			while (c.moveToNext()) {
				if (Utils.containsPoint(Utils.unpackRings(c.getBlob(1)), lat, lon)) return c.getString(0);
			}
		}
		return null;
	}

	/**
	 * Walks {@code boundaries} up via {@code parent_id} from startRegionId to the depth-0 (country)
	 * ancestor, returning {country_iso2, admin1_name, admin2_name} (admin1/admin2 blank if the
	 * imported/containing region is shallower than that depth). Returns null if startRegionId has no
	 * {@code boundaries} row at all. Exposed (not just used internally) so {@code deleteRegion} can
	 * find which {@code boundaries} row(s) resolve to a given region id, to clear their imported_at.
	 */
	public static String[] resolveTriple(SQLiteDatabase db, String startRegionId) {
		String country = "", admin1 = "", admin2 = "";
		String currentId = startRegionId;
		boolean reachedCountry = false;
		// Bounds a pathological parent cycle; real Geofabrik hierarchies are at most a few levels deep.
		for (int hops = 0; hops < 16 && currentId != null; hops++) {
			String parentId = null;
			try (Cursor c = db.query("boundaries", new String[]{"parent_id", "name", "depth", "iso2"},
					"region_id=?", new String[]{currentId}, null, null, null)) {
				if (!c.moveToFirst()) break;
				parentId = c.getString(0);
				String name = c.getString(1);
				int depth = c.getInt(2);
				String iso2 = c.getString(3);
				if (depth == 1) admin1 = name;
				else if (depth == 2) admin2 = name;
				if (depth == 0) {
					country = iso2 != null ? iso2 : "";
					reachedCountry = true;
					break;
				}
			}
			currentId = parentId;
		}
		if (!reachedCountry) return null;
		return new String[]{country, admin1, admin2};
	}

	/** Looks up or creates the {@code regions} row for this (country, admin1, admin2) triple. */
	private static int upsertRegion(SQLiteDatabase db, String country, String admin1, String admin2) {
		Integer existing = findRegion(db, country, admin1, admin2);
		if (existing != null) return existing;
		ContentValues cv = new ContentValues();
		cv.put("country", country);
		cv.put("admin1", admin1);
		cv.put("admin2", admin2);
		long id = db.insertWithOnConflict("regions", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
		if (id >= 0) return (int) id;
		// CONFLICT_IGNORE no-opped (a concurrent insert won the UNIQUE race) — the row now exists.
		existing = findRegion(db, country, admin1, admin2);
		return existing != null ? existing : OSMDatabaseHelper.REGION_WORLD;
	}

	private static Integer findRegion(SQLiteDatabase db, String country, String admin1, String admin2) {
		try (Cursor c = db.query("regions", new String[]{"id"},
				"country=? AND admin1=? AND admin2=?",
				new String[]{country, admin1, admin2}, null, null, null)) {
			if (c.moveToFirst()) return c.getInt(0);
		}
		return null;
	}
}
