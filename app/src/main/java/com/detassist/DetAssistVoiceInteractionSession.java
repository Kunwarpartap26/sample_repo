package com.detassist;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VoiceInteractionSession — Assistant UI & Interaction Handler
 *
 * ARCHITECTURE (Option A from design audit):
 *   - Extends VoiceInteractionSession (NOT AccessibilityService)
 *   - Uses android.speech.SpeechRecognizer for real STT
 *   - Configures RecognizerIntent.EXTRA_PREFER_OFFLINE for on-device recognition
 *   - Routes recognized text through RegexCommandParser → ActionDispatcher
 *
 * LIFECYCLE:
 *   onShow() → create SpeechRecognizer, startListening()
 *   RecognitionListener.onResults() → processRecognizedText()
 *   onHide() / onDestroy() → destroy SpeechRecognizer
 *
 * WAKE-WORD PATH:
 *   When invoked via AudioListenerService (hands-free), audio is captured as PCM
 *   and transcribed via an offline Vosk recognizer (see VoskTranscriber).
 *   When invoked via OS long-press, SpeechRecognizer handles STT.
 */
public class DetAssistVoiceInteractionSession extends VoiceInteractionSession
        implements RecognitionListener {

    private static final String TAG = "DetAssistSession";

    // ========================================
    // Components
    // ========================================
    private final RegexCommandParser mParser;
    private final AppIndex mAppIndex;
    private final ActionDispatcher mDispatcher;
    private final Handler mMainHandler;
    private final Context mContext;

    // SpeechRecognizer (OS-provided STT for long-press invocation)
    private SpeechRecognizer mSpeechRecognizer;
    private boolean mRecognizerActive = false;

    // Vosk offline transcriber (for wake-word audio path)
    private VoskTranscriber mVoskTranscriber;

    // State
    private boolean mIsActive = false;
    private View mRootView;
    private boolean mWakeWordInvocation = false; // true if launched from wake word

    // ========================================
    // Constructor
    // ========================================
    public DetAssistVoiceInteractionSession(Context context, Bundle args) {
        super(context);
        mContext = context.getApplicationContext();
        Log.i(TAG, "Session created");

        mMainHandler = new Handler(Looper.getMainLooper());

        // Use the SHARED parser from Application (fix for issue 6.2)
        DetAssistApp app = DetAssistApp.getInstance();
        mParser = (app != null) ? app.getParser() : new RegexCommandParser();

        // Get shared AppIndex from Application
        mAppIndex = (app != null) ? app.getAppIndex() : new AppIndex(mContext, mParser);

        // Build index if needed
        if (!mAppIndex.isBuilt()) {
            mAppIndex.build();
        }

        // Create dispatcher
        mDispatcher = new ActionDispatcher(mContext, mAppIndex);
        mDispatcher.setCallback(new ActionDispatcher.ExecutionCallback() {
            @Override
            public void onCommandStarted(RegexCommandParser.ParsedCommand command) {
                Log.i(TAG, "Command started: " + command);
            }

            @Override
            public void onCommandCompleted(RegexCommandParser.ParsedCommand command, boolean success) {
                Log.i(TAG, "Command completed: " + command + " success=" + success);
            }

            @Override
            public void onSequenceCompleted(int totalCommands, int successCount) {
                Log.i(TAG, "Sequence completed: " + successCount + "/" + totalCommands);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Execution error: " + error);
                showFeedback(error);
            }

            @Override
            public void onSpeechFeedback(String text) {
                showFeedback(text);
            }
        });

        // Initialize Vosk transcriber for offline wake-word audio path
        mVoskTranscriber = new VoskTranscriber(mContext);
    }

    // ========================================
    // Session Lifecycle
    // ========================================

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Log.i(TAG, "Session shown, showFlags=" + showFlags);
        mIsActive = true;

        // Create and display the session UI
        SessionView sessionView = new SessionView(getContext());
        setContentView(sessionView);
        mRootView = sessionView;

        // Start OS-level speech recognition (long-press home path)
        startSpeechRecognition();
    }

    @Override
    public void onHide() {
        super.onHide();
        Log.i(TAG, "Session hidden");
        mIsActive = false;
        stopSpeechRecognition();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Session destroyed");
        stopSpeechRecognition();
        mDispatcher.release();
        if (mVoskTranscriber != null) {
            mVoskTranscriber.release();
            mVoskTranscriber = null;
        }
    }

    // ========================================
    // SpeechRecognizer — OS-Provided STT
    // ========================================

    /**
     * Initialize and start the system SpeechRecognizer.
     * Uses EXTRA_PREFER_OFFLINE to request on-device recognition when available.
     */
    private void startSpeechRecognition() {
        if (mRecognizerActive) {
            Log.w(TAG, "Recognizer already active");
            return;
        }

        try {
            mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            mSpeechRecognizer.setRecognitionListener(this);

            Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            // Prefer on-device recognition
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

            mSpeechRecognizer.startListening(recognizerIntent);
            mRecognizerActive = true;
            showFeedback("Listening...");
            Log.i(TAG, "Speech recognition started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start speech recognition", e);
            showFeedback("Voice recognition unavailable");
        }
    }

    /**
     * Stop and release the SpeechRecognizer.
     */
    private void stopSpeechRecognition() {
        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.stopListening();
                mSpeechRecognizer.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping recognizer", e);
            }
            mSpeechRecognizer = null;
            mRecognizerActive = false;
        }
    }

    // ========================================
    // RecognitionListener Callbacks
    // ========================================

    @Override
    public void onReadyForSpeech(Bundle params) {
        Log.i(TAG, "Ready for speech");
        showFeedback("Speak now...");
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.i(TAG, "Speech began");
        showFeedback("Listening...");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Update visual audio level indicator
        if (mRootView instanceof SessionView) {
            ((SessionView) mRootView).setAudioLevel(rmsdB);
        }
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // Raw audio buffer — not needed for text-based pipeline
    }

    @Override
    public void onEndOfSpeech() {
        Log.i(TAG, "Speech ended");
        showFeedback("Processing...");
    }

    @Override
    public void onError(int error) {
        Log.w(TAG, "Recognition error: " + error);
        mRecognizerActive = false;

        String feedback;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                feedback = "I didn't catch that. Try again.";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                feedback = "I didn't hear anything.";
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                feedback = "Audio recording error.";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                feedback = "Network error. Using offline mode.";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                feedback = "Recognition client error.";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                feedback = "Microphone permission required.";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                feedback = "Recognition service busy. Try again.";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                feedback = "Server error.";
                break;
            default:
                feedback = "Recognition error (" + error + ").";
                break;
        }
        showFeedback(feedback);

        // Auto-close after error
        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mIsActive) {
                    finish();
                }
            }
        }, 2000);
    }

    @Override
    public void onResults(Bundle results) {
        mRecognizerActive = false;
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);

        if (matches == null || matches.isEmpty()) {
            showFeedback("I didn't catch that.");
            return;
        }

        String recognizedText = matches.get(0);
        Log.i(TAG, "Recognition result: '" + recognizedText + "'");

        // Route through deterministic parser → dispatcher pipeline
        processRecognizedText(recognizedText);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> partial = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (partial != null && !partial.isEmpty()) {
            // Show live transcription in UI
            showFeedback("..." + partial.get(0));
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        Log.d(TAG, "Recognition event: " + eventType);
    }

    // ========================================
    // Wake-Word Path (AudioListenerService)
    // ========================================

    /**
     * Transcribe captured PCM audio using Vosk offline engine
     * and route through the same command pipeline.
     *
     * Called from AudioListenerService when an utterance is captured after wake-word.
     *
     * @param audioData raw 16kHz 16-bit mono PCM samples
     * @param sampleCount number of valid samples
     */
    public void transcribeAndProcess(final short[] audioData, final int sampleCount) {
        if (mVoskTranscriber == null) {
            Log.e(TAG, "Vosk transcriber not available");
            showFeedback("Offline transcription unavailable");
            return;
        }

        showFeedback("Decoding speech...");

        // Run transcription on background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String text = mVoskTranscriber.transcribe(audioData, sampleCount);

                    if (text == null || text.trim().isEmpty()) {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                showFeedback("Didn't understand that.");
                            }
                        });
                        return;
                    }

                    Log.i(TAG, "Vosk transcription: '" + text + "'");
                    final String finalText = text;
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            processRecognizedText(finalText);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Transcription failed", e);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showFeedback("Transcription failed.");
                        }
                    });
                }
            }
        }, "VoskTranscription").start();
    }

    // ========================================
    // Command Processing Pipeline
    // ========================================

    /**
     * Process recognized text through the deterministic pipeline:
     *   1. Parse text into command(s) via RegexCommandParser
     *   2. Dispatch to ActionDispatcher for execution
     */
    private void processRecognizedText(String text) {
        showFeedback("Processing...");

        // Parse
        List<RegexCommandParser.ParsedCommand> commands = mParser.parse(text);

        if (commands.isEmpty()) {
            Log.w(TAG, "No commands parsed from: '" + text + "'");
            showFeedback("I didn't understand that.");
            return;
        }

        Log.i(TAG, "Parsed " + commands.size() + " command(s)");
        for (RegexCommandParser.ParsedCommand cmd : commands) {
            Log.i(TAG, "  -> " + cmd);
        }

        // Execute
        mDispatcher.executeSequence(commands);
    }

    // ========================================
    // UI
    // ========================================

    /**
     * Show text feedback in the session UI.
     */
    private void showFeedback(final String text) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRootView instanceof SessionView) {
                    ((SessionView) mRootView).setFeedbackText(text);
                }
            }
        });
    }

    /**
     * Custom session view for displaying assistant state.
     */
    private static class SessionView extends FrameLayout {

        private final TextView mFeedbackText;
        private final Paint mBackgroundPaint;
        private final Paint mBorderPaint;
        private float mAudioLevel = 0f;

        public SessionView(Context context) {
            super(context);

            setWillNotDraw(false);

            // Background styling
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.argb(230, 20, 20, 30));
            mBackgroundPaint.setAntiAlias(true);

            mBorderPaint = new Paint();
            mBorderPaint.setColor(Color.argb(200, 66, 133, 244));
            mBorderPaint.setAntiAlias(true);
            mBorderPaint.setStyle(Paint.Style.STROKE);
            mBorderPaint.setStrokeWidth(3f);

            // Feedback text view
            mFeedbackText = new TextView(context);
            mFeedbackText.setTextColor(Color.WHITE);
            mFeedbackText.setTextSize(18f);
            mFeedbackText.setPadding(48, 48, 48, 48);

            LayoutParams params = new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.gravity = android.view.Gravity.CENTER;
            addView(mFeedbackText, params);

            mFeedbackText.setText("Listening...");
        }

        public void setFeedbackText(String text) {
            mFeedbackText.setText(text);
        }

        public void setAudioLevel(float level) {
            mAudioLevel = level;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Rect rect = new Rect(0, 0, getWidth(), getHeight());
            canvas.drawRect(rect, mBackgroundPaint);
            canvas.drawRect(rect, mBorderPaint);
            super.onDraw(canvas);
        }
    }
}
