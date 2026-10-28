package com.wikiponder.touchmap.classes.ui;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.Utils;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.interfaces.UserLocationListener;

import java.util.Locale;

public class NodeAddingDialogBox
implements UserLocationListener {
	
	private final Context context;
	private final Locale preferredLocale;
	private Location location;
	private EditText nameEditText;
	private TextView messageView;
	private AlertDialog dialog;
	
	
	public NodeAddingDialogBox(Context context, Locale preferredLocale) {
		this.context = context;
		this.preferredLocale = preferredLocale;
		this.location = null;
		MyApplication.getAnnouncementManager(context).pause();
		MyApplication.getUserLocationManager(context).addListener(this);
	}
	
	public void show() {
		// Inflate the custom layout
		LayoutInflater inflater = LayoutInflater.from(context);
		View dialogView = inflater.inflate(R.layout.dialog_node_adding, null);
		VoiceInputEditText voiceInputEditText = dialogView.findViewById(R.id.name);
		voiceInputEditText.setupSpeechRecognizer(preferredLocale);
		
		nameEditText = voiceInputEditText.getEditText();
		nameEditText.setHint(R.string.hint_enter_point_name);
		messageView = dialogView.findViewById(R.id.message_tv);
		updateMessage();

		dialog = new AlertDialog.Builder(context)
				.setView(dialogView)
				.setTitle(R.string.add_point_dialog_title)
				.setPositiveButton(R.string.action_save_node, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.action_reset_location, null)
				.create();
		
		// add button actions
		dialog.setOnShowListener(dialogInterface -> {
			
			// Get the positive button from the dialog
			Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
			positiveButton.setOnClickListener(view -> {
				String name= nameEditText.getText().toString();
				if (name.isBlank()) {
					Toast.makeText(context, R.string.toast_enter_name_required, Toast.LENGTH_SHORT).show();
				} else if (location == null) {
					// OSMDatabaseHelper.insertNode dereferences location unconditionally — without this
					// guard, tapping Save before the first onLocationUpdated() callback arrives (a real
					// possibility: GPS fixes aren't instant, especially indoors) throws a
					// NullPointerException on the background thread below and crashes the app.
					Toast.makeText(context, R.string.toast_waiting_for_location, Toast.LENGTH_SHORT).show();
				} else {
					new Thread(() -> {
						// save node
						OSMDatabaseHelper.insertNode(null, location, name);
						// refresh the loaded-data cache so the new point appears
						MyApplication.getMapTileManager(context).reloadCurrentArea();

						MyApplication.getUserLocationManager(context).removeListener(this);
						
						// show a toast using Main thread
						if (context instanceof Activity activity) {
							activity.runOnUiThread(() -> Toast.makeText(context, R.string.toast_point_added, Toast.LENGTH_SHORT).show());
						}
						dialog.dismiss();
					}).start();
				}
			});
			
			// Get the negative button from the dialog
			Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
			negativeButton.setOnClickListener(view -> dialog.dismiss());
			
			// Get the neutral button from the dialog
			Button neutralButton= dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
			neutralButton.setOnClickListener(view -> {
				location = null;
				updateMessage();
				messageView.post(() -> messageView.requestFocus());
				messageView.post(() -> messageView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
			});
		});
		
		
		dialog.show();
	}
	
	
	@Override
	public void onLocationUpdated(Location location) {
		if (this.location == null || location.getAccuracy() < this.location.getAccuracy()) {
			this.location = location;
			updateMessage();
		}
	}
	
	private void updateMessage() {
		if (location == null)
			messageView.setText(R.string.status_getting_location);
		else
			messageView.setText(Utils.fmt(context, R.string.status_getting_location_with_accuracy, (int) location.getAccuracy()));
	}
}
