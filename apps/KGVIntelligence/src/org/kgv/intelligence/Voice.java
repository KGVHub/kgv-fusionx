package org.kgv.intelligence;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Voice input. Uses the phone's speech recognizer when one is installed; otherwise records
 * the voice and sends it to a Whisper-compatible /audio/transcriptions endpoint, such as
 * whisper.cpp's server running locally.
 */
final class Voice {
    interface Listener {
        void onListening(boolean listening);

        void onText(String text);

        void onError(String message);
    }

    private static final int RATE = 16000;
    private static final int MAX_SECONDS = 60;

    private final Context mContext;
    private final Listener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private SpeechRecognizer mRecognizer;
    private volatile boolean mRecording;

    Voice(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
    }

    boolean isListening() {
        return mRecording || mRecognizer != null;
    }

    void toggle() {
        if (isListening()) {
            stop();
        } else if (SpeechRecognizer.isRecognitionAvailable(mContext)) {
            recognize();
        } else {
            record();
        }
    }

    void stop() {
        if (mRecognizer != null) {
            mRecognizer.stopListening();
        }
        mRecording = false;
    }

    void release() {
        if (mRecognizer != null) {
            mRecognizer.destroy();
            mRecognizer = null;
        }
        mRecording = false;
    }

    private void recognize() {
        mRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
        mRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                mListener.onListening(true);
            }
            @Override public void onBeginningOfSpeech() {
            }
            @Override public void onRmsChanged(float rmsdB) {
            }
            @Override public void onBufferReceived(byte[] buffer) {
            }
            @Override public void onEndOfSpeech() {
            }
            @Override public void onError(int error) {
                finishRecognizer();
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    mListener.onError("Didn't catch that.");
                } else {
                    mListener.onError("Speech recognizer error " + error);
                }
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> r = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                finishRecognizer();
                if (r != null && !r.isEmpty()) mListener.onText(r.get(0));
            }
            @Override public void onPartialResults(Bundle partialResults) {
            }
            @Override public void onEvent(int eventType, Bundle params) {
            }
        });
        mRecognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true));
    }

    private void finishRecognizer() {
        if (mRecognizer != null) mRecognizer.destroy();
        mRecognizer = null;
        mListener.onListening(false);
    }

    /** Records until stop() (or a minute), then transcribes through the endpoint. */
    private void record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4);
        } catch (SecurityException | IllegalArgumentException e) {
            mListener.onError("Can't use the microphone: " + e.getMessage());
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            mListener.onError("The microphone is busy.");
            return;
        }
        mRecording = true;
        mListener.onListening(true);
        new Thread(() -> {
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buf = new byte[min];
            rec.startRecording();
            long end = System.currentTimeMillis() + MAX_SECONDS * 1000L;
            while (mRecording && System.currentTimeMillis() < end) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) pcm.write(buf, 0, n);
            }
            rec.stop();
            rec.release();
            mRecording = false;
            mMain.post(() -> mListener.onListening(false));
            if (pcm.size() < RATE / 2) return;   // under a quarter second: a mis-tap
            Prefs prefs = new Prefs(mContext);
            if (prefs.localOnly() && !Prefs.isLocalAddress(prefs.sttEndpoint())) {
                mMain.post(() -> mListener.onError("Local-only mode: set a local "
                        + "speech-to-text endpoint in settings."));
                return;
            }
            try {
                String text = new LlmClient(prefs.sttEndpoint(), prefs.apiKey(), prefs.model())
                        .transcribe(wav(pcm.toByteArray()), prefs.sttModel());
                mMain.post(() -> {
                    if (text.isEmpty()) mListener.onError("Didn't catch that.");
                    else mListener.onText(text);
                });
            } catch (Exception e) {
                mMain.post(() -> mListener.onError("Voice: " + e.getMessage()
                        + ". Set a speech-to-text endpoint (e.g. whisper.cpp server) in "
                        + "settings."));
            }
        }, "kgv-voice").start();
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
