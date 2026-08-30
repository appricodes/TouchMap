package com.wikiponder.touchmap.classes.ui;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.TagTranslator;
import com.wikiponder.touchmap.classes.models.MapElement;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Point details" dialog — shared by MapActivity and NodeListActivity's node-actions menu (both used
 * to carry their own near-identical copy of this). One line per displayable tag (see
 * {@link MapElement#getDisplayableTags()}), each line its own TextView (not one big multi-line block)
 * inside a ScrollView, since a feature can carry an unbounded number of tags.
 *
 * <p>A tag's value is occasionally itself a JSON object serialized to a string (some data sources pack
 * structured sub-data — e.g. multiple contact fields — into a single OSM tag this way). Such a value is
 * expanded into one "key: value" line per key of that JSON object, using the JSON's own keys rather than
 * the outer OSM tag's key/single value. A value that isn't a JSON object (plain text, a number, a JSON
 * array, malformed JSON — anything that doesn't parse as a "{...}" object) is shown exactly as-is, one
 * "OsmKey: value" line, same as before this class existed.
 */
public final class ElementDetailsDialog {
	private ElementDetailsDialog() {
	}

	public static void show(Context context, MapElement element) {
		Map<String, String> displayTags = element.getDisplayableTags();
		if (displayTags.isEmpty()) {
			Toast.makeText(context, R.string.toast_no_additional_details, Toast.LENGTH_SHORT).show();
			return;
		}

		TagTranslator translator = MyApplication.getTagTranslator(context);
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, String> tag : displayTags.entrySet()) {
			JSONObject asJson = tryParseJsonObject(tag.getValue());
			if (asJson != null) {
				for (java.util.Iterator<String> it = asJson.keys(); it.hasNext(); ) {
					String innerKey = it.next();
					lines.add(formatLine(translator, innerKey, String.valueOf(asJson.opt(innerKey))));
				}
			} else {
				lines.add(formatLine(translator, tag.getKey(), tag.getValue()));
			}
		}

		LinearLayout container = new LinearLayout(context);
		container.setOrientation(LinearLayout.VERTICAL);
		container.setPadding(40, 20, 40, 20);
		for (String line : lines) {
			TextView lineView = new TextView(context);
			lineView.setText(line);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.bottomMargin = 24;
			lineView.setLayoutParams(lp);
			container.addView(lineView);
		}

		ScrollView scrollView = new ScrollView(context);
		scrollView.addView(container);

		new AlertDialog.Builder(context)
				.setTitle(element.getFormattedName(context, false))
				.setView(scrollView)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	/** Null unless {@code value} parses as a genuine JSON *object* — a JSON array, a bare number/
	 * string/boolean, or plain non-JSON text all return null here (org.json's JSONObject constructor
	 * already rejects anything not shaped like "{...}", so this is mostly just naming that behavior). */
	private static JSONObject tryParseJsonObject(String value) {
		if (value == null || value.isEmpty()) return null;
		try {
			return new JSONObject(value);
		} catch (JSONException e) {
			return null;
		}
	}

	private static String formatLine(TagTranslator translator, String key, String value) {
		String translatedKey = translator.translate(key);
		if (!translatedKey.isEmpty()) {
			translatedKey = translatedKey.substring(0, 1).toUpperCase() + translatedKey.substring(1);
		}
		return translatedKey + ": " + translator.translate(stripAPrefix(value));
	}

	/** Some source data prefixes a value with a literal "a:" (e.g. "a:something") — stripped before
	 * display since it's not meant to be user-facing. Only the exact "a:" prefix; anything else the
	 * value starts with is left untouched. Not String.stripLeading() — that needs API 33, below this
	 * app's minSdk 26. */
	private static String stripAPrefix(String value) {
		if (value != null && value.startsWith("a:")) {
			String rest = value.substring(2);
			int i = 0;
			while (i < rest.length() && Character.isWhitespace(rest.charAt(i))) i++;
			return rest.substring(i);
		}
		return value;
	}
}
