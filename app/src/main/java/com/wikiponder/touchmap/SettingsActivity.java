package com.wikiponder.touchmap;

import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.ui.EdgeToEdgeActionBar;
import com.wikiponder.touchmap.classes.ui.TranslationDisclaimerDialog;

public class SettingsActivity extends AppCompatActivity
implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
	
	private static final String TITLE_TAG = "settingsActivityTitle";


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_activity);
		// See EdgeToEdgeActionBar's doc for why this screen needs it: without it, on API 35+ the
		// preference list's first row renders underneath the ActionBar.
		EdgeToEdgeActionBar.apply(this);
		// See BaseActivity's onCreate for why: routes the hardware volume rocker to the same
		// Accessibility stream every sound in this app plays on (AudioClient).
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);

		if (savedInstanceState == null) {
			getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.settings, new HeaderFragment())
					.commit();
		} else {
			setTitle(savedInstanceState.getCharSequence(TITLE_TAG));
		}
		getSupportFragmentManager().addOnBackStackChangedListener(
				new FragmentManager.OnBackStackChangedListener() {
					@Override
					public void onBackStackChanged() {
						if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
							setTitle(R.string.title_activity_settings);
						}
					}
				});
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
	}
	
	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		// Save current activity title so we can set it again after a configuration change
		outState.putCharSequence(TITLE_TAG, getTitle());
	}
	
	@Override
	public boolean onSupportNavigateUp() {
		if (getSupportFragmentManager().popBackStackImmediate()) {
			return true;
		}
		return super.onSupportNavigateUp();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Paused/resumed on the resume/pause boundary, not just once in onCreate, so it's correct
		// across the whole show/hide cycle (e.g. the screen locking and unlocking again while Settings
		// is still on top must leave announcements paused, not accidentally resume them). Covers every
		// sub-screen too (Shortcuts/Announcement/Sound are fragments within this same activity, not
		// separate activities, so this activity's own onResume/onPause spans all of them).
		MyApplication.getAnnouncementManager(this).pause();
		// Runs the freshly-recreated Activity (see TranslationDisclaimerDialog's class doc for why
		// the show itself is deferred here rather than happening right in the preference-change
		// listener); a no-op on every resume that isn't the one right after a language change.
		TranslationDisclaimerDialog.consumePendingIfNeeded(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
		// Resume as soon as Settings is no longer the active screen, for ANY reason — navigating back
		// to MapActivity (which also unconditionally resumes in its own onStart(), harmlessly
		// redundant there) or the whole app going to the background (screen lock, Home, another app
		// coming forward). Announcements are driven by BackgroundService, a foreground service
		// independent of any activity's lifecycle, and must keep running while backgrounded.
		MyApplication.getAnnouncementManager(this).resume();
	}
	
	@Override
	public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, Preference pref) {
		// Instantiate the new Fragment
		final Bundle args = pref.getExtras();
		final Fragment fragment = getSupportFragmentManager().getFragmentFactory().instantiate(
				getClassLoader(),
				pref.getFragment());
		fragment.setArguments(args);
		fragment.setTargetFragment(caller, 0);
		// Replace the existing Fragment with the new Fragment
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.settings, fragment)
				.addToBackStack(null)
				.commit();
		setTitle(pref.getTitle());
		return true;
	}
	
	public static class HeaderFragment extends PreferenceFragmentCompat {

		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			setPreferencesFromResource(R.xml.header_preferences, rootKey);
			// preferred locale
			findPreference("language_preference").setOnPreferenceChangeListener((preference, newValue) -> {
				final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
				sharedPreferences.edit()
						.putString("language_preference", (String)newValue)
						.commit();
				MyApplication myApplication = MyApplication.getMyApplication(getContext());
				myApplication.refreshPreferredLocale();
				// getPreferredLocale().getLanguage() is the resolved language regardless of whether the
				// user picked an explicit tag or "Default language of device" — either way, if it isn't
				// English the strings the recreated Activity is about to show came from AI translation.
				TranslationDisclaimerDialog.markPendingIfNeeded(getContext(), myApplication.getPreferredLocale().getLanguage());
				return true;
			});
			
			// delete all downloaded maps
			Preference deleteMapsPref = findPreference("delete_all_maps");
			deleteMapsPref.setOnPreferenceClickListener(preference -> {
				new AlertDialog.Builder(getContext())
						.setTitle(R.string.delete_all_maps_confirm_title)
						.setMessage(R.string.delete_all_maps_confirm_message)
						.setPositiveButton(R.string.delete_all_maps_confirm_yes, (d, w) -> deleteAllMaps())
						.setNegativeButton(R.string.delete_all_maps_confirm_no, null)
						.show();
				return true;
			});
		}

		private void deleteAllMaps() {
			SettingsActivity parent = (SettingsActivity) getActivity();
			if (parent == null) return;
			new Thread(() -> {
				OSMDatabaseHelper.deleteAllDownloadedMaps(parent);
				parent.runOnUiThread(() -> {
					Toast.makeText(parent, R.string.delete_all_maps_done, Toast.LENGTH_SHORT).show();
					// The map cache (loaded nodes/ways) can otherwise keep showing stale data from
					// before the deletion until the user pans away and back.
					MyApplication.getMapTileManager(parent).reloadCurrentArea();
				});
			}, "delete-all-maps").start();
		}

	}
	
	public static class AnnouncementFragment extends PreferenceFragmentCompat {
		
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		setPreferencesFromResource(R.xml.announcement_preferences, rootKey);
		}
	}
	
	public static class SoundFragment extends PreferenceFragmentCompat {
		
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			setPreferencesFromResource(R.xml.sound_vibration_preferences, rootKey);
		}
	}
	
	
}