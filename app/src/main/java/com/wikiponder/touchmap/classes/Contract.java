package com.wikiponder.touchmap.classes;

public class Contract {
	public static final double EARTH_RADIUS = 6378137.0;
	public static final double TILE_SIZE_METERS = 1000.0;

	// Coarse cell size for the normalized nodes/ways spatial index (query accelerant only — never a
	// storage/clipping unit). Reuses the same equirectangular projection as TILE_SIZE_METERS, just
	// coarser, so most features land in exactly one cell and only large ones span several.
	public static final double CELL_SIZE_METERS = 8000.0;
}
