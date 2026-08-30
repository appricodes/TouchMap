package com.wikiponder.touchmap.classes.models;

import java.util.List;

/**
 * A simple container class that holds the pre-calculated,
 * tile-local coordinates (in meters) for a single drawable way (e.g., a street).
 */
public class DrawableWay {
	public final List<MyPoint> points;
	
	public DrawableWay(List<MyPoint> points) {
		this.points = points;
	}
}