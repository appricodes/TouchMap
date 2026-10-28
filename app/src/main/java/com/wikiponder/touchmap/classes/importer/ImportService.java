package com.wikiponder.touchmap.classes.importer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.Utils;

/**
 * Foreground service that runs a {@link BoxImporter} so a large map download/import survives the
 * screen turning off, the download screen being closed, or MapActivity being closed/swiped from
 * Recents. Progress is shown in a notification and mirrored into {@link ImportState} for the UI.
 *
 * <p>Deliberately does not override {@code onTaskRemoved}: Android's default behavior keeps a
 * started service alive after its app's task is removed, which is exactly what's wanted here — an
 * in-progress import must keep running independently of MapActivity/BackgroundService's lifecycle.
 */
public class ImportService extends Service {
	private static final String TAG = "ImportService";
	public static final String ACTION_START = "com.wikiponder.touchmap.action.IMPORT_START";
	public static final String ACTION_CANCEL = "com.wikiponder.touchmap.action.IMPORT_CANCEL";
	/** The location to resolve into a downloadable box (see BoxManifest) — required. */
	public static final String EXTRA_TARGET_LAT = "target_lat";
	public static final String EXTRA_TARGET_LON = "target_lon";
	/** Display-only label for the notification (e.g. "this area") — optional. */
	public static final String EXTRA_NAME = "name";
	/** "Larger map" option — also downloads the west/east/north/south neighbor boxes. Optional,
	 * defaults to false. */
	public static final String EXTRA_INCLUDE_NEIGHBORS = "include_neighbors";

	private static final String CHANNEL_ID = "TouchMapImportChannel";
	private static final int NOTIF_ID = 42;

	private Cancellable activeImporter;
	private Thread worker;
	private volatile boolean active;
	private long lastNotifUpdate = 0;
	// Held only while an import runs: this device has been observed force-freezing/killing this
	// foreground service once the screen locks (Samsung Device Care), despite FOREGROUND_SERVICE
	// normally being enough to keep a service's process alive under Doze. A partial wake lock keeps
	// the CPU awake for the duration of the import regardless of screen/battery-management state.
	private PowerManager.WakeLock wakeLock;

	@Override
	public void onCreate() {
		super.onCreate();
		NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID, "Map import", NotificationManager.IMPORTANCE_LOW);
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (manager != null) manager.createNotificationChannel(channel);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
			if (activeImporter != null) {
				activeImporter.cancel();
			} else if (ImportState.get().isRunning()) {
				// No import actually running in THIS service instance — most likely the previous
				// instance was torn down (killed, or its own onDestroy already ran) without its worker
				// ever reaching finish(), leaving ImportState stuck reporting an import in progress
				// forever with nothing left to cancel. There's nothing to actually stop here, but the
				// UI (which only dismisses ImportProgressDialog on finish()) needs to be told the run
				// is over rather than wait on a service that no longer exists.
				ImportState.get().finish(false, getString(R.string.import_cancelled));
			}
			return START_NOT_STICKY;
		}
		if (active) {
			// An import is already running; ignore duplicate starts.
			return START_NOT_STICKY;
		}
		if (intent == null || !intent.hasExtra(EXTRA_TARGET_LAT) || !intent.hasExtra(EXTRA_TARGET_LON)) {
			stopSelf();
			return START_NOT_STICKY;
		}

		final double lat = intent.getDoubleExtra(EXTRA_TARGET_LAT, 0);
		final double lon = intent.getDoubleExtra(EXTRA_TARGET_LON, 0);
		final boolean includeNeighbors = intent.getBooleanExtra(EXTRA_INCLUDE_NEIGHBORS, false);
		final String name = intent.getStringExtra(EXTRA_NAME) != null
				? intent.getStringExtra(EXTRA_NAME) : getString(R.string.download_area_default_name);
		startBoxImport(lat, lon, includeNeighbors, name);
		return START_NOT_STICKY;
	}

	private void startBoxImport(double lat, double lon, boolean includeNeighbors, String name) {
		beginForegroundImport(name);
		final BoxImporter importer = new BoxImporter(this, new ProgressListener() {
			@Override
			public void onProgress(String phase, String stage, int percent) {
				reportProgress(phase, stage, percent);
			}

			@Override
			public void onComplete(int featuresWritten) {
				finish(true, Utils.fmt(ImportService.this, R.string.download_complete, featuresWritten));
			}

			@Override
			public void onError(String message) {
				finish(false, Utils.fmt(ImportService.this, R.string.download_failed, message));
			}
		});
		activeImporter = importer;

		worker = new Thread(() -> {
			importer.importForLocation(lat, lon, includeNeighbors);
			// If importForLocation returned without onComplete/onError (active still true), it was
			// cancelled — stop the foreground service so it doesn't leak.
			if (active) {
				finish(false, getString(R.string.import_cancelled));
			}
		}, "box-import");
		worker.start();
	}

	private void beginForegroundImport(String name) {
		String startingPhase = getString(R.string.import_notif_starting_phase);
		startForegroundNotification(startingPhase, Utils.fmt(ImportService.this, R.string.import_notif_starting, name), -1);
		ImportState.get().start(name, startingPhase);
		active = true;
		acquireWakeLock();
	}

	private void reportProgress(String phase, String stage, int percent) {
		ImportState.get().progress(phase, stage, percent);
		long now = System.currentTimeMillis();
		if (now - lastNotifUpdate > 1000) {
			lastNotifUpdate = now;
			updateNotification(phase, stage, percent);
		}
	}

	private void finish(boolean success, String message) {
		active = false;
		ImportState.get().finish(success, message);
		showFinalNotification(message);
		// STOP_FOREGROUND_REMOVE (not DETACH) so the ongoing progress notification — with its now-
		// dead Cancel action — actually disappears instead of being left frozen on its last state
		// forever. The completion/failure notification posted above (a different id) is unaffected.
		stopForeground(STOP_FOREGROUND_REMOVE);
		releaseWakeLock();
		stopSelf();
	}

	private void acquireWakeLock() {
		if (wakeLock != null && wakeLock.isHeld()) return;
		PowerManager pm = getSystemService(PowerManager.class);
		if (pm == null) return;
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TouchMap:import");
		// Safety timeout well beyond any realistic import — a stuck/never-finished import must not
		// hold the CPU awake forever if something goes wrong in cleanup.
		wakeLock.acquire(60 * 60 * 1000L);
	}

	private void releaseWakeLock() {
		if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
		wakeLock = null;
	}

	private void startForegroundNotification(String phase, String text, int percent) {
		Notification n = buildNotification(phase, text, percent, true);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
		} else {
			startForeground(NOTIF_ID, n);
		}
	}

	private void updateNotification(String phase, String text, int percent) {
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (manager != null) manager.notify(NOTIF_ID, buildNotification(phase, text, percent, true));
	}

	private void showFinalNotification(String text) {
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (manager != null) manager.notify(NOTIF_ID + 1, buildNotification(null, text, 100, false));
	}

	private Notification buildNotification(String phase, String text, int percent, boolean ongoing) {
		// percent is one continuous 0-100 across the WHOLE multi-box run (see BoxImporter's class
		// doc), not a per-phase cycle, so it rises monotonically through this notification's whole
		// lifetime rather than resetting every time the phase name below changes. The title still
		// names the *current pipeline phase* (connecting / downloading / extracting / saving)
		// alongside it — a user glancing at the notification (or hearing it via TalkBack, which
		// doesn't read the progress bar) otherwise can't tell what's actually happening right now.
		String title;
		if (ongoing && phase != null && !phase.isEmpty()) {
			title = percent >= 0
					? Utils.fmt(ImportService.this, R.string.import_notif_title_phase_percent, phase, percent)
					: phase;
		} else {
			title = getString(R.string.import_notif_title);
		}
		NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(title)
				.setContentText(text)
				.setSmallIcon(R.drawable.ic_map_download)
				.setOnlyAlertOnce(true)
				// Ongoing: pinned while the import runs (FLAG_ONGOING_EVENT), so it cannot be
				// swiped away — the user must explicitly cancel or wait for completion. The
				// completion/failure notification (a different id) is a normal, dismissible one.
				.setOngoing(ongoing);
		if (ongoing) {
			b.setCategory(NotificationCompat.CATEGORY_PROGRESS);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
			}
			if (percent < 0) b.setProgress(0, 0, true);
			else b.setProgress(100, percent, false);
			// Cancel-download action button on the progress notification.
			Intent cancelIntent = new Intent(this, ImportService.class).setAction(ACTION_CANCEL);
			PendingIntent cancelPending = PendingIntent.getService(this, 0, cancelIntent,
					PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
			b.addAction(R.drawable.ic_map_more, getString(R.string.download_cancel_button), cancelPending);
		}
		return b.build();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (activeImporter != null) activeImporter.cancel();
		// If this instance's own finish() already ran (the normal success/failure/cancel path),
		// `active` is already false and ImportState.finish() has already fired. But the system can
		// tear this service down (process death, an OEM's aggressive background-app freezing, etc.)
		// while the "box-import" worker thread is still stuck deep in a non-interruptible step (see
		// BoxImporter's cancelled checks) and never gets to call finish() itself — leaving ImportState
		// permanently stuck reporting an import in progress, with a dialog nothing would ever dismiss.
		// Settle it here too so the UI isn't left waiting on a service that no longer exists.
		if (active) {
			active = false;
			ImportState.get().finish(false, getString(R.string.import_cancelled));
		}
		releaseWakeLock();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
