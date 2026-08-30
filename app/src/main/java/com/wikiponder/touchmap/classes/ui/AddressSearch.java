package com.wikiponder.touchmap.classes.ui;

import android.app.Activity;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wikiponder.touchmap.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AddressSearch {
	
	private final Context context;
	private final OnAddressSelectedListener listener;
	private final Locale preferredLocale;
	EditText addressEditText;
	private AlertDialog inputDialog;
	
	// Listener to return the final result
	public interface OnAddressSelectedListener {
		void onAddressSelected(Location location);
		void onError(String message);
		void onDialogDismissed();
	}
	
	public AddressSearch(Context context, Locale preferredLocale, OnAddressSelectedListener listener) {
		this.context = context;
		this.preferredLocale = preferredLocale;
		this.listener = listener;
	}
	
	public void show() {
		// Inflate the custom layout
		LayoutInflater inflater = LayoutInflater.from(context);
		View dialogView = inflater.inflate(R.layout.dialog_address_search, null);
		VoiceInputEditText voiceInputEditText = dialogView.findViewById(R.id.address);
		voiceInputEditText.setupSpeechRecognizer(preferredLocale);
		addressEditText = voiceInputEditText.getEditText();
		
		inputDialog = new AlertDialog.Builder(context)
				.setView(dialogView)
				.setPositiveButton(R.string.action_search, (dialog, which) -> {
					String addressString = addressEditText.getText().toString();
					if (!addressString.isEmpty()) {
						startGeocoding(addressString);
					} else {
						Toast.makeText(context, R.string.address_search_empty_toast, Toast.LENGTH_SHORT).show();
					}
				})
				.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
					if (listener != null) {
						listener.onDialogDismissed();
					}
				})
				.create();
		
		inputDialog.show();
	}
	
	private void startGeocoding(String addressString) {
		// Show a loading indicator
		AlertDialog progressDialog = new AlertDialog.Builder(context)
				.setMessage(R.string.address_search_searching)
				.setCancelable(false)
				.show();
		
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Handler handler = new Handler(Looper.getMainLooper());
		
		executor.execute(() -> {
			Geocoder geocoder = new Geocoder(context);
			try {
				List<Address> addressList = geocoder.getFromLocationName(addressString, 5); // Get max 5 results
				handler.post(() -> {
					if (isHostGone()) return;
					progressDialog.dismiss();
					if (addressList != null && !addressList.isEmpty()) {
						showResultsDialog(addressList);
					} else {
						if(listener != null) listener.onError("No matching address found.");
					}
				});
			} catch (IOException e) {
				handler.post(() -> {
					if (isHostGone()) return;
					progressDialog.dismiss();
					if(listener != null) listener.onError("Geocoding failed. Check network.");
				});
			}
		});
		// Single-use executor: shut down as soon as the one queued task is accepted, so its worker
		// thread exits once the geocode finishes instead of idling for the life of the process. (An
		// un-shutdown Executors.newSingleThreadExecutor() keeps a non-daemon core thread alive
		// forever, so every address search used to leak one.) shutdown() lets the already-submitted
		// task run to completion; it is not shutdownNow().
		executor.shutdown();
	}

	/** True if the hosting Activity is gone (finishing/destroyed), so a queued main-thread callback
	 * must not touch its dialogs — dismissing or showing a dialog on a dead window throws
	 * IllegalArgumentException / BadTokenException. The Geocoder call is a network round-trip, so the
	 * user has plenty of time to press Back before it returns. */
	private boolean isHostGone() {
		if (context instanceof Activity) {
			Activity a = (Activity) context;
			return a.isFinishing() || a.isDestroyed();
		}
		return false;
	}
	
	private void showResultsDialog(List<Address> addressList) {
		ArrayList<String> formattedAddresses = new ArrayList<>();
		for (Address address : addressList) {
			String addressLine = address.getAddressLine(0); // Get the first line of the address
			if(addressLine != null) {
				formattedAddresses.add(addressLine);
			}
		}
		
		if (formattedAddresses.isEmpty()) {
			if(listener != null) listener.onError("Could not format found addresses.");
			return;
		}
		
		CharSequence[] items = formattedAddresses.toArray(new CharSequence[0]);
		
		new AlertDialog.Builder(context)
				.setTitle(R.string.address_search_select_title)
				.setItems(items, (dialog, which) -> {
					Address selectedAddress = addressList.get(which);
					if (listener != null) {
						Location location = new Location("");
						location.setLatitude(selectedAddress.getLatitude());
						location.setLongitude(selectedAddress.getLongitude());
						listener.onAddressSelected(location);
					}
				})
				.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
					if (listener != null) listener.onDialogDismissed();
				})
				.show();
	}
}