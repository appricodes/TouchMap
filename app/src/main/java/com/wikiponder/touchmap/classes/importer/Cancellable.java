package com.wikiponder.touchmap.classes.importer;

/** Common cancel-ability shared by any import pipeline (PBF region or Overpass bbox), so
 * ImportService can hold either kind without knowing which one is currently running. */
interface Cancellable {
	void cancel();
}
