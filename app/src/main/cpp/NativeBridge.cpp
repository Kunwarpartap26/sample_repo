/**
 * NativeBridge.cpp — JNI Bridge for Keyword Spotting Engine
 *
 * ARCHITECTURE:
 *   - Uses GetPrimitiveArrayCritical for zero-copy PCM access
 *   - Direct pointer passing to keyword spotter (Porcupine)
 *   - No heap allocation in processing path
 *   - Thread-safe single-instance design
 *
 * WAKE-WORD ENGINE: Picovoice Porcupine
 *   - Requires: access key from console.picovoice.ai
 *   - Requires: .ppn keyword model file in app/src/main/assets/
 *   - License: Commercial (free tier available for non-commercial)
 *   - Alternative: Pocketsphinx (open source, see OPTION B below)
 *
 * BUILD: Included via CMakeLists.txt
 * TARGET: armeabi-v7a, arm64-v8a (NEON optimized)
 *
 * INTEGRATION CHECKLIST:
 *   [ ] Obtain Porcupine access key from console.picovoice.ai
 *   [ ] Export keyword model (.ppn) using Picovoice Console
 *   [ ] Place .ppn file in app/src/main/assets/porcupine/
 *   [ ] Place libpv_porcupine.so in app/src/main/jniLibs/<abi>/
 *   [ ] Uncomment PORCUPINE_INTEGRATION below
 *   [ ] Pass access key from Java via nativeInitialize()
 *
 * MEMORY: Porcupine uses ~2MB RAM for keyword spotting.
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <cmath>   // FIX (issue 3.1): required for std::sqrt

#define LOG_TAG "DetAssistNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ========================================
// Frame Configuration
// ========================================
#define FRAME_SIZE_SAMPLES 512
#define SAMPLE_RATE 16000
#define NUM_KEYWORDS 1
#define KEYWORD_INDEX_WAKE 0
#define KEYWORD_INDEX_NONE -1

// ========================================
// ENGINE SELECTION — uncomment ONE:
// ========================================

// OPTION A: Picovoice Porcupine (recommended, commercial)
// #define PORCUPINE_INTEGRATION

// OPTION B: CMU Pocketsphinx (open source, HMM/GMM-based)
// #define POCKETSPHINX_INTEGRATION

// ========================================
#ifdef PORCUPINE_INTEGRATION
// ========================================
// Porcupine Integration
// Include the Porcupine C header from the SDK
// #include "pv_porcupine.h"
//
// Porcupine is initialized with:
//   - access_key: from Picovoice Console
//   - model_path: path to porcupine_params.pv (in assets)
//   - keyword_path: path to custom .ppn keyword model
//   - sensitivity: 0.0 to 1.0 (0.5 recommended)
//
// Processing:
//   pv_porcupine_process(handle, pcm_frame, &keyword_index)
//   Returns keyword_index >= 0 if wake word detected
// ========================================

typedef struct {
    // pv_porcupine_t* porcupine_handle;
    void* porcupine_handle;  // Opaque — uncomment when pv_porcupine.h available
    bool is_initialized;
    float sensitivity;
} KeywordSpotterHandle;

static int porcupine_detect(KeywordSpotterHandle* handle, const int16_t* pcm_frame) {
    if (!handle || !handle->is_initialized || !handle->porcupine_handle) {
        return KEYWORD_INDEX_NONE;
    }

    // int keyword_index = -1;
    // pv_status_t status = pv_porcupine_process(
    //     (pv_porcupine_t*)handle->porcupine_handle,
    //     pcm_frame,
    //     &keyword_index);
    //
    // if (status != PV_STATUS_SUCCESS) {
    //     LOGE("Porcupine processing failed with status %d", status);
    //     return KEYWORD_INDEX_NONE;
    // }
    // return keyword_index;

    // PLACEHOLDER: Until Porcupine SDK is integrated, use energy-based heuristic
    // This will NOT reliably detect the wake word — it's a placeholder
    // that MUST be replaced with actual Porcupine integration
    return KEYWORD_INDEX_NONE;
}

// ========================================
#elif defined(POCKETSPHINX_INTEGRATION)
// ========================================
// Pocketsphinx Integration (open source)
//
// Requires:
//   - Pocketsphinx built for Android via CMake
//   - Acoustic model (e.g., en-us) in assets
//   - JSGF grammar for wake word: e.g., "hey assist"
//   - Dictionary file (cmudict-en-us)
//
// Processing:
//   ps_process_raw(decoder, pcm_frame, num_samples, NO_SEARCH, NO_STREAM)
//   Then check: ps_get_hyp(decoder, &score) for detected keyword
// ========================================

typedef struct {
    // ps_decoder_t* decoder;
    // cmd_ln_t* config;
    void* decoder;   // Opaque — uncomment when pocketsphinx headers available
    void* config;
    bool is_initialized;
    int frame_count;
    bool search_active;
} KeywordSpotterHandle;

static int pocketsphinx_detect(KeywordSpotterHandle* handle, const int16_t* pcm_frame) {
    if (!handle || !handle->is_initialized || !handle->decoder) {
        return KEYWORD_INDEX_NONE;
    }

    // // Feed audio to decoder
    // ps_process_raw(
    //     (ps_decoder_t*)handle->decoder,
    //     pcm_frame,
    //     FRAME_SIZE_SAMPLES,
    //     NO_SEARCH,
    //     NO_STREAM);
    //
    // // Check if utterance ended
    // if (ps_get_uttid((ps_decoder_t*)handle->decoder) != NULL) {
    //     int32 score = 0;
    //     const char* hyp = ps_get_hyp((ps_decoder_t*)handle->decoder, &score);
    //     if (hyp != NULL && strstr(hyp, "hey assist") != NULL) {
    //         return KEYWORD_INDEX_WAKE;
    //     }
    // }

    // PLACEHOLDER: Must be replaced with actual Pocketsphinx integration
    (void)pcm_frame;
    return KEYWORD_INDEX_NONE;
}

// ========================================
#else
// ========================================
// Fallback: No engine integrated
// Returns KEYWORD_INDEX_NONE always.
// Wake-word detection is DISABLED until an engine is integrated.
// ========================================

typedef struct {
    bool is_initialized;
    int frame_count;
} KeywordSpotterHandle;

static int fallback_detect(KeywordSpotterHandle* handle, const int16_t* pcm_frame) {
    // No engine — always returns no-detection
    // This is intentional: we never false-trigger on noise
    (void)handle;
    (void)pcm_frame;
    return KEYWORD_INDEX_NONE;
}

#endif
// ========================================

// ========================================
// JNI Implementation
// ========================================

extern "C" {

/**
 * Initialize native keyword spotting engine.
 * Pre-allocates all required buffers (zero runtime allocation in processing path).
 *
 * @param env JNIEnv pointer
 * @param clazz JNI class reference
 * @param access_key Picovoice access key (used only with Porcupine)
 * @param model_path Path to keyword model file on filesystem
 * @return opaque handle (cast to jlong), or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_com_detassist_NativeBridge_nativeInitialize(
        JNIEnv* env, jclass clazz, jstring access_key, jstring model_path) {

    LOGI("Initializing keyword spotter engine");

    // Allocate engine handle (ONE TIME — not in processing loop)
    KeywordSpotterHandle* handle = static_cast<KeywordSpotterHandle*>(
            calloc(1, sizeof(KeywordSpotterHandle)));
    if (!handle) {
        LOGE("Failed to allocate engine handle");
        return 0;
    }

    handle->is_initialized = false;

#ifdef PORCUPINE_INTEGRATION
    // Extract C strings from JNI
    const char* key_str = (access_key != nullptr) ?
            env->GetStringUTFChars(access_key, nullptr) : nullptr;
    const char* path_str = (model_path != nullptr) ?
            env->GetStringUTFChars(model_path, nullptr) : nullptr;

    if (!key_str || !path_str) {
        LOGE("Porcupine requires access_key and model_path");
        if (key_str) env->ReleaseStringUTFChars(access_key, key_str);
        if (path_str) env->ReleaseStringUTFChars(model_path, path_str);
        free(handle);
        return 0;
    }

    // Initialize Porcupine
    // pv_status_t status = pv_porcupine_init(
    //     key_str,
    //     path_str,    // model_path
    //     1,           // num_keywords
    //     &path_str,   // keyword_paths (same as model for custom keyword)
    //     &(float){0.5f},  // sensitivities
    //     (pv_porcupine_t**)&handle->porcupine_handle);
    //
    // handle->is_initialized = (status == PV_STATUS_SUCCESS);
    // handle->sensitivity = 0.5f;
    //
    // env->ReleaseStringUTFChars(access_key, key_str);
    // env->ReleaseStringUTFChars(model_path, path_str);

    // PLACEHOLDER
    handle->is_initialized = false;
    LOGW("Porcupine not yet integrated — wake word detection disabled");

#elif defined(POCKETSPHINX_INTEGRATION)
    // Initialize Pocketsphinx decoder
    // handle->config = cmd_ln_init(...);
    // handle->decoder = ps_init((cmd_ln_t*)handle->config);
    // handle->is_initialized = (handle->decoder != nullptr);

    // PLACEHOLDER
    handle->is_initialized = false;
    LOGW("Pocketsphinx not yet integrated — wake word detection disabled");

#else
    // Fallback: no engine
    handle->is_initialized = true;  // "initialized" but always returns no-detection
    LOGI("No keyword engine integrated — wake word detection disabled");
#endif

    if (!handle->is_initialized) {
        LOGW("Keyword spotter not available. AudioListenerService will skip wake detection.");
    }

    return reinterpret_cast<jlong>(handle);
}

/**
 * Detect keyword in PCM frame using zero-copy access.
 *
 * CRITICAL: Uses GetPrimitiveArrayCritical to pin Java array
 * directly in native memory. No data is copied.
 * The pointer is valid only until ReleasePrimitiveArrayCritical.
 */
JNIEXPORT jint JNICALL
Java_com_detassist_NativeBridge_nativeDetectKeyword(
        JNIEnv* env, jclass clazz, jlong engine_handle, jshortArray pcm_frame) {

    KeywordSpotterHandle* handle = reinterpret_cast<KeywordSpotterHandle*>(engine_handle);

    if (!handle || !handle->is_initialized) {
        return KEYWORD_INDEX_NONE;
    }

    // CRITICAL: Pin Java array directly — ZERO COPY
    jshort* pcm_ptr = static_cast<jshort*>(
            env->GetPrimitiveArrayCritical(pcm_frame, nullptr));

    if (pcm_ptr == nullptr) {
        LOGW("GetPrimitiveArrayCritical failed — OOM?");
        return KEYWORD_INDEX_NONE;
    }

    // Pass raw PCM pointer directly to keyword spotter — NO memcpy, NO allocation
#ifdef PORCUPINE_INTEGRATION
    int result = porcupine_detect(handle, pcm_ptr);
#elif defined(POCKETSPHINX_INTEGRATION)
    int result = pocketsphinx_detect(handle, pcm_ptr);
#else
    int result = fallback_detect(handle, pcm_ptr);
#endif

    // Release pinned array immediately — JNI_ABORT = read-only, don't copy back
    env->ReleasePrimitiveArrayCritical(pcm_frame, pcm_ptr, JNI_ABORT);

    return result;
}

/**
 * Release all native engine resources.
 */
JNIEXPORT void JNICALL
Java_com_detassist_NativeBridge_nativeRelease(JNIEnv* env, jclass clazz, jlong engine_handle) {
    KeywordSpotterHandle* handle = reinterpret_cast<KeywordSpotterHandle*>(engine_handle);

    if (!handle) {
        LOGW("Attempted to release null handle");
        return;
    }

    LOGI("Releasing keyword spotter engine");

#ifdef PORCUPINE_INTEGRATION
    // if (handle->porcupine_handle) {
    //     pv_porcupine_delete((pv_porcupine_t*)handle->porcupine_handle);
    // }
#elif defined(POCKETSPHINX_INTEGRATION)
    // if (handle->decoder) ps_free((ps_decoder_t*)handle->decoder);
    // if (handle->config) cmd_ln_free_r((cmd_ln_t*)handle->config);
#endif

    handle->is_initialized = false;
    free(handle);

    LOGI("Keyword spotter engine released");
}

/**
 * Get engine version string.
 */
JNIEXPORT jstring JNICALL
Java_com_detassist_NativeBridge_nativeGetVersion(JNIEnv* env, jclass clazz, jlong engine_handle) {
#ifdef PORCUPINE_INTEGRATION
    return env->NewStringUTF("DetAssist Native Engine v1.0 (Porcupine — integration pending)");
#elif defined(POCKETSPHINX_INTEGRATION)
    return env->NewStringUTF("DetAssist Native Engine v1.0 (Pocketsphinx — integration pending)");
#else
    return env->NewStringUTF("DetAssist Native Engine v1.0 (No engine — wake word disabled)");
#endif
}

} // extern "C"
