package com.wikiponder.touchmap.classes.location;

import android.location.Location;

public class KalmanFilter {
	
	private long lastTimestamp;
	private final double qMetresPerSecond;
	
	private double[] x; // State vector [lat, lon, vel_lat, vel_lon]
	private double[][] P; // Covariance matrix
	
	public KalmanFilter(double initialLatitude, double initialLongitude, float accuracy, long timestamp) {
		x = new double[]{initialLatitude, initialLongitude, 0, 0};
		P = new double[][]{
				{accuracy, 0, 0, 0},
				{0, accuracy, 0, 0},
				{0, 0, 1, 0},
				{0, 0, 0, 1}
		};
		this.lastTimestamp = timestamp;
		this.qMetresPerSecond = 1.0;
	}
	
	public void predict() {
		long now = System.currentTimeMillis();
		double dt = (now - lastTimestamp) / 1000.0;
		if (dt < 0) dt = 0;
		lastTimestamp = now;
		
		x[0] += x[2] * dt;
		x[1] += x[3] * dt;
		
		double q_dt = qMetresPerSecond * dt;
		P[0][0] += q_dt;
		P[1][1] += q_dt;
		P[2][2] += q_dt;
		P[3][3] += q_dt;
	}
	
	/**
	 * Updates the filter state using only position measurements.
	 * @param lat Measured latitude.
	 * @param lon Measured longitude.
	 * @param accuracy The accuracy of the measurement.
	 */
	public void update(double lat, double lon, float accuracy) {
		double[] y = {lat - x[0], lon - x[1]};
		
		double s00 = P[0][0] + accuracy;
		double s11 = P[1][1] + accuracy;
		
		double k00 = P[0][0] / s00;
		double k11 = P[1][1] / s11;
		double k20 = P[2][0] / s00;
		double k31 = P[3][1] / s11;
		
		x[0] += k00 * y[0];
		x[1] += k11 * y[1];
		x[2] += k20 * y[0];
		x[3] += k31 * y[1];
		
		P[0][0] *= (1 - k00);
		P[1][1] *= (1 - k11);
	}
	
	/**
	 * Updates the filter state using both position and velocity measurements.
	 * @param lat Measured latitude.
	 * @param lon Measured longitude.
	 * @param speed Measured speed in m/s.
	 * @param bearing Measured bearing in degrees.
	 * @param accuracy The accuracy of the measurement.
	 */
	public void update(double lat, double lon, float speed, float bearing, float accuracy) {
		final double R = 6371000;
		double bearingRad = Math.toRadians(bearing);
		
		// Convert speed and bearing to velocity components in degrees/sec
		double velN = speed * Math.cos(bearingRad); // m/s North
		double velE = speed * Math.sin(bearingRad); // m/s East
		
		double latRad = Math.toRadians(lat);
		double velLat = velN / (R * Math.PI / 180.0);
		double velLon = velE / ((R * Math.PI / 180.0) * Math.cos(latRad));
		
		// Full 4-dimensional measurement update
		double[] z = {lat, lon, velLat, velLon};
		double[] y = {z[0] - x[0], z[1] - x[1], z[2] - x[2], z[3] - x[3]};
		
		// For simplicity, we assume velocity measurements have a fixed uncertainty.
		// A more complex model could use speedAccuracy if available.
		float velocityUncertainty = 1.0f;
		
		double s00 = P[0][0] + accuracy;
		double s11 = P[1][1] + accuracy;
		double s22 = P[2][2] + velocityUncertainty;
		double s33 = P[3][3] + velocityUncertainty;
		
		double k00 = P[0][0] / s00;
		double k11 = P[1][1] / s11;
		double k22 = P[2][2] / s22;
		double k33 = P[3][3] / s33;
		
		x[0] += k00 * y[0];
		x[1] += k11 * y[1];
		x[2] += k22 * y[2];
		x[3] += k33 * y[3];
		
		P[0][0] *= (1 - k00);
		P[1][1] *= (1 - k11);
		P[2][2] *= (1 - k22);
		P[3][3] *= (1 - k33);
	}
	
	
	public Location getFilteredLocation() {
		Location loc = new Location("KalmanFilter");
		loc.setLatitude(x[0]);
		loc.setLongitude(x[1]);
		
		final double R = 6371000;
		double latRad = Math.toRadians(x[0]);
		double velN = x[2] * (R * Math.PI / 180.0);
		double velE = x[3] * (R * Math.PI / 180.0) * Math.cos(latRad);
		
		float speed = (float) Math.sqrt(velN * velN + velE * velE);
		float bearing = (float) (Math.toDegrees(Math.atan2(velE, velN)) + 360) % 360;
		
		loc.setSpeed(speed);
		loc.setBearing(bearing);
		loc.setAccuracy((float) Math.sqrt(P[0][0] + P[1][1]));
		loc.setTime(System.currentTimeMillis());
		
		return loc;
	}
}