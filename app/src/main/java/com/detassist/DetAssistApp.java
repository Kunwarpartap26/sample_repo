package com.detassist;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Application class - Initialization & Shared Components
 * 
 * Responsibilities:
 * - Initialize shared AppIndex (package cache)
 * - Setup notification channels
 * - Start AudioListenerService on boot
 * - Provide singleton access to shared components
 */
public class DetAssistApp extends Application {

    private static final String TAG = "DetAssistApp";

    // Shared components
    private RegexCommandParser mParser;
    private AppIndex mAppIndex;
    
    // Singleton access
    private static DetAssistApp sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        
        Log.i(TAG, "Application created");
        
        // Initialize shared components
        mParser = new RegexCommandParser();
        mAppIndex = new AppIndex(this, mParser);
        
        // Build app index asynchronously
        new Thread(new Runnable() {
            @Override
            public void run() {
                mAppIndex.build();
                Log.i(TAG, "App index build complete: " + mAppIndex.getAppCount() + " apps");
            }
        }, "AppIndexBuilder").start();
        
        // Create notification channels
        createNotificationChannels();
    }

    /**
     * Start the AudioListenerService (foreground service for wake-word detection).
     */
    public void startAudioListener() {
        Log.i(TAG, "Starting AudioListenerService");
        
        try {
            Intent serviceIntent = new Intent(this, AudioListenerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioListenerService", e);
        }
    }

    /**
     * Get the shared AppIndex instance.
     */
    public AppIndex getAppIndex() {
        return mAppIndex;
    }

    /**
     * Get the shared RegexCommandParser instance.
     */
    public RegexCommandParser getParser() {
        return mParser;
    }

    /**
     * Get the application instance.
     */
    public static DetAssistApp getInstance() {
        return sInstance;
    }

    /**
     * Create all notification channels needed by the app.
     */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            
            // Wake word listening channel
            NotificationChannel wakeChannel = new NotificationChannel(
                "det_assist_wake",
                "Wake Word Detection",
                NotificationManager.IMPORTANCE_LOW
            );
            wakeChannel.setDescription("Indicates the assistant is listening for the wake word");
            wakeChannel.setShowBadge(false);
            manager.createNotificationChannel(wakeChannel);
            
            // Action feedback channel
            NotificationChannel feedbackChannel = new NotificationChannel(
                "det_assist_feedback",
                "Assistant Feedback",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            feedbackChannel.setDescription("Shows assistant responses to commands");
            feedbackChannel.setShowBadge(false);
            manager.createNotificationChannel(feedbackChannel);
            
            // Timer notification channel
            NotificationChannel timerChannel = new NotificationChannel(
                "det_assist_timer",
                "Timer Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            timerChannel.setDescription("Timer completion alerts");
            timerChannel.enableVibration(true);
            timerChannel.setShowBadge(false);
            manager.createNotificationChannel(timerChannel);
            
            Log.i(TAG, "Notification channels created");
        }
    }

    @Override
    public void onTerminate() {
        Log.i(TAG, "Application terminating");
        super.onTerminate();
    }
}
