package com.wikiponder.touchmap.classes.ui;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.location.Location;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.classes.Contract;
import com.wikiponder.touchmap.classes.MapTileManager;
import com.wikiponder.touchmap.classes.TimerLogger;
import com.wikiponder.touchmap.classes.Utils;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.MyPoint;
import com.wikiponder.touchmap.classes.models.NearestElement;
import com.wikiponder.touchmap.classes.models.NodeRecord;
import com.wikiponder.touchmap.classes.models.WayRecord;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MapView extends View {
	
	private static final String TAG = "MapView";
	private static final float INITIAL_MAP_WIDTH_METERS = 200f;
	private static final float MAX_ZOOM = 4.0f;
	private static final float MIN_ZOOM = 1 / 8f;
	// Labels (street names, POI names) only draw once zoomed in this far — below it the map is too
	// zoomed out for text to be legible, and would just be visual noise over the lines/dots.
	private static final float LABEL_MIN_ZOOM = 1.0f;

	// Way "type" strings (see WayRecord.type / tools/build_global_cluster_dbs.py's get_way_type,
	// which this grouping must stay in sync with) grouped into the road classes paintForWay() styles
	// distinctly. Kept as static Sets rather than a chain of equals() so paintForWay reads as a
	// classification table, not a pile of if/else.
	private static final Set<String> MAJOR_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
			"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"));
	private static final Set<String> MID_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
			"secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
			"residential", "living_street", "road"));
	private static final Set<String> PEDESTRIAN_WAY_TYPES = new HashSet<>(Arrays.asList(
			"pedestrian", "footway", "path", "cycleway", "steps", "corridor", "via_ferrata", "busway"));

	// --- paints for showing map ---
	// Street/way name labels — drawn along the road's own path via Canvas.drawTextOnPath, so they
	// follow the road's orientation instead of always sitting flat.
	private final Paint wayLabelFillPaint;
	private final Paint wayLabelStrokePaint; // halo so text stays legible over any way colour/background
	// Point-of-interest name labels — drawn beside the dot, same fill/halo approach.
	private final Paint nodeLabelFillPaint;
	private final Paint nodeLabelStrokePaint;
	private final Paint nodeFillPaint; // fallback node colour (also CATEGORY_ADDRESS/CATEGORY_NONE)
	private final Paint nodeBorderPaint; // default (non-favorite) node border
	private final Paint favoriteBorderPaint; // overrides nodeBorderPaint for favorited points
	// Per-category node fill colours (HEALTH/TRAFFIC/BARRIER/SHOP/AMENITY/LEISURE/TOURISM/NATURAL/
	// INTERSECTION/MANUAL) — see buildNodeCategoryPaints(). Falls back to nodeFillPaint for anything
	// not in the table (CATEGORY_ADDRESS, CATEGORY_NONE, or any future category not yet styled).
	private final SparseArray<Paint> nodeCategoryFillPaints;
	private final Paint userFillPaint;
	// Way styling by road class — distinct colour/width/dash pattern per class so a glance at the map
	// distinguishes a motorway from a footpath from a building outline, not just "line vs dot".
	private final Paint wayPaint; // fallback for any way type not otherwise recognized
	private final Paint highwayMajorPaint; // motorway/trunk/primary (+ _link)
	private final Paint highwayMidPaint; // secondary/tertiary/unclassified/residential/living_street/road
	private final Paint highwayMinorPaint; // service roads
	private final Paint pathPaint; // pedestrian/footway/path/cycleway/steps/corridor/via_ferrata/busway
	private final Paint barrierWayPaint; // fences, walls, etc. traced as ways (distinct from barrier nodes)
	private final Paint platformPaint; // public transport platforms / tram stops
	private final Paint buildingFillPaint; // building=yes footprints — filled polygons, not just outlines
	private final Paint buildingBorderPaint;
	private final Context context;
	
	// --- Map State ---
	private Location userLocation = new Location("");
	private Location centerLocation = new Location("");
	private Location centerLocationBuffer = new Location("");
	
	private float metersPerPixel = 1.0f;
	private float currentZoom = 1.0f;
	private float mapRotation = 0f; // Rotation in degrees
	private float mapRotationBuffer = 0f;
	
	// --- Touch Handling ---
	private MyPoint lastTouchPoint = new MyPoint();
	private boolean isTouching = false;
	// Last tile for which "no map here" was announced, so dragging within it doesn't repeat.
	private Point lastAnnouncedMissingTile = null;
	// Debounce for isTileImported() (a synchronous db query): handleTab runs on every touch-move/
	// hover-move frame, and without this it re-queries the db every single frame while exploring or
	// dragging across empty space (water, gaps between points) — competing with MapTileManager's own
	// cache-reload queries for the small SQLite connection pool and, since this runs on the main
	// thread, delaying whatever else is queued on it (including BackgroundService's GPS/compass
	// callbacks, which is why that could show up as announcements going briefly quiet right after
	// scrolling the map). Only re-queried when the touched tile actually changes.
	private Point lastCheckedImportedTile = null;
	private boolean lastCheckedImportedResult = false;
	
	private UpdateListener updateListener;
	private boolean isCenteredOnUser = true;
	private boolean compassLock = true;
	private float compassRotation = 0f;
	private float tilt = 0f;
	
	/** A projected way (line or polygon), cached at updateDrawingCache time — the geometry never
	 * changes per-frame, only whether/how it's styled and labeled does (see paintForWay). */
	private static final class WayShape {
		final Path path;
		final String type;
		final int catId;
		final String name;
		WayShape(Path path, String type, int catId, String name) {
			this.path = path;
			this.type = type;
			this.catId = catId;
			this.name = name;
		}
	}

	/** A projected point feature (real POI, manually-added point, or world-place gazetteer
	 * stand-in), cached at updateDrawingCache time. */
	private static final class NodePoint {
		final float x, y;
		final int catId;
		final boolean favorite;
		final String name;
		NodePoint(float x, float y, int catId, boolean favorite, String name) {
			this.x = x;
			this.y = y;
			this.catId = catId;
			this.favorite = favorite;
			this.name = name;
		}
	}

	// --- Drawing Cache for Performance ---
	private final List<WayShape> cachedWayShapes = new ArrayList<>();
	// Real POIs and world-places gazetteer stand-ins alike (see OSMDatabaseHelper.isWorldPlaceCategory
	// at draw time for the distinct-styling split); they never coexist in the same loaded area anyway.
	private final List<NodePoint> cachedNodePoints = new ArrayList<>();
	// The loaded-nodes snapshot the render cache was last built from — used to detect new data.
	private List<NodeRecord> lastDrawnNodes = null;

	private long lastDrawingCacheTime;

	// --- Optional graphical rendering (off by default; for low-vision users) ---
	private boolean renderEnabled = false;
	private final Paint backgroundPaint;
	private final Paint worldPlaceFillPaint;
	private final Paint worldPlaceBorderPaint;
	
	
	public interface UpdateListener {
		void onElementSelected(MapElement element, Location userLocation, double mapRotation, double distance);
		void onMapMessage(String message);
		void onTouchStart(float meterPerPixel);
		void onTouchStop();
		/** The touched location's tile has no imported map data (and no world-place was near enough). */
		void onUndownloadedAreaTouched(Location location);
	}
	
	public MapView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		this.context = context;
		setClickable(true);
		
		// set paints
		this.wayLabelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.wayLabelFillPaint.setStyle(Paint.Style.FILL);
		this.wayLabelFillPaint.setColor(Color.parseColor("#1A1A1A"));
		this.wayLabelFillPaint.setTextSize(26);
		this.wayLabelFillPaint.setTextAlign(Paint.Align.LEFT);

		this.wayLabelStrokePaint = new Paint(wayLabelFillPaint);
		this.wayLabelStrokePaint.setStyle(Paint.Style.STROKE);
		this.wayLabelStrokePaint.setStrokeWidth(5);
		this.wayLabelStrokePaint.setColor(Color.parseColor("#E0F4F1EA")); // AARRGGBB — background colour halo
		this.wayLabelStrokePaint.setStrokeJoin(Paint.Join.ROUND);

		this.nodeLabelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.nodeLabelFillPaint.setStyle(Paint.Style.FILL);
		this.nodeLabelFillPaint.setColor(Color.parseColor("#1A1A1A"));
		this.nodeLabelFillPaint.setTextSize(26);
		this.nodeLabelFillPaint.setTextAlign(Paint.Align.LEFT);

		this.nodeLabelStrokePaint = new Paint(nodeLabelFillPaint);
		this.nodeLabelStrokePaint.setStyle(Paint.Style.STROKE);
		this.nodeLabelStrokePaint.setStrokeWidth(5);
		this.nodeLabelStrokePaint.setColor(Color.WHITE);
		this.nodeLabelStrokePaint.setStrokeJoin(Paint.Join.ROUND);

		this.nodeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.nodeFillPaint.setStyle(Paint.Style.FILL);
		this.nodeFillPaint.setColor(Color.parseColor("#D7263D"));

		this.nodeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.nodeBorderPaint.setStyle(Paint.Style.STROKE);
		this.nodeBorderPaint.setColor(Color.BLACK);
		this.nodeBorderPaint.setStrokeWidth(4f);

		// Favorited points keep their category colour but get a gold border instead of black, so
		// they stand out from the rest of their own category too, not just from other categories.
		this.favoriteBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.favoriteBorderPaint.setStyle(Paint.Style.STROKE);
		this.favoriteBorderPaint.setColor(Color.parseColor("#D69E2E"));
		this.favoriteBorderPaint.setStrokeWidth(5f);

		this.nodeCategoryFillPaints = buildNodeCategoryPaints();

		this.userFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.userFillPaint.setStyle(Paint.Style.FILL);
		this.userFillPaint.setColor(Color.parseColor("#0B6E4F"));

		// High-contrast way rendering for low-vision users: distinct colour/width/dash pattern per
		// road class (see paintForWay) rather than one line style for every way.
		this.wayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.wayPaint.setStyle(Paint.Style.STROKE);
		this.wayPaint.setColor(Color.parseColor("#102A43"));
		this.wayPaint.setStrokeWidth(8f);
		this.wayPaint.setStrokeJoin(Paint.Join.ROUND);
		this.wayPaint.setStrokeCap(Paint.Cap.ROUND);

		this.highwayMajorPaint = new Paint(wayPaint);
		this.highwayMajorPaint.setColor(Color.parseColor("#C2410C")); // burnt orange — motorway/trunk/primary
		this.highwayMajorPaint.setStrokeWidth(13f);

		this.highwayMidPaint = new Paint(wayPaint);
		this.highwayMidPaint.setColor(Color.parseColor("#334E68")); // slate — secondary/tertiary/residential
		this.highwayMidPaint.setStrokeWidth(9f);

		this.highwayMinorPaint = new Paint(wayPaint);
		this.highwayMinorPaint.setColor(Color.parseColor("#829AB1")); // light grey — service roads
		this.highwayMinorPaint.setStrokeWidth(6f);

		this.pathPaint = new Paint(wayPaint);
		this.pathPaint.setColor(Color.parseColor("#2F855A")); // green — pedestrian/footway/path/cycleway/steps
		this.pathPaint.setStrokeWidth(5f);
		this.pathPaint.setStrokeCap(Paint.Cap.ROUND);
		this.pathPaint.setPathEffect(new DashPathEffect(new float[]{14f, 12f}, 0f));

		this.barrierWayPaint = new Paint(wayPaint);
		this.barrierWayPaint.setColor(Color.parseColor("#9B2C2C")); // dark red — fences/walls traced as ways
		this.barrierWayPaint.setStrokeWidth(4f);
		this.barrierWayPaint.setPathEffect(new DashPathEffect(new float[]{4f, 6f}, 0f));

		this.platformPaint = new Paint(wayPaint);
		this.platformPaint.setColor(Color.parseColor("#00838F")); // teal — transit platforms/tram stops
		this.platformPaint.setStrokeWidth(6f);
		this.platformPaint.setPathEffect(new DashPathEffect(new float[]{10f, 6f}, 0f));

		this.buildingFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.buildingFillPaint.setStyle(Paint.Style.FILL);
		this.buildingFillPaint.setColor(Color.parseColor("#D9CBB8"));

		this.buildingBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.buildingBorderPaint.setStyle(Paint.Style.STROKE);
		this.buildingBorderPaint.setColor(Color.parseColor("#8C7B65"));
		this.buildingBorderPaint.setStrokeWidth(3f);

		this.backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.backgroundPaint.setStyle(Paint.Style.FILL);
		this.backgroundPaint.setColor(Color.parseColor("#F4F1EA")); // soft map background

		// World-place dots (gazetteer stand-ins where no map is imported yet): distinct blue,
		// smaller and thinner-bordered than real POI dots so the two are never confused.
		this.worldPlaceFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.worldPlaceFillPaint.setStyle(Paint.Style.FILL);
		this.worldPlaceFillPaint.setColor(Color.parseColor("#4A90D9"));

		this.worldPlaceBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.worldPlaceBorderPaint.setStyle(Paint.Style.STROKE);
		this.worldPlaceBorderPaint.setColor(Color.parseColor("#1B3A5C"));
		this.worldPlaceBorderPaint.setStrokeWidth(3f);
	}

	/** One fill-colour Paint per real-POI node category (OSMDatabaseHelper.CATEGORY_*), so a glance
	 * at the map tells a shop from a restaurant from a park without opening it. Anything not in this
	 * table (CATEGORY_ADDRESS, CATEGORY_NONE, or a future category) falls back to nodeFillPaint's
	 * plain red — see cachedNodeFillPaint(). */
	private static SparseArray<Paint> buildNodeCategoryPaints() {
		SparseArray<Paint> paints = new SparseArray<>();
		paints.put(OSMDatabaseHelper.CATEGORY_MANUAL, categoryPaint("#D69E2E")); // gold — always stands out
		paints.put(OSMDatabaseHelper.CATEGORY_HEALTH, categoryPaint("#E53E3E")); // red
		paints.put(OSMDatabaseHelper.CATEGORY_TRAFFIC, categoryPaint("#3182CE")); // blue
		paints.put(OSMDatabaseHelper.CATEGORY_BARRIER, categoryPaint("#8B6F47")); // brown
		paints.put(OSMDatabaseHelper.CATEGORY_SHOP, categoryPaint("#805AD5")); // purple
		paints.put(OSMDatabaseHelper.CATEGORY_AMENITY, categoryPaint("#DD6B20")); // orange
		paints.put(OSMDatabaseHelper.CATEGORY_LEISURE, categoryPaint("#38A169")); // green
		paints.put(OSMDatabaseHelper.CATEGORY_TOURISM, categoryPaint("#D53F8C")); // pink
		paints.put(OSMDatabaseHelper.CATEGORY_NATURAL, categoryPaint("#276749")); // dark green
		paints.put(OSMDatabaseHelper.CATEGORY_INTERSECTION, categoryPaint("#A0AEC0")); // pale grey — synthesized, low visual weight
		// Plain addr:* points (house numbers) are usually the single most numerous node category in
		// any downloaded area — in a dense residential block there can be dozens per screen. Giving
		// them the same bright red as the "nothing else matched" fallback would drown out every real
		// POI in a wall of dots, so they get their own muted, unobtrusive colour instead (see also the
		// smaller radius for this category in onDraw).
		paints.put(OSMDatabaseHelper.CATEGORY_ADDRESS, categoryPaint("#B7A99A"));
		return paints;
	}

	private static Paint categoryPaint(String hexColor) {
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		p.setStyle(Paint.Style.FILL);
		p.setColor(Color.parseColor(hexColor));
		return p;
	}

	/** Enables or disables graphical rendering of the map (off = touch/sound only). */
	public void setRenderEnabled(boolean enabled) {
		this.renderEnabled = enabled;
		if (enabled) {
			updateDrawingCache();
		}
		invalidate();
	}
	
	public void setUserLocation(Location location) {
		if (location == null) return;
		
		this.userLocation = location;
		
		if (isCenteredOnUser) {
			setCenterLocation(this.userLocation, true);
		}
	}
	
	
	public void setCenterLocation(Location centerLocation, boolean isCentered) {
		this.centerLocationBuffer = centerLocation;
		if (!isTouching) {
			copycenterLocationBuffer();
		}
		this.isCenteredOnUser = isCentered;
	}
	
	private void copycenterLocationBuffer() {
		centerLocation = centerLocationBuffer;
		updateMapView();
		updateDrawingCache();
		invalidate();
	}
	
	private void copyMapRotationBuffer() {
		mapRotation = mapRotationBuffer;
	}
	
	private void updateMapView() {
		double width = metersPerPixel * getWidth();
		double height = metersPerPixel * getHeight();
		MyApplication.getMapTileManager(context).requestLoadingMap(centerLocation, width, height);
	}
	
	public void setCompassLock(boolean compassLock) {
		this.compassLock = compassLock;
		this.mapRotation = (compassLock) ? compassRotation : 0;
		this.mapRotationBuffer = (compassLock) ? compassRotation : 0;
	}
	
	public Location getCenterLocation() {
		return centerLocation;
	}
	
	public void setOnUpdateListener(UpdateListener listener) {
		this.updateListener = listener;
	}
	
	public void setCompassRotation(float rotation, float tilt) {
		this.compassRotation = rotation;
		this.tilt = tilt;
		
		if (compassLock) {
			mapRotationBuffer = rotation;
			if (!isTouching) {
				mapRotation = mapRotationBuffer;
				updateDrawingCache();
				invalidate();
			}
		}
	}
	
	public void zoomIn() {
		currentZoom = Math.min(currentZoom * 2, MAX_ZOOM);
		updateScale();
		updateDrawingCache();
		invalidate();
		updateListener.onMapMessage(String.format(
				Locale.US,
				"Zoomed in to %d meters",
				(int) (metersPerPixel * getWidth())));
	}
	
	public void zoomOut() {
		currentZoom = Math.max(currentZoom / 2, MIN_ZOOM);
		updateScale();
		updateMapView();
		updateDrawingCache();
		invalidate();
		updateListener.onMapMessage(String.format(
				Locale.US,
				"Zoomed out to %d meters",
				(int) (metersPerPixel * getWidth())));
	}
	
	public void recenter() {
		setCenterLocation(userLocation, true);
	}
	
	@Override
	protected void onSizeChanged(int w, int h, int oldW, int oldH) {
		super.onSizeChanged(w, h, oldW, oldH);
		updateScale();
	}
	
	private void updateScale() {
		if (getWidth() == 0)
			return;
		metersPerPixel = (INITIAL_MAP_WIDTH_METERS / currentZoom) / getWidth();
	}
	
	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);
		// When rendering is disabled the map is explored by touch and sound only.
		if (!renderEnabled)
			return;

		MapTileManager mapTileManager = MyApplication.getMapTileManager(context);
		List<NodeRecord> currentNodes = (mapTileManager != null) ? mapTileManager.getLoadedNodes() : null;

		// New data loaded since the last cache build? Rebuild the screen-projection cache.
		if (currentNodes != lastDrawnNodes) {
			updateDrawingCache();
		}

		// Background
		canvas.drawColor(backgroundPaint.getColor());

		boolean showLabels = currentZoom >= LABEL_MIN_ZOOM;

		// Ways — buildings draw as filled polygons (their geometry is a closed ring in OSM data);
		// everything else draws as a styled line, the style keyed off its road/feature class so a
		// motorway, a footpath, and a fence never look alike. See paintForWay().
		for (WayShape shape : cachedWayShapes) {
			if (isBuildingWay(shape)) {
				canvas.drawPath(shape.path, buildingFillPaint);
				canvas.drawPath(shape.path, buildingBorderPaint);
				continue;
			}
			Paint linePaint = paintForWay(shape);
			canvas.drawPath(shape.path, linePaint);
			if (showLabels && isLabelableWay(shape) && shape.name != null && !shape.name.isEmpty()) {
				canvas.drawTextOnPath(shape.name, shape.path, 0, -6f, wayLabelStrokePaint);
				canvas.drawTextOnPath(shape.name, shape.path, 0, -6f, wayLabelFillPaint);
			}
		}

		// Nodes (points of interest) as bordered dots, coloured by category (see
		// buildNodeCategoryPaints) so a shop, a restaurant, and a park are visually distinct at a
		// glance. World-place gazetteer stand-ins (areas with no imported map yet), plain addresses,
		// and synthesized intersections all render smaller than real POIs — the first so they're
		// never mistaken for a real imported point, the latter two because they're typically the most
		// numerous dots on screen by far and shouldn't visually compete with an actual shop/amenity/
		// etc. Favorited points keep their category colour but swap to a gold border.
		float radius = 9f;
		float worldPlaceRadius = 6f;
		float lowEmphasisRadius = 5f; // addresses, intersections
		for (NodePoint p : cachedNodePoints) {
			boolean isWorldPlace = OSMDatabaseHelper.isWorldPlaceCategory(p.catId);
			boolean isLowEmphasis = p.catId == OSMDatabaseHelper.CATEGORY_ADDRESS
					|| p.catId == OSMDatabaseHelper.CATEGORY_INTERSECTION;
			float r = isWorldPlace ? worldPlaceRadius : (isLowEmphasis ? lowEmphasisRadius : radius);
			Paint fill = isWorldPlace ? worldPlaceFillPaint : nodeCategoryFillPaints.get(p.catId, nodeFillPaint);
			Paint border = isWorldPlace ? worldPlaceBorderPaint : (p.favorite ? favoriteBorderPaint : nodeBorderPaint);
			canvas.drawCircle(p.x, p.y, r, fill);
			canvas.drawCircle(p.x, p.y, r, border);
			if (showLabels && isLabelableNode(p) && p.name != null && !p.name.isEmpty()) {
				float labelX = p.x + r + 4f;
				canvas.drawText(p.name, labelX, p.y + 8f, nodeLabelStrokePaint);
				canvas.drawText(p.name, labelX, p.y + 8f, nodeLabelFillPaint);
			}
		}

		// User marker at the screen centre, pointing "up" (the map rotates around the user)
		drawUserMarker(canvas);
	}

	/** True for building=yes footprints (WayRecord.type == "address") — these are filled as
	 * polygons rather than drawn as an outline stroke, since OSM building ways are closed rings. */
	private static boolean isBuildingWay(WayShape shape) {
		return "address".equals(shape.type);
	}

	/** Real roads/paths are worth labeling with their street name; barriers, transit platforms, and
	 * buildings (labeled with the building fill instead, not text) are not. */
	private static boolean isLabelableWay(WayShape shape) {
		String type = shape.type;
		return type != null && (MAJOR_HIGHWAY_TYPES.contains(type) || MID_HIGHWAY_TYPES.contains(type)
				|| PEDESTRIAN_WAY_TYPES.contains(type) || "service".equals(type));
	}

	/** Real, named POIs are worth labeling; raw addresses and synthesized intersections are too
	 * dense/uninteresting to label, and world-place stand-ins get their own zoomed-out UI instead. */
	private static boolean isLabelableNode(NodePoint p) {
		return p.catId != OSMDatabaseHelper.CATEGORY_ADDRESS
				&& p.catId != OSMDatabaseHelper.CATEGORY_INTERSECTION
				&& !OSMDatabaseHelper.isWorldPlaceCategory(p.catId);
	}

	/** Picks the line style for a way by its road/feature class (see WayRecord.type /
	 * tools/build_global_cluster_dbs.py's get_way_type) — buildings are handled separately in
	 * onDraw as filled polygons, never reach here. */
	private Paint paintForWay(WayShape shape) {
		String type = shape.type;
		if (type == null) return wayPaint;
		if (type.startsWith("barrier:")) return barrierWayPaint;
		if ("platform".equals(type) || "tram_stop".equals(type)) return platformPaint;
		if (PEDESTRIAN_WAY_TYPES.contains(type)) return pathPaint;
		if (MAJOR_HIGHWAY_TYPES.contains(type)) return highwayMajorPaint;
		if (MID_HIGHWAY_TYPES.contains(type)) return highwayMidPaint;
		if ("service".equals(type)) return highwayMinorPaint;
		return wayPaint;
	}

	private void drawUserMarker(Canvas canvas) {
		float cx = getWidth() / 2f;
		float cy = getHeight() / 2f;
		float r = 22f;
		Path marker = new Path();
		marker.moveTo(cx, cy - r);            // tip (forward)
		marker.lineTo(cx - r * 0.7f, cy + r * 0.8f);
		marker.lineTo(cx, cy + r * 0.4f);
		marker.lineTo(cx + r * 0.7f, cy + r * 0.8f);
		marker.close();
		canvas.drawPath(marker, userFillPaint);
		canvas.drawPath(marker, nodeBorderPaint);
	}
	
	private void updateDrawingCache() {
		// Only maintain the render cache when the map is actually being drawn.
		if (!renderEnabled) return;
		if (System.currentTimeMillis() - lastDrawingCacheTime < 400) return;
		lastDrawingCacheTime = System.currentTimeMillis();
		TimerLogger.startTimer("updateDrawingCache");
		cachedWayShapes.clear();
		cachedNodePoints.clear();

		MapTileManager mapTileManager = MyApplication.getMapTileManager(context);
		if (mapTileManager == null) return;

		List<NodeRecord> nodes = mapTileManager.getLoadedNodes();
		List<WayRecord> ways = mapTileManager.getLoadedWays();
		lastDrawnNodes = nodes;

		float screenCenterX = getWidth() / 2f;
		float screenCenterY = getHeight() / 2f;
		double centerLat = centerLocation.getLatitude();
		double centerLon = centerLocation.getLongitude();
		double cosLat = Math.cos(Math.toRadians(centerLat));

		// Loaded-cache padding (see MapTileManager.LOAD_PADDING_METERS) is deliberately bigger than
		// the visible viewport, so skip anything clearly off-screen before projecting it — cheap
		// bbox-vs-circle check first, safe regardless of map rotation (uses the half-diagonal as the
		// radius, a superset of the rotated rectangle for any rotation angle).
		double screenRadiusM = Math.sqrt(getWidth() * (double) getWidth() + getHeight() * (double) getHeight())
				* metersPerPixel / 2.0 * 1.05;

		// Ways: full absolute-coordinate geometry projected straight to screen — no tile frames.
		// Building rings (type == "address") are closed so they fill correctly as polygons; every
		// other way is left open and drawn as a stroked line (see isBuildingWay/paintForWay).
		for (WayRecord w : ways) {
			double minEast = metersEastOf(w.minLon, centerLon, cosLat);
			double maxEast = metersEastOf(w.maxLon, centerLon, cosLat);
			double minNorth = metersNorthOf(w.minLat, centerLat);
			double maxNorth = metersNorthOf(w.maxLat, centerLat);
			if (maxEast < -screenRadiusM || minEast > screenRadiusM
					|| maxNorth < -screenRadiusM || minNorth > screenRadiusM) continue;
			Path path = new Path();
			for (int i = 0; i < w.lats.length; i++) {
				MyPoint sp = project(w.lats[i], w.lons[i], centerLat, centerLon, cosLat, screenCenterX, screenCenterY);
				if (i == 0) path.moveTo(sp.x, sp.y);
				else path.lineTo(sp.x, sp.y);
			}
			if (path.isEmpty()) continue;
			if ("address".equals(w.type)) path.close();
			cachedWayShapes.add(new WayShape(path, w.type, w.catId, w.name));
		}

		// Point features — real POIs, or (when the area has no imported data at all) world-place
		// gazetteer stand-ins that MapTileManager already merged into loadedNodes; catId (in the
		// CATEGORY_WORLD_* range for the latter) drives the distinct styling in onDraw.
		for (NodeRecord nd : nodes) {
			double east = metersEastOf(nd.lon, centerLon, cosLat);
			double north = metersNorthOf(nd.lat, centerLat);
			if (Math.abs(east) > screenRadiusM || Math.abs(north) > screenRadiusM) continue;
			MyPoint sp = project(nd.lat, nd.lon, centerLat, centerLon, cosLat, screenCenterX, screenCenterY);
			cachedNodePoints.add(new NodePoint(sp.x, sp.y, nd.catId, nd.favorite, nd.name));
		}
		TimerLogger.logTime("updateDrawingCache", "Finished");
	}

	private static double metersEastOf(double lon, double centerLon, double cosLat) {
		return Math.toRadians(lon - centerLon) * cosLat * Contract.EARTH_RADIUS;
	}

	private static double metersNorthOf(double lat, double centerLat) {
		return Math.toRadians(lat - centerLat) * Contract.EARTH_RADIUS;
	}

	/** Projects an absolute lat/lon to a screen point (equirectangular around the map centre + rotation). */
	private MyPoint project(double lat, double lon, double centerLat, double centerLon, double cosLat,
							float screenCenterX, float screenCenterY) {
		float east = (float) (Math.toRadians(lon - centerLon) * cosLat * Contract.EARTH_RADIUS);
		float north = (float) (Math.toRadians(lat - centerLat) * Contract.EARTH_RADIUS);
		return new MyPoint(east / metersPerPixel, -north / metersPerPixel)
				.rotate(mapRotation)
				.add(new MyPoint(screenCenterX, screenCenterY));
	}

	@Override
	public boolean dispatchHoverEvent(MotionEvent event) {
		final int action = event.getAction();
		switch (action) {
			case MotionEvent.ACTION_HOVER_ENTER:
			case MotionEvent.ACTION_HOVER_MOVE:
				isTouching = true;
				if (updateListener != null)
					updateListener.onTouchStart(getWidth() * metersPerPixel);
				handleTab(event.getX(), event.getY());
				break;
			case MotionEvent.ACTION_HOVER_EXIT:
				isTouching = false;
				lastAnnouncedMissingTile = null;
				// Bounded to one touch session: a stale "not imported" result must never survive past
				// it, since a download can finish (see MapTileManager.reloadCurrentArea) while the user
				// isn't actively touching, and the next touch session on that same tile must re-check.
				lastCheckedImportedTile = null;
				copyMapRotationBuffer();
				copycenterLocationBuffer();
				if (updateListener != null)
					updateListener.onTouchStop();
				break;
		}
		return super.dispatchHoverEvent(event);
	}
	
	@Override
	public boolean performClick() {
		return super.performClick();
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		int pointerCount = event.getPointerCount();
		switch (action) {
			case MotionEvent.ACTION_DOWN:
			case MotionEvent.ACTION_POINTER_DOWN:
				isTouching = true;
				if (updateListener != null)
					updateListener.onTouchStart(getWidth() * metersPerPixel);
				if (isTalkBackEnabled() && pointerCount == 1) {
					updateListener.onMapMessage("Scrolling");
					lastTouchPoint = new MyPoint(event.getX(), -event.getY());
				}
				break;
			case MotionEvent.ACTION_MOVE:
				if (!isTalkBackEnabled() && pointerCount == 1) {
					handleTab(event.getX(), event.getY());
					return true;
				}
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				isTouching = false;
				lastAnnouncedMissingTile = null;
				lastCheckedImportedTile = null; // see the ACTION_HOVER_EXIT case for why this resets here too
				// Missing here (unlike the ACTION_HOVER_EXIT case below) meant AnnouncementManager.isTouching
				// — set true by onTouchStart on ACTION_DOWN above — never got reset back to false for any
				// gesture that goes through onTouchEvent rather than dispatchHoverEvent (a two-finger pan
				// with TalkBack off, or — the reported case — a one-finger pan gesture with TalkBack on,
				// which Android delivers as raw touch rather than hover). announceNearestPoint/announceAiming
				// both bail out while isTouching is true, so a single pan gesture silently and permanently
				// suppressed announcements until the next hover-based touch (e.g. touching a point) happened
				// to reset it via onTouchStop there. ACTION_CANCEL resets the same way (a gesture the system
				// aborted mid-touch must not leave this stuck either) but must not fall into the pan-commit
				// logic below, which is why that block still checks for ACTION_UP specifically.
				if (updateListener != null)
					updateListener.onTouchStop();
				copyMapRotationBuffer();
				copycenterLocationBuffer();
				if (action == MotionEvent.ACTION_UP
						&& ((isTalkBackEnabled() && pointerCount == 1) || (!isTalkBackEnabled() && pointerCount == 2))) {
					MyPoint currentTouchPoint = new MyPoint(event.getX(), -event.getY());
					MyPoint transferInMeters = lastTouchPoint
							.subtract(currentTouchPoint)
							.rotate(-mapRotation)
							.multiply(metersPerPixel);
					double scaleX = Math.toDegrees(1/ Contract.EARTH_RADIUS/Math.cos(Math.toRadians(centerLocation.getLatitude())));
					double scaleY = Math.toDegrees(1/ Contract.EARTH_RADIUS);
					
					double deltaLat = transferInMeters.y * scaleY;
					double deltaLon = transferInMeters.x * scaleX;
					
					centerLocation.setLatitude(centerLocation.getLatitude() + deltaLat);
					centerLocation.setLongitude(centerLocation.getLongitude() + deltaLon);
					
					int d = (int)Math.sqrt(transferInMeters.x * transferInMeters.x + transferInMeters.y * transferInMeters.y);
					updateListener.onMapMessage("Moved " + d + "meters");
					isCenteredOnUser = false;
					updateMapView();
					invalidate();
					return true;
				}
				break;
		}
		return super.onTouchEvent(event);
	}
	
	private void handleTab(float touchX, float touchY) {
		MyPoint deltaInMeters = new MyPoint(touchX, -touchY)
				.add(new MyPoint(-getWidth()/2, getHeight()/2))
				.rotate(-mapRotation)
				.multiply(metersPerPixel);
		double scaleX = Math.toDegrees(1/ Contract.EARTH_RADIUS/Math.cos(Math.toRadians(centerLocation.getLatitude())));
		double scaleY = Math.toDegrees(1/ Contract.EARTH_RADIUS);
		
		double deltaLat = deltaInMeters.y * scaleY;
		double deltaLon = deltaInMeters.x * scaleX;
		
		Location touchLocation = new Location("");
		touchLocation.setLatitude(centerLocation.getLatitude() + deltaLat);
		touchLocation.setLongitude(centerLocation.getLongitude() + deltaLon);
		
		NearestElement ne = MyApplication.getMapTileManager(context).getNearestElement(touchLocation, currentZoom, true);
		
		double userDistance = (userLocation!= null) ? userLocation.distanceTo(touchLocation) : Double.MAX_VALUE;
		double  maxAllowedDistance = getWidth() * 0.15 * metersPerPixel;
		
		double minDistance = Math.min(userDistance, ne.distance);
		if (minDistance > maxAllowedDistance || updateListener == null) {
			// Nothing real (or world-place) found in range: if this specific tile has no imported
			// map data at all, let the user know they can download it — once per tile visited, so
			// dragging across a big empty area doesn't repeat the announcement. isTileImported() is a
			// db query, so it's only actually re-run when the touched tile changes (see
			// lastCheckedImportedTile) rather than on every single touch-move frame this branch is hit.
			if (updateListener != null) {
				int[] idx = Utils.latLonToIndex(touchLocation.getLatitude(), touchLocation.getLongitude());
				Point tile = new Point(idx[0], idx[1]);
				boolean imported;
				if (tile.equals(lastCheckedImportedTile)) {
					imported = lastCheckedImportedResult;
				} else {
					imported = MyApplication.getMapTileManager(context).isTileImported(touchLocation);
					lastCheckedImportedTile = tile;
					lastCheckedImportedResult = imported;
				}
				if (!imported && !tile.equals(lastAnnouncedMissingTile)) {
					lastAnnouncedMissingTile = tile;
					updateListener.onUndownloadedAreaTouched(touchLocation);
				}
			}
			return;
		}
		lastAnnouncedMissingTile = null; // touched something real; reset so re-entering empty space announces again

		if (minDistance == ne.distance) {
			updateListener.onElementSelected(ne.element, userLocation, mapRotation, minDistance);
			return;
		}
		if (minDistance == userDistance ) {
			updateListener.onMapMessage("You");
			updateListener.onTouchStop();
			return;
		}
	}
	
	private boolean isTalkBackEnabled() {
		AccessibilityManager accessibilityManager =
				(AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
		
		if (accessibilityManager == null) {
			return false;
		}
		
		List<AccessibilityServiceInfo> enabledServices =
				accessibilityManager.getEnabledAccessibilityServiceList(
						AccessibilityServiceInfo.FEEDBACK_SPOKEN);
		
		return enabledServices != null && !enabledServices.isEmpty();
	}
}