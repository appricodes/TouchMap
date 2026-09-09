package com.wikiponder.touchmap;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Who publishes this app, how to reach them, and what data leaves the device — an Impressum, which
 * is required to distribute in Germany/the EU. Reachable from MapActivity's More menu
 * ({@code R.id.menu_about}).
 *
 * <p>Deliberately separate from {@link CreditsActivity}: that screen is third-party attribution
 * (OpenStreetMap's ODbL notice and friends), this one is the publisher's own details. They answer
 * different questions and only one of them is a legal obligation of the publisher.
 *
 * <p>The publisher name, postal address, e-mail and policy URL live in {@code strings.xml} as
 * {@code translatable="false"} resources: they are real-world data, identical in all 47 locales.
 */
public class AboutActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_about);
		setTitle(R.string.about_title);
		// Matches CreditsActivity: routes the hardware volume rocker to the Accessibility stream
		// every sound in this app plays on (AudioClient).
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		showVersion();
		wireEmailRow();
		wireWebsiteRow();
		wirePrivacyRow();
	}

	/**
	 * Read from PackageInfo rather than a literal, so this can't drift from build.gradle.kts on the
	 * next version bump. Same source CrashReporting uses to tag reports.
	 */
	private void showVersion() {
		TextView versionView = findViewById(R.id.about_version);
		String name = "?";
		long code = 0;
		try {
			PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
			if (info.versionName != null) {
				name = info.versionName;
			}
			code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
					? info.getLongVersionCode()
					: info.versionCode;
		} catch (PackageManager.NameNotFoundException e) {
			// Cannot happen for our own package; leave the placeholders rather than crash a screen
			// whose whole job is to be readable.
		}
		versionView.setText(getString(R.string.about_version, name, code));
	}

	private void wireEmailRow() {
		TextView emailView = findViewById(R.id.about_email);
		String address = getString(R.string.about_email);
		// TalkBack would otherwise announce this identically to the static body rows around it. The
		// address plus the action tells the user both what it is and that it does something.
		emailView.setContentDescription(address + ", " + getString(R.string.about_email_action));
		emailView.setOnClickListener(v ->
				open(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address))));
	}

	private void wireWebsiteRow() {
		TextView websiteView = findViewById(R.id.about_website);
		String host = getString(R.string.about_website);
		websiteView.setContentDescription(host + ", " + getString(R.string.about_website_action));
		// The resource is the bare host (it is what should be *displayed*), so the scheme is added
		// here — ACTION_VIEW on a schemeless Uri resolves to nothing.
		websiteView.setOnClickListener(v ->
				open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + host))));
	}

	private void wirePrivacyRow() {
		TextView linkView = findViewById(R.id.about_privacy_link);
		linkView.setOnClickListener(v ->
				open(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_privacy_url)))));
	}

	/**
	 * A device with no mail client (or no browser) is unusual but real, and an unhandled
	 * ActivityNotFoundException here would kill the screen — an outcome a blind user has no way to
	 * recover from in context. Toast and stay put instead.
	 */
	private void open(Intent intent) {
		try {
			startActivity(intent);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, R.string.about_no_app_for_link, Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}
}
