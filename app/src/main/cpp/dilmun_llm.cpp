// JNI layer between app.dilmun.core.Llama and llama.cpp.
//
// Text crosses the boundary as UTF-8 byte arrays, never as JNI strings, so
// any character (emoji included) is safe, and a token that ends halfway
// through a character is held back until the character is complete.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "common.h"
#include "chat.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "dilmun_llm"
#endif

namespace {

struct Handle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    common_chat_templates_ptr tmpls;
    int n_ctx = 0;
    int n_batch = 512;
    std::atomic<bool> stop{false};
    // stats of the last generation
    long prompt_tokens = 0, gen_tokens = 0;
    double prompt_ms = 0, gen_ms = 0;
    std::string stop_reason;
};

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level < GGML_LOG_LEVEL_WARN) return;
#ifdef __ANDROID__
    __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, LOG_TAG, "%s", text);
#else
    fputs(text, stderr);
#endif
}

std::string bytes_of(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out && !s.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

// Length of the longest prefix of s that ends on a complete UTF-8 character.
size_t complete_utf8(const std::string & s) {
    size_t i = s.size();
    // step back over at most 3 continuation bytes to the start of the last character
    size_t back = 0;
    while (back < 4 && i > 0 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) { i--; back++; }
    if (i == 0) return s.size();                        // not UTF-8 at all: let Java replace it
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    size_t need = lead < 0x80 ? 1 : (lead >> 5) == 0x6 ? 2 : (lead >> 4) == 0xE ? 3 : (lead >> 3) == 0x1E ? 4 : 1;
    return (back + 1 >= need) ? s.size() : i - 1;
}

std::string json_escape(const std::string & s) {
    std::string o;
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
                else o += c;
        }
    }
    return o;
}

double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_app_dilmun_core_Llama_nativeInit(JNIEnv * env, jclass, jbyteArray backend_dir) {
    llama_log_set(log_cb, nullptr);
    std::string dir = bytes_of(env, backend_dir);
    if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str());
    else ggml_backend_load_all();
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_app_dilmun_core_Llama_nativeLoad(JNIEnv * env, jclass, jbyteArray jpath, jint n_ctx, jint n_threads) {
    std::string path = bytes_of(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                                   // CPU only; the phone's cores
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }

    auto * h = new Handle();
    h->model = model;
    h->ctx = ctx;
    h->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    try {
        h->tmpls = common_chat_templates_init(model, "");
    } catch (...) {
        h->tmpls = common_chat_templates_init(model, "chatml");
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jbyteArray JNICALL
Java_app_dilmun_core_Llama_nativeInfo(JNIEnv * env, jclass, jlong handle) {
    auto * h = reinterpret_cast<Handle *>(handle);
    char desc[256] = {0};
    llama_model_desc(h->model, desc, sizeof desc);
    char arch[64] = {0};
    llama_model_meta_val_str(h->model, "general.architecture", arch, sizeof arch);
    char name[256] = {0};
    llama_model_meta_val_str(h->model, "general.name", name, sizeof name);
    // the tokenizer family and pre-tokenizer: a mismatch here is a common cause of garbled output
    char tok[64] = {0};
    llama_model_meta_val_str(h->model, "tokenizer.ggml.model", tok, sizeof tok);
    char pre[64] = {0};
    llama_model_meta_val_str(h->model, "tokenizer.ggml.pre", pre, sizeof pre);
    std::string j = std::string("{")
        + "\"desc\":\"" + json_escape(desc) + "\","
        + "\"arch\":\"" + json_escape(arch) + "\","
        + "\"name\":\"" + json_escape(name) + "\","
        + "\"tokenizer\":\"" + json_escape(tok) + "\","
        + "\"pre\":\"" + json_escape(pre) + "\","
        + "\"n_vocab\":" + std::to_string(llama_vocab_n_tokens(llama_model_get_vocab(h->model))) + ","
        + "\"params\":" + std::to_string(llama_model_n_params(h->model)) + ","
        + "\"bytes\":" + std::to_string(llama_model_size(h->model)) + ","
        + "\"n_ctx\":" + std::to_string(h->n_ctx) + ","
        + "\"n_ctx_train\":" + std::to_string(llama_model_n_ctx_train(h->model)) + ","
        + "\"template\":" + (common_chat_templates_was_explicit(h->tmpls.get()) ? "true" : "false") + ","
        + "\"system\":\"" + json_escape(llama_print_system_info()) + "\""
        + "}";
    return to_bytes(env, j);
}

JNIEXPORT jint JNICALL
Java_app_dilmun_core_Llama_nativeCountTokens(JNIEnv * env, jclass, jlong handle, jbyteArray jtext) {
    auto * h = reinterpret_cast<Handle *>(handle);
    return static_cast<jint>(common_tokenize(h->ctx, bytes_of(env, jtext), false, false).size());
}

JNIEXPORT void JNICALL
Java_app_dilmun_core_Llama_nativeStop(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Handle *>(handle)->stop = true;
}

// Returns the generated text as UTF-8, or null if the prompt could not be processed.
JNIEXPORT jbyteArray JNICALL
Java_app_dilmun_core_Llama_nativeGenerate(JNIEnv * env, jclass, jlong handle,
                                         jobjectArray roles, jobjectArray contents,
                                         jint max_tokens, jfloat temp, jboolean think, jbyteArray grammar, jobject sink) {
    auto * h = reinterpret_cast<Handle *>(handle);
    h->stop = false;
    h->prompt_tokens = h->gen_tokens = 0;
    h->prompt_ms = h->gen_ms = 0;
    h->stop_reason = "";

    // messages → prompt, through the model's own chat template
    std::vector<common_chat_msg> msgs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto jr = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
        auto jc = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        common_chat_msg m;
        m.role = bytes_of(env, jr);
        m.content = bytes_of(env, jc);
        msgs.push_back(m);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    // a last "prefill" message is the start of the model's own reply: it continues from it
    std::string prefill;
    if (!msgs.empty() && msgs.back().role == "prefill") {
        prefill = msgs.back().content;
        msgs.pop_back();
    }
    std::string prompt, think_tag;
    common_chat_templates_inputs in;
    in.messages = msgs;
    in.add_generation_prompt = true;
    in.enable_thinking = think == JNI_TRUE;
    try {
        in.use_jinja = true;
        auto p = common_chat_templates_apply(h->tmpls.get(), in);
        prompt = p.prompt;
        think_tag = p.thinking_start_tag;
    } catch (...) {
        try {
            in.use_jinja = false;
            auto p = common_chat_templates_apply(h->tmpls.get(), in);
            prompt = p.prompt;
            think_tag = p.thinking_start_tag;
        } catch (...) {
            for (auto & m : msgs) prompt += m.role + ": " + m.content + "\n";
            prompt += "assistant: ";
        }
    }
    // With thinking on, the prefill goes inside the thinking block, opened here
    // unless the template's generation prompt already opened it. It is returned
    // and streamed as part of the reply, so the reasoning shown starts with it.
    std::string shown;
    if (!prefill.empty()) {
        if (think == JNI_TRUE) {
            if (think_tag.empty()) think_tag = "<think>";
            size_t end = prompt.find_last_not_of(" \n\r\t");
            std::string trimmed = end == std::string::npos ? "" : prompt.substr(0, end + 1);
            bool opened = trimmed.size() >= think_tag.size()
                && trimmed.compare(trimmed.size() - think_tag.size(), think_tag.size(), think_tag) == 0;
            if (opened) prompt = trimmed + "\n";
            else prompt += think_tag + "\n";
            shown = think_tag + "\n";
        }
        shown += prefill;
        prompt += prefill;
    }

    llama_memory_clear(llama_get_memory(h->ctx), true);
    std::vector<llama_token> tokens = common_tokenize(h->ctx, prompt, true, true);
    h->prompt_tokens = static_cast<long>(tokens.size());
    if (tokens.empty() || static_cast<int>(tokens.size()) + 8 >= h->n_ctx) {
        h->stop_reason = "prompt does not fit the context";
        return nullptr;
    }

    auto t0 = std::chrono::steady_clock::now();
    for (size_t i = 0; i < tokens.size(); i += h->n_batch) {
        int32_t cnt = static_cast<int32_t>(std::min(tokens.size() - i, static_cast<size_t>(h->n_batch)));
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, cnt)) != 0) {
            h->stop_reason = "decode failed";
            return nullptr;
        }
        if (h->stop) { h->stop_reason = "stopped"; return to_bytes(env, ""); }
    }
    h->prompt_ms = ms_since(t0);

    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // An output grammar from the arbiters: the model can only write what their rules could accept.
    if (grammar) {
        std::string g = bytes_of(env, grammar);
        if (!g.empty()) {
            llama_sampler * gs = llama_sampler_init_grammar(vocab, g.c_str(), "root");
            if (!gs) {
                llama_sampler_free(smpl);
                h->stop_reason = "the output grammar did not parse";
                return nullptr;
            }
            llama_sampler_chain_add(smpl, gs);
        }
    }
    // A repetition penalty helps free writing, but a list of facts repeats "|",
    // attributes and names on every line by design; penalising those makes a
    // small model stop early or garble the format. So greedy decoding (temp 0,
    // what extraction uses) runs without it; the caller stops a loop instead.
    if (temp > 0.0f)
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jmethodID on_piece = nullptr;
    if (sink) on_piece = env->GetMethodID(env->GetObjectClass(sink), "onPiece", "([B)Z");

    std::string out = shown, pending;
    if (!shown.empty() && on_piece) {
        jbyteArray b = to_bytes(env, shown);
        env->CallBooleanMethod(sink, on_piece, b);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(b);
    }
    int pos = static_cast<int>(tokens.size());
    auto t1 = std::chrono::steady_clock::now();
    h->stop_reason = "length";
    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) { h->stop_reason = "end"; break; }
        std::string piece = common_token_to_piece(h->ctx, tok, false);
        out += piece;
        pending += piece;
        h->gen_tokens++;
        size_t ok = complete_utf8(pending);
        if (ok > 0 && on_piece) {
            jbyteArray b = to_bytes(env, pending.substr(0, ok));
            jboolean more = env->CallBooleanMethod(sink, on_piece, b);
            env->DeleteLocalRef(b);
            pending.erase(0, ok);
            if (env->ExceptionCheck()) { env->ExceptionClear(); h->stop_reason = "stopped"; break; }
            if (!more) { h->stop_reason = "stopped"; break; }
        }
        if (h->stop) { h->stop_reason = "stopped"; break; }
        if (pos + 1 >= h->n_ctx) { h->stop_reason = "context full"; break; }
        if (llama_decode(h->ctx, llama_batch_get_one(&tok, 1)) != 0) { h->stop_reason = "decode failed"; break; }
        pos++;
    }
    h->gen_ms = ms_since(t1);
    llama_sampler_free(smpl);
    if (!pending.empty() && on_piece) {
        jbyteArray b = to_bytes(env, pending);
        env->CallBooleanMethod(sink, on_piece, b);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(b);
    }
    return to_bytes(env, out);
}

JNIEXPORT jbyteArray JNICALL
Java_app_dilmun_core_Llama_nativeStats(JNIEnv * env, jclass, jlong handle) {
    auto * h = reinterpret_cast<Handle *>(handle);
    std::string j = "{\"prompt_tokens\":" + std::to_string(h->prompt_tokens)
        + ",\"gen_tokens\":" + std::to_string(h->gen_tokens)
        + ",\"prompt_ms\":" + std::to_string(static_cast<long>(h->prompt_ms))
        + ",\"gen_ms\":" + std::to_string(static_cast<long>(h->gen_ms))
        + ",\"stop\":\"" + json_escape(h->stop_reason) + "\"}";
    return to_bytes(env, j);
}

JNIEXPORT void JNICALL
Java_app_dilmun_core_Llama_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * h = reinterpret_cast<Handle *>(handle);
    if (!h) return;
    h->tmpls.reset();
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

}  // extern "C"
