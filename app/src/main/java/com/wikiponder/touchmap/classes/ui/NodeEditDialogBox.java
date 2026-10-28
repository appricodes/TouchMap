package com.wikiponder.touchmap.classes.ui;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.Utils;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.models.MapElement;

import java.util.Locale;

/**
 * "Edit Node" dialog: lets the user rename an existing node — real downloaded OSM point or
 * manually-added, any of them — and, only for manually-added points (matching the app's original,
 * narrower delete capability), remove it entirely after a confirmation. Replaces the old plain
 * "Remove point" action, which this dialog now folds delete into as one of two choices instead of a
 * separate menu item.
 */
public class NodeEditDialogBox {

	public interface Listener {
		/** The node's name was changed and saved to the db. */
		void onSaved(MapElement element, String newName);
		/** The node was deleted (Delete is only ever offered for CATEGORY_MANUAL points). */
		void onDeleted(MapElement element);
	}

	public static void show(Context context, Locale preferredLocale, MapElement element, Listener listener) {
		LayoutInflater inflater = LayoutInflater.from(context);
		View dialogView = inflater.inflate(R.layout.dialog_node_edit, null);
		VoiceInputEditText voiceInputEditText = dialogView.findViewById(R.id.name);
		voiceInputEditText.setupSpeechRecognizer(preferredLocale);
		EditText nameEditText = voiceInputEditText.getEditText();
		nameEditText.setText(element.name);
		if (element.name != null) nameEditText.setSelection(element.name.length());

		// Matches OSMDatabaseHelper.deleteNode's own reasoning for the old "Remove point" gating —
		// real downloaded data can be locally renamed but not removed outright.
		boolean canDelete = element.catId == OSMDatabaseHelper.CATEGORY_MANUAL;

		AlertDialog.Builder builder = new AlertDialog.Builder(context)
				.setTitle(R.string.action_edit_node)
				.setView(dialogView)
				.setPositiveButton(R.string.action_save, null)
				.setNegativeButton(android.R.string.cancel, null);
		if (canDelete) {
			builder.setNeutralButton(R.string.action_delete, null);
		}
		AlertDialog dialog = builder.create();

		// Buttons wired via setOnShowListener (not the Builder callbacks directly) so Save can
		// validate and keep the dialog open on an empty name, the same idiom NodeAddingDialogBox uses.
		dialog.setOnShowListener(shown -> {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
				String newName = nameEditText.getText().toString().trim();
				if (newName.isEmpty()) {
					Toast.makeText(context, R.string.toast_enter_name_required, Toast.LENGTH_SHORT).show();
					return;
				}
				new Thread(() -> {
					OSMDatabaseHelper.updateNodeName(context, element.id, newName);
					runOnMain(context, () -> {
						Toast.makeText(context, R.string.toast_point_updated, Toast.LENGTH_SHORT).show();
						dialog.dismiss();
						if (listener != null) listener.onSaved(element, newName);
					});
				}, "edit-node-save").start();
			});

			if (canDelete) {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
						new AlertDialog.Builder(context)
								.setTitle(R.string.confirm_removal_title)
								.setMessage(Utils.fmt(context, R.string.confirm_removal_message, element.getFormattedName(context, true)))
								.setPositiveButton(R.string.action_remove, (confirmDialog, which) -> new Thread(() -> {
									OSMDatabaseHelper.deleteNode(context, element.id);
									runOnMain(context, () -> {
										Toast.makeText(context, R.string.toast_point_removed, Toast.LENGTH_SHORT).show();
										dialog.dismiss();
										if (listener != null) listener.onDeleted(element);
									});
								}, "edit-node-delete").start())
								.setNegativeButton(android.R.string.cancel, null)
								.setCancelable(true)
								.show());
			}
		});

		dialog.show();
	}

	private static void runOnMain(Context context, Runnable action) {
		if (context instanceof Activity activity) {
			activity.runOnUiThread(action);
		}
	}
}
