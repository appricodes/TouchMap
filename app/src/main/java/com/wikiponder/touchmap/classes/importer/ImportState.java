package com.wikiponder.touchmap.classes.importer;

/**
 * Process-wide holder for the current import's progress, shared between {@link ImportService}
 * (which runs the work) and the download screen (which displays it). Because the state lives here
 * rather than in the Activity, an import keeps running — and stays observable — even if the screen
 * is turned off or the Activity is recreated.
 */
public class ImportState {

	public interface Listener {
		void onImportProgress(String phase, String stage, int percent);
		void onImportFinished(boolean success, String message);
	}

	private static final ImportState INSTANCE = new ImportState();

	public static ImportState get() {
		return INSTANCE;
	}

	private Listener listener;
	private boolean running;
	private String regionName = "";
	private String phase = "";
	private String stage = "";
	private int percent = -1;
	// Bumped on every successful completion. Lets an activity that wasn't the registered listener
	// at the moment an import finished (e.g. it was backgrounded on the download screen, which owns
	// the listener slot while visible) notice on its next onStart() that new data arrived, without
	// needing to have been listening at the exact moment — see MapActivity's use of this.
	private long completedImportVersion = 0;

	private ImportState() {
	}

	public synchronized void setListener(Listener l) {
		this.listener = l;
	}

	/**
	 * Clears the listener only if {@code l} is still the currently-registered one. Two activities can
	 * briefly overlap during a screen transition (Android runs {@code new.onStart()} before
	 * {@code old.onStop()}), so an unconditional {@code setListener(null)} in {@code onStop()} can wipe
	 * out a registration the *new* screen just made a moment earlier. Callers should use this instead
	 * of {@code setListener(null)} in {@code onStop()}.
	 */
	public synchronized void clearListener(Listener l) {
		if (this.listener == l) this.listener = null;
	}

	public synchronized boolean isRunning() {
		return running;
	}

	public synchronized String getRegionName() {
		return regionName;
	}

	public synchronized String getPhase() {
		return phase;
	}

	public synchronized String getStage() {
		return stage;
	}

	public synchronized int getPercent() {
		return percent;
	}

	public synchronized long getCompletedImportVersion() {
		return completedImportVersion;
	}

	void start(String regionName, String startingPhase) {
		Listener l;
		synchronized (this) {
			this.running = true;
			this.regionName = regionName != null ? regionName : "";
			this.phase = startingPhase != null ? startingPhase : "";
			this.stage = "Starting…";
			this.percent = -1;
			l = listener;
		}
		if (l != null) l.onImportProgress(phase, stage, percent);
	}

	void progress(String phase, String stage, int percent) {
		Listener l;
		synchronized (this) {
			this.phase = phase;
			this.stage = stage;
			this.percent = percent;
			l = listener;
		}
		if (l != null) l.onImportProgress(phase, stage, percent);
	}

	void finish(boolean success, String message) {
		Listener l;
		synchronized (this) {
			this.running = false;
			this.stage = message;
			if (success) completedImportVersion++;
			l = listener;
		}
		if (l != null) l.onImportFinished(success, message);
	}
}
