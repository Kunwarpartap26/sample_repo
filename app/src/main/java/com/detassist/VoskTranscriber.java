package com.detassist;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;

/**
 * Vosk Offline Speech-to-Text Transcriber
 *
 * Wraps the Vosk Android library for fully offline, on-device speech recognition.
 * Used in the wake-word audio path: when AudioListenerService captures an utterance
 * after detecting the wake word, this class converts the PCM buffer to text.
 *
 * Vosk model files:
 *   - Download a small English model from https://alphacephei.com/vosk/models
 *   - Recommended: vosk-model-small-en-us-0.15 (~40MB)
 *   - Place in: app/src/main/assets/vosk-model/
 *   - Or download at runtime and store in app's internal storage
 *
 * LICENSE: Vosk is Apache 2.0 licensed. The model files are separately licensed.
 *
 * INTEGRATION:
 *   1. Add to build.gradle: implementation 'com.alphacephei:vosk-android:0.3.47'
 *   2. In build.gradle android.defaultConfig.ndk, add: abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'
 *   3. Place model files in assets/vosk-model/
 *   4. Call transcribe(short[], int) to convert PCM to text
 *
 * MEMORY: Vosk's small model uses ~50MB RAM during recognition.
 * For ultra-low-RAM targets (4GB total), use the "tiny" model variant (~20MB).
 */
public class VoskTranscriber {

    private static final String TAG = "VoskTranscriber";
    private static final String MODEL_DIR = "vosk-model";
    private static final int SAMPLE_RATE = 16000;

    private final Context mContext;
    private Object mVoskModel;      // org.vosk.Model (Object type to allow graceful degradation)
    private Object mVoskRecognizer; // org.vosk.Recognizer
    private boolean mAvailable = false;
    private String mModelPath;

    // ========================================
    // Constructor & Initialization
    // ========================================

    public VoskTranscriber(Context context) {
        mContext = context.getApplicationContext();
        initialize();
    }

    /**
     * Initialize Vosk engine with model from assets.
     * If Vosk library is not available or model not found, gracefully degrades
     * (transcribe() returns null, caller handles this).
     */
    private void initialize() {
        try {
            // Check if Vosk library is available at runtime
            Class<?> modelClass = Class.forName("org.vosk.Model");
            Class<?> recognizerClass = Class.forName("org.vosk.Recognizer");

            // Extract model from assets to internal storage
            mModelPath = extractModelFromAssets();

            if (mModelPath == null) {
                Log.w(TAG, "Vosk model not found in assets. Transcription unavailable.");
                Log.w(TAG, "To enable: place Vosk model in app/src/main/assets/" + MODEL_DIR + "/");
                mAvailable = false;
                return;
            }

            // Create model and recognizer via reflection (avoids hard compile dependency)
            mVoskModel = modelClass.getConstructor(String.class).newInstance(mModelPath);
            mVoskRecognizer = recognizerClass.getConstructor(modelClass, float.class)
                    .newInstance(mVoskModel, (float) SAMPLE_RATE);

            mAvailable = true;
            Log.i(TAG, "Vosk transcriber initialized successfully");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Vosk library not found in classpath. Offline transcription disabled.");
            Log.w(TAG, "Add to build.gradle: implementation 'com.alphacephei:vosk-android:0.3.47'");
            mAvailable = false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Vosk", e);
            mAvailable = false;
        }
    }

    /**
     * Extract Vosk model from APK assets to internal storage.
     * Vosk requires model files on the filesystem (not inside APK).
     *
     * @return path to extracted model directory, or null if not found
     */
    private String extractModelFromAssets() {
        File modelDir = new File(mContext.getFilesDir(), MODEL_DIR);

        // Already extracted?
        if (modelDir.exists() && modelDir.isDirectory()) {
            File[] contents = modelDir.listFiles();
            if (contents != null && contents.length > 0) {
                return modelDir.getAbsolutePath();
            }
        }

        // Extract from assets
        try {
            AssetManager assets = mContext.getAssets();
            String[] assetFiles = assets.list(MODEL_DIR);

            if (assetFiles == null || assetFiles.length == 0) {
                Log.w(TAG, "No Vosk model files found in assets/" + MODEL_DIR);
                return null;
            }

            // Create target directory
            if (!modelDir.exists()) {
                modelDir.mkdirs();
            }

            // Copy all model files
            for (String fileName : assetFiles) {
                copyAssetFile(MODEL_DIR + "/" + fileName, new File(modelDir, fileName));
            }

            Log.i(TAG, "Vosk model extracted to: " + modelDir.getAbsolutePath());
            return modelDir.getAbsolutePath();

        } catch (IOException e) {
            Log.e(TAG, "Failed to extract Vosk model from assets", e);
            return null;
        }
    }

    /**
     * Copy a single file from assets to filesystem.
     */
    private void copyAssetFile(String assetPath, File outFile) throws IOException {
        AssetManager assets = mContext.getAssets();

        // Check if it's a directory
        String[] subFiles = assets.list(assetPath);
        if (subFiles != null && subFiles.length > 0) {
            // It's a directory — recurse
            outFile.mkdirs();
            for (String sub : subFiles) {
                copyAssetFile(assetPath + "/" + sub, new File(outFile, sub));
            }
            return;
        }

        // It's a file — copy it
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = assets.open(assetPath);
            out = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    // ========================================
    // Transcription
    // ========================================

    /**
     * Transcribe a PCM audio buffer to text.
     *
     * @param pcmData 16-bit 16kHz mono PCM samples
     * @param sampleCount number of valid samples in the array
     * @return transcribed text, or null if unavailable/failed
     */
    public String transcribe(short[] pcmData, int sampleCount) {
        if (!mAvailable || mVoskRecognizer == null) {
            Log.w(TAG, "Transcriber not available");
            return null;
        }

        if (pcmData == null || sampleCount <= 0) {
            return null;
        }

        try {
            // Reset recognizer for new utterance
            mVoskRecognizer.getClass().getMethod("reset").invoke(mVoskRecognizer);

            // Feed audio in chunks (Vosk processes in real-time)
            int chunkSize = 4000; // Process in 4000-sample chunks
            StringBuilder resultBuilder = new StringBuilder();

            for (int offset = 0; offset < sampleCount; offset += chunkSize) {
                int length = Math.min(chunkSize, sampleCount - offset);
                byte[] byteBuffer = shortsToBytes(pcmData, offset, length);

                // Call acceptWaveForm(byte[], int) via reflection
                boolean hasResult = (Boolean) mVoskRecognizer.getClass()
                        .getMethod("acceptWaveForm", byte[].class, int.class)
                        .invoke(mVoskRecognizer, byteBuffer, length * 2);

                if (hasResult) {
                    String partial = (String) mVoskRecognizer.getClass()
                            .getMethod("getResult").invoke(mVoskRecognizer);
                    String text = extractTextFromResult(partial);
                    if (text != null && !text.isEmpty()) {
                        resultBuilder.append(text).append(" ");
                    }
                }
            }

            // Signal end of audio and get final result
            // Feed silence to flush
            byte[] silence = new byte[chunkSize * 2];
            mVoskRecognizer.getClass()
                    .getMethod("acceptWaveForm", byte[].class, int.class)
                    .invoke(mVoskRecognizer, silence, silence.length);

            String finalResult = (String) mVoskRecognizer.getClass()
                    .getMethod("getFinalResult").invoke(mVoskRecognizer);
            String finalText = extractTextFromResult(finalResult);

            if (finalText != null && !finalText.isEmpty()) {
                resultBuilder.append(finalText);
            }

            String result = resultBuilder.toString().trim();
            Log.i(TAG, "Transcription result: '" + result + "'");
            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            Log.e(TAG, "Transcription error", e);
            return null;
        }
    }

    /**
     * Extract plain text from Vosk JSON result.
     * Vosk returns: {"text": "recognized words"}
     */
    private String extractTextFromResult(String jsonResult) {
        if (jsonResult == null || jsonResult.isEmpty()) return null;

        // Simple JSON parsing without importing a JSON library
        // Looking for "text": "value"
        int textKeyIdx = jsonResult.indexOf("\"text\"");
        if (textKeyIdx < 0) return null;

        int colonIdx = jsonResult.indexOf(':', textKeyIdx);
        if (colonIdx < 0) return null;

        int quoteStart = jsonResult.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;

        int quoteEnd = jsonResult.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;

        return jsonResult.substring(quoteStart + 1, quoteEnd).trim();
    }

    /**
     * Convert short[] PCM samples to byte[] for Vosk's acceptWaveForm.
     */
    private byte[] shortsToBytes(short[] shorts, int offset, int length) {
        byte[] bytes = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            short sample = shorts[offset + i];
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return bytes;
    }

    // ========================================
    // Cleanup
    // ========================================

    /**
     * Release Vosk resources.
     */
    public void release() {
        try {
            if (mVoskRecognizer != null) {
                mVoskRecognizer.getClass().getMethod("close").invoke(mVoskRecognizer);
                mVoskRecognizer = null;
            }
            if (mVoskModel != null) {
                mVoskModel.getClass().getMethod("close").invoke(mVoskModel);
                mVoskModel = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing Vosk", e);
        }
        mAvailable = false;
        Log.i(TAG, "Vosk transcriber released");
    }

    /**
     * Check if transcriber is ready.
     */
    public boolean isAvailable() {
        return mAvailable;
    }
}
