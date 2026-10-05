#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <climits>
#include <cstring>
#include <cmath>
#include <vector>
#include <fcntl.h>
#include <memory>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
class UtfChars {
    JNIEnv *env;
    jstring value;
public:
    const char *chars;
    UtfChars(JNIEnv *env, jstring value) : env(env), value(value), chars(env->GetStringUTFChars(value, nullptr)) {
        if (!chars) throw std::runtime_error("Unable to read the audio or model path.");
    }
    ~UtfChars() { env->ReleaseStringUTFChars(value, chars); }
};

class MappedPcm {
    int fd = -1;
    void *mapping = MAP_FAILED;
    size_t length = 0;
public:
    explicit MappedPcm(const char *path) {
        fd = open(path, O_RDONLY | O_CLOEXEC);
        struct stat info{};
        if (fd < 0 || fstat(fd, &info) != 0 || info.st_size <= 0 || info.st_size % sizeof(float) != 0 ||
            info.st_size / sizeof(float) > INT_MAX) {
            if (fd >= 0) close(fd);
            fd = -1;
            throw std::runtime_error("The extracted audio is empty, damaged, or too long.");
        }
        length = static_cast<size_t>(info.st_size);
        mapping = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapping == MAP_FAILED) {
            close(fd);
            fd = -1;
            throw std::runtime_error("Not enough memory to open the extracted audio.");
        }
    }
    ~MappedPcm() {
        if (mapping != MAP_FAILED) munmap(mapping, length);
        if (fd >= 0) close(fd);
    }
    const float *data() const { return static_cast<const float *>(mapping); }
    int samples() const { return static_cast<int>(length / sizeof(float)); }
};

// ggml may invoke abort callbacks on native worker threads, which need their own JNIEnv.
class ScopedEnvironment {
    JavaVM *vm;
    bool attached = false;
public:
    JNIEnv *env = nullptr;
    explicit ScopedEnvironment(JavaVM *vm) : vm(vm) {
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
            if (!attached) env = nullptr;
        }
    }
    ~ScopedEnvironment() { if (attached) vm->DetachCurrentThread(); }
};

struct Callbacks {
    JavaVM *vm;
    jobject target;
    jmethodID cancelled;
    jmethodID progress;
    std::atomic<bool> failed{false};
    std::atomic<int> lastProgress{0};
    int sectionStart = 0;
    int sectionSamples = 0;
    int totalSamples = 1;

    bool isCancelled() {
        if (failed.load()) return true;
        ScopedEnvironment scoped(vm);
        if (!scoped.env) { failed = true; return true; }
        const bool result = scoped.env->CallBooleanMethod(target, cancelled);
        if (scoped.env->ExceptionCheck()) {
            scoped.env->ExceptionClear();
            failed = true;
            return true;
        }
        return result;
    }
    void reportProgress(int value) {
        value = std::clamp(value, 0, 100);
        int previous = lastProgress.load();
        while (value > previous && !lastProgress.compare_exchange_weak(previous, value)) {}
        if (value < previous) return;
        ScopedEnvironment scoped(vm);
        if (!scoped.env) { failed = true; return; }
        scoped.env->CallVoidMethod(target, progress, value);
        if (scoped.env->ExceptionCheck()) { scoped.env->ExceptionClear(); failed = true; }
    }
};

// Owned by one Kotlin run and used serially while the decoder is backpressured.
struct Session {
    std::unique_ptr<whisper_context, decltype(&whisper_free)> context{nullptr, whisper_free};
    std::unique_ptr<whisper_vad_context, decltype(&whisper_vad_free)> vad{nullptr, whisper_vad_free};
};

class GlobalRef {
    JNIEnv *env;
public:
    jobject value;
    GlobalRef(JNIEnv *env, jobject value) : env(env), value(env->NewGlobalRef(value)) {
        if (!this->value) throw std::runtime_error("Not enough memory to start transcription.");
    }
    ~GlobalRef() { env->DeleteGlobalRef(value); }
};
}

static void transcribe(JNIEnv *env, jstring modelPath, jstring vadPath, jstring pcmPath,
                       jstring languageCode, jstring modelId, jlong audioOffsetMs, jint threads,
                       jintArray savedPlan, jint completed, bool checkpointed, jobject callback, Session *session = nullptr, int coreStart = 0, int coreEnd = -1) {
    try {
        UtfChars model(env, modelPath);
        UtfChars pcm(env, pcmPath);
        UtfChars vadModel(env, vadPath);
        UtfChars language(env, languageCode);
        UtfChars selectedModel(env, modelId);
        const bool automatic = std::strcmp(language.chars, "auto") == 0;
        const bool turbo = std::strcmp(selectedModel.chars, "TURBO") == 0;
        if ((!turbo && std::strcmp(selectedModel.chars, "BASE") != 0 && std::strcmp(selectedModel.chars, "SMALL") != 0) ||
            (!automatic && std::strcmp(language.chars, "en") != 0 && std::strcmp(language.chars, "zh") != 0 && std::strcmp(language.chars, "yue") != 0) ||
            (std::strcmp(language.chars, "yue") == 0 && !turbo) || audioOffsetMs < 0 ||
            audioOffsetMs > LLONG_MAX - static_cast<int64_t>(INT_MAX) * 1000 / WHISPER_SAMPLE_RATE) {
            throw std::runtime_error("Invalid transcription language or model selection.");
        }
        MappedPcm audio(pcm.chars);
        if (coreEnd < 0) coreEnd = audio.samples();
        if (coreStart < 0 || coreEnd <= coreStart || coreEnd > audio.samples())
            throw std::runtime_error("Invalid audio window boundaries.");
        GlobalRef global(env, callback);
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        jclass type = env->GetObjectClass(callback);
        jmethodID cancelled = env->GetMethodID(type, "isCancelled", "()Z");
        jmethodID progress = env->GetMethodID(type, "onProgress", "(I)V");
        jmethodID segment = env->GetMethodID(type, "onSegment", "(JJ[B)V");
        jmethodID planCallback = checkpointed ? env->GetMethodID(type, "onPlan", "([I)V") : nullptr;
        jmethodID sectionComplete = checkpointed ? env->GetMethodID(type, "onSectionComplete", "(I)V") : nullptr;
        env->DeleteLocalRef(type);
        if (!cancelled || !progress || !segment || (checkpointed && (!planCallback || !sectionComplete))) return;
        Callbacks callbacks{vm, global.value, cancelled, progress};
        if (callbacks.isCancelled()) return;

        auto contextParams = whisper_context_default_params();
        contextParams.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> ownedContext(nullptr, whisper_free);
        if (!session) ownedContext.reset(whisper_init_from_file_with_params(model.chars, contextParams));
        auto *context = session ? session->context.get() : ownedContext.get();
        if (!context) throw std::runtime_error("Unable to load the offline model. Check free memory and try again.");
        if (callbacks.isCancelled()) return;

        if (!whisper_is_multilingual(context)) throw std::runtime_error("The selected model must support multilingual speech.");
        const int cantoneseId = whisper_lang_id("yue");
        const bool hasCantonese = cantoneseId >= 0 && whisper_token_lang(context, cantoneseId) < whisper_token_translate(context);
        if (turbo && !hasCantonese) throw std::runtime_error("The selected model does not support Cantonese. Install Turbo and retry.");
        callbacks.totalSamples = audio.samples();

        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::clamp(static_cast<int>(threads), 1, 4);
        params.language = language.chars;
        params.no_context = true;
        params.translate = false;
        params.no_timestamps = false;
        params.print_special = false;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.suppress_nst = true;
        params.progress_callback = [](whisper_context *, whisper_state *, int value, void *user) {
            auto *callback = static_cast<Callbacks *>(user);
            callback->reportProgress(static_cast<int>((static_cast<int64_t>(callback->sectionStart) * 100 +
                static_cast<int64_t>(callback->sectionSamples) * value) / callback->totalSamples));
        };
        params.progress_callback_user_data = &callbacks;
        params.abort_callback = [](void *user) { return static_cast<Callbacks *>(user)->isCancelled(); };
        params.abort_callback_user_data = &callbacks;
        params.encoder_begin_callback = [](whisper_context *, whisper_state *, void *user) {
            return !static_cast<Callbacks *>(user)->isCancelled();
        };
        params.encoder_begin_callback_user_data = &callbacks;

        auto transcribeSection = [&](int start, int end) {
            if (callbacks.isCancelled() || end <= start) return;
            callbacks.sectionStart = start;
            callbacks.sectionSamples = end - start;
            callbacks.reportProgress(static_cast<int>(static_cast<int64_t>(start) * 100 / audio.samples()));
            if (automatic) {
                if (whisper_pcm_to_mel(context, audio.data() + start, end - start, params.n_threads) != 0)
                    throw std::runtime_error("Offline transcription could not prepare a speech section.");
                if (callbacks.isCancelled()) return;
                std::vector<float> probabilities(whisper_lang_max_id() + 1, 0.0f);
                if (whisper_lang_auto_detect(context, 0, params.n_threads, probabilities.data()) < 0)
                    throw std::runtime_error("Offline transcription could not detect the speech language.");
                if (callbacks.isCancelled()) return;
                int chosen = whisper_lang_id("en");
                for (const char *eligible : {"zh", "yue"}) {
                    const int id = whisper_lang_id(eligible);
                    if (id >= 0 && (std::strcmp(eligible, "yue") != 0 || (turbo && hasCantonese)) && probabilities[id] > probabilities[chosen]) chosen = id;
                }
                params.language = whisper_lang_str(chosen);
            }
            const int result = whisper_full(context, params, audio.data() + start, end - start);
            if (callbacks.failed) throw std::runtime_error("Transcription progress could not be delivered.");
            if (callbacks.isCancelled()) return;
            if (result != 0) throw std::runtime_error("Offline transcription failed. Try a shorter video or free some memory.");
            const int count = whisper_full_n_segments(context);
            for (int index = 0; index < count; ++index) {
                if (callbacks.isCancelled()) return;
                const char *text = whisper_full_get_segment_text(context, index);
                const auto length = static_cast<jsize>(std::strlen(text));
                jbyteArray utf8 = env->NewByteArray(length);
                if (!utf8) return;
                env->SetByteArrayRegion(utf8, 0, length, reinterpret_cast<const jbyte *>(text));
                // Whisper times are centiseconds; PCM offsets use 16 kHz. UTF-8 is never converted by JNI.
                const auto durationMs = static_cast<int64_t>(end - start) * 1000 / WHISPER_SAMPLE_RATE;
                const auto sectionOffsetMs = static_cast<int64_t>(start) * 1000 / WHISPER_SAMPLE_RATE + audioOffsetMs;
                const auto t0 = std::clamp(whisper_full_get_segment_t0(context, index) * 10, int64_t{0}, durationMs);
                const auto t1 = std::clamp(whisper_full_get_segment_t1(context, index) * 10, t0, durationMs);
                env->CallVoidMethod(callback, segment, static_cast<jlong>(sectionOffsetMs + t0), static_cast<jlong>(sectionOffsetMs + t1), utf8);
                env->DeleteLocalRef(utf8);
                if (env->ExceptionCheck()) return;
            }
        };

        std::vector<jint> plan;
        if (savedPlan) {
            const auto length = env->GetArrayLength(savedPlan);
            if (length % 2 != 0) throw std::runtime_error("Invalid saved speech section plan.");
            plan.resize(length);
            env->GetIntArrayRegion(savedPlan, 0, length, plan.data());
            if (env->ExceptionCheck()) return;
        } else if (!automatic) {
            constexpr int window = 30 * WHISPER_SAMPLE_RATE;
            for (int64_t offset = coreStart; offset < coreEnd; offset += window) {
                plan.push_back(static_cast<int>(offset));
                plan.push_back(static_cast<int>(std::min(offset + window, static_cast<int64_t>(coreEnd))));
            }
        } else {
            auto vadContextParams = whisper_vad_default_context_params();
            vadContextParams.use_gpu = false;
            vadContextParams.n_threads = params.n_threads;
            std::unique_ptr<whisper_vad_context, decltype(&whisper_vad_free)> ownedVad(nullptr, whisper_vad_free);
            auto &vadOwner = session ? session->vad : ownedVad;
            if (!vadOwner) vadOwner.reset(whisper_vad_init_from_file_with_params(vadModel.chars, vadContextParams));
            auto *vad = vadOwner.get();
            if (!vad) throw std::runtime_error("Unable to load the offline speech detector. Check free memory and retry.");
            auto vadParams = whisper_vad_default_params();
            vadParams.threshold = 0.5f;
            vadParams.min_speech_duration_ms = 250;
            vadParams.min_silence_duration_ms = 250;
            vadParams.speech_pad_ms = 30;
            // Split long spans ourselves, so no speech is dropped by Silero's forced-split heuristic.
            vadParams.max_speech_duration_s = 1000000.0f;
            vadParams.samples_overlap = 0.0f;
            constexpr int window = 30 * WHISPER_SAMPLE_RATE;
            constexpr int halo = WHISPER_SAMPLE_RATE;
            // Detector context on both sides keeps continuous speech intact at the 30-second boundary.
            // Only each window's disjoint core is transcribed; probability storage is bounded to 32 seconds.
            for (int64_t offset = coreStart; offset < coreEnd; offset += window) {
                if (env->ExceptionCheck() || callbacks.isCancelled()) return;
                const int windowStart = static_cast<int>(offset);
                const int windowEnd = static_cast<int>(std::min(offset + window, static_cast<int64_t>(coreEnd)));
                const int scanStart = std::max(0, windowStart - halo);
                const int scanEnd = static_cast<int>(std::min(static_cast<int64_t>(windowEnd) + halo, static_cast<int64_t>(audio.samples())));
                std::unique_ptr<whisper_vad_segments, decltype(&whisper_vad_free_segments)> spans(
                    whisper_vad_segments_from_samples(vad, vadParams, audio.data() + scanStart, scanEnd - scanStart), whisper_vad_free_segments);
                if (!spans) throw std::runtime_error("Offline transcription could not find speech boundaries.");
                if (callbacks.isCancelled()) return;
                int previousEnd = windowStart;
                for (int i = 0; i < whisper_vad_segments_n_segments(spans.get()); ++i) {
                    const auto spanStart = static_cast<int64_t>(scanStart) + std::llround(whisper_vad_segments_get_segment_t0(spans.get(), i) * 160.0);
                    const auto spanEnd = static_cast<int64_t>(scanStart) + std::llround(whisper_vad_segments_get_segment_t1(spans.get(), i) * 160.0);
                    const int start = static_cast<int>(std::clamp<int64_t>(spanStart, previousEnd, windowEnd));
                    const int end = static_cast<int>(std::clamp<int64_t>(spanEnd, start, windowEnd));
                    if (end > start) { plan.push_back(start); plan.push_back(end); }
                    if (env->ExceptionCheck() || callbacks.isCancelled()) return;
                    previousEnd = end;
                }

            }
        }
        int previousEnd = coreStart;
        for (size_t index = 0; index < plan.size(); index += 2) {
            if (plan[index] < previousEnd || plan[index + 1] <= plan[index] || plan[index + 1] > coreEnd ||
                plan[index + 1] - plan[index] > 30 * WHISPER_SAMPLE_RATE)
                throw std::runtime_error("Invalid saved speech section plan.");
            previousEnd = plan[index + 1];
        }
        if (completed < 0 || static_cast<size_t>(completed) > plan.size() / 2)
            throw std::runtime_error("Invalid completed speech section count.");
        if (checkpointed && !savedPlan) {
            jintArray array = env->NewIntArray(static_cast<jsize>(plan.size()));
            if (!array) return;
            env->SetIntArrayRegion(array, 0, static_cast<jsize>(plan.size()), plan.data());
            env->CallVoidMethod(callback, planCallback, array);
            env->DeleteLocalRef(array);
            if (env->ExceptionCheck()) return;
        }
        for (size_t index = static_cast<size_t>(completed) * 2; index < plan.size(); index += 2) {
            transcribeSection(plan[index], plan[index + 1]);
            if (env->ExceptionCheck() || callbacks.isCancelled()) return;
            if (checkpointed) {
                env->CallVoidMethod(callback, sectionComplete, static_cast<jint>(index / 2));
                if (env->ExceptionCheck()) return;
            }
            callbacks.reportProgress(static_cast<int>(static_cast<int64_t>(plan[index + 1]) * 100 / audio.samples()));
        }
        callbacks.reportProgress(100);
        if (callbacks.failed) throw std::runtime_error("Transcription progress could not be delivered.");
    } catch (const std::bad_alloc &) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "Not enough memory to transcribe this video.");
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_video2text_WhisperBridge_transcribe(JNIEnv *env, jobject, jstring model, jstring vad, jstring pcm,
    jstring language, jstring modelId, jlong offset, jint threads, jobject callback) {
    transcribe(env, model, vad, pcm, language, modelId, offset, threads, nullptr, 0, false, callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_video2text_WhisperBridge_transcribeSections(JNIEnv *env, jobject, jstring model, jstring vad, jstring pcm,
    jstring language, jstring modelId, jlong offset, jint threads, jintArray plan, jint completed, jobject callback) {
    transcribe(env, model, vad, pcm, language, modelId, offset, threads, plan, completed, true, callback);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_video2text_WhisperBridge_openSession(JNIEnv *env, jobject, jstring modelPath) {
    try {
        UtfChars model(env, modelPath);
        auto session = std::make_unique<Session>();
        auto params = whisper_context_default_params();
        params.use_gpu = false;
        session->context.reset(whisper_init_from_file_with_params(model.chars, params));
        if (!session->context) throw std::runtime_error("Unable to load the offline model. Check free memory and try again.");
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_video2text_WhisperBridge_closeSession(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_video2text_WhisperBridge_transcribeWindow(JNIEnv *env, jobject, jlong handle,
    jstring model, jstring vad, jstring pcm, jstring language, jstring modelId, jlong offset,
    jint threads, jint coreStart, jint coreEnd, jintArray plan, jint completed, jobject callback) {
    if (!handle) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "The transcription session is closed.");
        return;
    }
    transcribe(env, model, vad, pcm, language, modelId, offset, threads, plan, completed, true,
        callback, reinterpret_cast<Session *>(handle), coreStart, coreEnd);
}
