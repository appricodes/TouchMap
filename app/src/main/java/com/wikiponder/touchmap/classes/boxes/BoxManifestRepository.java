package com.wikiponder.touchmap.classes.boxes;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads {@code boxes_manifest.json}, the index of every planet-tiling box db hosted at
 * {@code https://wikiponder.com/navia/global_boxes/} (see {@link BoxManifest}). This is the only
 * source of downloadable map data now — there is no per-country/per-region picker and no Overpass
 * radius fallback; {@link com.wikiponder.touchmap.classes.importer.BoxImporter} always resolves
 * "download the map here" to exactly one box via this manifest.
 *
 * <p><b>Always read from the copy bundled in the APK ({@code assets/boxes_manifest.json}) — never
 * fetched from the server.</b> The manifest is small (~1 MB) and changes only when the server-side
 * tiling tool is re-run, which isn't something worth a network round-trip (or its failure modes) on
 * every "download the map here". Keeping it current means updating {@code assets/boxes_manifest.json}
 * in the repo and shipping a new app build whenever the server-side box set changes — a deliberate
 * choice, not a stopgap (this app has one developer and one server, not a fleet of independently
 * updating clients). The actual box {@code .db.gz} files are still downloaded from the server —
 * only the manifest itself is bundled-only.
 *
 * <p>Parsed once and cached in memory for the life of the process.
 */
public class BoxManifestRepository {
	public static final String BASE_URL = "https://wikiponder.com/navia/global_boxes/";
	private static final String ASSET_NAME = "boxes_manifest.json";

	private static volatile BoxManifest cached;

	private BoxManifestRepository() {
	}

	/** Url of one box's gzipped db, e.g. box_000123.db.gz. */
	public static String boxDbUrl(int index) {
		return String.format(java.util.Locale.US, "%sbox_%06d.db.gz", BASE_URL, index);
	}

	/** Returns the cached manifest if already parsed this process, else parses it from the bundled
	 * asset. Blocking — call off the main thread. Throws only if the bundled asset is somehow missing
	 * or corrupt, which should never happen since it ships with the APK. */
	public static BoxManifest getOrFetch(Context context) throws Exception {
		BoxManifest m = cached;
		if (m != null) return m;
		synchronized (BoxManifestRepository.class) {
			if (cached != null) return cached;
			BoxManifest parsed = BoxManifest.parse(readBundledAsset(context));
			cached = parsed;
			return parsed;
		}
	}

	private static String readBundledAsset(Context context) throws Exception {
		try (InputStream in = context.getApplicationContext().getAssets().open(ASSET_NAME)) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
			return bos.toString(StandardCharsets.UTF_8.name());
		}
	}
}
