#pragma once
// The LFM2.5 planner (spec 2026-10-15 "Command path"): the operator's words in, an ordered list of 0-5 short English
// single-command steps out, as grammar-constrained JSON {"steps":[...]}. Shared by the Android JNI (ai_jni.cpp) and
// the host evaluation (tools/ai-eval), so both run the same prompt, grammar and decoding.
#include "llama.h"
#include <nlohmann/json.hpp>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace aeronex {

inline const char *const kPlannerSystem =
    "You turn a drone operator's request into drone commands. Answer with JSON {\"steps\":[...]}: one step for "
    "each action the operator said, in the order they should happen, at most 5. Most requests are one step. "
    "Every step is exactly one of: take off to N meters / move forward N meters / move backward N meters / "
    "move left N meters / move right N meters / move up N meters / move down N meters / hold / land / status.\n"
    "Rules:\n"
    "- N is the number the operator said, in digits: three is 3, two point five is 2.5, zero point five is 0.5, "
    "two and a half is 2.5, half a meter is 0.5. Never change, round or invent a number.\n"
    "- Never add a step, a direction or a number the operator did not say. Never drop a step they said.\n"
    "- climb, ascend, rise and higher mean up. descend, drop and lower mean down. ahead and advance mean forward. "
    "back, reverse and retreat mean backward. hover, wait and stay mean hold. battery and telemetry mean status.\n"
    "- Answer {\"steps\":[]} when the request is negated (not, don't, never, no), when a move or takeoff has no "
    "number, when it is unclear, and when it asks for anything else or anything unsafe.";

// Worked examples, sent as earlier chat turns of the cached prefix (small models follow turns better than prose).
// None of them is in tools/ai-eval/corpus.json.
struct PlannerExample {
    const char *request;
    const char *answer;
};
inline const PlannerExample kPlannerExamples[] = {
    {"go forward like 4 meters", "{\"steps\":[\"move forward 4 meters\"]}"},
    {"uh rise six meters and then come forward two", "{\"steps\":[\"move up 6 meters\",\"move forward 2 meters\"]}"},
    {"launch to 3 meters", "{\"steps\":[\"take off to 3 meters\"]}"},
    {"back up one point five meters", "{\"steps\":[\"move backward 1.5 meters\"]}"},
    {"take off to 4 meters, wait there, then land",
     "{\"steps\":[\"take off to 4 meters\",\"hold\",\"land\"]}"},
    {"before landing go left two meters", "{\"steps\":[\"move left 2 meters\",\"land\"]}"},
    {"6 meter right go then 1 meter down go", "{\"steps\":[\"move right 6 meters\",\"move down 1 meter\"]}"},
    {"is the battery okay", "{\"steps\":[\"status\"]}"},
    {"stay where you are", "{\"steps\":[\"hold\"]}"},
    {"please don't land yet", "{\"steps\":[]}"},
    {"go right a bit", "{\"steps\":[]}"},
    {"fly over the trees", "{\"steps\":[]}"},
};

// Grammar-constrained output: always {"steps":[...]} with 0-5 steps of 1-8 words; a word is lowercase letters or a
// plain decimal number (no sign, no exponent).
inline const char *const kPlannerGrammar = R"gbnf(
root ::= "{\"steps\":[" ( step ( "," step ){0,4} )? "]}"
step ::= "\"" word ( " " word ){0,7} "\""
word ::= [a-z]{1,12} | [0-9]{1,3} ( "." [0-9]{1,2} )?
)gbnf";

constexpr int kPlannerContext = 1024;
constexpr int kPlannerMaxNewTokens = 128;
constexpr int kPlannerMaxSteps = 5;
constexpr size_t kPlannerMaxInputBytes = 1000;

struct PlannerResult {
    bool complete = false;           // the JSON closed within the token budget and was not cancelled
    std::vector<std::string> steps;  // empty: the model refused (negated, unclear, unsafe)
    std::string text;                // the raw generated text
    std::string stop;                // "done", "limit", "cancelled" or "timeout"
    int promptTokens = 0;
    int generatedTokens = 0;
    double loadMs = 0;               // model load + system prefix, only on a cold call
    double promptMs = 0;
    double generateMs = 0;

    nlohmann::json json() const {
        return {{"complete", complete}, {"steps", steps}, {"text", text}, {"stop", stop},
                {"promptTokens", promptTokens}, {"generatedTokens", generatedTokens},
                {"loadMs", loadMs}, {"promptMs", promptMs}, {"generateMs", generateMs}};
    }
};

// One loaded model and context, reused between calls (mmap'd weights). Not thread-safe by itself: callers hold mutex().
class Planner {
public:
    Planner() = default;
    Planner(const Planner &) = delete;
    Planner &operator=(const Planner &) = delete;
    ~Planner() { unload(); }

    std::mutex &mutex() { return mutex_; }
    bool loaded() const { return model_ != nullptr; }

    // Loads the model and evaluates the fixed system prefix without a deadline (pre-warm); returns milliseconds.
    double warmUp(const std::string &path, int threads) {
        static const std::atomic<bool> never{false};
        const auto started = Clock::now();
        cancel_ = &never;
        deadline_ = Clock::now() + std::chrono::minutes(5);
        timedOut_ = false;
        try {
            load(path, threads);
            const bool ready = !prefixState_.empty() || evaluatePrefix();
            cancel_ = nullptr;
            if (!ready) throw std::runtime_error("The planner could not evaluate its instructions.");
        } catch (...) {
            cancel_ = nullptr;
            throw;
        }
        return elapsedMs(started);
    }

    // The whole call, including a lazy load and the system prefix, runs within timeoutMs and stops on cancel.
    PlannerResult plan(const std::string &path, const std::string &text, int threads, int timeoutMs,
                       const std::atomic<bool> &cancel) {
        if (text.empty() || text.size() > kPlannerMaxInputBytes) throw std::runtime_error("Say a shorter command.");
        cancel_ = &cancel;
        deadline_ = Clock::now() + std::chrono::milliseconds(timeoutMs > 0 ? timeoutMs : 1);
        timedOut_ = false;
        try {
            auto result = run(path, text, threads);
            cancel_ = nullptr;
            return result;
        } catch (...) {
            cancel_ = nullptr;
            throw;
        }
    }

    void unload() {
        if (grammar_) llama_sampler_free(grammar_);
        if (ctx_) llama_free(ctx_);
        if (model_) llama_model_free(model_);
        grammar_ = nullptr;
        ctx_ = nullptr;
        model_ = nullptr;
        vocab_ = nullptr;
        path_.clear();
        prefix_.clear();
        prefixState_.clear();
    }

private:
    using Clock = std::chrono::steady_clock;

    static double elapsedMs(Clock::time_point since) {
        return std::chrono::duration<double, std::milli>(Clock::now() - since).count();
    }

    PlannerResult run(const std::string &path, const std::string &text, int threads) {
        PlannerResult result;
        auto stopped = [&](const char *stop) {
            result.stop = stop;
            return result;
        };
        auto aborted = [&] { return stopped(timedOut_ ? "timeout" : "cancelled"); };
        if (abort()) return aborted();
        const auto loadStarted = Clock::now();
        const bool cold = !model_ || path != path_ || prefixState_.empty();
        load(path, threads);
        if (prefixState_.empty() && !evaluatePrefix()) return aborted();
        if (cold) result.loadMs = elapsedMs(loadStarted);

        const auto promptStarted = Clock::now();
        if (!restorePrefix()) return aborted();
        // The operator's words are never parsed for special tokens: "<|im_end|>" in the text stays plain text.
        auto suffix = tokenize(kUserOpen, false, true);
        const auto words = tokenize(text, false, false);
        const auto close = tokenize(kAssistantOpen, false, true);
        suffix.insert(suffix.end(), words.begin(), words.end());
        suffix.insert(suffix.end(), close.begin(), close.end());
        result.promptTokens = static_cast<int>(prefix_.size() + suffix.size());
        if (result.promptTokens + kPlannerMaxNewTokens > kPlannerContext) throw std::runtime_error("Say a shorter command.");
        if (!decode(suffix)) return aborted();
        result.promptMs = elapsedMs(promptStarted);

        const auto generateStarted = Clock::now();
        llama_sampler_reset(grammar_);
        std::vector<llama_token_data> candidates(llama_vocab_n_tokens(vocab_));
        for (int i = 0; i < kPlannerMaxNewTokens; ++i) {
            if (abort()) return aborted();
            const llama_token token = sample(candidates);
            ++result.generatedTokens;
            if (llama_vocab_is_eog(vocab_, token)) break;
            result.text += piece(token);
            // The grammar allows "]}" only as the end of the answer: stop without decoding the last token.
            if (result.text.size() >= 2 && result.text.compare(result.text.size() - 2, 2, "]}") == 0) {
                result.complete = true;
                break;
            }
            if (!decode({token})) return aborted();
        }
        result.generateMs = elapsedMs(generateStarted);
        if (!result.complete) return stopped("limit");
        const auto parsed = nlohmann::json::parse(result.text, nullptr, false);
        if (!parsed.is_object() || !parsed.contains("steps") || !parsed.at("steps").is_array())
            throw std::runtime_error("The planner returned invalid JSON.");
        for (const auto &step : parsed.at("steps")) result.steps.push_back(step.get<std::string>());
        return stopped("done");
    }

    void load(const std::string &path, int threads) {
        if (model_ && path == path_) {
            if (threads != threads_) {
                llama_set_n_threads(ctx_, threads, threads);
                threads_ = threads;
            }
            return;
        }
        unload();
        static std::once_flag backend;
        std::call_once(backend, [] {
            llama_log_set([](ggml_log_level level, const char *text, void *) {
                if (level == GGML_LOG_LEVEL_ERROR) std::fputs(text, stderr);
            }, nullptr);
            llama_backend_init();
        });
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_ = llama_model_load_from_file(path.c_str(), mp);
        if (!model_) throw std::runtime_error("Cannot load the LFM planner model. Import it again.");
        path_ = path;
        try {
            auto cp = llama_context_default_params();
            cp.n_ctx = kPlannerContext;
            cp.n_batch = 512;
            cp.n_ubatch = 256;
            cp.n_seq_max = 1;
            cp.n_threads = threads;
            cp.n_threads_batch = threads;
            cp.no_perf = true;
            cp.abort_callback = [](void *data) { return static_cast<Planner *>(data)->abort(); };
            cp.abort_callback_data = this;
            ctx_ = llama_init_from_model(model_, cp);
            if (!ctx_) throw std::runtime_error("Cannot allocate the LFM planner context.");
            threads_ = threads;
            vocab_ = llama_model_get_vocab(model_);
            grammar_ = llama_sampler_init_grammar(vocab_, kPlannerGrammar, "root");
            if (!grammar_) throw std::runtime_error("The planner grammar is invalid.");
            prefix_ = tokenize(prefixText(), true, true);
        } catch (...) {
            unload();
            throw;
        }
    }

    // System turn plus the worked examples as earlier turns; LFM2 chat format.
    static std::string prefixText() {
        std::string text = kSystemOpen + std::string(kPlannerSystem) + kTurnClose;
        for (const auto &example : kPlannerExamples)
            text += kUserOpen + std::string(example.request) + kAssistantOpen + example.answer + kTurnClose;
        return text;
    }

    // True once the caller cancelled or the deadline passed; also the llama.cpp abort callback.
    bool abort() {
        if (!cancel_) return false;
        if (cancel_->load()) return true;
        if (Clock::now() >= deadline_) {
            timedOut_ = true;
            return true;
        }
        return false;
    }

    std::vector<llama_token> tokenize(const std::string &text, bool addSpecial, bool parseSpecial) const {
        const int n = -llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()), nullptr, 0,
            addSpecial, parseSpecial);
        if (n <= 0) throw std::runtime_error("Tokenization failed.");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()), tokens.data(), n,
                addSpecial, parseSpecial) != n)
            throw std::runtime_error("Tokenization failed.");
        return tokens;
    }

    std::string piece(llama_token token) const {
        char buffer[256];
        const int size = llama_token_to_piece(vocab_, token, buffer, sizeof buffer, 0, false);
        if (size < 0) throw std::runtime_error("Token conversion failed.");
        return std::string(buffer, size);
    }

    // false: aborted (cancel or deadline). Throws on any other decode failure.
    bool decode(std::vector<llama_token> tokens) {
        for (size_t at = 0; at < tokens.size(); at += 512) {
            const auto count = static_cast<int32_t>(std::min<size_t>(512, tokens.size() - at));
            const int status = llama_decode(ctx_, llama_batch_get_one(tokens.data() + at, count));
            if (status == 2 || abort()) return false;
            if (status != 0) throw std::runtime_error("The planner could not evaluate the command.");
        }
        return true;
    }

    // Evaluates the system prefix and snapshots it; false (no snapshot) if aborted, so a later call retries.
    bool evaluatePrefix() {
        llama_memory_clear(llama_get_memory(ctx_), true);
        prefixState_.clear();
        if (!decode(prefix_)) return false;
        std::vector<uint8_t> state(llama_state_seq_get_size(ctx_, 0));
        if (state.empty() || llama_state_seq_get_data(ctx_, state.data(), state.size(), 0) != state.size())
            throw std::runtime_error("The planner could not save its instructions.");
        prefixState_ = std::move(state);
        return true;
    }

    // Each call restores the snapshot: the recurrent LFM2 layers cannot drop a token range, so restoring the
    // evaluated prefix is the only cheap rewind.
    bool restorePrefix() {
        llama_memory_clear(llama_get_memory(ctx_), true);
        if (llama_state_seq_set_data(ctx_, prefixState_.data(), prefixState_.size(), 0) == prefixState_.size())
            return true;
        return evaluatePrefix();
    }

    // Greedy under the grammar: try the unconstrained best token first and filter the whole vocabulary only if the
    // grammar rejects it (same token, much faster).
    llama_token sample(std::vector<llama_token_data> &candidates) {
        const float *logits = llama_get_logits_ith(ctx_, -1);
        if (!logits) throw std::runtime_error("The planner produced no output.");
        const int vocabulary = static_cast<int>(candidates.size());
        llama_token best = 0;
        for (llama_token id = 1; id < vocabulary; ++id) if (logits[id] > logits[best]) best = id;
        llama_token_data single{best, logits[best], 0.0f};
        llama_token_data_array one{&single, 1, -1, false};
        llama_sampler_apply(grammar_, &one);
        if (!std::isfinite(one.data[0].logit)) {
            for (llama_token id = 0; id < vocabulary; ++id) candidates[id] = {id, logits[id], 0.0f};
            llama_token_data_array all{candidates.data(), candidates.size(), -1, false};
            llama_sampler_apply(grammar_, &all);
            best = -1;
            for (size_t i = 0; i < all.size; ++i) {
                if (!std::isfinite(all.data[i].logit)) continue;
                if (best < 0 || all.data[i].logit > logits[best]) best = all.data[i].id;
            }
            if (best < 0) throw std::runtime_error("The planner grammar has no valid continuation.");
        }
        llama_sampler_accept(grammar_, best);
        return best;
    }

    static constexpr const char *kSystemOpen = "<|im_start|>system\n";
    static constexpr const char *kTurnClose = "<|im_end|>\n";
    static constexpr const char *kUserOpen = "<|im_start|>user\n";
    static constexpr const char *kAssistantOpen = "<|im_end|>\n<|im_start|>assistant\n";

    std::mutex mutex_;
    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    const llama_vocab *vocab_ = nullptr;
    llama_sampler *grammar_ = nullptr;
    std::string path_;
    int threads_ = 0;
    std::vector<llama_token> prefix_;
    std::vector<uint8_t> prefixState_;
    const std::atomic<bool> *cancel_ = nullptr;
    Clock::time_point deadline_{};
    bool timedOut_ = false;
};

}  // namespace aeronex
