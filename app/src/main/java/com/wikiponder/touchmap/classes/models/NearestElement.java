package com.wikiponder.touchmap.classes.models;

public class NearestElement {
	public double distance;
	public MapElement element;
	public double bearing;
	public double speed;
	public double weightedDistance; // consider movement direction
	
	public NearestElement(double distance, MapElement element) {
		this(distance, distance, 0.0, 0.0, element);
	}
	
	public NearestElement(double distance, double weightedDistance, double bearing, double speed, MapElement element) {
		this.distance = distance;
		this.weightedDistance = weightedDistance;
		this.bearing = bearing;
		this.speed = speed;
		this.element = element;
	}
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof NearestElement)) return false;
		NearestElement other = (NearestElement) o;
		return this.element.id == other.element.id;
	}
	
	@Override
	public int hashCode() {
		return element.hashCode();
	}
	
}
