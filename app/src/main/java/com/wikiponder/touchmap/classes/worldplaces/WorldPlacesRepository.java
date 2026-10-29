package com.wikiponder.touchmap.classes.worldplaces;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.classes.geofabrik.GeofabrikIndex;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Manages the offline {@code world_places.db} gazetteer file (built by
 * tools/build_world_places.py): world cities/towns/villages, city sections, landmarks, airports,
 * railway stations and hospitals.
 *
 * <p>The database is downloaded once (on first run, or whenever missing) into
 * {@code getFilesDir()} — this keeps the APK small and lets the data be refreshed server-side
 * without an app update. Its {@code nodes} table is shaped identically to the app's own
 * normalized {@code nodes} table (see OSMDatabaseHelper), so {@link com.wikiponder.touchmap.classes.MapTileManager}
 * queries it directly via {@code ATTACH DATABASE} with the same SQL shape used for real imported
 * data — this class only owns the download/attach lifecycle, not querying.
 */
public class WorldPlacesRepository {
	private static final String TAG = "WorldPlacesRepository";
	private static final String DB_FILE_NAME = "world_places.db";
	private static final String DOWNLOAD_URL = "https://wikiponder.com/navia/world_places.db";
	private static final String PREF_VERSION = "world_places_version";
	/** Alias used in `ATTACH DATABASE ... AS wp` — MapTileManager queries `wp.nodes`. */
	public static final String ATTACHED_ALIAS = "wp";

	private final Context context;
	private final Object lock = new Object();
	private volatile boolean ready = false;

	public WorldPlacesRepository(Context context) {
		this.context = context.getApplicationContext();
	}

	/** Kicks off the (possibly network) initialization off the calling thread. Call once at startup. */
	public void initializeAsync() {
		new Thread(this::ensureReady, "world-places-init").start();
	}

	/** True once the database has been downloaded and is ready to attach. Cheap; never blocks. */
	public boolean isReady() {
		return ready;
	}

	private void ensureReady() {
		if (ready) return;
		synchronized (lock) {
			if (ready) return;
			File dbFile = new File(context.getFilesDir(), DB_FILE_NAME);
			if (!dbFile.exists() && !downloadDb(dbFile)) {
				return; // network/storage failure; will retry on the next app start
			}
			// Sanity-check the file opens before declaring it ready, so a corrupt/partial download
			// doesn't get attached later and surface as a query-time failure instead.
			try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(
					dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
				ready = true;
			} catch (Exception e) {
				Log.e(TAG, "Failed to open world_places.db; deleting so it re-downloads next time", e);
				//noinspection ResultOfMethodCallIgnored
				dbFile.delete();
			}
		}
		if (ready) checkForUpdate(new File(context.getFilesDir(), DB_FILE_NAME));
	}

	/**
	 * Checked once per launch (called right after {@link #ensureReady} succeeds, on the same
	 * background thread): compares the shared {@code updates.json}'s {@code "world_places"} field
	 * against the last version we downloaded, and silently refreshes {@code world_places.db} if
	 * newer. Any failure — server down, bad JSON, corrupt download — is swallowed and leaves the
	 * current file in place; this session just keeps using whatever's already attached, the refresh
	 * is picked up next launch. Shares its version-check file with
	 * {@link com.wikiponder.touchmap.classes.geofabrik.GeofabrikIndexRepository}'s "regions" field.
	 */
	private void checkForUpdate(File dbFile) {
		try {
			int serverVersion = new JSONObject(downloadText(GeofabrikIndex.UPDATES_URL)).optInt("world_places", -1);
			if (serverVersion <= 0) return;

			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
			if (!prefs.contains(PREF_VERSION)) {
				// The first-run download never recorded a version, and the hosted file hadn't changed
				// when updates.json first went up (at 10). Adopt the server's number as a baseline
				// instead of re-downloading the same 16+ MB right after (or long after) first run.
				prefs.edit().putInt(PREF_VERSION, serverVersion).apply();
				return;
			}
			int localVersion = prefs.getInt(PREF_VERSION, 0);
			if (serverVersion <= localVersion) return;

			File tmp = new File(dbFile.getPath() + ".update");
			if (!downloadDb(tmp)) return;
			try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(
					tmp.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
				// opens fine — good enough to trust the download
			} catch (Exception e) {
				Log.w(TAG, "Downloaded world_places.db update failed to open; discarding", e);
				//noinspection ResultOfMethodCallIgnored
				tmp.delete();
				return;
			}
			if (!tmp.renameTo(dbFile)) {
				//noinspection ResultOfMethodCallIgnored
				tmp.delete();
				return;
			}
			prefs.edit().putInt(PREF_VERSION, serverVersion).apply();
			Log.i(TAG, "world_places.db updated to version " + serverVersion);
		} catch (Exception e) {
			// Server down, unreachable, malformed JSON, etc. — not fatal, just skip this launch's check.
			Log.d(TAG, "updates.json check skipped: " + e.getMessage());
		}
	}

	private static String downloadText(String url) throws Exception {
		HttpURLConnection conn = GeofabrikIndex.openWithRedirects(url);
		try {
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + code);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			try (InputStream in = conn.getInputStream()) {
				byte[] buf = new byte[4096];
				int n;
				while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
			}
			return bos.toString(StandardCharsets.UTF_8.name());
		} finally {
			conn.disconnect();
		}
	}

	/**
	 * Attaches the gazetteer file to the given (already-open) connection as {@value #ATTACHED_ALIAS},
	 * so queries can reference {@code wp.nodes}. Safe to call repeatedly — a connection only gets
	 * attached once (SQLite errors on attaching the same alias twice on one connection).
	 */
	public void attachTo(SQLiteDatabase db) {
		if (!ready) return;
		File dbFile = new File(context.getFilesDir(), DB_FILE_NAME);
		try {
			db.execSQL("ATTACH DATABASE ? AS " + ATTACHED_ALIAS, new Object[]{dbFile.getAbsolutePath()});
		} catch (Exception e) {
			// Already attached on this connection, or attach genuinely failed — either way, callers
			// find out for real when their own query against wp.nodes succeeds or fails.
			Log.d(TAG, "attachTo: " + e.getMessage());
		}
	}

	private boolean downloadDb(File dest) {
		File tmp = new File(dest.getPath() + ".part");
		try {
			HttpURLConnection conn = GeofabrikIndex.openWithRedirects(DOWNLOAD_URL);
			try {
				int code = conn.getResponseCode();
				if (code != HttpURLConnection.HTTP_OK) {
					Log.w(TAG, "world_places.db download HTTP " + code);
					return false;
				}
				try (InputStream in = conn.getInputStream();
					 OutputStream out = new FileOutputStream(tmp)) {
					byte[] buf = new byte[65536];
					int n;
					while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
				}
			} finally {
				conn.disconnect();
			}
			return tmp.renameTo(dest);
		} catch (Exception e) {
			Log.w(TAG, "world_places.db download failed", e);
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			return false;
		}
	}
}
