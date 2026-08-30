package com.wikiponder.touchmap.classes.models;

// A simple model class to represent a node
public class Node {
	private final long id;
	private final String name;
	private final String details;
	private final int categoryId;
	private final double lat;
	private final double lon;
	private float distance; // Distance in meters from the user's location
	
	public Node(long id, String name, String details, int categoryId, double lat, double lon) {
		this.id = id;
		this.name = name;
		this.details = details;
		this.categoryId = categoryId;
		this.lat = lat;
		this.lon = lon;
		this.distance = 0; // Will be calculated later
	}
	
	// --- Getters ---
	public long getId() { return id; }
	public String getName() { return name; }
	public String getDetails() { return details; }
	public int getCategoryId() { return categoryId; }
	public float getDistance() { return distance; }
	
	// --- Setter ---
	public void setDistance(float distance) { this.distance = distance; }
	
	// Helper method to get a human-readable category name
	public String getCategoryName() {
		return "";
	}
}
