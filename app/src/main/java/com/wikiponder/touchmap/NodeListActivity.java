package com.wikiponder.touchmap;

import android.content.Intent;
import android.location.Location;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.interfaces.UserLocationListener;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.NearestElement;
import com.wikiponder.touchmap.classes.ui.ActionsDialog;
import com.wikiponder.touchmap.classes.ui.ElementDetailsDialog;
import com.wikiponder.touchmap.classes.ui.NodeAdapter;
import com.wikiponder.touchmap.classes.ui.NodeEditDialogBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NodeListActivity extends AppCompatActivity implements UserLocationListener, NodeAdapter.OnNodeClickListener {

	/** Optional keyword filter — narrows the nearby-nodes query down
	 * to nodes whose type/name matches, on top of the usual distance radius. Absent for the plain
	 * "list nearby nodes" entry point. */
	public static final String EXTRA_SEARCH_KEYWORD = "search_keyword";

	private static final String TAG = "NodeListActivity";
	// **NEW:** Minimum time between list refreshes (10 seconds)
	private static final long MIN_UPDATE_INTERVAL_MS = 10000;
	// Nodes only, within this distance of the chosen center on each side (not ways, and not a real
	// geodesic distance — see loadNearbyNodes/MapTileManager.queryNearbyNodes). Capped at
	// MapTileManager.NEARBY_NODES_MAX (10000) rows there too.
	private static final double NEARBY_RADIUS_METERS = 1500;
	// How far apart the map center and the user's live location must be before it's worth asking
	// which one to center the query on — below this, a NEARBY_RADIUS_METERS query from either point
	// would substantially overlap the other's results anyway, so asking would just be noise. Above
	// it, silently picking one is confusing: the two lists could be entirely different.
	private static final double CENTER_MISMATCH_THRESHOLD_METERS = NEARBY_RADIUS_METERS;
	// How long to wait for a fresh GPS fix before falling back to the map center alone, so the screen
	// doesn't sit blank indefinitely if one never arrives (no permission, no signal, etc.) — long
	// enough for the fast/high-accuracy fix UserLocationManager.addListener() requests to usually land.
	private static final long INITIAL_LOCATION_WAIT_MS = 2000;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private NodeAdapter nodeAdapter;
	private List<MapElement> allElements = new ArrayList<>();
	private Location currentUserLocation;
	private AlertDialog loadingDialog;
	private boolean initialLoadStarted = false;
	// Whichever center the currently-displayed list was actually queried around — remembered so a
	// post-mutation refresh (favorite toggle, delete) re-queries the same place instead of silently
	// switching centers or re-asking which one to use.
	private Location activeCenter;
	// Set from EXTRA_SEARCH_KEYWORD, or null for the plain "nearby nodes" entry point.
	private String searchKeyword;

	// **NEW:** Timestamp of the last UI refresh
	private long lastUiUpdateTime = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_node_list);
		// See BaseActivity's onCreate for why: routes the hardware volume rocker to the same
		// Accessibility stream every sound in this app plays on (AudioClient).
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);

		searchKeyword = getIntent() != null ? getIntent().getStringExtra(EXTRA_SEARCH_KEYWORD) : null;
		if (searchKeyword != null && !searchKeyword.isEmpty()) {
			setTitle(getString(R.string.app_name) + " — \"" + searchKeyword + "\"");
		}

		// --- Setup RecyclerView ---
		RecyclerView recyclerView = findViewById(R.id.nodes_recycler_view);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		nodeAdapter = new NodeAdapter(this, this);
		recyclerView.setAdapter(nodeAdapter);

		// Hide the original filter buttons as they are replaced by the dynamic list
		findViewById(R.id.filter_category_button).setVisibility(View.GONE);
		findViewById(R.id.filter_keyword_button).setVisibility(View.GONE);
		findViewById(R.id.update_sort_button).setVisibility(View.GONE);

		// Registered before the initial load (not after) so a fast GPS fix has a chance to arrive in
		// time to decide whether the map-center-vs-my-location chooser is needed — see
		// startInitialLoadIfNeeded.
		MyApplication.getUserLocationManager(this).addListener(this);
		mainHandler.postDelayed(this::startInitialLoadIfNeeded, INITIAL_LOCATION_WAIT_MS);
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Paused/resumed on the resume/pause boundary, not just once in onCreate, so it's correct
		// across the whole show/hide cycle (e.g. the screen locking and unlocking again while this
		// screen is still on top must leave announcements paused, not accidentally resume them). This
		// screen doesn't extend BaseActivity (so it doesn't get that class's window-focus-based
		// pause/resume for its own dialogs — not needed here since this activity is paused for its
		// entire visible lifetime regardless).
		MyApplication.getAnnouncementManager(this).pause();
	}

	@Override
	protected void onPause() {
		super.onPause();
		// Resume as soon as this screen is no longer the active one, for ANY reason — navigating back
		// to MapActivity (which also unconditionally resumes in its own onStart(), harmlessly
		// redundant there) or the whole app going to the background (screen lock, Home, another app
		// coming forward). Announcements are driven by BackgroundService, a foreground service
		// independent of any activity's lifecycle, and must keep running while backgrounded.
		MyApplication.getAnnouncementManager(this).resume();
	}

	/** Starts the very first nearby-nodes load, once — either because a GPS fix arrived in time
	 * (letting {@link #loadNearbyNodes()} decide whether the map center and live location diverge
	 * enough to ask which to use) or because {@link #INITIAL_LOCATION_WAIT_MS} elapsed without one,
	 * whichever happens first. */
	private void startInitialLoadIfNeeded() {
		if (initialLoadStarted) return;
		initialLoadStarted = true;
		loadNearbyNodes();
	}

	/** Decides which center to query "nearby nodes" around. If the map is currently showing somewhere
	 * meaningfully different from the user's real, live location (see
	 * {@link #CENTER_MISMATCH_THRESHOLD_METERS}), asks — silently picking one would be confusing,
	 * since the two lists could be entirely different. Otherwise queries directly around whichever is
	 * available (map center preferred, matching the previous default) — asking would just be noise
	 * when the two substantially overlap anyway, or there's nothing to choose between. */
	private void loadNearbyNodes() {
		Location mapCenter = MyApplication.getMapTileManager(this).getLastCenter();
		Location userLoc = currentUserLocation;
		if (mapCenter == null && userLoc == null) {
			Toast.makeText(this, R.string.toast_map_location_unavailable, Toast.LENGTH_SHORT).show();
			return;
		}
		if (mapCenter != null && userLoc != null
				&& mapCenter.distanceTo(userLoc) > CENTER_MISMATCH_THRESHOLD_METERS) {
			new AlertDialog.Builder(this)
					.setTitle(R.string.nearby_nodes_dialog_title)
					.setMessage(R.string.nearby_nodes_dialog_message)
					.setPositiveButton(R.string.action_around_me, (d, w) -> queryAndShow(userLoc))
					.setNegativeButton(R.string.action_around_map_center, (d, w) -> queryAndShow(mapCenter))
					// Cancelable (Back or tap-outside) rather than forcing a choice — a user who opened
					// this screen by mistake, or just wants out, needs a way to leave. onCancel finishes
					// the screen outright (there's nothing sensible to show — neither center was chosen)
					// rather than leaving a dismissed dialog over a permanently empty list.
					.setOnCancelListener(d -> finish())
					.show();
		} else {
			queryAndShow(mapCenter != null ? mapCenter : userLoc);
		}
	}

	/** Queries and displays the nearby-nodes list around {@code center} — directly against the db
	 * (not the loaded-viewport cache), off the main thread, since it's a fresh query rather than a
	 * filter over whatever's already loaded, and can cover more area than the current viewport. A
	 * loading dialog covers the gap instead of showing a blank/half-populated list while it runs.
	 * Remembers {@code center} as {@link #activeCenter} so a later post-mutation refresh (favorite
	 * toggle, delete) queries the same place again instead of silently switching or re-asking. */
	private void queryAndShow(Location center) {
		activeCenter = center;
		showLoadingDialog();
		new Thread(() -> {
			List<MapElement> nearby = MyApplication.getMapTileManager(this).queryNearbyNodes(center, NEARBY_RADIUS_METERS, searchKeyword);
			runOnUiThread(() -> {
				dismissLoadingDialog();
				allElements = nearby;
				String message = (searchKeyword != null && !searchKeyword.isEmpty())
						? "Found " + allElements.size() + " nodes matching \"" + searchKeyword + "\"."
						: "Found " + allElements.size() + " nearby nodes.";
				Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
				if (currentUserLocation != null) {
					updateListWithDistances();
				} else {
					// No GPS fix yet — show the list as-is rather than leaving it empty until one arrives.
					List<NearestElement> unsorted = new ArrayList<>();
					for (MapElement element : allElements) unsorted.add(new NearestElement(0, element));
					nodeAdapter.setElements(unsorted);
				}
			});
		}, "nearby-nodes-query").start();
	}

	private void showLoadingDialog() {
		ProgressBar progressBar = new ProgressBar(this);
		int pad = (int) (32 * getResources().getDisplayMetrics().density);
		progressBar.setPadding(pad, pad, pad, pad);
		loadingDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.nearby_nodes_loading_title)
				.setView(progressBar)
				.setCancelable(false)
				.show();
	}

	private void dismissLoadingDialog() {
		if (loadingDialog != null && loadingDialog.isShowing()) {
			loadingDialog.dismiss();
		}
		loadingDialog = null;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		dismissLoadingDialog();
		// --- Stop listening for location updates to save battery ---
		MyApplication.getUserLocationManager(this).removeListener(this);
	}

	@Override
	public void onLocationUpdated(Location location) {
		this.currentUserLocation = location;
		startInitialLoadIfNeeded();

		// **MODIFIED:** Check if enough time has passed since the last update
		long currentTime = System.currentTimeMillis();
		if (currentTime - lastUiUpdateTime > MIN_UPDATE_INTERVAL_MS) {
			updateListWithDistances();
		}
	}

	/**
	 * Calculates the distance from the user to every known element,
	 * sorts the list, and updates the RecyclerView.
	 */
	private void updateListWithDistances() {
		if (currentUserLocation == null || allElements.isEmpty()) {
			return;
		}

		List<NearestElement> nearestElements = new ArrayList<>();
		for (MapElement element : allElements) {
			double distance = currentUserLocation.distanceTo(element.getLocation());
			nearestElements.add(new NearestElement(distance, element));
		}

		// Sort the list by distance, closest first
		Collections.sort(nearestElements, (o1, o2) -> Double.compare(o1.distance, o2.distance));

		// Update the adapter with the newly sorted list
		nodeAdapter.setElements(nearestElements);

		// **NEW:** Record the time of this update
		lastUiUpdateTime = System.currentTimeMillis();
	}

	@Override
	public void onNodeClick(MapElement element) {
		// **NEW:** Reset the update timer to prevent an immediate refresh after user interaction
		lastUiUpdateTime = System.currentTimeMillis();

		// When a user clicks an item in the list, show the action dialog
		showNodeActionsDialog(element);
	}

	/**
	 * Displays a popup menu with actions for the selected MapElement — the same set MapActivity
	 * offers when tapping a point on the map itself: Aim/Navigate/Zoom/Favorite/Remove/Details.
	 */
	private void showNodeActionsDialog(final MapElement element) {
		Map<String, Runnable> actions = new LinkedHashMap<>();

		if (element.catId != OSMDatabaseHelper.CATEGORY_WAY) {
			actions.put(getString(R.string.action_aim_at_point), () -> {
				// The actual aiming (MapTileManager.aimedElement, read by BackgroundService regardless
				// of which screen is showing) takes effect immediately; requestAimUiRefresh just tells
				// MapActivity.onStart() to show its aim-cancel action button to match once we finish()
				// back to it — see that method's doc for why this is a one-shot hand-off rather than
				// something onStart() checks unconditionally every time.
				MyApplication.getMapTileManager(this).setAimedElement(element);
				MyApplication.getMapTileManager(this).requestAimUiRefresh(element);
				Toast.makeText(this, getString(R.string.aiming_started, element.getFormattedName(this, true)), Toast.LENGTH_SHORT).show();
				finish();
			});
			actions.put(getString(R.string.action_navigate_google_maps), () -> navigateToLocation(element.lat, element.lon));
			actions.put(getString(R.string.action_zoom_to_point_on_map), () -> {
				// Regardless of wherever the map is currently showing — see
				// MapTileManager.requestZoomTo/consumePendingZoomTarget, consumed by
				// MapActivity.onStart() once this screen finishes.
				MyApplication.getMapTileManager(this).requestZoomTo(element.getLocation());
				finish();
			});
		}

		actions.put(getString(element.isFavorite() ? R.string.action_remove_from_favorites : R.string.action_add_to_favorites),
				() -> toggleElementFavorite(element));

		// Any node (manually-added or a real downloaded OSM point) can be renamed — never a world-place
		// gazetteer stand-in, since OSMDatabaseHelper's node writes only ever touch the local nodes
		// table (a world-place point lives in the attached read-only world_places.db instead, so
		// editing one would silently do nothing). Ways are excluded from this whole list already
		// (queryNearbyNodes is nodes-only), so no separate CATEGORY_WAY check is needed here. Deleting
		// from inside that dialog is still limited to manually-added points — see NodeEditDialogBox.
		if (!OSMDatabaseHelper.isWorldPlaceCategory(element.catId)) {
			actions.put(getString(R.string.action_edit_node), () -> showEditNodeDialog(element));
		}

		final String detailsLabel = getString(R.string.action_details);
		actions.put(detailsLabel, () -> ElementDetailsDialog.show(this, element));

		ActionsDialog.show(this, element.getFormattedName(this, true), actions, detailsLabel, element.hasDisplayableTags());
	}

	private void navigateToLocation(double latitude, double longitude) {
		String uri = String.format(Locale.US, "google.navigation:q=%f,%f&mode=w", latitude, longitude);
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
		intent.setPackage("com.google.android.apps.maps");
		if (intent.resolveActivity(getPackageManager()) != null) {
			startActivity(intent);
		} else {
			String browserUri = String.format(
					Locale.US,
					"https://www.google.com/maps/dir/?api=1&destination=%f,%f&travelmode=walking",
					latitude,
					longitude);
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(browserUri)));
		}
	}

	private void toggleElementFavorite(MapElement element) {
		new Thread(() -> {
			boolean status = element.isFavorite();
			OSMDatabaseHelper.setFavorite(element.id, !status, element.getLocation());
			// refresh the map's own loaded-data cache so the favorite state is reflected there too
			MyApplication.getMapTileManager(this).reloadCurrentArea();
			// and refresh this screen's own list, which was populated by a one-off db query rather
			// than the cache — otherwise it would silently keep showing the old favorite state.
			runOnUiThread(() -> queryAndShow(activeCenter));
		}).start();
	}

	private void showEditNodeDialog(MapElement element) {
		Locale preferredLocale = MyApplication.getMyApplication(this).getPreferredLocale();
		NodeEditDialogBox.show(this, preferredLocale, element, new NodeEditDialogBox.Listener() {
			@Override
			public void onSaved(MapElement element, String newName) {
				// refresh the map's own cache and this screen's own list — see toggleElementFavorite
				// for why both are needed (this list is a one-off db query, not the cache).
				MyApplication.getMapTileManager(NodeListActivity.this).reloadCurrentArea();
				queryAndShow(activeCenter);
			}

			@Override
			public void onDeleted(MapElement element) {
				MyApplication.getMapTileManager(NodeListActivity.this).reloadCurrentArea();
				queryAndShow(activeCenter);
			}
		});
	}

}
