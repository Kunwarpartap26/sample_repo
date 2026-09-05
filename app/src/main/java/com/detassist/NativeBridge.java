package com.detassist;

import android.util.Log;

/**
 * JNI Bridge to native C++ keyword spotting engine.
 *
 * ARCHITECTURAL CONSTRAINTS:
 *   - Zero-copy memory access via GetPrimitiveArrayCritical
 *   - Direct PCM pointer passing to embedded engine
 *   - No memory allocation in native processing path
 *
 * NATIVE ENGINE OPTIONS:
 *   - Porcupine (Picovoice) — Commercial, highly optimized, ~2MB RAM
 *   - Pocketsphinx (CMU) — Open source, HMM/GMM-based, ~5MB RAM
 *
 * Currently: fallback mode (no engine integrated, returns -1 always).
 * To activate: uncomment PORCUPINE_INTEGRATION or POCKETSPHINX_INTEGRATION
 * in NativeBridge.cpp and provide required model files.
 */
public class NativeBridge {

    private static final String TAG = "NativeBridge";
    private static final String LIB_NAME = "detassist_native";

    // Native engine handle (opaque pointer)
    private long mEngineHandle = 0;
    private boolean mInitialized = false;
    private boolean mEngineAvailable = false; // True if a real engine is loaded

    // Load native library
    static {
        try {
            System.loadLibrary(LIB_NAME);
            Log.i(TAG, "Native library loaded: " + LIB_NAME);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
        }
    }

    /**
     * Initialize the native keyword spotting engine.
     * Must be called before detectKeyword().
     *
     * @param accessKey Picovoice access key (null if not using Porcupine)
     * @param modelPath Path to keyword model file on filesystem (null if not available)
     * @return true if initialization successful
     */
    public boolean initialize(String accessKey, String modelPath) {
        if (mInitialized) {
            Log.w(TAG, "Already initialized");
            return true;
        }

        try {
            mEngineHandle = nativeInitialize(accessKey, modelPath);
            mInitialized = (mEngineHandle != 0);

            // Check if a real engine is available (vs fallback mode)
            String version = nativeGetVersion(mEngineHandle);
            mEngineAvailable = version != null && !version.contains("No engine");

            if (mInitialized) {
                Log.i(TAG, "Native engine initialized: " + version);
                if (!mEngineAvailable) {
                    Log.w(TAG, "Wake word detection is DISABLED — no engine integrated");
                }
            } else {
                Log.e(TAG, "Native engine initialization failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during initialization", e);
            mInitialized = false;
            mEngineAvailable = false;
        }

        return mInitialized;
    }

    /**
     * Convenience: Initialize with no engine parameters (fallback mode).
     */
    public boolean initialize() {
        return initialize(null, null);
    }

    /**
     * Detect wake word keyword in audio frame.
     *
     * CRITICAL: This method uses GetPrimitiveArrayCritical to pin the Java array
     * directly in native memory, ensuring ZERO data duplication.
     *
     * @param pcmFrame 16-bit PCM audio frame (512 samples @ 16kHz = 32ms)
     * @return keyword index (0+ if detected, -1 if not detected)
     */
    public int detectKeyword(short[] pcmFrame) {
        if (!mInitialized || mEngineHandle == 0) {
            return -1;
        }

        if (pcmFrame == null || pcmFrame.length != 512) {
            Log.w(TAG, "Invalid frame: length=" + (pcmFrame != null ? pcmFrame.length : 0));
            return -1;
        }

        // Native call with critical array pinning
        return nativeDetectKeyword(mEngineHandle, pcmFrame);
    }

    /**
     * Check if a real keyword spotting engine is available.
     * When false, detectKeyword() always returns -1.
     */
    public boolean isEngineAvailable() {
        return mEngineAvailable;
    }

    /**
     * Release native engine resources.
     */
    public void release() {
        if (!mInitialized) {
            return;
        }

        try {
            nativeRelease(mEngineHandle);
            mEngineHandle = 0;
            mInitialized = false;
            mEngineAvailable = false;
            Log.i(TAG, "Native engine released");
        } catch (Exception e) {
            Log.e(TAG, "Exception during release", e);
        }
    }

    /**
     * Get engine version string.
     */
    public String getVersion() {
        if (!mInitialized) {
            return "uninitialized";
        }
        return nativeGetVersion(mEngineHandle);
    }

    // ========================================
    // Native Method Declarations (JNI)
    // ========================================

    /**
     * Initialize native engine and return opaque handle.
     * @param accessKey Picovoice access key (may be null)
     * @param modelPath Path to keyword model file (may be null)
     */
    private static native long nativeInitialize(String accessKey, String modelPath);

    /**
     * Detect keyword in PCM frame.
     * Uses GetPrimitiveArrayCritical for zero-copy access.
     */
    private static native int nativeDetectKeyword(long engineHandle, short[] pcmFrame);

    /**
     * Release native engine resources.
     */
    private static native void nativeRelease(long engineHandle);

    /**
     * Get engine version string.
     */
    private static native String nativeGetVersion(long engineHandle);
}
