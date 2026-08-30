package com.wikiponder.touchmap.classes.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.wikiponder.touchmap.classes.Contract;
import com.wikiponder.touchmap.classes.Utils;

/**
 * A second, tiny SQLite database — separate from osm.db on purpose — that mirrors every node the
 * user has personally created or changed: manually-added points, renames (of a manual point or a
 * local override on a real downloaded one), and favorites. osm.db itself is excluded from Android
 * Auto Backup (see backup_rules.xml/data_extraction_rules.xml) because downloaded map data routinely
 * runs into the hundreds of MB, far past the ~25MB quota, and exceeding that quota fails the WHOLE
 * backup rather than just the oversized part. This file stays small (a handful of bytes per edit) and
 * IS included in backup, so a reinstall/restore doesn't silently lose data the user actually typed —
 * only re-downloadable OSM data, which was never at risk of loss in the first place.
 *
 * <p>Each row fully owns itself: {@link #replayInto} can be called at any time (app startup, after
 * every import) and only ever INSERT-OR-IGNOREs a manual node's full row, or UPDATEs the name/favorite
 * columns of a node that already exists — both are no-ops if there's nothing to apply yet, so replay
 * needs no "have I already restored?" flag and is safe to call repeatedly and unconditionally.
 *
 * <p>{@code is_manual = 1} rows are self-contained (this app is the only source of that node — no
 * import will ever recreate it, so replay must insert the full row: lat/lon/cat_id/region/name). {@code
 * is_manual = 0} rows are a partial override on a node that (may) come from an import: {@code name}
 * is nullable and left NULL when only the favorite flag was ever touched, so replay never blanks out a
 * real point's actual OSM name just because the user favorited it without renaming it.
 */
public class UserEditsBackupHelper extends SQLiteOpenHelper {
	private static final String DB_NAME = "user_edits.db";
	private static final int DB_VERSION = 1;

	private static volatile UserEditsBackupHelper instance;

	private UserEditsBackupHelper(Context context) {
		super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
	}

	public static UserEditsBackupHelper getInstance(Context context) {
		if (instance == null) {
			synchronized (UserEditsBackupHelper.class) {
				if (instance == null) {
					instance = new UserEditsBackupHelper(context);
				}
			}
		}
		return instance;
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE edited_nodes(" +
				"  id       INTEGER PRIMARY KEY, " +
				"  is_manual INTEGER NOT NULL DEFAULT 0, " +
				"  lat      REAL, " +
				"  lon      REAL, " +
				"  cat_id   INTEGER, " +
				"  region   INTEGER, " +
				"  name     TEXT, " +          // NULL = no rename recorded for this id
				"  favorite INTEGER NOT NULL DEFAULT 0" +
				");");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// Nothing to migrate yet — first version of this store.
	}

	/** Called from {@link OSMDatabaseHelper#insertNode}: every row it writes is a manual-category
	 * node (that method always stamps CATEGORY_MANUAL, regardless of caller — see its own code), so
	 * it is always fully self-contained and always needs the complete row backed up.
	 *
	 * <p>CONFLICT_IGNORE, not REPLACE: node ids are never reused by this app, so this only ever fires
	 * once per id in practice — but staying IGNORE means that if it ever did fire twice for the same
	 * id, it couldn't clobber a favorite/name recorded by {@link #recordFavorite}/{@link #recordRename}
	 * in between back down to this row's defaults. */
	public static void recordManualNode(Context context, long id, double lat, double lon, int catId,
										 int region, String name) {
		ContentValues cv = new ContentValues();
		cv.put("id", id);
		cv.put("is_manual", 1);
		cv.put("lat", lat);
		cv.put("lon", lon);
		cv.put("cat_id", catId);
		cv.put("region", region);
		cv.put("name", name);
		getInstance(context).getWritableDatabase()
				.insertWithOnConflict("edited_nodes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
	}

	/** Called from {@link OSMDatabaseHelper#deleteNode}. Only manual nodes can be deleted (see that
	 * method's doc), so this just drops whatever backup row exists for this id, if any. */
	public static void recordDeleted(Context context, long id) {
		getInstance(context).getWritableDatabase()
				.delete("edited_nodes", "id = ?", new String[]{String.valueOf(id)});
	}

	/** Called from {@link OSMDatabaseHelper#updateNodeName}. Updates just the name column — a manual
	 * node's own is_manual/lat/lon/cat_id/region (already recorded by recordManualNode) are left
	 * untouched; a real node being renamed for the first time gets a fresh partial (is_manual=0) row. */
	public static void recordRename(Context context, long id, String name) {
		ContentValues cv = new ContentValues();
		cv.put("name", name);
		updateOneColumn(context, id, cv);
	}

	/** Called from {@link OSMDatabaseHelper#setFavorite}. Updates just the favorite column, same
	 * preserve-the-rest-of-the-row behavior as {@link #recordRename}. */
	public static void recordFavorite(Context context, long id, boolean favorite) {
		ContentValues cv = new ContentValues();
		cv.put("favorite", favorite ? 1 : 0);
		updateOneColumn(context, id, cv);
	}

	/** INSERT-OR-IGNORE-then-UPDATE, not a SQL "ON CONFLICT DO UPDATE" upsert: this app's minSdk (26)
	 * reaches devices whose bundled SQLite predates upsert syntax (added in SQLite 3.24, Android didn't
	 * guarantee that until API 30), so a real upsert would throw on those. This two-step version is
	 * plain, portable SQL: the INSERT OR IGNORE only ever creates a bare all-default row if none
	 * exists yet, and never touches an existing one — the following UPDATE is what actually applies
	 * {@code values}, to whichever row now exists either way. */
	private static void updateOneColumn(Context context, long id, ContentValues values) {
		SQLiteDatabase db = getInstance(context).getWritableDatabase();
		ContentValues idOnly = new ContentValues();
		idOnly.put("id", id);
		db.insertWithOnConflict("edited_nodes", null, idOnly, SQLiteDatabase.CONFLICT_IGNORE);
		db.update("edited_nodes", values, "id = ?", new String[]{String.valueOf(id)});
	}

	/** Applies every recorded edit into osm.db. Safe to call unconditionally and repeatedly — see
	 * class doc. Called once at app startup (covers manual nodes, which don't depend on any import)
	 * and again after every completed box import (covers renames/favorites on real nodes that only
	 * just became present in {@code nodes} for the first time). Runs entirely on whatever thread the
	 * caller is already using for its own db work — callers are responsible for staying off the main
	 * thread, same as every other db entry point in this app. */
	public static void replayInto(Context context) {
		SQLiteDatabase editsDb = getInstance(context).getReadableDatabase();
		SQLiteDatabase osmDb = OSMDatabaseHelper.getInstance(context).getWritableDatabase();

		try (Cursor c = editsDb.query("edited_nodes", null, null, null, null, null, null)) {
			int idIdx = c.getColumnIndexOrThrow("id");
			int manualIdx = c.getColumnIndexOrThrow("is_manual");
			int latIdx = c.getColumnIndexOrThrow("lat");
			int lonIdx = c.getColumnIndexOrThrow("lon");
			int catIdx = c.getColumnIndexOrThrow("cat_id");
			int regionIdx = c.getColumnIndexOrThrow("region");
			int nameIdx = c.getColumnIndexOrThrow("name");
			int favIdx = c.getColumnIndexOrThrow("favorite");

			while (c.moveToNext()) {
				long id = c.getLong(idIdx);
				boolean isManual = c.getInt(manualIdx) != 0;
				String name = c.isNull(nameIdx) ? null : c.getString(nameIdx);
				boolean favorite = c.getInt(favIdx) != 0;

				if (isManual) {
					double lat = c.getDouble(latIdx);
					double lon = c.getDouble(lonIdx);
					int catId = c.getInt(catIdx);
					int region = c.getInt(regionIdx);
					int[] cell = Utils.latLonToIndex(lat, lon, Contract.CELL_SIZE_METERS);

					ContentValues cv = new ContentValues();
					cv.put("id", id);
					cv.put("lat", lat);
					cv.put("lon", lon);
					cv.put("cell_x", cell[0]);
					cv.put("cell_y", cell[1]);
					cv.put("cat_id", catId);
					cv.put("favorite", favorite ? 1 : 0);
					cv.put("type", "");
					cv.put("name", name);
					cv.put("tags", new byte[0]);
					cv.put("region", region);
					// IGNORE, not REPLACE: if this id is already present (the common case — this ran
					// before, or the node was never actually lost) leave it exactly as-is rather than
					// clobbering it with a possibly-stale backup snapshot.
					osmDb.insertWithOnConflict("nodes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
				} else {
					ContentValues cv = new ContentValues();
					if (name != null) cv.put("name", name);
					cv.put("favorite", favorite ? 1 : 0);
					// A plain UPDATE against a not-yet-imported id affects 0 rows — a harmless no-op,
					// not an error — which is exactly what makes calling this after every import safe.
					osmDb.update("nodes", cv, "id = ?", new String[]{String.valueOf(id)});
				}
			}
		}
	}
}
