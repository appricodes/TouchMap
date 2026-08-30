package com.wikiponder.touchmap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.classes.AngleListener;
import com.wikiponder.touchmap.classes.interfaces.UserLocationListener;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.NearestElement;

import java.util.List;

/**
 * BackgroundService
 * Runs while the app is in foreground. For API 26+ it uses a Foreground Service.
 */
public class BackgroundService extends Service
implements UserLocationListener, AngleListener {
	
	public static final String ACTION_STOP = "com.wikiponder.touchmap.action.STOP";
	private static final String TAG = "BackgroundService";
	private static final String CHANNEL_ID = "TouchMapBackgroundChannel";
	private static final float MAX_FLAT_PITCH = 20;
	// Beyond this, the map has been panned far enough from the user's real GPS position that
	// "nearby points around the user" (onLocationUpdated below) would be reporting real-world
	// surroundings the user isn't paying attention to (they're browsing a distant place on
	// screen) — see onLocationUpdated. Does NOT gate onAverageAnglesComputed's flat-device
	// "aiming" path: pointing the phone at something is always a deliberate action, whether at an
	// explicitly aimed element (announceAiming) or whatever's directly ahead
	// (getNearestElementAhead/announceNearestPoint), so it keeps working regardless of this
	// distance — only the passive walking-proximity chatter is suppressed.
	private static final float MAP_USER_DISTANCE_THRESHOLD_METERS = 1000;
	private float azimuth = 0;
	private float lastProcessedAzimuth = 0;
	private float pitch = 90;
	private Location userLocation = null;
	private NearestElement lastNearestElement;
	private NearestElement lastNearestElementInFront;
	private long lastNodeAnnouncementTime;
	private Location lastAnnouncedLocation;
	private long lastAzimuthAnnouncementTime;
	private boolean isFlat;
	
	@Override
	public void onCreate() {
		super.onCreate();
		// Create notification channel (required for foreground service on API 26+)
		NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID,
				"TouchMap Background Service",
				NotificationManager.IMPORTANCE_LOW
		);
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (manager != null) {
			manager.createNotificationChannel(channel);
		}
		azimuth = 0;
		pitch = 0;
		
		// Register as a location listener
		MyApplication.getUserLocationManager(this)
				.addListener(this);
		
		// Register as an angle listener
		MyApplication.getAngleManager(this)
				.addListener(this);
		
	}
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// Always start as a foreground service (required on API 26+)
		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(R.string.notif_background_title))
				//.setContentText(getString(R.string.notif_background_text))
				.setSmallIcon(R.drawable.ic_launcher_foreground) // replace with your app icon
				.build();
		startForeground(1, notification);
		
		if (intent != null && intent.getAction() != null) {
			String action = intent.getAction();
			
			if (ACTION_STOP.equals(action)) {
				stopAnnouncements();
			}
		} else {
			// Service started without an intent — just idle or keep alive
			Log.d(TAG, "Service started without intent (keep-alive mode).");
		}
		
		return START_NOT_STICKY; // Do not restart if killed
	}
	
	
	@Override
	public void onTaskRemoved(Intent rootIntent) {
		super.onTaskRemoved(rootIntent);
		// The user swiped the app away from Recents. Treat this the same as explicitly closing
		// the map: stop announcing/vibrating immediately. ImportService is a separate service and
		// is unaffected, so an in-progress map import keeps running.
		stopAnnouncements();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(TAG, "OnDestroy()");
		// Safety net in case the service is torn down without going through stopAnnouncements()
		// (e.g. the system kills it directly). Idempotent if already removed.
		MyApplication.getUserLocationManager(this).removeListener(this);
		MyApplication.getAngleManager(this).removeListener(this);
	}

	/**
	 * Stops announcements/vibration immediately and shuts the service down. Unregistering the
	 * angle listener here (not just in onDestroy) matters: AngleManager holds a strong reference
	 * to this service in its listener list, so until removed, the compass sensor keeps calling
	 * onAverageAnglesComputed() on this instance — which included unconditional vibration calls
	 * (announceFlatDevice) — even after stopSelf() was requested.
	 */
	private void stopAnnouncements() {
		MyApplication.getAnnouncementManager(this).pause();
		MyApplication.getUserLocationManager(this).removeListener(this);
		MyApplication.getAngleManager(this).removeListener(this);
		stopForeground(true);
		stopSelf();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null; // Not a bound service
	}
	
	@Override
	public void onLocationUpdated(Location location) {
		userLocation = location;

		// Keep MapTileManager's announcement cache centered on the device's live GPS position —
		// independent of wherever the map view (if MapActivity is open) happens to be panned to.
		// Cheap to call on every fix: it no-ops once the area is already loaded. Requested
		// unconditionally, before the preference/pitch checks below, since onAverageAnglesComputed's
		// "nearest point ahead" announcement also depends on this cache regardless of those settings.
		MyApplication.getMapTileManager(this).requestLoadingAnnounceArea(location);

		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (!sharedPreferences.getBoolean("announcement_proximity_movement", true))
			return;
		
		// if the device is horizontal, nearest point  will be processed in onAverageAnglesComputed
		if (Math.abs(pitch) < MAX_FLAT_PITCH)
			return;

		// The map has been panned far from the user's real position (browsing a distant place,
		// not the user's actual surroundings) — suppress passive proximity announcements about
		// wherever the user physically is, since that's not what's on screen right now. A null
		// map center (MapActivity never opened this session, e.g. after a reboot) means no pan has
		// happened, so nothing to suppress.
		Location mapCenter = MyApplication.getMapTileManager(this).getLastCenter();
		if (mapCenter != null && mapCenter.distanceTo(location) > MAP_USER_DISTANCE_THRESHOLD_METERS)
			return;

		// find nearest element regardless of user direction
		// Get the user's bearing. Use GPS bearing if moving, otherwise fallback to compass.
		Double userBearing = (location.hasBearing() && location.getSpeed() > 0.2) ? (double) location.getBearing() : (double) azimuth;
		
		// Get a list of the nearest elements
		List<NearestElement> nearestElements = MyApplication.getMapTileManager(this).getNearestElements(location, 3);
		
		// Let the AnnouncementManager decide which one to announce, providing the bearing
		MyApplication.getAnnouncementManager(this).announceNearestElement(location, nearestElements, userBearing);
		
	}
	
	@Override
	public void onAverageAnglesComputed(float avgAzimuth, float avgPitch, float avgRoll) {
		this.azimuth = avgAzimuth;
		this.pitch = avgPitch;

		// Compass events can arrive before the first GPS fix (onLocationUpdated hasn't run yet).
		if (userLocation == null) return;

		if (Math.abs(pitch) >= MAX_FLAT_PITCH) {
			lastProcessedAzimuth = 1000;
			if (isFlat)
				MyApplication.getAnnouncementManager(this).announceFlatDevice(false);
			isFlat = false;
			return;
		}
		
		if (!isFlat) {
			MyApplication.getAnnouncementManager(this).announceFlatDevice(true);
			isFlat = true;
			// do not announce anything for a short while
			lastAzimuthAnnouncementTime = System.currentTimeMillis() + 1500;
		}
		
		// is aiming enabled
		MapElement element =  MyApplication.getMapTileManager(this).getAimedElement();
		if (element != null) {
			double distance = userLocation.distanceTo(element.getLocation());
			double bearing = userLocation.bearingTo(element.getLocation()) - azimuth;
			MyApplication.getAnnouncementManager(this).announceAiming(element, distance, bearing);
			return;
		}
		
		
		// announcement intervals are related to delta azimuth
		double deltaAzimuth = Math.abs(azimuth - lastProcessedAzimuth);
		int interval = (int)(30000 / (deltaAzimuth+ 2));
		if (deltaAzimuth <2 || System.currentTimeMillis() - lastAzimuthAnnouncementTime < interval)
			return;
		
		lastAzimuthAnnouncementTime = System.currentTimeMillis();
		lastProcessedAzimuth = azimuth;
		NearestElement ne;
		
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (sharedPreferences.getBoolean("announcement_poi_direction", true)) {
			if (Math.abs(avgRoll) < 50 || !sharedPreferences.getBoolean("announcement_include_ways", false))
				ne = MyApplication.getMapTileManager(this).getNearestElementAhead(userLocation, azimuth, 3, Math.cos(3.14 / 6 * 0.5), false);
			else
				ne = MyApplication.getMapTileManager(this).getNearestElementAhead(userLocation, azimuth, 3, Math.cos(3.14 / 6 * 0.5), true);
			
			if (ne.element != null)
				MyApplication.getAnnouncementManager(this).announceNearestPoint(ne, false, true);
		}
	}
}