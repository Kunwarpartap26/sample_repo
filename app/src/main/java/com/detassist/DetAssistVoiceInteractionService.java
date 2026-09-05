package com.detassist;

import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionService;
import android.util.Log;

/**
 * VoiceInteractionService — OS-Level Assistant Registration
 *
 * This is the core service that registers our app as the device's default
 * voice assistant. It's invoked when the user:
 *   - Long-presses the home button
 *   - Triggers from the lock screen
 *   - Presses the search/assistant hardware button
 *
 * ARCHITECTURAL CONSTRAINTS:
 *   - Extends VoiceInteractionService (not AccessibilityService)
 *   - Zero AI/ML for intent parsing
 *   - Deterministic regex-based command processing
 *
 * FLOW:
 *   OS triggers → onReady() → showSession() → VoiceInteractionSessionService
 *   → onNewSession() → DetAssistVoiceInteractionSession (handles STT + commands)
 */
public class DetAssistVoiceInteractionService extends VoiceInteractionService {

    private static final String TAG = "DetAssistVIS";

    private Handler mMainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "VoiceInteractionService created");
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Called by the OS when this service is bound as the default assistant.
     */
    @Override
    public void onReady() {
        super.onReady();
        Log.i(TAG, "VoiceInteractionService ready");

        // The AudioListenerService handles wake-word detection via AudioRecord.
        // This VoiceInteractionService is the OS registration point.
        //
        // AlwaysOnHotwordDetector could be used if the OEM provides
        // hardware-level keyword detection support (e.g., Qualcomm Sensing Hub).
        // For broad compatibility, we use our own AudioRecord-based approach.
    }

    /**
     * Called when the user invokes the assistant (home button long-press, etc.)
     */
    @Override
    public void onHandleAssist(
            android.os.Bundle data,
            android.service.voice.AssistStructure structure,
            android.service.voice.AssistContent content) {
        super.onHandleAssist(data, structure, content);
        Log.i(TAG, "onHandleAssist invoked");
    }

    /**
     * Called when the assistant is invoked with screen context.
     */
    @Override
    public void onHandleScreenshot(byte[] screenshot) {
        super.onHandleScreenshot(screenshot);
        Log.d(TAG, "Screenshot captured for context");
    }

    /**
     * Request the system to show the session.
     * This triggers VoiceInteractionSessionService.onNewSession().
     */
    public void showAssistant() {
        showSession(null, 0);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "VoiceInteractionService destroyed");
        super.onDestroy();
    }
}
