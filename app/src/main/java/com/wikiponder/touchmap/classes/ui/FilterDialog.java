package com.wikiponder.touchmap.classes.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.wikiponder.touchmap.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * "Filter map elements" dialog: a free-text keyword filter plus the per-category checkboxes.
 *
 * <p>The keyword field replaces the separate search dialog that used to hang off the toolbar's
 * search button (that button now opens the address search instead). Keeping the keyword here rather
 * than in its own dialog puts every "what shows on the map" control in one place — it is a filter in
 * exactly the same sense the category checkboxes are, and it composes with them.
 *
 * <p>Built from {@code dialog_filter.xml} rather than {@link AlertDialog.Builder#setMultiChoiceItems}
 * because the dialog now needs both a text field and the checkbox list, and those would compete for
 * the same slot.
 */
public class FilterDialog {

	// Category constants
	public static final int CATEGORY_MANUAL = -20;
	public static final int CATEGORY_ADDRESS = 0;
	public static final int CATEGORY_HEALTH = 5;
	public static final int CATEGORY_TRAFFIC = 10;
	public static final int CATEGORY_BARRIER = 15;
	public static final int CATEGORY_SHOP = 20;
	public static final int CATEGORY_AMENITY = 25;
	public static final int CATEGORY_LEISURE = 30;
	public static final int CATEGORY_TOURISM = 35;
	public static final int CATEGORY_NATURAL = 50;
	public static final int CATEGORY_WAY = 60;

	// Map category IDs to human-readable names. LinkedHashMap so the checkbox order is stable
	// between openings — with a HashMap the rows could reshuffle, which is disorienting when the
	// list is being navigated by screen reader.
	private static final Map<Integer, String> CATEGORY_NAMES = new LinkedHashMap<>();
	static {
		CATEGORY_NAMES.put(CATEGORY_MANUAL, "Manual");
		CATEGORY_NAMES.put(CATEGORY_ADDRESS, "Address");
		CATEGORY_NAMES.put(CATEGORY_HEALTH, "Health");
		CATEGORY_NAMES.put(CATEGORY_TRAFFIC, "Traffic");
		CATEGORY_NAMES.put(CATEGORY_BARRIER, "Barrier");
		CATEGORY_NAMES.put(CATEGORY_SHOP, "Shop");
		CATEGORY_NAMES.put(CATEGORY_AMENITY, "Amenity");
		CATEGORY_NAMES.put(CATEGORY_LEISURE, "Leisure");
		CATEGORY_NAMES.put(CATEGORY_TOURISM, "Tourism");
		CATEGORY_NAMES.put(CATEGORY_NATURAL, "Natural");
		CATEGORY_NAMES.put(CATEGORY_WAY, "Way");
	}

	// Callback interface. `keyword` is null when the field was left empty — never an empty string,
	// so callers can treat "no keyword filter" as a plain null check.
	public interface FilterDialogListener {
		void onShowSelected(Set<Integer> selectedCategories, String keyword);
		void onShowFavorites(String keyword);
		void onShowAll();
	}

	/**
	 * @param currentKeyword the keyword filter currently in effect, so reopening the dialog shows
	 *                       what is actually applied rather than a blank field; may be null.
	 */
	public static void showDialog(Context context, Set<Integer> selectedCategories,
								  String currentKeyword, FilterDialogListener listener) {
		View content = LayoutInflater.from(context).inflate(R.layout.dialog_filter, null);

		VoiceInputEditText keywordInput = content.findViewById(R.id.filter_keyword);
		keywordInput.setupSpeechRecognizer(java.util.Locale.getDefault());
		if (currentKeyword != null) {
			keywordInput.setText(currentKeyword);
		}

		LinearLayout categoryContainer = content.findViewById(R.id.filter_category_container);
		final Map<Integer, CheckBox> boxes = new HashMap<>();
		for (Map.Entry<Integer, String> entry : CATEGORY_NAMES.entrySet()) {
			CheckBox box = new CheckBox(context);
			box.setText(entry.getValue());
			box.setChecked(selectedCategories.contains(entry.getKey()));
			box.setMinHeight((int) (48 * context.getResources().getDisplayMetrics().density));
			categoryContainer.addView(box);
			boxes.put(entry.getKey(), box);
		}

		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setTitle(R.string.filter_dialog_title);
		builder.setView(content);

		// Button: Show selected
		builder.setPositiveButton(R.string.filter_show_selected, (dialog, which) -> {
			Set<Integer> updatedCatIds = new HashSet<>();
			for (Map.Entry<Integer, CheckBox> e : boxes.entrySet()) {
				if (e.getValue().isChecked()) updatedCatIds.add(e.getKey());
			}
			listener.onShowSelected(updatedCatIds, trimmedKeyword(keywordInput));
		});

		// Button: Show favorites
		builder.setNeutralButton(R.string.filter_show_favorites, (dialog, which) ->
				listener.onShowFavorites(trimmedKeyword(keywordInput)));

		// Button: Show all — "no filters at all", so it deliberately drops the keyword too rather
		// than leaving an invisible text filter still narrowing the map.
		builder.setNegativeButton(R.string.filter_show_all, (dialog, which) -> listener.onShowAll());

		builder.create().show();
	}

	/** The keyword field's contents, or null if blank — see {@link FilterDialogListener}. */
	private static String trimmedKeyword(VoiceInputEditText input) {
		String text = input.getText();
		if (text == null) return null;
		text = text.trim();
		return text.isEmpty() ? null : text;
	}
}
