package com.detassist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ActionDispatcher {

    private static final String TAG = "ActionDispatcher";
    private static final long SETTLE_DELAY_MS = 350;

    private final Context mContext;
    private final AppIndex mAppIndex;
    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;
    private Handler mMainHandler;

    private boolean mFlashlightOn = false;
    private String mCameraId = null;

    private final AtomicLong mCurrentSequenceId = new AtomicLong(0);
    private final Object mSequenceLock = new Object();
    private volatile long mActiveSequenceId = -1;

    public interface ExecutionCallback {
        void onCommandStarted(RegexCommandParser.ParsedCommand command);
        void onCommandCompleted(RegexCommandParser.ParsedCommand command, boolean success);
        void onSequenceCompleted(int totalCommands, int successCount);
        void onError(String error);
        void onSpeechFeedback(String text);
    }

    private ExecutionCallback mCallback;

    public ActionDispatcher(Context context, AppIndex appIndex) {
        mContext = context.getApplicationContext();
        mAppIndex = appIndex;
        mMainHandler = new Handler(Looper.getMainLooper());

        try {
            CameraManager camManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
            if (camManager != null) {
                String[] ids = camManager.getCameraIdList();
                for (String id : ids) {
                    if (camManager.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == Boolean.TRUE) {
                        mCameraId = id;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Camera manager not available", e);
        }

        mWorkerThread = new HandlerThread("ActionDispatcherThread");
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());
    }

    public void setCallback(ExecutionCallback callback) {
        mCallback = callback;
    }

    public void executeSequence(final List<RegexCommandParser.ParsedCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            Log.w(TAG, "No commands to execute");
            return;
        }

        long newId = mCurrentSequenceId.incrementAndGet();
        mActiveSequenceId = newId;

        mWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                executeSequenceInternal(commands, newId, 0, 0);
            }
        });
    }

    private void executeSequenceInternal(
            final List<RegexCommandParser.ParsedCommand> commands,
            final long sequenceId,
            final int index,
            final int successCount) {

        if (sequenceId != mActiveSequenceId) {
            Log.i(TAG, "Sequence " + sequenceId + " cancelled (new sequence active)");
            return;
        }

        if (index >= commands.size()) {
            notifySequenceCompleted(commands.size(), successCount);
            return;
        }

        final RegexCommandParser.ParsedCommand cmd = commands.get(index);

        if (cmd.type == RegexCommandParser.TYPE_UNKNOWN) {
            Log.e(TAG, "HALT: Unknown command at position " + index + ": " + cmd.rawText);
            notifyError("I didn't understand: " + cmd.rawText);
            notifySpeechFeedback("I didn't understand that");
            return;
        }

        notifyCommandStarted(cmd);

        boolean success = executeCommand(cmd);

        if (success) {
            notifyCommandCompleted(cmd, true);
            int newSuccessCount = successCount + 1;

            if (index < commands.size() - 1) {
                mWorkerHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        executeSequenceInternal(commands, sequenceId, index + 1, newSuccessCount);
                    }
                }, SETTLE_DELAY_MS);
            } else {
                notifySequenceCompleted(commands.size(), newSuccessCount);
            }
        } else {
            notifyCommandCompleted(cmd, false);
            Log.e(TAG, "HALT: Command failed at position " + index + ": " + cmd);
            notifyError("Failed to execute: " + cmd);
        }
    }

    public void cancelCurrentSequence() {
        mCurrentSequenceId.incrementAndGet();
        mWorkerHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Current sequence cancelled");
    }

    private boolean executeCommand(RegexCommandParser.ParsedCommand cmd) {
        Log.i(TAG, "Executing: " + cmd);

        switch (cmd.type) {
            case RegexCommandParser.TYPE_OPEN_APP:
                return executeOpenApp(cmd);
            case RegexCommandParser.TYPE_CLOSE_APP:
                return executeCloseApp(cmd);
            case RegexCommandParser.TYPE_TOGGLE:
                return executeToggle(cmd);
            case RegexCommandParser.TYPE_SEND_MESSAGE:
                return executeSendMessage(cmd);
            case RegexCommandParser.TYPE_CALL:
                return executeCall(cmd);
            case RegexCommandParser.TYPE_SET_TIMER:
                return executeTimer(cmd);
            case RegexCommandParser.TYPE_NAVIGATE:
                return executeNavigate(cmd);
            case RegexCommandParser.TYPE_SEARCH:
                return executeSearch(cmd);
            default:
                Log.e(TAG, "Unknown command type: " + cmd.type);
                return false;
        }
    }

    private boolean executeOpenApp(RegexCommandParser.ParsedCommand cmd) {
        String spokenName = cmd.appName;
        AppIndex.AppEntry entry = mAppIndex.resolve(spokenName);

        if (entry == null) {
            String knownPackage = mAppIndex.getKnownPackage(spokenName);
            if (knownPackage != null) {
                entry = mAppIndex.resolveByPackage(knownPackage);
            }
        }

        if (entry == null) {
            Log.e(TAG, "App not found: " + spokenName);
            notifySpeechFeedback("I couldn't find an app called " + spokenName);
            return false;
        }

        Intent launchIntent = mContext.getPackageManager()
                .getLaunchIntentForPackage(entry.packageName);

        if (launchIntent == null) {
            launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.setClassName(entry.packageName, entry.activityClass);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        }

        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED |
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);

        try {
            mContext.startActivity(launchIntent);
            notifySpeechFeedback("Opening " + entry.label);
            Log.i(TAG, "Launched: " + entry.label + " (" + entry.packageName + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch: " + entry.packageName, e);
            notifySpeechFeedback("Failed to open " + entry.label);
            return false;
        }
    }

    private boolean executeCloseApp(RegexCommandParser.ParsedCommand cmd) {
        String spokenName = cmd.appName;
        AppIndex.AppEntry entry = mAppIndex.resolve(spokenName);

        if (entry == null) {
            notifySpeechFeedback("I couldn't find " + spokenName);
            return false;
        }

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + entry.packageName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            mContext.startActivity(intent);
            notifySpeechFeedback("I can't force-close apps, but here's the settings for " + entry.label);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open app settings", e);
            return false;
        }
    }

    @SuppressLint("NewApi")
    private boolean executeToggle(RegexCommandParser.ParsedCommand cmd) {
        String feature = cmd.feature;
        boolean turnOn = (cmd.toggleState == RegexCommandParser.TOGGLE_ON);

        switch (feature) {
            case RegexCommandParser.FEATURE_FLASHLIGHT:
                return toggleFlashlight(turnOn);
            case RegexCommandParser.FEATURE_AUTO_ROTATE:
                return toggleAutoRotate(turnOn);
            case RegexCommandParser.FEATURE_WIFI:
                return toggleViaSettingsPanel("Wi-Fi",
                        Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                        Settings.ACTION_WIFI_SETTINGS);
            case RegexCommandParser.FEATURE_BLUETOOTH:
                return toggleBluetooth();
            case RegexCommandParser.FEATURE_DND:
                return toggleDND();
            case RegexCommandParser.FEATURE_AIRPLANE:
                return toggleViaSettings("Airplane mode",
                        Settings.ACTION_AIRPLANE_MODE_SETTINGS);
            case RegexCommandParser.FEATURE_MOBILE_DATA:
                return toggleViaSettingsPanel("Mobile data",
                        Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                        Settings.ACTION_DATA_ROAMING_SETTINGS);
            case RegexCommandParser.FEATURE_HOTSPOT:
                return toggleViaSettings("Hotspot",
                        Settings.ACTION_WIFI_SETTINGS);
            case RegexCommandParser.FEATURE_LOCATION:
                return toggleViaSettings("Location",
                        Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            case RegexCommandParser.FEATURE_NFC:
                return toggleViaSettings("NFC",
                        Settings.ACTION_NFC_SETTINGS);
            default:
                Log.w(TAG, "Unknown feature: " + feature);
                notifySpeechFeedback("I don't know how to control " + feature);
                return false;
        }
    }

    private boolean toggleFlashlight(boolean on) {
        if (mCameraId == null) {
            notifySpeechFeedback("Flashlight not available on this device");
            return false;
        }

        try {
            CameraManager camManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
            if (camManager != null) {
                camManager.setTorchMode(mCameraId, on);
                mFlashlightOn = on;
                notifySpeechFeedback(on ? "Flashlight turned on" : "Flashlight turned off");
                return true;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error", e);
        }

        notifySpeechFeedback("Failed to toggle flashlight");
        return false;
    }

    private boolean toggleBluetooth() {
        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
            notifySpeechFeedback("Opening Bluetooth settings — please toggle it there");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Bluetooth settings", e);
            notifySpeechFeedback("Failed to open Bluetooth settings");
            return false;
        }
    }

    private boolean toggleDND() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent intent = new Intent(Settings.Panel.ACTION_VOLUME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mContext.startActivity(intent);
                notifySpeechFeedback("Opening volume settings — toggle Do Not Disturb there");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to open volume panel", e);
            }
        }

        Intent intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
            notifySpeechFeedback("Opening sound settings — toggle Do Not Disturb there");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open sound settings", e);
            notifySpeechFeedback("Failed to open sound settings");
            return false;
        }
    }

    private boolean toggleViaSettingsPanel(String name, String panelAction, String fallbackAction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Intent intent = new Intent(panelAction);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                notifySpeechFeedback("Opening " + name + " settings — please toggle it there");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to open settings panel for " + name, e);
            }
        }
        return toggleViaSettings(name, fallbackAction);
    }

    private boolean toggleViaSettings(String name, String settingsAction) {
        Intent intent = new Intent(settingsAction);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
            notifySpeechFeedback("Opening " + name + " settings — please toggle it there");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open settings for " + name, e);
            notifySpeechFeedback("Failed to open " + name + " settings");
            return false;
        }
    }

    private boolean toggleAutoRotate(boolean on) {
        try {
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
            notifySpeechFeedback(on ? "Auto-rotate turned on" : "Auto-rotate turned off");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to toggle auto-rotate (needs WRITE_SETTINGS permission)", e);
            return toggleViaSettings("Auto-rotate", Settings.ACTION_DISPLAY_SETTINGS);
        }
    }

    private boolean executeSendMessage(RegexCommandParser.ParsedCommand cmd) {
        String message = cmd.messageText;
        String contact = cmd.contact;
        String app = cmd.messagingApp.toLowerCase();

        ContactResult contactResult = resolveContactBest(contact);

        if (contactResult == null) {
            notifySpeechFeedback("I couldn't find " + contact + " in your contacts");
            return false;
        }

        if (contactResult.isAmbiguous) {
            notifySpeechFeedback("Sending to " + contactResult.displayName +
                    " — there are multiple matches, this may not be the right person");
        }

        String phoneNumber = contactResult.phoneNumber;

        if (app.contains("whatsapp") || app.contains("whats app")) {
            return sendMessageViaWhatsApp(phoneNumber, message);
        } else if (app.contains("telegram")) {
            return sendMessageViaTelegram(phoneNumber, message);
        } else if (app.contains("signal")) {
            return sendMessageViaSignal(phoneNumber, message);
        } else {
            return sendMessageViaSms(phoneNumber, message);
        }
    }

    private boolean sendMessageViaWhatsApp(String phone, String message) {
        try {
            PackageManager pm = mContext.getPackageManager();
            pm.getPackageInfo("com.whatsapp", 0);

            String encodedMessage = Uri.encode(message);
            String cleanPhone = phone.replaceAll("[^0-9+]", "");

            Uri uri = Uri.parse("whatsapp://send")
                    .buildUpon()
                    .appendQueryParameter("phone", cleanPhone)
                    .appendQueryParameter("text", encodedMessage)
                    .build();

            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage("com.whatsapp");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(intent);
            notifySpeechFeedback("Sending WhatsApp message");
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            notifySpeechFeedback("WhatsApp is not installed");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send WhatsApp message", e);
            notifySpeechFeedback("Failed to send message");
            return false;
        }
    }

    private boolean sendMessageViaSms(String phone, String message) {
        try {
            Uri uri = Uri.parse("sms:" + phone);
            Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
            intent.putExtra("sms_body", message);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(intent);
            notifySpeechFeedback("Opening message app");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open SMS", e);
            notifySpeechFeedback("Failed to open messaging");
            return false;
        }
    }

    private boolean sendMessageViaTelegram(String phone, String message) {
        try {
            PackageManager pm = mContext.getPackageManager();
            pm.getPackageInfo("org.telegram.messenger", 0);

            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("tg://msg?text=" + Uri.encode(message)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(intent);
            notifySpeechFeedback("Opening Telegram");
            return true;
        } catch (Exception e) {
            notifySpeechFeedback("Telegram not available");
            return false;
        }
    }

    private boolean sendMessageViaSignal(String phone, String message) {
        try {
            PackageManager pm = mContext.getPackageManager();
            pm.getPackageInfo("org.thoughtcrime.securesms", 0);

            Intent intent = new Intent(Intent.ACTION_SENDTO,
                    Uri.parse("sgnl://signal.me/#p/" + Uri.encode(phone)));
            intent.putExtra("text", message);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(intent);
            notifySpeechFeedback("Opening Signal");
            return true;
        } catch (Exception e) {
            notifySpeechFeedback("Signal not available");
            return false;
        }
    }

    private static class ContactResult {
        final String phoneNumber;
        final String displayName;
        final boolean isAmbiguous;

        ContactResult(String phoneNumber, String displayName, boolean isAmbiguous) {
            this.phoneNumber = phoneNumber;
            this.displayName = displayName;
            this.isAmbiguous = isAmbiguous;
        }
    }

    private ContactResult resolveContactBest(String spokenName) {
        if (spokenName.matches("\\+?[0-9\\-\\s()]+") && spokenName.replaceAll("[^0-9]", "").length() >= 10) {
            return new ContactResult(spokenName, spokenName, false);
        }

        try {
            String[] projection = {
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            };

            String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {"%" + spokenName + "%"};

            android.database.Cursor cursor = mContext.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, selection, selectionArgs, null);

            if (cursor == null) return null;

            String bestNumber = null;
            String bestName = null;
            int bestDistance = Integer.MAX_VALUE;
            int matchCount = 0;
            RegexCommandParser parser = new RegexCommandParser();

            while (cursor.moveToNext()) {
                String number = cursor.getString(0);
                String name = cursor.getString(1);
                matchCount++;

                int dist = parser.calculateDistance(spokenName.toLowerCase(),
                        name.toLowerCase());
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestNumber = number;
                    bestName = name;
                }
            }
            cursor.close();

            if (bestNumber == null) return null;

            boolean ambiguous = matchCount > 1;
            return new ContactResult(bestNumber, bestName, ambiguous);

        } catch (SecurityException e) {
            Log.e(TAG, "READ_CONTACTS permission not granted", e);
            return null;
        }
    }

    private boolean executeCall(RegexCommandParser.ParsedCommand cmd) {
        ContactResult contactResult = resolveContactBest(cmd.callTarget);

        if (contactResult == null) {
            notifySpeechFeedback("I couldn't find " + cmd.callTarget + " in your contacts");
            return false;
        }

        String phoneNumber = contactResult.phoneNumber;

        try {
            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + phoneNumber));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
            notifySpeechFeedback("Calling " + contactResult.displayName);
            return true;
        } catch (SecurityException e) {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phoneNumber));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mContext.startActivity(intent);
                notifySpeechFeedback("Opening dialer for " + contactResult.displayName);
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "Failed to open dialer", ex);
                return false;
            }
        }
    }

    private boolean executeTimer(RegexCommandParser.ParsedCommand cmd) {
        long durationMs = cmd.durationMs;

        if (durationMs < 0) {
            notifySpeechFeedback("I didn't catch how long to set the timer for. Try saying something like 'set timer for 5 minutes'.");
            return false;
        }

        if (durationMs == 0) {
            notifySpeechFeedback("Timer duration is zero. Please specify a duration.");
            return false;
        }

        try {
            android.app.AlarmManager alarmManager =
                    (android.app.AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

            if (alarmManager != null) {
                long triggerTime = System.currentTimeMillis() + durationMs;

                Intent intent = new Intent("com.detassist.TIMER_FIRED");
                intent.putExtra("duration_ms", durationMs);

                android.app.PendingIntent pendingIntent = android.app.PendingIntent.getBroadcast(
                        mContext, 0, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                } else {
                    alarmManager.setExact(
                            android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                }

                long minutes = durationMs / 60000;
                long seconds = (durationMs % 60000) / 1000;
                String feedback;
                if (minutes > 0 && seconds > 0) {
                    feedback = "Timer set for " + minutes + " minutes " + seconds + " seconds";
                } else if (minutes > 0) {
                    feedback = "Timer set for " + minutes + " minutes";
                } else {
                    feedback = "Timer set for " + seconds + " seconds";
                }

                notifySpeechFeedback(feedback);
                Log.i(TAG, "Timer set: " + durationMs + "ms");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set timer", e);
        }

        notifySpeechFeedback("Failed to set timer");
        return false;
    }

    private boolean executeNavigate(RegexCommandParser.ParsedCommand cmd) {
        try {
            String encodedDest = Uri.encode(cmd.destination);
            Uri uri = Uri.parse("google.navigation:q=" + encodedDest);

            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage("com.google.android.apps.maps");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(intent);
            notifySpeechFeedback("Navigating to " + cmd.destination);
            return true;
        } catch (Exception e) {
            try {
                Uri uri = Uri.parse("geo:0,0?q=" + Uri.encode(cmd.destination));
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                notifySpeechFeedback("Opening maps for " + cmd.destination);
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "Failed to navigate", ex);
                notifySpeechFeedback("Failed to open navigation");
                return false;
            }
        }
    }

    private boolean executeSearch(RegexCommandParser.ParsedCommand cmd) {
        String query = cmd.searchQuery;

        AppIndex.AppEntry appMatch = mAppIndex.resolve(query);
        if (appMatch != null) {
            Intent launchIntent = mContext.getPackageManager()
                    .getLaunchIntentForPackage(appMatch.packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    mContext.startActivity(launchIntent);
                    notifySpeechFeedback("Opening " + appMatch.label);
                    return true;
                } catch (Exception e) {
                    // Fall through to web search
                }
            }
        }

        try {
            Uri uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query));
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(intent);
            notifySpeechFeedback("Opening web search for " + query + " — this requires an internet connection");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Search failed", e);
            notifySpeechFeedback("Search not available");
            return false;
        }
    }

    private void notifyCommandStarted(RegexCommandParser.ParsedCommand cmd) {
        if (mCallback != null) mCallback.onCommandStarted(cmd);
    }

    private void notifyCommandCompleted(RegexCommandParser.ParsedCommand cmd, boolean success) {
        if (mCallback != null) mCallback.onCommandCompleted(cmd, success);
    }

    private void notifySequenceCompleted(int total, int successCount) {
        if (mCallback != null) mCallback.onSequenceCompleted(total, successCount);
    }

    private void notifyError(String error) {
        if (mCallback != null) mCallback.onError(error);
    }

    private void notifySpeechFeedback(String text) {
        if (mCallback != null) mCallback.onSpeechFeedback(text);
    }

    public void release() {
        cancelCurrentSequence();
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
            mWorkerThread = null;
        }
    }
}
