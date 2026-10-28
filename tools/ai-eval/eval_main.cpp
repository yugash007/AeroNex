// Runs the on-device AI step on the host: LFM planner (lfm_planner.h) then one Needle tool call per step
// (cactus_runtime.h -> libaeronex_cactus.so -> needle_result.h), for every utterance of a corpus. Writes one JSON
// line per utterance; evaluate.py scores them. Never sends anything anywhere and never executes a tool.
//
//   aeronex-ai-eval --lfm M.gguf --needle needle3.cact --cactus-lib libaeronex_cactus.so --corpus corpus.json
//                  --out results.jsonl [--threads 4] [--timeout-ms 4500]
#include <atomic>
#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include "cactus_runtime.h"
#include "lfm_planner.h"
#include "needle_result.h"

using nlohmann::json;

static double since(std::chrono::steady_clock::time_point started) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
}

int main(int argc, char **argv) {
    std::map<std::string, std::string> args{{"--threads", "4"}, {"--timeout-ms", "4500"}};
    for (int i = 1; i + 1 < argc; i += 2) args[argv[i]] = argv[i + 1];
    for (const char *required : {"--lfm", "--needle", "--cactus-lib", "--corpus", "--out"}) {
        if (!args.count(required)) {
            std::cerr << "missing " << required << '\n';
            return 2;
        }
    }
    const int threads = std::stoi(args["--threads"]);
    const int timeoutMs = std::stoi(args["--timeout-ms"]);
    std::ifstream corpusFile(args["--corpus"]);
    const json corpus = json::parse(corpusFile);
    std::ofstream out(args["--out"]);
    std::atomic<bool> cancel{false};
    aeronex::Planner planner;

    const double warmMs = planner.warmUp(args["--lfm"], threads);
    // The first Needle call maps and loads the model; time it apart from the per-step numbers.
    const auto needleStarted = std::chrono::steady_clock::now();
    aeronex_cactus(args["--cactus-lib"].c_str(), args["--needle"].c_str(), "hold");
    const double needleLoadMs = since(needleStarted);
    out << json({{"kind", "load"}, {"lfmWarmUpMs", warmMs}, {"needleFirstCallMs", needleLoadMs},
                 {"threads", threads}, {"timeoutMs", timeoutMs}}).dump() << '\n';
    std::cerr << "LFM warm-up " << warmMs << " ms, Needle first call " << needleLoadMs << " ms\n";

    for (const auto &item : corpus.at("utterances")) {
        // English utterances go to the planner as said; Indic ones as their lexicon gloss.
        const auto input = item.value("plannerInput", item.at("utterance").get<std::string>());
        json line{{"kind", "utterance"}, {"id", item.at("id")}, {"plannerInput", input}};
        const auto started = std::chrono::steady_clock::now();
        try {
            const auto plan = planner.plan(args["--lfm"], input, threads, timeoutMs, cancel);
            line["plan"] = plan.json();
            json tools = json::array();
            for (const auto &step : plan.steps) {
                const auto stepStarted = std::chrono::steady_clock::now();
                json tool{{"step", step}};
                try {
                    tool["result"] = aeronex_needle_result(json::parse(aeronex_cactus(
                        args["--cactus-lib"].c_str(), args["--needle"].c_str(), step.c_str())), step);
                } catch (const std::exception &e) {
                    tool["error"] = e.what();
                }
                tool["ms"] = since(stepStarted);
                tools.push_back(tool);
            }
            line["tools"] = tools;
        } catch (const std::exception &e) {
            line["error"] = e.what();
        }
        line["totalMs"] = since(started);
        out << line.dump() << '\n';
        out.flush();
        std::cerr << item.at("id").get<std::string>() << ": " << line.value("plan", json::object()).value("text", "")
                  << " (" << line["totalMs"].get<double>() << " ms)\n";
    }
    return 0;
}
