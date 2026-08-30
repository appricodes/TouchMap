package com.wikiponder.touchmap.classes.importer;

/** Shared progress/completion callback for any import pipeline (PBF region download or Overpass bbox). */
public interface ProgressListener {
	/**
	 * phase is a short, stable label for the current pipeline stage ("Connecting", "Downloading
	 * map", "Extracting map", "Saving map") — suitable for a notification title, since it stays
	 * constant while percent counts up within that stage. stage is the fuller, already-localized
	 * detail text (area index/total, byte counts) shown on the download progress dialog. percent is
	 * one continuous 0-100 across the WHOLE import run (every box, every phase, size-weighted per
	 * box) — see {@link BoxImporter}'s class doc for how it's computed — never -1: BoxImporter
	 * always knows at least how much of the run's total weight has been completed so far, even
	 * during a phase (connecting, extracting) that isn't itself byte-instrumented.
	 */
	void onProgress(String phase, String stage, int percent);
	/** featuresWritten is the number of nodes + ways added to the database. */
	void onComplete(int featuresWritten);
	void onError(String message);
}
