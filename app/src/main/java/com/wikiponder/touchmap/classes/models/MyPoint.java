package com.wikiponder.touchmap.classes.models;
import android.graphics.Point;
import android.location.Location;

import androidx.annotation.NonNull;

import java.util.Locale;

public class MyPoint {
	public float x;
	public float y;
	
	// Earth's mean radius in meters
	private static final float EARTH_RADIUS = 6378137.0F;
	
	public MyPoint() {
		this(0f, 0f);
	}
	
	public MyPoint(float x, float y) {
		this.x = x;
		this.y = y;
	}
	
	public MyPoint(Point point) {
		this.x = point.x;
		this.y = point.y;
	}
	
	public MyPoint add(MyPoint other) {
		return new MyPoint(this.x + other.x, this.y + other.y);
	}
	
	public MyPoint subtract(MyPoint other) {
		return new MyPoint(this.x - other.x, this.y - other.y);
	}
	public MyPoint multiply(float f) {
		return this.multiply(f, f);
	}
	
	public MyPoint multiply(float fx, float fy) {
		return new MyPoint(this.x * fx, this.y * fy);
	}
	public MyPoint rotate(float angleDegrees) {
		return this.rotate(new MyPoint(), angleDegrees);
	}
	public MyPoint rotate(MyPoint center, float angleDegrees) {
		float dx = this.x - center.x;
		float dy = this.y - center.y;
		double radians = Math.toRadians(angleDegrees);
		float cos = (float) Math.cos(radians);
		float sin = (float) Math.sin(radians);
		float newX = dx * cos - dy * sin;
		float newY = dx * sin + dy * cos;
		return new MyPoint(newX + center.x, newY + center.y);
	}
	
	/**
	 * Calculates the straight-line distance (in meters) between this point
	 * and another point in the same coordinate system.
	 *
	 * @param other the other point
	 * @return distance in meters
	 */
	public float distanceTo(MyPoint other) {
		float dx = this.x - other.x;
		float dy = this.y - other.y;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}
	public static MyPoint fromLocation(Location location) {
		double latitudeDeg = (float) location.getLatitude();
		double longitudeDeg = (float) location.getLongitude();
		return fromGeographic(latitudeDeg, longitudeDeg);
	}
	public static MyPoint fromGeographic(double latitudeDeg, double longitudeDeg) {
		double latRad = Math.toRadians(latitudeDeg);
		double lonRad = Math.toRadians(longitudeDeg);
		float x = (float) (EARTH_RADIUS * lonRad * Math.cos(latRad));
		float y = (float) (EARTH_RADIUS * latRad);
		return new MyPoint(x, y);
	}
	
	/**
	 * Converts this Point (x,y in meters) into an Android Location.
	 *
	 * @return Location instance with latitude and longitude set
	 */
	public Location toLocation() {
		String provider = "";
		// inverse of fromGeographic:
		double latRad = this.y / EARTH_RADIUS;
		double latitude = Math.toDegrees(latRad);
		
		double lonRad = this.x / (EARTH_RADIUS * Math.cos(latRad));
		double longitude = Math.toDegrees(lonRad);
		
		Location loc = new Location(provider);
		loc.setLatitude(latitude);
		loc.setLongitude(longitude);
		return loc;
	}
	
	@NonNull
	@Override
	public String toString() {
		return String.format(Locale.US, "Point(%.3f, %.3f)", x, y);
	}
	
	/**
	 * Computes the signed area * 2 of the triangle (a, b, c).
	 */
	private float signedArea2(MyPoint a, MyPoint b, MyPoint c) {
		return (b.x - a.x) * (c.y - a.y)
				- (b.y - a.y) * (c.x - a.x);
	}
	
	/**
	 * Returns true if p is inside the triangle (a, b, c).
	 * Allows points on the edge.
	 */
	private boolean isPointInTriangle(MyPoint a, MyPoint b, MyPoint c) {
		float areaPBC = signedArea2(this, b, c);
		float areaAPC = signedArea2(a, this, c);
		float areaABP = signedArea2(a, b, this);
		
		boolean hasSameSign = (areaPBC >= 0 && areaAPC >= 0 && areaABP >= 0)
				|| (areaPBC <= 0 && areaAPC <= 0 && areaABP <= 0);
		
		return hasSameSign;
	}
	
	/**
	 * Returns true if point p lies inside the convex quadrilateral
	 * defined by a → b → c → d (in order).
	 */
	public boolean isPointInQuadrilateral(MyPoint a, MyPoint b, MyPoint c, MyPoint d) {
		// Check triangles ABC and ACD
		return isPointInTriangle(a, b, c)
				|| isPointInTriangle(a, c, d);
	}
}
