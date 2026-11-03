package dev.dworks.apps.anexplorer.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.Utils;

/**
 * Opens the microphone behind a small "speak now" dialog and hands back what it heard. The
 * remote's own microphone key belongs to the system's assistant, so this is the app's way in.
 * Microphone permission is the caller's job: call {@link #listen()} once it is granted.
 */
public class VoiceSearch {
    private final Activity mActivity;
    private final OnWords mOnWords;
    private SpeechRecognizer mRecognizer;
    private Dialog mDialog;
    private ImageView mMic;
    private TextView mStatus;
    private TextView mWords;
    private boolean mListening;

    public interface OnWords {
        void onWords(String words);
    }

    public VoiceSearch(Activity activity, OnWords onWords) {
        mActivity = activity;
        mOnWords = onWords;
    }

    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(mActivity);
    }

    public boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    public void listen() {
        if (mRecognizer == null) {
            mRecognizer = SpeechRecognizer.createSpeechRecognizer(mActivity);
            mRecognizer.setRecognitionListener(mListener);
        }
        mListening = true;
        showDialog();
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        mRecognizer.startListening(intent);
    }

    /** Stops listening because the user backed out, without treating it as an error. */
    public void cancel() {
        mListening = false;
        if (mRecognizer != null) {
            mRecognizer.cancel();
        }
        dismissDialog();
    }

    /** Cancels, and frees the recognizer; the next {@link #listen()} makes a new one. */
    public void release() {
        cancel();
        if (mRecognizer != null) {
            mRecognizer.destroy();
            mRecognizer = null;
        }
    }

    private void showDialog() {
        if (mDialog == null) {
            final Dialog dialog = new Dialog(mActivity);
            dialog.setContentView(R.layout.dialog_voice_search);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(d -> cancel());
            final Window window = dialog.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(0.4f);
            }
            mMic = dialog.findViewById(R.id.voice_mic);
            mStatus = dialog.findViewById(R.id.voice_status);
            mWords = dialog.findViewById(R.id.voice_words);
            mDialog = dialog;
        }
        mStatus.setText(R.string.voice_search_speak_now);
        mWords.setVisibility(View.GONE);
        mMic.setScaleX(1f);
        mMic.setScaleY(1f);
        mDialog.show();
    }

    private void dismissDialog() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
    }

    private static int errorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return R.string.voice_search_permission_needed;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return R.string.voice_search_offline;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return R.string.voice_search_no_match;
            default:
                return R.string.voice_search_failed;
        }
    }

    private final RecognitionListener mListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            mStatus.setText(R.string.voice_search_speak_now);
        }

        @Override
        public void onBeginningOfSpeech() {
            mStatus.setText(R.string.voice_search_listening);
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            if (!mListening || mMic == null) {
                return;
            }
            // The mic grows with the voice, so the remote is clearly being heard
            final float level = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f));
            final float scale = 1f + level * 0.3f;
            mMic.setScaleX(scale);
            mMic.setScaleY(scale);
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            if (!mListening) {
                return;
            }
            mListening = false;
            dismissDialog();
            if (Utils.isActivityAlive(mActivity)) {
                Toast.makeText(mActivity, errorText(error), Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (!mListening) {
                return;
            }
            mListening = false;
            dismissDialog();
            final ArrayList<String> matches = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null || matches.isEmpty()) {
                Toast.makeText(mActivity, R.string.voice_search_no_match, Toast.LENGTH_LONG).show();
                return;
            }
            mOnWords.onWords(matches.get(0));
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (!mListening || mWords == null) {
                return;
            }
            final ArrayList<String> matches = partialResults == null ? null
                    : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                mWords.setText(matches.get(0));
                mWords.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
