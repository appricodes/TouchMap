package com.wikiponder.touchmap.classes.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.wikiponder.touchmap.R;

/**
 * The map-download progress UI: a single non-dismissible dialog covering the whole multi-box
 * run (see BoxImporter's overall-percent weighting), owned and driven by whichever
 * {@code ImportState.Listener} is currently registered — MapActivity, since
 * DownloadMapActivity now finishes as soon as it starts the import rather than showing progress
 * itself. There is deliberately no "run in background" affordance here: the import is a
 * foreground service regardless (see ImportService), so closing this dialog was never actually
 * required to keep a download going — only Cancel is offered.
 */
public final class ImportProgressDialog {

	/** Invoked when the user confirms they want to cancel, after the confirmation sub-dialog. */
	public interface CancelListener {
		void onCancelConfirmed();
	}

	private final AlertDialog dialog;
	private final TextView statusText;
	private final ProgressBar progressBar;

	private ImportProgressDialog(Context context, CancelListener cancelListener) {
		View view = LayoutInflater.from(context).inflate(R.layout.dialog_import_progress, null);
		statusText = view.findViewById(R.id.importProgressStatusText);
		progressBar = view.findViewById(R.id.importProgressBar);

		dialog = new AlertDialog.Builder(context)
				.setTitle(R.string.download_map_title)
				.setView(view)
				// Not outside-touch-dismissible and no system back dismissal either — Cancel is the
				// only way out, matching "only the Cancel button should exist" for this screen.
				.setCancelable(false)
				.setNegativeButton(R.string.download_cancel_button, (DialogInterface d, int which) -> {
					// no-op here; the real handler is wired in show() below, once the dialog exists,
					// so it can show the confirm dialog instead of letting this one auto-dismiss.
				})
				.create();
		dialog.setOnShowListener(d -> {
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
				new AlertDialog.Builder(context)
						.setTitle(R.string.download_cancel_confirm_title)
						.setMessage(R.string.download_cancel_confirm_message)
						.setPositiveButton(R.string.download_cancel_confirm_yes, (dd, w) -> cancelListener.onCancelConfirmed())
						.setNegativeButton(R.string.download_cancel_confirm_no, null)
						.show();
			});
		});
	}

	public static ImportProgressDialog show(Context context, CancelListener cancelListener) {
		ImportProgressDialog d = new ImportProgressDialog(context, cancelListener);
		d.dialog.show();
		return d;
	}

	public void updateProgress(String stageText, int percent) {
		if (!dialog.isShowing()) return;
		statusText.setText(stageText);
		if (percent < 0) {
			progressBar.setIndeterminate(true);
		} else {
			progressBar.setIndeterminate(false);
			progressBar.setProgress(percent);
		}
	}

	public boolean isShowing() {
		return dialog.isShowing();
	}

	public void dismiss() {
		if (dialog.isShowing()) dialog.dismiss();
	}
}
