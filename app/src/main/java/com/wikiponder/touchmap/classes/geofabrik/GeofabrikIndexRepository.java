package com.wikiponder.touchmap.classes.geofabrik;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Manages the bundled {@code geofabrik_index.db} (built by tools/build_geofabrik_index.py): a
 * compact copy of Geofabrik's region index (country/province hierarchy, border polygons, PBF
 * download URLs), consumed by {@link GeofabrikIndex#load}.
 *
 * <p>This file ships inside the APK ({@code assets/geofabrik_index.db}), so first run needs no
 * network at all — the download screen's country list and location-based defaulting work offline
 * immediately. A shared {@code updates.json} is checked once per launch; when its {@code "regions"}
 * field is newer than what's stored locally, this class rebuilds the local db itself, straight from
 * Geofabrik's own {@code index-v1.json} ({@link GeofabrikIndex#downloadFresh}) — unlike
 * {@link com.wikiponder.touchmap.classes.worldplaces.WorldPlacesRepository} (whose db requires
 * heavy off-device GeoNames processing), there is no pre-built db to separately host and re-upload;
 * {@code updates.json} only needs a bumped number to trigger a refresh.
 */
public class GeofabrikIndexRepository {
	private static final String TAG = "GeofabrikIndexRepo";
	private static final String DB_FILE_NAME = "geofabrik_index.db";
	private static final String PREF_VERSION = "geofabrik_index_version";

	private final Context context;
	private final Object lock = new Object();
	private volatile boolean ready = false;

	public GeofabrikIndexRepository(Context context) {
		this.context = context.getApplicationContext();
	}

	/** Kicks off (possibly network) initialization off the calling thread. Call once at startup. */
	public void initializeAsync() {
		new Thread(this::ensureReady, "geofabrik-index-init").start();
	}

	/** True once the database is in place and ready to open. Cheap; never blocks. */
	public boolean isReady() {
		return ready;
	}

	/** The local db file, once {@link #isReady()} — null before that. */
	public File getDbFile() {
		return ready ? new File(context.getFilesDir(), DB_FILE_NAME) : null;
	}

	/**
	 * Blocking version of the readiness check — safe to call directly from a background thread
	 * (e.g. {@link GeofabrikIndex#load}) instead of only relying on {@link #initializeAsync()}
	 * having already finished. Idempotent; returns immediately once ready.
	 */
	public File ensureReady() {
		if (ready) return getDbFile();
		synchronized (lock) {
			if (ready) return getDbFile();
			File dbFile = new File(context.getFilesDir(), DB_FILE_NAME);
			if (!dbFile.exists() && !copyBundledAsset(dbFile)) {
				return null; // asset copy failed (e.g. disk full); caller falls back to JSON download
			}
			try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(
					dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
				ready = true;
			} catch (Exception e) {
				Log.e(TAG, "Failed to open geofabrik_index.db; deleting so it re-copies next time", e);
				//noinspection ResultOfMethodCallIgnored
				dbFile.delete();
				return null;
			}
		}
		checkForUpdate(new File(context.getFilesDir(), DB_FILE_NAME));
		return getDbFile();
	}

	private boolean copyBundledAsset(File dest) {
		File tmp = new File(dest.getPath() + ".part");
		try (InputStream in = context.getAssets().open(DB_FILE_NAME);
			 OutputStream out = new FileOutputStream(tmp)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			out.flush();
		} catch (Exception e) {
			Log.e(TAG, "Failed to copy bundled geofabrik_index.db asset", e);
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			return false;
		}
		return tmp.renameTo(dest);
	}

	/**
	 * Checked once per launch (called right after {@link #ensureReady} succeeds): compares the
	 * shared {@code updates.json}'s {@code "regions"} field against the last version rebuilt, and if
	 * newer, downloads {@code index-v1.json} straight from Geofabrik and rebuilds the local db from
	 * it ({@link GeofabrikIndex#downloadFresh}/{@link GeofabrikIndex#writeToDb}) — no separate db
	 * needs to be hosted anywhere; {@code updates.json} is only a lightweight "something changed,
	 * worth re-fetching" signal. Any failure — server down, bad JSON, corrupt fetch — is swallowed
	 * and leaves the current (bundled or previously rebuilt) file in place.
	 */
	private void checkForUpdate(File dbFile) {
		try {
			int serverVersion = new JSONObject(downloadText(GeofabrikIndex.UPDATES_URL)).optInt("regions", -1);
			if (serverVersion <= 0) return;

			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
			int localVersion = prefs.getInt(PREF_VERSION, 0);
			if (serverVersion <= localVersion) return;

			GeofabrikIndex fresh = GeofabrikIndex.downloadFresh();
			File tmp = new File(dbFile.getPath() + ".update");
			fresh.writeToDb(tmp);
			try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(
					tmp.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
				// opens fine — good enough to trust the rebuild
			} catch (Exception e) {
				Log.w(TAG, "Rebuilt geofabrik_index.db failed to open; discarding", e);
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
			Log.i(TAG, "geofabrik_index.db rebuilt at version " + serverVersion);
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
}
