package com.detassist;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Boot Receiver — Auto-start assistant after device boot.
 *
 * FIXES APPLIED:
 *   - Permission check before starting AudioListenerService (issue 2.1)
 *   - Receiver is declared android:exported="false" in manifest (issue 2.2)
 *     so only the system can deliver BOOT_COMPLETED to it
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        // Verify this is actually a boot completed broadcast
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.i(TAG, "Boot completed detected");

        // FIX (2.1): Check permissions BEFORE attempting to start the service.
        // Without RECORD_AUDIO, the service will crash (NPE on AudioRecord).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO not granted — skipping audio listener start on boot");
            return;
        }

        DetAssistApp app = DetAssistApp.getInstance();
        if (app != null) {
            app.startAudioListener();
            Log.i(TAG, "Audio listener started on boot");
        } else {
            Log.w(TAG, "DetAssistApp instance not available");
        }
    }
}
