package com.wikiponder.touchmap.classes.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@code setItems}-style action-list dialog, shared by {@code MapActivity} and
 * {@code NodeListActivity}'s "tap a node/way" menus, with one addition: a single item (identified by
 * its label) can be shown disabled — greyed and unclickable — instead of being omitted entirely.
 * Used for "Point details" when the element has nothing to show (see
 * {@code MapElement#hasDisplayableTags}), so the option stays visible/predictable (useful for
 * TalkBack users navigating by list position) rather than the menu silently having one fewer item
 * some of the time.
 */
public final class ActionsDialog {
	private ActionsDialog() {
	}

	/** actions: label -> what to run when tapped, iteration order is display order. Pass a null
	 * disabledLabel (or leave every item enabled) to show a plain, fully-enabled action list. */
	public static void show(Context context, CharSequence title, Map<String, Runnable> actions,
							 String disabledLabel, boolean disabledLabelEnabled) {
		List<String> options = new ArrayList<>(actions.keySet());
		List<Runnable> runnables = new ArrayList<>(actions.values());

		ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, options) {
			@Override
			public boolean areAllItemsEnabled() {
				return false;
			}

			@Override
			public boolean isEnabled(int position) {
				return disabledLabelEnabled || disabledLabel == null || !options.get(position).equals(disabledLabel);
			}

			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				View v = super.getView(position, convertView, parent);
				TextView tv = (TextView) v;
				boolean on = isEnabled(position);
				tv.setEnabled(on);
				if (!on) tv.setTextColor(0xFF9E9E9E);
				return v;
			}
		};

		new AlertDialog.Builder(context)
				.setTitle(title)
				.setAdapter(adapter, (dialog, which) -> runnables.get(which).run())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Convenience for the common case: every item enabled, in a plain {@link LinkedHashMap} order. */
	public static void show(Context context, CharSequence title, Map<String, Runnable> actions) {
		show(context, title, actions, null, true);
	}
}
