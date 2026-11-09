// voicex_jni.cpp - the JNI layer between com.voicex.app.engine.TranscribeNative and transcribe.cpp.
//
// Only functions declared in include/transcribe.h are used. Policy (which device to try, the
// TRANSCRIBE_ERR_BACKEND -> CPU reload-and-retry, the benchmark) lives in Kotlin (AsrEngine), so this
// file stays a thin, testable wrapper. It also builds for the host (Linux + JDK headers) so the JNI
// smoke test can run in a plain JVM unit test.

#include <jni.h>

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <sched.h>
#include <unistd.h>

#include "transcribe.h"

#ifdef __ANDROID__
#include <android/log.h>
#define VX_LOG(prio, ...) __android_log_print(prio, "VoiceX-native", __VA_ARGS__)
#define VX_INFO ANDROID_LOG_INFO
#define VX_WARN ANDROID_LOG_WARN
#define VX_ERROR ANDROID_LOG_ERROR
#define VX_DEBUG ANDROID_LOG_DEBUG
#else
#define VX_LOG(prio, ...) (std::fprintf(stderr, "[VoiceX-native] " __VA_ARGS__), std::fputc('\n', stderr))
#define VX_INFO 0
#define VX_WARN 0
#define VX_ERROR 0
#define VX_DEBUG 0
#endif

namespace {

// One loaded model + session. The handle given to Kotlin is a pointer to this.
struct Engine {
    transcribe_session * session = nullptr;
    std::atomic<bool>    abort{false};
    std::string          backend;  // transcribe_model_backend(), e.g. "CPU", "Vulkan0", "HTP0"
};

bool abort_cb(void * user_data) {
    return static_cast<Engine *>(user_data)->abort.load(std::memory_order_relaxed);
}

void log_cb(transcribe_log_level level, const char * msg, void * /*userdata*/) {
    if (msg == nullptr) {
        return;
    }
    // transcribe.cpp messages may end with a newline; logcat adds its own.
    std::string m(msg);
    while (!m.empty() && (m.back() == '\n' || m.back() == '\r')) {
        m.pop_back();
    }
    if (m.empty()) {
        return;
    }
    switch (level) {
        case TRANSCRIBE_LOG_LEVEL_ERROR:
            VX_LOG(VX_ERROR, "%s", m.c_str());
            break;
        case TRANSCRIBE_LOG_LEVEL_WARN:
            VX_LOG(VX_WARN, "%s", m.c_str());
            break;
        case TRANSCRIBE_LOG_LEVEL_INFO:
            VX_LOG(VX_INFO, "%s", m.c_str());
            break;
        default:
            VX_LOG(VX_DEBUG, "%s", m.c_str());
            break;
    }
}

Engine * from_handle(jlong h) {
    return reinterpret_cast<Engine *>(static_cast<intptr_t>(h));
}

jbyteArray to_utf8_bytes(JNIEnv * env, const char * s) {
    // Raw UTF-8 bytes; Kotlin decodes them. NewStringUTF expects *modified* UTF-8 and would mangle
    // 4-byte sequences (emoji), so it is not used for transcript text.
    const size_t n   = (s != nullptr) ? std::strlen(s) : 0;
    jbyteArray   out = env->NewByteArray(static_cast<jsize>(n));
    if (out != nullptr && n > 0) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(s));
    }
    return out;
}

std::string jstr(JNIEnv * env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string  r = c != nullptr ? c : "";
    if (c != nullptr) {
        env->ReleaseStringUTFChars(s, c);
    }
    return r;
}

const char * device_type_name(transcribe_device_type t) {
    switch (t) {
        case TRANSCRIBE_DEVICE_TYPE_CPU:
            return "cpu";
        case TRANSCRIBE_DEVICE_TYPE_GPU:
            return "gpu";
        case TRANSCRIBE_DEVICE_TYPE_IGPU:
            return "igpu";
        case TRANSCRIBE_DEVICE_TYPE_ACCEL:
            return "accel";
    }
    return "unknown";
}

}  // namespace

#define JNI_FN(name) Java_com_voicex_app_engine_TranscribeNative_##name

extern "C" {

JNIEXPORT jstring JNICALL JNI_FN(nativeVersion)(JNIEnv * env, jobject) {
    std::string v = std::string(transcribe_version()) + " (" + transcribe_version_commit() + ")";
    return env->NewStringUTF(v.c_str());
}

JNIEXPORT jstring JNICALL JNI_FN(nativeStatusString)(JNIEnv * env, jobject, jint status) {
    const char * s = transcribe_status_string(status);
    return env->NewStringUTF(s != nullptr ? s : "unknown status");
}

// Registers the log callback and loads the backend modules from `dir` (the app's nativeLibraryDir, where
// libggml-cpu-*.so / libggml-vulkan.so / libggml-opencl.so / libggml-hexagon.so are extracted).
// Must run once, before any other call. Returns a transcribe_status.
JNIEXPORT jint JNICALL JNI_FN(nativeInitBackends)(JNIEnv * env, jobject, jstring dir) {
    transcribe_log_set(log_cb, nullptr);
    const std::string d = jstr(env, dir);

    transcribe_backend_init_params p;
    transcribe_backend_init_params_init(&p);
    p.artifact_dir     = d.empty() ? nullptr : d.c_str();
    p.allowed_backends = TRANSCRIBE_BACKEND_MASK_ALL;  // CPU + Vulkan + OTHER (OpenCL, Hexagon)
    const transcribe_status st = transcribe_init_backends_ex(&p);
    VX_LOG(VX_INFO, "init_backends(%s) -> %s; %d device(s)", d.empty() ? "<default>" : d.c_str(),
           transcribe_status_string(st), transcribe_device_count());
    return st;
}

// One entry per registered device: "index\tname\tdescription\tkind\tdevice_type".
JNIEXPORT jobjectArray JNICALL JNI_FN(nativeDevices)(JNIEnv * env, jobject) {
    const int    n   = transcribe_device_count();
    jclass       cls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n > 0 ? n : 0, cls, nullptr);
    for (int i = 0; i < n; ++i) {
        transcribe_device_t           dev = transcribe_device_get(i);
        struct transcribe_device_info info;
        transcribe_device_info_init(&info);
        std::string row = std::to_string(i);
        if (dev != nullptr && transcribe_device_get_info(dev, &info) == TRANSCRIBE_OK) {
            row += "\t";
            row += info.name != nullptr ? info.name : "?";
            row += "\t";
            row += info.description != nullptr ? info.description : "";
            row += "\t";
            row += info.kind != nullptr ? info.kind : "unknown";
            row += "\t";
            row += device_type_name(info.device_type);
        } else {
            row += "\t?\t\tunknown\tunknown";
        }
        jstring js = env->NewStringUTF(row.c_str());
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

// Loads a model and opens a session.
//   deviceIndex >= 0 : exactly that registered device (backend AUTO + exact handle; works for Vulkan,
//                      OpenCL and Hexagon, which the backend enum has no value for).
//   deviceIndex <  0 : strict CPU (TRANSCRIBE_BACKEND_CPU).
// Returns a transcribe_status; on TRANSCRIBE_OK the handle is written to out[0].
// The handle is NOT folded into the return value: on arm64 Android heap pointers carry a tag in the top byte
// (0xB4...), so as a jlong they are negative and can't be told apart from "-status".
JNIEXPORT jint JNICALL JNI_FN(nativeOpen)(JNIEnv * env, jobject, jstring jpath, jint deviceIndex, jint nThreads,
                                          jlongArray out) {
    const std::string path = jstr(env, jpath);

    struct transcribe_model_load_params lp;
    transcribe_model_load_params_init(&lp);
    if (deviceIndex >= 0) {
        transcribe_device_t dev = transcribe_device_get(deviceIndex);
        if (dev == nullptr) {  // NULL would mean "automatic"; never let an out-of-range index do that
            return TRANSCRIBE_ERR_INVALID_ARG;
        }
        lp.backend = TRANSCRIBE_BACKEND_AUTO;
        lp.device  = dev;
    } else {
        lp.backend = TRANSCRIBE_BACKEND_CPU;
        lp.device  = nullptr;
    }

    struct transcribe_session_params sp;
    transcribe_session_params_init(&sp);
    sp.n_threads = nThreads > 0 ? nThreads : 0;

    auto *                    e  = new Engine();
    const transcribe_status st = transcribe_open(path.c_str(), &lp, &sp, &e->session);
    if (st != TRANSCRIBE_OK || e->session == nullptr) {
        VX_LOG(VX_WARN, "transcribe_open(device=%d) failed: %s", deviceIndex, transcribe_status_string(st));
        delete e;
        return st != TRANSCRIBE_OK ? st : TRANSCRIBE_ERR_BACKEND;
    }
    transcribe_set_abort_callback(e->session, abort_cb, e);
    const char * be = transcribe_model_backend(transcribe_get_model(e->session));
    e->backend      = be != nullptr ? be : "?";
    VX_LOG(VX_INFO, "model loaded on %s (%d threads)", e->backend.c_str(), sp.n_threads);
    const jlong h = static_cast<jlong>(reinterpret_cast<intptr_t>(e));
    if (out == nullptr || env->GetArrayLength(out) < 1) {
        transcribe_close(e->session);
        delete e;
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    env->SetLongArrayRegion(out, 0, 1, &h);
    return TRANSCRIBE_OK;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeBackend)(JNIEnv * env, jobject, jlong h) {
    Engine * e = from_handle(h);
    return env->NewStringUTF(e != nullptr ? e->backend.c_str() : "");
}

// Batch transcription of 16 kHz mono float PCM in [-1, 1]. Returns a transcribe_status. The partial
// text after OUTPUT_TRUNCATED / OUTPUT_REPETITION / ABORTED is still readable with nativeText.
JNIEXPORT jint JNICALL JNI_FN(nativeRun)(JNIEnv * env, jobject, jlong h, jfloatArray jpcm, jint n) {
    Engine * e = from_handle(h);
    if (e == nullptr || e->session == nullptr || jpcm == nullptr) {
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    const jsize len = env->GetArrayLength(jpcm);
    if (n <= 0 || n > len) {
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    // Copy out (not a critical section: a run can take seconds and must not block the GC).
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    e->abort.store(false);
    struct transcribe_run_params rp;
    transcribe_run_params_init(&rp);
    rp.language   = "en";
    rp.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    transcribe_status st = transcribe_run(e->session, pcm.data(), n, &rp);
    if (st == TRANSCRIBE_ERR_UNSUPPORTED_TIMESTAMPS || st == TRANSCRIBE_ERR_UNSUPPORTED_LANGUAGE) {
        // be lenient with model variants: retry with pure defaults
        st = transcribe_run(e->session, pcm.data(), n, nullptr);
    }
    return st;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeText)(JNIEnv * env, jobject, jlong h) {
    Engine * e = from_handle(h);
    return to_utf8_bytes(env, e != nullptr && e->session != nullptr ? transcribe_full_text(e->session) : nullptr);
}

// [load_ms, mel_ms, encode_ms, decode_ms]
JNIEXPORT jfloatArray JNICALL JNI_FN(nativeTimings)(JNIEnv * env, jobject, jlong h) {
    float    v[4] = { 0, 0, 0, 0 };
    Engine * e    = from_handle(h);
    if (e != nullptr && e->session != nullptr) {
        struct transcribe_timings t;
        transcribe_timings_init(&t);
        if (transcribe_get_timings(e->session, &t) == TRANSCRIBE_OK) {
            v[0] = t.load_ms;
            v[1] = t.mel_ms;
            v[2] = t.encode_ms;
            v[3] = t.decode_ms;
        }
    }
    jfloatArray out = env->NewFloatArray(4);
    env->SetFloatArrayRegion(out, 0, 4, v);
    return out;
}

// Thread-safe: flips the flag the abort callback polls (between chunks / decode steps).
JNIEXPORT void JNICALL JNI_FN(nativeAbort)(JNIEnv *, jobject, jlong h) {
    Engine * e = from_handle(h);
    if (e != nullptr) {
        e->abort.store(true);
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv *, jobject, jlong h) {
    Engine * e = from_handle(h);
    if (e == nullptr) {
        return;
    }
    transcribe_close(e->session);  // the session owns the model (transcribe_open)
    delete e;
}

// Pins the calling thread to the given CPUs. ggml's worker threads are created from the thread that
// runs the graph and inherit its affinity, so pinning the inference thread pins the whole pool.
// Returns 0 on success, errno otherwise.
JNIEXPORT jint JNICALL JNI_FN(nativePinThread)(JNIEnv * env, jobject, jintArray jcpus) {
    cpu_set_t set;
    CPU_ZERO(&set);
    const jsize n = jcpus != nullptr ? env->GetArrayLength(jcpus) : 0;
    if (n == 0) {
        // reset: all online CPUs
        const long ncpu = sysconf(_SC_NPROCESSORS_CONF);
        for (long i = 0; i < ncpu && i < CPU_SETSIZE; ++i) {
            CPU_SET(static_cast<int>(i), &set);
        }
    } else {
        std::vector<jint> cpus(static_cast<size_t>(n));
        env->GetIntArrayRegion(jcpus, 0, n, cpus.data());
        for (jint c : cpus) {
            if (c >= 0 && c < CPU_SETSIZE) {
                CPU_SET(c, &set);
            }
        }
    }
    return sched_setaffinity(0, sizeof(set), &set) == 0 ? 0 : errno;
}

}  // extern "C"
