package com.wikiponder.touchmap.classes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.wikiponder.touchmap.BuildConfig;

/**
 * Thin wrapper around Firebase Crashlytics — if a real user's app crashes, the stack trace (plus
 * device model/OS version, which Crashlytics attaches automatically) uploads to the Firebase
 * console for the touchmap-testlab project on the crashing user's next app launch, with no action
 * required from them. No manual exception-handler wiring is needed for the crash-capture itself:
 * the Crashlytics SDK installs its own {@code Thread.UncaughtExceptionHandler} (chained with
 * whatever was already set) as soon as it's on the classpath. This class only adds the small
 * amount of manual value beyond that default: recording non-fatal (caught) errors that would
 * otherwise vanish into a log no one reads, and a couple of custom keys so a report in the console
 * is easier to place (which build, TalkBack on/off).
 */
public final class CrashReporting {

	private CrashReporting() {}

	private static final String DEBUG_FORCE_CRASH_ACTION = "com.wikiponder.touchmap.DEBUG_FORCE_CRASH";

	/** SharedPreferences key of the Settings "Send crash reports" switch (default on). */
	public static final String PREF_KEY = "crash_reports";

	public static void init(Context context) {
		FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
		// The manifest turns automatic collection off (firebase_crashlytics_collection_enabled), so
		// nothing is sent until this honours the user's choice — see setCollectionEnabled.
		crashlytics.setCrashlyticsCollectionEnabled(PreferenceManager
				.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, true));
		crashlytics.setCustomKey("version_name", versionName(context));
		if (BuildConfig.DEBUG) {
			registerDebugCrashTrigger(context);
		}
	}

	/** Applies the Settings switch immediately. Turning it off also discards any report already
	 * queued on the device, so nothing recorded before the opt-out is uploaded afterwards. */
	public static void setCollectionEnabled(boolean enabled) {
		FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
		crashlytics.setCrashlyticsCollectionEnabled(enabled);
		if (!enabled) {
			crashlytics.deleteUnsentReports();
		}
	}

	/** Debug builds only: `adb shell am broadcast -a com.wikiponder.touchmap.DEBUG_FORCE_CRASH`
	 * throws immediately, so the whole capture -> next-launch-upload -> Firebase-console pipeline
	 * can be exercised on a real device without shipping a "Force crash" button to real users. */
	private static void registerDebugCrashTrigger(Context context) {
		Context appContext = context.getApplicationContext();
		BroadcastReceiver receiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context ctx, Intent intent) {
				throw new RuntimeException("Debug-triggered test crash (CrashReporting.DEBUG_FORCE_CRASH)");
			}
		};
		IntentFilter filter = new IntentFilter(DEBUG_FORCE_CRASH_ACTION);
		// The 3-arg overload (with an explicit exported/not-exported flag) only exists from API 33 —
		// minSdk here is 26, so this has to branch rather than always using it.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
		} else {
			appContext.registerReceiver(receiver, filter);
		}
	}

	/** Records a caught (non-fatal) exception so it still shows up in the Crashlytics console —
	 * for spots that currently catch-and-swallow an error that's worth knowing about remotely,
	 * without crashing the app outright. {@code context} is a short human label (e.g. "box import"),
	 * not an Android Context. */
	public static void reportNonFatal(String context, Throwable t) {
		FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
		crashlytics.log(context);
		crashlytics.recordException(t);
	}

	private static String versionName(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return info.versionName != null ? info.versionName : "unknown";
		} catch (PackageManager.NameNotFoundException e) {
			return "unknown";
		}
	}
}
