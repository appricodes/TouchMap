package com.wikiponder.touchmap.classes;


import android.content.Context;

import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.R;

import java.util.UUID;

public class AudioClient implements TextToSpeech.OnInitListener {
	private static final String TAG = "AudioClient";

	public static final int SOUND_SUCCESS = 0;
	public static final int SOUND_DONE = 1;
	public static final int SOUND_BEEP = 2;
	public static final int SOUND_DING = 3;
	public static final int SOUND_WAIT = 4;
	public static final int SOUND_LOCATION = 5;
	public static final int SOUND_POINT_TOUCH = 6;
	public static final int SOUND_WAY_TOUCH = 7;
	public static final int SOUND_AIM_TICK = 8;
	public static final int SOUND_AIM_LOCK = 9;
	public static final int SOUND_WELCOME = 10;
	public static final int SOUND_GOODBYE = 11;

	private static final int SOUND_COUNT = 12;
	// One pre-rendered binaural clip per 20 degrees around the listener (0 = ahead, clockwise
	// positive), replacing the old GvrAudioEngine spatializer — see playPointDirectional and
	// tools/generate_sounds.py's build_point_dir_set for how these were rendered.
	private static final int POINT_DIR_COUNT = 18;
	private static final int POINT_DIR_STEP_DEG = 360 / POINT_DIR_COUNT;

	public static final String SOUND_EARCON_DONE = "sound_done";

	private final SoundPool soundPool;
	private final int[] soundIds = new int[SOUND_COUNT];
	private final int[] pointDirIds = new int[POINT_DIR_COUNT];
	private final Context context;

	// All SoundPool loads are async (SoundPool.load() returns immediately, decoding happens on a
	// worker thread) — playing a sound before its onLoadComplete fires is a silent no-op. Every
	// existing call site got away without tracking this because real UI sounds only start after
	// the user has interacted with the screen for a moment, which was always enough lead time. The
	// welcome chime is the first sound the app ever plays, right at process start, so it can't rely
	// on that — playWelcome() below queues instead of firing blind.
	private int loadedSoundCount = 0;
	private volatile boolean soundsReady = false;
	private Runnable pendingOnSoundsReady = null;

	private TextToSpeech tts;
	private boolean isTtsReady = false;

	public AudioClient(Context context) {
		this.context = context;

		// Initialize the TTS engine
		tts = new TextToSpeech(context, this);

		// load sounds — Accessibility usage so every SoundPool effect follows the Accessibility
		// volume (the one a TalkBack/low-vision user actually adjusts) instead of Media volume.
		AudioAttributes accessibilityAttributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build();
		soundPool = new SoundPool.Builder()
				.setMaxStreams(3)
				.setAudioAttributes(accessibilityAttributes)
				.build();
		soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> onSoundLoaded());

		soundIds[SOUND_SUCCESS] = soundPool.load(context, R.raw.success, 1);
		soundIds[SOUND_DONE] = soundPool.load(context, R.raw.done, 1);
		soundIds[SOUND_BEEP] = soundPool.load(context, R.raw.beep, 1);
		soundIds[SOUND_DING] = soundPool.load(context, R.raw.ding, 1);
		soundIds[SOUND_WAIT] = soundPool.load(context, R.raw.waiting, 1);
		soundIds[SOUND_LOCATION] = soundPool.load(context, R.raw.location, 1);
		soundIds[SOUND_POINT_TOUCH] = soundPool.load(context, R.raw.point_touch, 1);
		soundIds[SOUND_WAY_TOUCH] = soundPool.load(context, R.raw.way_touch, 1);
		soundIds[SOUND_AIM_TICK] = soundPool.load(context, R.raw.aim_tick, 1);
		soundIds[SOUND_AIM_LOCK] = soundPool.load(context, R.raw.aim_lock, 1);
		soundIds[SOUND_WELCOME] = soundPool.load(context, R.raw.welcome, 1);
		soundIds[SOUND_GOODBYE] = soundPool.load(context, R.raw.goodbye, 1);

		pointDirIds[0] = soundPool.load(context, R.raw.point_dir_000, 1);
		pointDirIds[1] = soundPool.load(context, R.raw.point_dir_020, 1);
		pointDirIds[2] = soundPool.load(context, R.raw.point_dir_040, 1);
		pointDirIds[3] = soundPool.load(context, R.raw.point_dir_060, 1);
		pointDirIds[4] = soundPool.load(context, R.raw.point_dir_080, 1);
		pointDirIds[5] = soundPool.load(context, R.raw.point_dir_100, 1);
		pointDirIds[6] = soundPool.load(context, R.raw.point_dir_120, 1);
		pointDirIds[7] = soundPool.load(context, R.raw.point_dir_140, 1);
		pointDirIds[8] = soundPool.load(context, R.raw.point_dir_160, 1);
		pointDirIds[9] = soundPool.load(context, R.raw.point_dir_180, 1);
		pointDirIds[10] = soundPool.load(context, R.raw.point_dir_200, 1);
		pointDirIds[11] = soundPool.load(context, R.raw.point_dir_220, 1);
		pointDirIds[12] = soundPool.load(context, R.raw.point_dir_240, 1);
		pointDirIds[13] = soundPool.load(context, R.raw.point_dir_260, 1);
		pointDirIds[14] = soundPool.load(context, R.raw.point_dir_280, 1);
		pointDirIds[15] = soundPool.load(context, R.raw.point_dir_300, 1);
		pointDirIds[16] = soundPool.load(context, R.raw.point_dir_320, 1);
		pointDirIds[17] = soundPool.load(context, R.raw.point_dir_340, 1);
	}

	private synchronized void onSoundLoaded() {
		loadedSoundCount++;
		if (loadedSoundCount >= SOUND_COUNT + POINT_DIR_COUNT) {
			soundsReady = true;
			if (pendingOnSoundsReady != null) {
				Runnable r = pendingOnSoundsReady;
				pendingOnSoundsReady = null;
				r.run();
			}
		}
	}

	@Override
	public void onInit(int status) {
		if (status == TextToSpeech.SUCCESS) {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

			// speech rate
			float rate = Float.parseFloat(prefs.getString("tts_rate", "1.5"));
			tts.setSpeechRate(rate);
			// Routes every utterance through the Accessibility stream, same as SoundPool above.
			// NOT done via the per-speak() Bundle (TextToSpeech.Engine.KEY_PARAM_STREAM) — that key
			// only accepts the old pre-Android-8 stream constants (MUSIC/ALARM/NOTIFICATION/RING/...)
			// and crashed the TTS engine's own process outright on a real device with
			// "STREAM_ACCESSIBILITY is not a legacy stream type that was used for audio playback".
			// This setter takes a full modern AudioAttributes instead and bypasses that legacy bridge.
			tts.setAudioAttributes(new AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build());
			Log.d(TAG, "Text-to-Speech is ready.");

			isTtsReady = true;
			tts.addEarcon(SOUND_EARCON_DONE, context.getPackageName(), R.raw.beep);

			tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
				@Override
				public void onDone(String utteranceId) {

				}

				@Override
				public void onError(String utteranceId) {

				}

				@Override
				public void onStart(String utteranceId) {
					onStartSpeechPart(utteranceId);

				}
			});
		} else {
			Log.e(TAG, "Cannot initialize TTS");
		}
	}

	private void onStartSpeechPart(String utteranceId) {
		if (utteranceId == null || utteranceId.isEmpty())
			return;
		String[] parts = utteranceId.split(":");
		if (parts[0].equalsIgnoreCase("location")) {
			float x = Float.parseFloat(parts[1]);
			float y = Float.parseFloat(parts[2]);
			float size = Float.parseFloat(parts[3]);

			float rate = 1.5f + (100 - y)/100 * 0.7f;
			playSound(SOUND_LOCATION, -1, 0, rate, x);
		}
	}

	public void speak(String text) {
		if (tts == null) return;
		if (isTtsReady && text != null && !text.trim().isEmpty()) {
			tts.speak(text, TextToSpeech.QUEUE_ADD, null, null);
		}
	}
	public void speakWithLocation(String text, double x, double y, double size) {
		if (tts == null) return;
		String utteranceId = new StringBuilder()
				.append("location:")
				.append((int)Math.floor(x)).append(":")
				.append((int)Math.floor(y)).append(":")
				.append((int)Math.floor(size)).append(":")
				.append(UUID.randomUUID().toString().replace("-", ""))
				.toString();
				System.out.println(utteranceId);
		tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
	}

	public void stopSpeaking() {
		if (tts == null) return;

		//Log.d(TAG, "stop speaking");
		if (tts.isSpeaking())
			tts.stop();
		tts.playSilentUtterance(50, TextToSpeech.QUEUE_FLUSH, null);
	}

	public void playEarcon(String id) {
		tts.playEarcon(id, TextToSpeech.QUEUE_ADD, null, null);
	}

	public int playSound(int id, double volume, int loop, double rate) {
			return playSound(id, volume, loop, rate, 50);
	}
	public int playSound(int id, double volume, int loop, double rate, double balance) {
		// if volume is negative, multiply it  by sound effects volume stored in settings
		if (volume <0) {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
			volume = -volume * Double.parseDouble(prefs.getString("pref_sound_effects_volume", "0.5"));
		}
		if (balance < 0)
			balance = 0;
		if (balance > 100)
			balance = 100;

		float leftVolume = (float)(volume * (100 - balance) / 100);
		float rightVolume = (float)(volume * balance / 100);
		return soundPool.play(soundIds[id], leftVolume, rightVolume, 0, loop, (float) rate);
	}

	/** Plays one of the 18 pre-rendered binaural point-touch earcons, closest to {@code bearingDeg}
	 * (0 = ahead, clockwise positive — same convention the old SpatialAudioClient used). Distance is
	 * simulated with plain volume — the clip's own baked-in left/right balance and ITD/head-shadow
	 * filtering already encode direction, so both channels get the same gain here rather than any
	 * extra panning, and rate is fixed at 1.0 since resampling would shift the baked-in ITD. */
	public void playPointDirectional(double bearingDeg, double volume) {
		if (volume <= 0) return;
		double norm = ((bearingDeg % 360) + 360) % 360;
		int idx = ((int) Math.round(norm / POINT_DIR_STEP_DEG)) % POINT_DIR_COUNT;
		float v = (float) Math.max(0f, Math.min(1f, volume));
		soundPool.play(pointDirIds[idx], v, v, 0, 0, 1.0f);
	}

	/** Plays the app-open chime once (MyApplication.initialize()). Queues itself until every
	 * SoundPool sample has finished its async load if called before then — see soundsReady. */
	public void playWelcome() {
		if (soundsReady) {
			playSound(SOUND_WELCOME, 1.0, 0, 1);
		} else {
			pendingOnSoundsReady = () -> playSound(SOUND_WELCOME, 1.0, 0, 1);
		}
	}

	/** Plays the app-close chime (BaseActivity's double-back-to-exit, via MapActivity.onStopByUser).
	 * By the time a user can trigger this the app has been running for a while, so — unlike
	 * playWelcome() — there's no realistic case where the sample isn't loaded yet. */
	public void playGoodbye() {
		playSound(SOUND_GOODBYE, 1.0, 0, 1);
	}

	public void stopSound(int streamId) {
		soundPool.stop(streamId);
	}

	public void release() {
		if (soundPool != null)
			soundPool.release();
		if (tts != null) {
			tts.stop();
			tts.shutdown();
			tts = null;
		}
	}
	public boolean isTTSSpeaking() {
		if (tts == null) return false;
		return tts != null && tts.isSpeaking();
	}
	public void playSilence(long duration) {
		if (tts == null) return;
		tts.playSilentUtterance(duration, TextToSpeech.QUEUE_ADD, null);
	}
	public void playSilenceWithLocation(long duration, double x, double y, double size) {
		if (tts == null) return;
		String utteranceId = new StringBuilder()
				.append("location:")
				.append((int)Math.floor(x)).append(":")
				.append((int)Math.floor(y)).append(":")
				.append((int)Math.floor(size)).append(":")
				.append(UUID.randomUUID().toString().replace("-", ""))
				.toString();

		tts.playSilentUtterance(duration, TextToSpeech.QUEUE_ADD, utteranceId);
	}
}
