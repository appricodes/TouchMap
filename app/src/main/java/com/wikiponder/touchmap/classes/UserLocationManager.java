package com.wikiponder.touchmap.classes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.wikiponder.touchmap.classes.interfaces.UserLocationListener;
import com.wikiponder.touchmap.classes.location.KalmanFilter;

import java.util.concurrent.CopyOnWriteArrayList;


public class UserLocationManager {
	private static final String TAG = "UserLocationManager";
	private static final float MAX_ACCEPTABLE_ACCURACY = 40.0f; // in meters
	// **NEW:** Minimum speed (m/s) to trust the location object's speed and bearing
	private static final float MIN_SPEED_FOR_VELOCITY_UPDATE = 0.5f;
	private static final boolean USE_KALMAN_FILTER = false;
	
	private final FusedLocationProviderClient fusedClient;
	private final CopyOnWriteArrayList<UserLocationListener> listeners = new CopyOnWriteArrayList<>();
	private LocationRequest activeRequest;
	
	// Adaptive cadence: ask for about one fix per TARGET_FIX_SPACING_M of travel, i.e. slower
	// when the user is slow or stationary, clamped between the base interval and
	// MAX_INTERVAL_FACTOR times it. Speed is the mean of the last two accepted fixes.
	private static final float TARGET_FIX_SPACING_M = 2f;
	private static final long MAX_INTERVAL_FACTOR = 3;
	private final long baseIntervalMs;
	private final long baseMinIntervalMs;
	private long activeIntervalMs;
	private float previousSpeed;
	private boolean hasPreviousSpeed = false;

	// A fix worse than MAX_ACCEPTABLE_ACCURACY is only discarded if a good one arrived this recently.
	private static final long MAX_GOOD_FIX_AGE_MS = 6000;
	private long lastGoodFixElapsedMs = -MAX_GOOD_FIX_AGE_MS;

	private boolean running = false;
	private KalmanFilter kalmanFilter;
	
	private final LocationCallback locationCallback = new LocationCallback() {
		@Override
		public void onLocationResult(LocationResult locationResult) {
			if (locationResult == null) return;
			Location location = locationResult.getLastLocation();
			if (location != null) {
				long now = SystemClock.elapsedRealtime();
				if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCEPTABLE_ACCURACY) {
					// Drop a rough fix only while we have a recent good one to fall back on;
					// otherwise accept it so the marker doesn't freeze in poor-signal areas.
					if (now - lastGoodFixElapsedMs < MAX_GOOD_FIX_AGE_MS) {
						return;
					}
				} else {
					lastGoodFixElapsedMs = now;
				}
				
				adaptIntervalToSpeed(location);

				if (USE_KALMAN_FILTER) {
					if (kalmanFilter == null) {
						kalmanFilter = new KalmanFilter(location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.getTime());
					}
					
					kalmanFilter.predict();
					
					// **MODIFIED:** Conditional update logic
					if (location.hasSpeed() && location.getSpeed() >= MIN_SPEED_FOR_VELOCITY_UPDATE && location.hasBearing()) {
						// If we have a reliable speed and bearing, use the advanced update
						kalmanFilter.update(location.getLatitude(), location.getLongitude(), location.getSpeed(), location.getBearing(), location.getAccuracy());
					} else {
						// Otherwise, fall back to the position-only update
						kalmanFilter.update(location.getLatitude(), location.getLongitude(), location.getAccuracy());
					}
					
					Location smoothedLocation = kalmanFilter.getFilteredLocation();
					notifyListeners(smoothedLocation);
				}
				else {
					notifyListeners(location);
				}
			}
		}
	};
	
	public UserLocationManager(Context context, long updateIntervalMs, long fastestIntervalMs) {
		this.fusedClient = LocationServices.getFusedLocationProviderClient(context);
		// Tuned for a pedestrian whose position changes as they walk:
		//  - HIGH_ACCURACY -> GPS/GNSS fixes (not coarse network location)
		//  - no waitForAccurateLocation: it holds back fixes until they're accurate, adding delay;
		//    MAX_ACCEPTABLE_ACCURACY already filters out rough fixes in the callback
		//  - maxUpdateAge 0 -> never deliver a stale/cached fix; always the newest reading
		//  - minUpdateInterval lets fixes arrive sooner than the nominal interval when moving
		this.baseIntervalMs = updateIntervalMs;
		this.baseMinIntervalMs = fastestIntervalMs;
		this.activeIntervalMs = updateIntervalMs;
		this.activeRequest = buildRequest(updateIntervalMs, fastestIntervalMs);
	}
	
	private static LocationRequest buildRequest(long intervalMs, long minIntervalMs) {
		return new LocationRequest.Builder(intervalMs)
				.setMinUpdateIntervalMillis(minIntervalMs)
				.setPriority(Priority.PRIORITY_HIGH_ACCURACY)
				.setGranularity(Granularity.GRANULARITY_FINE)
				.setWaitForAccurateLocation(false)
				.setMaxUpdateAgeMillis(0)
				.build();
	}
	
	// Runs on the main looper (the callback's looper), as does the re-request it triggers.
	@SuppressLint("MissingPermission")
	private void adaptIntervalToSpeed(Location location) {
		// No speed reading tells us nothing about how fast the user is moving: leave the interval alone.
		if (!location.hasSpeed()) return;
		float speed = location.getSpeed();
		float avgSpeed = hasPreviousSpeed ? (previousSpeed + speed) / 2f : speed;
		previousSpeed = speed;
		hasPreviousSpeed = true;
		
		// Aim for roughly one fix per TARGET_FIX_SPACING_M of travel, rounded to whole seconds so
		// GPS speed noise doesn't re-issue the request on every fix.
		long maxIntervalMs = baseIntervalMs * MAX_INTERVAL_FACTOR;
		long wantedMs = maxIntervalMs;
		if (avgSpeed > 0.01f) {
			wantedMs = Math.round(TARGET_FIX_SPACING_M / avgSpeed) * 1000L;
		}
		wantedMs = Math.max(baseIntervalMs, Math.min(maxIntervalMs, wantedMs));
		
		if (wantedMs != activeIntervalMs && running) {
			activeIntervalMs = wantedMs;
			// The fastest allowed rate scales with the interval (2s/1s up to 6s/3s by default).
			long minMs = baseMinIntervalMs * wantedMs / baseIntervalMs;
			activeRequest = buildRequest(wantedMs, minMs);
			// Same callback: this replaces the previous request rather than adding a second one.
			fusedClient.requestLocationUpdates(activeRequest, locationCallback, Looper.getMainLooper());
		}
	}
	
	@SuppressLint("MissingPermission") // caller must check permission
	public void addListener(UserLocationListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
			// Request a fresh high-accuracy fix rather than getLastLocation(), which can be
			// minutes old and would place a walking user well behind their real position.
			CurrentLocationRequest request = new CurrentLocationRequest.Builder()
					.setPriority(Priority.PRIORITY_HIGH_ACCURACY)
					.setMaxUpdateAgeMillis(0)
					.build();
			fusedClient.getCurrentLocation(request, null)
					.addOnSuccessListener(location -> {
						if (location != null) {
							listener.onLocationUpdated(location);
						}
					});
		}
		if (!running && !listeners.isEmpty()) {
			start();
		}
	}
	
	public void removeListener(UserLocationListener listener) {
		listeners.remove(listener);
		if (listeners.isEmpty() && running) {
			stop();
		}
	}
	
	@SuppressLint("MissingPermission")
	private synchronized void start() {
		if (!running) {
			// Start at the fastest cadence until we've seen the user's speed.
			activeIntervalMs = baseIntervalMs;
			activeRequest = buildRequest(baseIntervalMs, baseMinIntervalMs);
			hasPreviousSpeed = false;
			fusedClient.requestLocationUpdates(activeRequest, locationCallback, Looper.getMainLooper());
			running = true;
		}
	}
	
	private synchronized void stop() {
		if (running) {
			fusedClient.removeLocationUpdates(locationCallback);
			running = false;
		}
	}
	
	private void notifyListeners(Location location) {
		for (UserLocationListener listener : listeners) {
			listener.onLocationUpdated(location);
		}
	}
}