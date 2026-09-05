package com.detassist;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.util.Log;

/**
 * VoiceInteractionSessionService - Session Factory
 *
 * The OS calls {@link #onNewSession(Bundle)} when the assistant needs to show UI.
 * This is a thin factory — actual session logic lives in DetAssistVoiceInteractionSession.
 *
 * CORRECT API: The framework abstract method is onNewSession(Bundle), NOT onCreateSession().
 * Verified against compileSdk 34 / API 34 Android SDK source.
 */
public class DetAssistVoiceInteractionSessionService extends VoiceInteractionSessionService {

    private static final String TAG = "DetAssistVISS";

    /**
     * Called by the Android framework when a new voice interaction session is needed.
     * This is the correct override — VoiceInteractionSessionService declares:
     *   public abstract VoiceInteractionSession onNewSession(Bundle args);
     *
     * @param args Bundle of arguments from the system (may contain session context)
     * @return a new VoiceInteractionSession instance
     */
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        Log.i(TAG, "onNewSession called, creating DetAssistVoiceInteractionSession");
        return new DetAssistVoiceInteractionSession(this, args);
    }
}
