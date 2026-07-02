#include <jni.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <mutex>
#include <cstring>
#include <cmath>

extern "C" {
#include "ft8/constants.h"
#include "ft8/message.h"
#include "ft8/encode.h"
#include "ft8/decode.h"
#include "common/monitor.h"
}

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// GFSK synthesis constants (from ft8_lib demo/gen_ft8.c).
static const float kGfskConstK = 5.336446f; // pi * sqrt(2 / log(2))
static const float kFt8SymbolBt = 2.0f;     // FT8 smoothing filter bandwidth factor
static const float kTxAmplitude = 0.7f;     // PCM headroom for transmit audio

// Decoder tuning (mirrors ft8_lib's demo/decode_ft8.c).
static const int kMinScore = 10;
static const int kMaxCandidates = 180;
static const int kLdpcIterations = 25;
static const int kFreqOsr = 2;
static const int kTimeOsr = 2;

static std::mutex g_decodeMutex;

// ---------------------------------------------------------------------------
// Callsign hash table (ported from ft8_lib demo) so ftx_message_decode can
// resolve hashed (nonstandard) callsigns across slots.
// ---------------------------------------------------------------------------
#define CALLSIGN_HASHTABLE_SIZE 256

static struct {
    char callsign[12];
    uint32_t hash;
} g_callsignHashtable[CALLSIGN_HASHTABLE_SIZE];
static int g_callsignHashtableSize = 0;

static void hashtable_init() {
    g_callsignHashtableSize = 0;
    std::memset(g_callsignHashtable, 0, sizeof(g_callsignHashtable));
}

// Entries not re-heard within this many decode passes (~15 s each) age out.
static const uint8_t kHashMaxAgeSlots = 40;

// Ported from ft8_lib demo/decode_ft8.c: age lives in bits 24-31 of the stored
// hash; survivors age by one per call, entries older than max_age are freed.
static void hashtable_cleanup(uint8_t max_age) {
    for (int idx = 0; idx < CALLSIGN_HASHTABLE_SIZE; ++idx) {
        if (g_callsignHashtable[idx].callsign[0] != '\0') {
            uint8_t age = (uint8_t)(g_callsignHashtable[idx].hash >> 24);
            if (age > max_age) {
                g_callsignHashtable[idx].callsign[0] = '\0';
                g_callsignHashtable[idx].hash = 0;
                g_callsignHashtableSize--;
            } else {
                g_callsignHashtable[idx].hash =
                    (((uint32_t)age + 1u) << 24) | (g_callsignHashtable[idx].hash & 0x3FFFFFu);
            }
        }
    }
}

static void hashtable_add(const char* callsign, uint32_t hash) {
    uint16_t hash10 = (hash >> 12) & 0x3FFu;
    int idx = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    int probes = 0;
    while (g_callsignHashtable[idx].callsign[0] != '\0') {
        if (((g_callsignHashtable[idx].hash & 0x3FFFFFu) == hash) &&
            (0 == std::strcmp(g_callsignHashtable[idx].callsign, callsign))) {
            g_callsignHashtable[idx].hash &= 0x3FFFFFu;  // known call re-heard: reset age
            return;
        }
        // Full table: skip the add rather than probing forever (upstream lacks
        // this guard; with persistence a spin here would ANR the decode thread).
        if (++probes >= CALLSIGN_HASHTABLE_SIZE) return;
        idx = (idx + 1) % CALLSIGN_HASHTABLE_SIZE;
    }
    g_callsignHashtableSize++;
    std::strncpy(g_callsignHashtable[idx].callsign, callsign, 11);
    g_callsignHashtable[idx].callsign[11] = '\0';
    g_callsignHashtable[idx].hash = hash;
}

static bool hashtable_lookup(ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign) {
    uint8_t shift = (hash_type == FTX_CALLSIGN_HASH_10_BITS) ? 12
                  : (hash_type == FTX_CALLSIGN_HASH_12_BITS ? 10 : 0);
    uint16_t hash10 = (hash >> (12 - shift)) & 0x3FFu;
    int idx = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    while (g_callsignHashtable[idx].callsign[0] != '\0') {
        if (((g_callsignHashtable[idx].hash & 0x3FFFFFu) >> shift) == hash) {
            std::strcpy(callsign, g_callsignHashtable[idx].callsign);
            return true;
        }
        idx = (idx + 1) % CALLSIGN_HASHTABLE_SIZE;
    }
    callsign[0] = '\0';
    return false;
}

// Positional init (lookup_hash, save_hash) to avoid relying on C++20 designated initializers.
static ftx_callsign_hash_interface_t g_hashIf = {
    hashtable_lookup,
    hashtable_add,
};

struct DecodeOut {
    std::string text;
    int snr;
    float dt;
    float freq;
    int score;
};

// ---------------------------------------------------------------------------
// GFSK waveform synthesis (ported from ft8_lib demo/gen_ft8.c, using heap
// buffers instead of stack VLAs since a slot is ~150k samples).
// ---------------------------------------------------------------------------
static void gfsk_pulse(int n_spsym, float symbol_bt, std::vector<float>& pulse) {
    pulse.resize(static_cast<size_t>(3 * n_spsym));
    for (int i = 0; i < 3 * n_spsym; ++i) {
        float t = i / (float)n_spsym - 1.5f;
        float arg1 = kGfskConstK * symbol_bt * (t + 0.5f);
        float arg2 = kGfskConstK * symbol_bt * (t - 0.5f);
        pulse[i] = (erff(arg1) - erff(arg2)) / 2;
    }
}

// synth_gfsk: synthesize GFSK PCM for symbols [offset_symbols, n_sym) of the
// transmission. The full symbol stream is still passed in (FEC is upstream);
// this function only controls which symbols become audio.
//
// signal[] must be sized for (n_sym - offset_symbols) * n_spsym samples when
// offset_symbols > 0 (no silence padding), or for the caller's full-slot
// buffer when offset_symbols == 0 (caller supplies pre/post silence padding).
static void synth_gfsk(const uint8_t* symbols, int n_sym, int offset_symbols,
                       float f0, float symbol_bt,
                       float symbol_period, int signal_rate, float* signal) {
    int n_spsym = (int)(0.5f + signal_rate * symbol_period);
    int emitted_syms = n_sym - offset_symbols;
    if (emitted_syms <= 0) return;
    int n_wave = emitted_syms * n_spsym;
    float hmod = 1.0f;
    float dphi_peak = 2 * (float)M_PI * hmod / n_spsym;

    std::vector<float> dphi(static_cast<size_t>(n_wave + 2 * n_spsym),
                            2 * (float)M_PI * f0 / signal_rate);
    std::vector<float> pulse;
    gfsk_pulse(n_spsym, symbol_bt, pulse);

    // Spread each emitted symbol's pulse across its window. Symbol indices are
    // referenced into the full symbols[] array starting at offset_symbols.
    for (int i = 0; i < emitted_syms; ++i) {
        int src = i + offset_symbols;
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; ++j) {
            dphi[j + ib] += dphi_peak * symbols[src] * pulse[j];
        }
    }
    // Ramp-edge pulses use the first and last EMITTED symbols, not the absolute
    // first/last of the FEC stream. This preserves the gentle envelope at the
    // truncated edge as well as the natural tail.
    for (int j = 0; j < 2 * n_spsym; ++j) {
        dphi[j] += dphi_peak * pulse[j + n_spsym] * symbols[offset_symbols];
        dphi[j + emitted_syms * n_spsym] += dphi_peak * pulse[j] * symbols[n_sym - 1];
    }

    float phi = 0;
    for (int k = 0; k < n_wave; ++k) {
        signal[k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2 * (float)M_PI);
    }

    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; ++i) {
        float env = (1 - cosf(2 * (float)M_PI * i / (2 * n_ramp))) / 2;
        signal[i] *= env;
        signal[n_wave - 1 - i] *= env;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_ft8vc_ft8native_Ft8Native_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("ft8vc-native 0.3.0 (ft8_lib 9fec6ca)");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_ft8vc_ft8native_Ft8Native_nativeDecode(
        JNIEnv* env, jobject, jshortArray samples, jint sampleRate) {

    std::lock_guard<std::mutex> guard(g_decodeMutex);

    jsize numSamples = env->GetArrayLength(samples);
    std::vector<float> signal(static_cast<size_t>(numSamples));
    {
        jshort* raw = env->GetShortArrayElements(samples, nullptr);
        for (jsize i = 0; i < numSamples; ++i) {
            signal[i] = static_cast<float>(raw[i]) / 32768.0f;
        }
        env->ReleaseShortArrayElements(samples, raw, JNI_ABORT);
    }

    monitor_config_t cfg = {};
    cfg.f_min = 200;
    cfg.f_max = 4000;
    cfg.sample_rate = sampleRate;
    cfg.time_osr = kTimeOsr;
    cfg.freq_osr = kFreqOsr;
    cfg.protocol = FTX_PROTOCOL_FT8;

    hashtable_cleanup(kHashMaxAgeSlots);

    monitor_t mon;
    monitor_init(&mon, &cfg);

    for (int pos = 0; pos + mon.block_size <= numSamples; pos += mon.block_size) {
        monitor_process(&mon, signal.data() + pos);
    }

    const ftx_waterfall_t* wf = &mon.wf;
    std::vector<ftx_candidate_t> candidates(kMaxCandidates);
    int numCandidates = ftx_find_candidates(wf, kMaxCandidates, candidates.data(), kMinScore);

    std::vector<DecodeOut> results;
    std::unordered_set<std::string> seen;

    for (int i = 0; i < numCandidates; ++i) {
        const ftx_candidate_t* cand = &candidates[i];

        ftx_message_t message;
        ftx_decode_status_t status;
        if (!ftx_decode_candidate(wf, cand, kLdpcIterations, &message, &status)) {
            continue;
        }

        std::string payloadKey(reinterpret_cast<const char*>(message.payload),
                               FTX_PAYLOAD_LENGTH_BYTES);
        if (!seen.insert(payloadKey).second) {
            continue; // duplicate
        }

        char text[FTX_MAX_MESSAGE_LENGTH];
        ftx_message_offsets_t offsets;
        if (ftx_message_decode(&message, &g_hashIf, text, &offsets) != FTX_MESSAGE_RC_OK) {
            continue;
        }

        float freqHz = (mon.min_bin + cand->freq_offset + (float)cand->freq_sub / wf->freq_osr) /
                       mon.symbol_period;
        float timeSec = (cand->time_offset + (float)cand->time_sub / wf->time_osr) *
                        mon.symbol_period;

        DecodeOut out;
        out.text = text;
        // SNR is computed in Kotlin (net.ft8vc.core.SnrEstimator); ft8_lib's
        // score*0.5 is a sync metric, not dB. Emit 0 so nothing downstream
        // mistakes this field for a real SNR.
        out.snr = 0;
        out.dt = timeSec;
        out.freq = freqHz;
        out.score = cand->score;
        results.push_back(std::move(out));
    }

    monitor_free(&mon);

    jclass cls = env->FindClass("net/ft8vc/ft8native/Ft8DecodeResult");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;IFFI)V");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(results.size()), cls, nullptr);

    for (jsize i = 0; i < (jsize)results.size(); ++i) {
        const DecodeOut& r = results[i];
        jstring msg = env->NewStringUTF(r.text.c_str());
        jobject obj = env->NewObject(cls, ctor, msg, r.snr, r.dt, r.freq, r.score);
        env->SetObjectArrayElement(array, i, obj);
        env->DeleteLocalRef(msg);
        env->DeleteLocalRef(obj);
    }

    return array;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_net_ft8vc_ft8native_Ft8Native_nativeEncode(
        JNIEnv* env, jobject, jstring message, jfloat freqHz, jint sampleRate, jint offsetSymbols) {

    std::lock_guard<std::mutex> guard(g_decodeMutex);

    if (offsetSymbols < 0) offsetSymbols = 0;
    if (offsetSymbols >= FT8_NN) {
        return env->NewShortArray(0);
    }

    ftx_message_t msg;
    {
        const char* text = env->GetStringUTFChars(message, nullptr);
        ftx_message_rc_t rc = ftx_message_encode(&msg, &g_hashIf, text);
        env->ReleaseStringUTFChars(message, text);
        if (rc != FTX_MESSAGE_RC_OK) {
            return env->NewShortArray(0);
        }
    }

    // Always run the full FEC encode over all 79 symbols — only the audio
    // synthesis is truncated. This is the load-bearing FEC-correctness invariant.
    uint8_t tones[FT8_NN];
    ft8_encode(msg.payload, tones);

    int nSpsym = (int)(0.5f + sampleRate * FT8_SYMBOL_PERIOD);

    if (offsetSymbols == 0) {
        // v1.0 path: 15-second buffer with silence padding centered on the waveform.
        // BYTE-IDENTICAL to the pre-parameter implementation (regression guard).
        int numSamples = FT8_NN * nSpsym;
        int total = (int)(FT8_SLOT_TIME * sampleRate);
        int numSilence = (total - numSamples) / 2;
        if (numSilence < 0) {
            numSilence = 0;
            total = numSamples;
        }

        std::vector<float> buf(static_cast<size_t>(total), 0.0f);
        synth_gfsk(tones, FT8_NN, 0, freqHz, kFt8SymbolBt, FT8_SYMBOL_PERIOD, sampleRate,
                   buf.data() + numSilence);

        std::vector<jshort> pcm(static_cast<size_t>(total));
        for (int i = 0; i < total; ++i) {
            float v = buf[i] * kTxAmplitude * 32767.0f;
            long r = std::lround(v);
            if (r > 32767) r = 32767;
            if (r < -32768) r = -32768;
            pcm[i] = (jshort)r;
        }

        jshortArray out = env->NewShortArray(total);
        env->SetShortArrayRegion(out, 0, total, pcm.data());
        return out;
    }

    // Late-TX path: no silence padding. Only emitted symbols.
    int emittedSyms = FT8_NN - offsetSymbols;
    int total = emittedSyms * nSpsym;

    std::vector<float> buf(static_cast<size_t>(total), 0.0f);
    synth_gfsk(tones, FT8_NN, offsetSymbols, freqHz, kFt8SymbolBt, FT8_SYMBOL_PERIOD, sampleRate,
               buf.data());

    std::vector<jshort> pcm(static_cast<size_t>(total));
    for (int i = 0; i < total; ++i) {
        float v = buf[i] * kTxAmplitude * 32767.0f;
        long r = std::lround(v);
        if (r > 32767) r = 32767;
        if (r < -32768) r = -32768;
        pcm[i] = (jshort)r;
    }

    jshortArray out = env->NewShortArray(total);
    env->SetShortArrayRegion(out, 0, total, pcm.data());
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_net_ft8vc_ft8native_Ft8Native_nativeClearCallsignTable(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_decodeMutex);
    hashtable_init();
}
