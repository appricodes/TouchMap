# TouchMap — Ideas backlog

Ideas for improving existing features and adding new ones, tailored to an accessible,
TalkBack-first map that blind and low-vision people explore by touch, sound, and by aiming the
phone. Grouped by theme and roughly ordered by value-to-effort within each group.

Legend: 🟢 quick win · 🟡 medium · 🔴 large · 🤖 uses AI/ML

---

## 1. Near-term plan (designed, not yet built)

### 1.1 Show world places in undownloaded areas 🟡
The `tools/build_world_places.py` database (cities/towns/villages + city sections + a few
landmarks, each pre-tagged with the app's tile index) is ready. Remaining app work:
- Ship `world_places.db` — **download on first run** (keeps the APK small; the DB can be
  refreshed without an app update). Store in `getFilesDir()`; open read-only.
- Add `WorldPlacesRepository` with `getPlacesInTiles(Set<tileX,tileY>)` and
  `nearest(lat,lon)` (indexed by `tile_x,tile_y` and `country,admin1,admin2`).
- In `MapTileManager.getNearestElement*` / `getNearestElements`, when a tile is **not** imported,
  fall back to world-places for that tile so touch/aim still announce "Paris, city, 2 km" etc.
- Render (when the map is shown) world-place dots in a distinct style from imported POIs.

### 1.2 Offer download for the area under the finger / search result 🟡
- When the user drags or searches to a location whose tile isn't imported, announce
  "No detailed map here — double-tap to download" and expose an action that opens
  `GeofabrikDownloadActivity`.
- Pre-select country + smallest sub-region using the world-places DB: `nearest(lat,lon)` →
  `country` + `admin1_name`/`admin2_name`, matched against Geofabrik region names (more reliable
  than the current reverse-geocode admin-area match, which depends on device locale). Pass the
  target lat/lon to the activity via intent extras.

---

## 2. Location, movement & orientation

- **Dead-reckoning between GPS fixes** 🟡 — fuse the rotation vector + step detector
  (`TYPE_STEP_DETECTOR`) to smoothly advance the user marker between 5 s fixes, so announcements
  of "approaching X" feel continuous while walking. Snap back to each real fix.
- **Confidence-aware announcements** 🟢 — when `location.getAccuracy()` is poor (e.g. >25 m),
  prefix announcements with "roughly" and soften the guidance; today a 40 m fix is treated the
  same as a 5 m one.
- **Auto North/Compass lock by motion** 🟢 — when speed > ~1 m/s, prefer GPS bearing; when
  standing still, prefer compass. Reduces jitter in the aiming feature.
- **Crossing & junction alerts** 🟡 — you already synthesize intersections (cat 62). Announce
  "junction ahead, 15 m: Main St and 2nd Ave" with a distinct earcon as the user approaches one.

## 3. Exploration & feedback (touch + audio)

- **Distinct earcons per category** 🟢 — shops, transport, crossings, water, parks each get a
  recognizable tone (extend `tools/generate_sounds.py`). Blind users learn the sound faster than
  a spoken category every time.
- **"Radar" sweep** 🟡 — a button that sweeps 12→11 o'clock… announcing the nearest feature in
  each direction with panned audio, giving a quick 360° mental map of the surroundings.
- **Breadcrumb trail** 🟡 — record where the user has walked; let them "retrace" with turn-by-turn
  spoken/haptic cues. Very useful for returning along an unfamiliar route.
- **Adjustable detail / decluttering** 🟢 — a single "detail level" control (already partly present
  via `detailsLevels`) surfaced as a slider: fewer, more important features when overwhelmed.
- **Haptic edge/boundary feedback** 🟢 — different vibration when the finger crosses a building
  outline, a road edge, or water, so shapes can be felt.

## 4. Search, routing & navigation

- **On-device geocoder over the world-places DB** 🟡 — search "Lyon" or "hospital near me" fully
  offline; today address search depends on the platform `Geocoder`.
- **Simple offline walking routing** 🔴 — build a pedestrian graph from imported way geometry
  (footway/path/crossing) and give spoken/haptic turn-by-turn guidance. This is the natural
  companion to an accessible map.
- **Favorites & recents as spoken shortcuts** 🟢 — quick actions like "take me to Home / Work".
- **Share & receive locations** 🟢 — accept `geo:` intents and shared coordinates so a sighted
  friend can send a destination.

## 5. Appearance & low-vision (in addition to what's done)

- **Themes: high-contrast light / dark / yellow-on-black** 🟢 — user-selectable palettes for the
  map and bars; respect system font scale everywhere.
- **Bigger, labelled POI markers with leader text** 🟡 — when the map is shown, draw the nearest
  few POIs with large text labels and a strong outline.
- **Colour-blind-safe category colours** 🟢 — pick the node/way palette from a CVD-safe set.
- **Zoom-to-fit gestures** 🟢 — pinch to zoom in the rendered map (currently button-only).

## 6. Data, offline & reliability

- **Manage downloaded regions** 🟡 — a screen listing imported areas with size and a delete
  button; show storage used. Today tiles accumulate with no UI to prune them.
- **Update / re-import** 🟢 — remember which Geofabrik region a tile came from and offer "update
  this area" when it's stale.
- **Import resumability & background service** 🟡 — run `OsmPbfImporter` in a foreground service
  with a progress notification so a large import survives the screen turning off.
- **Integrity check** 🟢 — validate the downloaded PBF (size / magic bytes) before importing.

## 7. AI / ML powered 🤖

- **Camera scene & obstacle description** 🤖🔴 — the project already bundles ML Kit
  (object detection, image labeling, text recognition). Add a "What's in front of me?" button that
  describes the scene, reads signs (OCR) and shop names aloud, and warns about obstacles. This is
  arguably the highest-impact AI feature for blind users.
- **Natural-language place search & Q&A** 🤖🟡 — "cafés with step-free access near me" or
  "which way to the nearest pharmacy?" answered from local OSM tags (wheelchair, tactile_paving,
  crossing) via an on-device or API LLM. The tag data is already imported.
- **Landmark-based guidance** 🤖🟡 — instead of "20 m at 2 o'clock", generate human phrasing:
  "the bakery is just past the bus stop on your right". An LLM turns nearby tags into a natural
  spoken cue.
- **Smart summaries of an area** 🤖🟡 — "You're on a quiet residential street; a park is 100 m
  north, shops are 200 m east." Summarize the surrounding tiles on demand.
- **Accessibility enrichment / crowd feedback** 🤖🟡 — let users voice-note hazards or corrections
  ("temporary construction here"); optionally classify/clean these with ML before saving as manual
  nodes and (opt-in) contributing back to OSM.
- **Speech-first command layer** 🤖🟡 — "explore north", "download this city", "what's around me"
  as spoken commands (on-device speech + intent parsing), reducing reliance on button hunting.
- **Personalized announcement tuning** 🤖🟢 — learn which categories a given user cares about from
  their interactions and re-rank announcements accordingly.

## 8. Quality & housekeeping

- **Remove dead server code** 🟢 — `OSMClient.downloadMapTile`, `BackgroundService.ACTION_DOWNLOAD_MAP`,
  `DownloadMapDialog` are unused after the Geofabrik switch.
- **Externalize hard-coded UI strings** 🟢 — several dialogs/toasts use literals; move to
  `strings.xml` for localization (the app already supports multiple locales).
- **Unit tests for the tile pipeline** 🟡 — golden-file tests for `PbfReader` + `OsmPbfImporter`
  against a tiny fixture PBF (e.g. Monaco), asserting the msgpack tile bytes.
- **Settings for announcement verbosity & units** 🟢 — meters/feet, clock vs degrees, terse vs
  verbose.
