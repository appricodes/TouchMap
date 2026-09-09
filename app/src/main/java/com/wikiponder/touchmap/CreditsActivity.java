package com.wikiponder.touchmap;

import android.media.AudioManager;
import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.wikiponder.touchmap.classes.ui.EdgeToEdgeActionBar;

/**
 * Attribution for OpenStreetMap (the app's map data, ODbL — attribution required) and the other
 * open data/open-source projects this app is built on. Reachable from MapActivity's More menu
 * ({@code R.id.menu_credits}). A plain scrolling list, not a PreferenceFragment like Settings —
 * there is nothing here to configure, only to read.
 */
public class CreditsActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_credits);
		// See EdgeToEdgeActionBar's doc for why this screen needs it: without it, on API 35+ this
		// scrolling list's first lines render underneath the ActionBar.
		EdgeToEdgeActionBar.apply(this);
		setTitle(R.string.credits_title);
		// See BaseActivity's onCreate for why: routes the hardware volume rocker to the same
		// Accessibility stream every sound in this app plays on (AudioClient) — this screen doesn't
		// extend BaseActivity (no map-related fullscreen/back-press behavior applies here).
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}
}
