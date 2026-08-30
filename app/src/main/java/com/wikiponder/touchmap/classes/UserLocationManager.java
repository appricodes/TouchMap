package com.wikiponder.touchmap.classes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Looper;

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
	private final LocationRequest locationRequest;
	
	private boolean running = false;
	private KalmanFilter kalmanFilter;
	
	private final LocationCallback locationCallback = new LocationCallback() {
		@Override
		public void onLocationResult(LocationResult locationResult) {
			if (locationResult == null) return;
			Location location = locationResult.getLastLocation();
			if (location != null) {
				if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCEPTABLE_ACCURACY) {
					return;
				}
				
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
		//  - waitForAccurateLocation -> the first fix isn't a rough estimate
		//  - maxUpdateAge 0 -> never deliver a stale/cached fix; always the newest reading
		//  - minUpdateInterval lets fixes arrive sooner than the nominal interval when moving
		this.locationRequest = new LocationRequest.Builder(updateIntervalMs)
				.setMinUpdateIntervalMillis(fastestIntervalMs)
				.setPriority(Priority.PRIORITY_HIGH_ACCURACY)
				.setGranularity(Granularity.GRANULARITY_FINE)
				.setWaitForAccurateLocation(true)
				.setMaxUpdateAgeMillis(0)
				.build();
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
			fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
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