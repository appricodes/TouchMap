package com.wikiponder.touchmap.classes;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class TimerLogger {
	
	private static final String TAG = "TimerLogger";
	private static final Map<String, Long> timers = new HashMap<>();
	
	/**
	 * Start or reset a timer.
	 *
	 * @param timerName The name of the timer
	 */
	public static void startTimer(String timerName) {
		timers.put(timerName, System.nanoTime());
		Log.d(TAG, "Started timer: " + timerName);
	}
	
	/**
	 * Log elapsed time for a timer since it was started.
	 *
	 * @param timerName      The name of the timer
	 * @param checkpointName A label for this checkpoint
	 */
	public static void logTime(String timerName, String checkpointName) {
		Long startTime = timers.get(timerName);
		if (startTime == null) {
			Log.w(TAG, "Timer '" + timerName + "' not started!");
			return;
		}
		
		long now = System.nanoTime();
		long elapsedMicros = (now - startTime) / 1000; // microseconds
		Log.d(TAG, "Timer [" + timerName + "] at checkpoint '" +
				checkpointName + "': " + (elapsedMicros/1000.0) + " ms");
	}
}
