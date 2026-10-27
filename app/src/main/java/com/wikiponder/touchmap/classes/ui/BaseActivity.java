package com.wikiponder.touchmap.classes.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;

public abstract class BaseActivity extends AppCompatActivity {
	/** SharedPreferences key of the Settings "Full screen" switch (default off: normal, with the
	 * status and navigation bars). */
	public static final String PREF_FULLSCREEN = "fullscreen_mode";

	// Track current fullscreen state
	private boolean isFullScreen = false;
	
	private boolean doubleBackToExitPressedOnce = false;
	
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		// Must be before super.onCreate or setContentView
		supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
		super.onCreate(savedInstanceState);
		// Let content go edge-to-edge on API30+
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
		}
		// Every sound this app plays (SoundPool + TTS, see AudioClient) is routed to
		// STREAM_ACCESSIBILITY, not the default Media stream — this makes the hardware volume
		// rocker adjust the same stream while any of this app's screens are in front.
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);

		// back button
		OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true /* enabled by default */) {
			@Override
			public void handleOnBackPressed() {
				if (doubleBackToExitPressedOnce) {
					// If the flag is true, the user has pressed back twice within 2 seconds.
					// set flag to false, in case the app remains in memory, it will work properly if open again
					doubleBackToExitPressedOnce = false;
					// The default back action (closing the app) will be executed.
					// We first disable this callback to prevent an infinite loop.
					//setEnabled(false);
					//getOnBackPressedDispatcher().onBackPressed();
					onStopByUser() ;
					finish();
					return;
				}
				
				// Set the flag to true on the first press.
				doubleBackToExitPressedOnce = true;
				
				// Show a toast message to the user.
				Toast.makeText(BaseActivity.this, R.string.please_click_back_again_to_exit, Toast.LENGTH_SHORT).show();
				
				// Use a Handler to reset the flag after a 2-second delay.
				new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
					@Override
					public void run() {
						doubleBackToExitPressedOnce = false;
					}
				}, 2000); // 2000 milliseconds = 2 seconds
			}
		};
		
		// Add the callback to the dispatcher.
		getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
		
	}
	
	protected void onStopByUser() {
	}

	// True strictly between onResume() and onPause() — used by onWindowFocusChanged to tell "a
	// dialog/popup is covering me while I'm still the active activity" (this stays true) apart from
	// "the whole app is going to the background" (screen lock, Home, another app coming forward —
	// onPause() always fires first/alongside those, so this is already false by the time the
	// resulting onWindowFocusChanged(false) arrives). See onWindowFocusChanged for why that
	// distinction matters.
	private boolean activityResumed = false;

	@Override
	protected void onResume() {
		super.onResume();
		activityResumed = true;
		// The Settings switch may have changed while another screen was in front.
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		setFullScreen(prefs.getBoolean(PREF_FULLSCREEN, false));
	}

	@Override
	protected void onPause() {
		super.onPause();
		activityResumed = false;
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && isFullScreen) {
			hideSystemUI();
		}
		// Nearest node/way announcements (driven by BackgroundService, a foreground service
		// independent of any activity's lifecycle) must keep running when the screen locks or another
		// app comes forward — this app is meant to keep guiding a user by audio even with the phone in
		// a pocket — and must PAUSE only while something belonging to this app itself (an activity, a
		// dialog, a popup menu) is what's actually on screen. Window focus alone can't tell those
		// apart: showing a dialog and backgrounding the whole app both fire onWindowFocusChanged(false)
		// identically. The `activityResumed` guard is what tells them apart — a dialog/popup never
		// takes the hosting activity out of onResume()/onPause() (that's an Activity-lifecycle
		// transition, dialogs don't trigger it), so `activityResumed` is still true when the *dialog*
		// case's onWindowFocusChanged(false) arrives, but already false (onPause ran first) by the
		// time the *backgrounding* case's onWindowFocusChanged(false) arrives.
		if (activityResumed) {
			if (hasFocus) {
				MyApplication.getAnnouncementManager(this).resume();
			} else {
				MyApplication.getAnnouncementManager(this).pause();
			}
		}
	}
	
	/**
	 * Public API to switch immersive mode on or off.
	 * @param enable true to hide bars (full-screen), false to show bars.
	 */
	public void setFullScreen(boolean enable) {
		isFullScreen = enable;
		if (enable) {
			hideSystemUI();
		} else {
			showSystemUI();
		}
		applyBarInsets();
	}

	/**
	 * Edge-to-edge is on for this activity (and forced on API 35+), so with the bars visible the
	 * layout would draw underneath them. Pads the content frame by the bar insets in normal mode, and
	 * by nothing in full-screen mode (the bars are hidden; a transient swipe-in must not shift the
	 * layout). The window background is the app's navy so the padded strips match the toolbars.
	 */
	private void applyBarInsets() {
		View content = findViewById(android.R.id.content);
		if (content == null) return;
		getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF102A43));
		ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
			Insets bars = isFullScreen
					? Insets.NONE
					: insets.getInsets(WindowInsetsCompat.Type.systemBars()
							| WindowInsetsCompat.Type.displayCutout());
			v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
			return insets;
		});
		ViewCompat.requestApplyInsets(content);
		// Light icons on the navy strips.
		WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
				.setAppearanceLightStatusBars(false);
		WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
				.setAppearanceLightNavigationBars(false);
	}
	
	/** Convenience: flip between full-screen and normal. */
	public void toggleFullScreen() {
		setFullScreen(!isFullScreen);
	}
	
	/** Hide status & nav bars in an immersive sticky mode. */
	private void hideSystemUI() {
		View decorView = getWindow().getDecorView();
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			WindowInsetsController ctl = decorView.getWindowInsetsController();
			if (ctl != null) {
				ctl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
				ctl.setSystemBarsBehavior(
						WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
				);
			}
		} else {
			// legacy
			getWindow().setFlags(
					WindowManager.LayoutParams.FLAG_FULLSCREEN,
					WindowManager.LayoutParams.FLAG_FULLSCREEN
			);
			decorView.setSystemUiVisibility(
					View.SYSTEM_UI_FLAG_LAYOUT_STABLE
							| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
							| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_FULLSCREEN
							| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
			);
		}
	}
	
	/** Reveal status & nav bars again. */
	private void showSystemUI() {
		View decorView = getWindow().getDecorView();
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			WindowInsetsController ctl = decorView.getWindowInsetsController();
			if (ctl != null) {
				ctl.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
			}
		} else {
			// legacy: clear fullscreen flag and reset ui visibility
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
			decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		}
	}
}
