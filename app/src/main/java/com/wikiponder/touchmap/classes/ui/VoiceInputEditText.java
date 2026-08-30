package com.wikiponder.touchmap.classes.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.wikiponder.touchmap.R;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Custom EditText with microphone button for voice input.
 * Stays inside activity (no external Google overlay).
 */
public class VoiceInputEditText extends LinearLayout {
	
	private static final int REQUEST_RECORD_AUDIO_PERMISSION = 2001;
	
	private EditText editText;
	private ImageButton micButton;
	private SpeechRecognizer speechRecognizer;
	private Intent recognizerIntent;
	private Activity hostActivity;
	private boolean isListening = false;
	
	public VoiceInputEditText(Context context) {
		super(context);
		init(context);
	}
	
	public VoiceInputEditText(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}
	
	public VoiceInputEditText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init(context);
	}
	
	private void init(Context context) {
		LayoutInflater.from(context).inflate(R.layout.view_voice_input_edit_text, this, true);
		editText = findViewById(R.id.editText);
		micButton = findViewById(R.id.micButton);
		
		if (context instanceof Activity) {
			hostActivity = (Activity) context;
		}
		
		micButton.setOnClickListener(v -> {
			if (hostActivity == null) return;
			
			if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
					== PackageManager.PERMISSION_GRANTED) {
				toggleListening();
			} else {
				ActivityCompat.requestPermissions(hostActivity,
						new String[]{Manifest.permission.RECORD_AUDIO},
						REQUEST_RECORD_AUDIO_PERMISSION);
			}
		});
	}
	
	public void setupSpeechRecognizer(Locale locale) {
		if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
			micButton.setEnabled(false);
			return;
		}
		
		speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
		recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
		
		speechRecognizer.setRecognitionListener(new RecognitionListener() {
			@Override public void onReadyForSpeech(Bundle params) { }
			@Override public void onBeginningOfSpeech() { }
			@Override public void onRmsChanged(float rmsdB) { }
			@Override public void onBufferReceived(byte[] buffer) { }
			@Override public void onEndOfSpeech() { }
			@Override public void onError(int error) {
				isListening = false;
				micButton.setImageResource(android.R.drawable.ic_btn_speak_now);
			}
			
			@Override
			public void onResults(Bundle results) {
				isListening = false;
				micButton.setImageResource(android.R.drawable.ic_btn_speak_now);
				
				ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
				if (matches != null && !matches.isEmpty()) {
					editText.setText(matches.get(0));
					editText.setSelection(editText.getText().length());
					editText.requestFocus();
				}
			}
			
			@Override public void onPartialResults(Bundle partialResults) { }
			@Override public void onEvent(int eventType, Bundle params) { }
		});
	}
	
	private void toggleListening() {
		if (isListening) {
			speechRecognizer.stopListening();
			isListening = false;
			micButton.setImageResource(android.R.drawable.ic_btn_speak_now);
		} else {
			speechRecognizer.startListening(recognizerIntent);
			isListening = true;
			micButton.setImageResource(android.R.drawable.presence_audio_online); // change icon while listening
		}
	}
	
	public EditText getEditText() {
		return editText;
	}
	
	/** Tears down the SpeechRecognizer. Called automatically on detach (see
	 * {@link #onDetachedFromWindow()}); also safe to call directly, and safe to call twice. */
	public void release() {
		if (speechRecognizer != null) {
			speechRecognizer.destroy();
			speechRecognizer = null;
		}
		isListening = false;
	}

	@Override
	protected void onDetachedFromWindow() {
		// A SpeechRecognizer holds a live binding to the system recognition service and must be
		// destroyed explicitly — it is not reclaimed by GC alone, and leaking one logs
		// "SpeechRecognizer leaked" / keeps the service connection alive. Every host of this view is a
		// dialog (AddressSearch, NodeAddingDialogBox, NodeEditDialogBox), and none of them were
		// calling release() on dismiss, so each voice-capable dialog opened leaked one recognizer.
		// Hooking detach instead of asking each dialog to remember covers all three (and any future
		// host) by construction.
		release();
		super.onDetachedFromWindow();
	}
	
	public void setText(String s) {
		editText.setText(s);
	}
	
	public String getText() {
		return editText.getText().toString();
	}
}
