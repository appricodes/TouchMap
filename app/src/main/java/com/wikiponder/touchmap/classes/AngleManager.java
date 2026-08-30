package com.wikiponder.touchmap.classes;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AngleManager
 * Computes tilt-independent azimuth, pitch, and roll of the device.
 * Azimuth: direction of top edge (USB → top)
 * Pitch: angle between top vector and horizontal plane
 * Roll: angle between left-right vector and horizontal plane
 */
public class AngleManager implements SensorEventListener {
	
	private final SensorManager sensorManager;
	private final Sensor rotationSensor;
	private final WindowManager windowManager; // **NEW**
	
	private final CopyOnWriteArrayList<AngleListener> listeners = new CopyOnWriteArrayList<>();
	private final Handler handler = new Handler(Looper.getMainLooper());
	
	private final long updatePeriodMs;
	
	// Circular averaging for azimuth
	private double sumSin = 0.0, sumCos = 0.0;
	private int azimuthCount = 0;
	
	// Linear buffers for pitch and roll
	private final List<Float> pitchBuffer = new ArrayList<>();
	private final List<Float> rollBuffer = new ArrayList<>();
	private final Object bufferLock = new Object();
	
	private volatile boolean running = false;
	
	private final Runnable computeTask = new Runnable() {
		@Override
		public void run() {
			computeAndNotify();
			if (running) {
				handler.postDelayed(this, updatePeriodMs);
			}
		}
	};
	
	public AngleManager(Context context, long updatePeriodMs) {
		this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		this.rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
		this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE); // **NEW**
		this.updatePeriodMs = updatePeriodMs;
	}
	
	/** Add listener and auto-start if first listener */
	public void addListener(AngleListener listener) {
		if (!listeners.contains(listener)) listeners.add(listener);
		if (!running && !listeners.isEmpty()) start();
	}
	
	/** Remove listener and auto-stop if no listeners */
	public void removeListener(AngleListener listener) {
		listeners.remove(listener);
		if (listeners.isEmpty() && running) stop();
	}
	
	private synchronized void start() {
		if (rotationSensor != null && !running) {
			sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
			handler.postDelayed(computeTask, updatePeriodMs);
			running = true;
		}
	}
	
	private synchronized void stop() {
		if (running) {
			sensorManager.unregisterListener(this);
			handler.removeCallbacks(computeTask);
			synchronized (bufferLock) {
				pitchBuffer.clear();
				rollBuffer.clear();
			}
			sumSin = sumCos = 0.0;
			azimuthCount = 0;
			running = false;
		}
	}
	
	@Override
	public void onSensorChanged(SensorEvent event) {
		if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
		
		float[] R = new float[9];
		SensorManager.getRotationMatrixFromVector(R, event.values);

		// **NEW: Remap coordinate system to match screen rotation**
		final int screenRotation = windowManager.getDefaultDisplay().getRotation();
		float[] adjustedR = new float[9];
		switch (screenRotation) {
			case Surface.ROTATION_0:
				adjustedR = R.clone();
				break;
			case Surface.ROTATION_90:
				SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjustedR);
				break;
			case Surface.ROTATION_180:
				SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjustedR);
				break;
			case Surface.ROTATION_270:
				SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjustedR);
				break;
		}
		// From now on, use 'adjustedR' instead of 'R'

		// Phone top vector (USB → top)
		float fxTop = adjustedR[1]; // X (East)
		float fyTop = adjustedR[4]; // Y (North)
		float fzTop = adjustedR[7]; // Z (Up)
		
		// Phone left-right vector (left → right)
		float fxRight = adjustedR[0]; // X (East)
		float fyRight = adjustedR[3]; // Y (North)
		float fzRight = adjustedR[6]; // Z (Up)
		
		// --- Azimuth: horizontal projection of top vector ---
		float normH = (float) Math.sqrt(fxTop * fxTop + fyTop * fyTop);
		if (normH < 1e-6f) return;
		float fxh = fxTop / normH;
		float fyh = fyTop / normH;
		
		float azimuthRad = (float) Math.atan2(fxh, fyh);
		float azimuthDeg = (float) Math.toDegrees(azimuthRad);
		if (azimuthDeg < 0) azimuthDeg += 360f;

		// --- Pitch: angle between top vector and horizontal plane ---
		float pitchRad = (float) Math.asin(fzTop);
		float pitchDeg = (float) Math.toDegrees(pitchRad);

		// --- Roll: angle between left-right vector and horizontal plane ---
		float rollRad = (float) Math.asin(fzRight);
		float rollDeg = (float) Math.toDegrees(rollRad);
		
		// Save values for averaging
		synchronized (this) {
			sumSin += Math.sin(Math.toRadians(azimuthDeg));
			sumCos += Math.cos(Math.toRadians(azimuthDeg));
			azimuthCount++;
		}
		synchronized (bufferLock) {
			pitchBuffer.add(pitchDeg);
			rollBuffer.add(rollDeg);
		}
	}
	
	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// not used
	}
	
	private void computeAndNotify() {
		float avgAzimuthDeg;
		float avgPitchDeg;
		float avgRollDeg;
		
		// Circular mean for azimuth
		synchronized (this) {
			if (azimuthCount == 0) return;
			double meanRad = Math.atan2(sumSin / azimuthCount, sumCos / azimuthCount);
			avgAzimuthDeg = (float) Math.toDegrees(meanRad);
			if (avgAzimuthDeg < 0) avgAzimuthDeg += 360f;
			sumSin = sumCos = 0.0;
			azimuthCount = 0;
		}
		
		// Linear mean for pitch & roll
		synchronized (bufferLock) {
			if (pitchBuffer.isEmpty() || rollBuffer.isEmpty()) return;
			
			float sumPitch = 0f, sumRoll = 0f;
			for (float p : pitchBuffer) sumPitch += p;
			for (float r : rollBuffer) sumRoll += r;
			
			avgPitchDeg = sumPitch / pitchBuffer.size();
			avgRollDeg = sumRoll / rollBuffer.size();
			
			pitchBuffer.clear();
			rollBuffer.clear();
		}
		
		// Notify listeners
		for (AngleListener listener : listeners) {
			listener.onAverageAnglesComputed(avgAzimuthDeg, avgPitchDeg, avgRollDeg);
		}
	}
}