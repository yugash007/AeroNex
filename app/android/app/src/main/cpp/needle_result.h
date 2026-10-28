#pragma once
// Maps one raw Needle answer for one planner step to at most one tool call. Shared by ai_jni.cpp and tools/ai-eval.
// The Kotlin side validates the call again (FlightCommand.fromToolCalls) and the session grounds it against the
// operator's own words (spec A4); this layer only refuses what Needle itself flagged or what the step cannot back.
#include <nlohmann/json.hpp>
#include <regex>
#include <stdexcept>
#include <string>

inline nlohmann::json aeronex_needle_refusal(const char *reason) {
    return {{"call", nullptr}, {"reason", reason}};
}

// Returns {"call": {"name", "arguments"} | null, "reason": "" | why there is no call}. Throws std::runtime_error when
// the runtime answer is not a Needle result at all.
inline nlohmann::json aeronex_needle_result(const nlohmann::json &result, const std::string &step) {
    using nlohmann::json;
    if (!result.is_object() || !result.contains("success") || !result.at("success").is_boolean() ||
        !result.contains("function_calls") || !result.at("function_calls").is_array())
        throw std::runtime_error("Needle returned an unreadable answer.");
    if (result.value("type", "") != "call" || result.at("success") != true || result.at("function_calls").empty())
        return aeronex_needle_refusal("no_call");
    const auto validation = result.value("validation", json::object());
    if (!validation.is_object() || !validation.contains("negation") || !validation.at("negation").is_boolean() ||
        !validation.contains("ungrounded") || !validation.at("ungrounded").is_array() ||
        !result.contains("suppressed_calls") || !result.at("suppressed_calls").is_array())
        throw std::runtime_error("Needle returned incomplete validation.");
    if (validation.at("negation").get<bool>()) return aeronex_needle_refusal("negated");
    if (!validation.at("ungrounded").empty()) return aeronex_needle_refusal("ungrounded");
    if (!result.at("suppressed_calls").empty()) return aeronex_needle_refusal("suppressed");
    if (result.at("function_calls").size() != 1) return aeronex_needle_refusal("several_calls");
    const auto &call = result.at("function_calls").at(0);
    if (!call.is_object() || !call.contains("name") || !call.at("name").is_string() ||
        !call.contains("arguments") || !call.at("arguments").is_object())
        return aeronex_needle_refusal("malformed");
    const auto name = call.at("name").get<std::string>();
    const auto &arguments = call.at("arguments");
    // Grammar range constraints can turn an out-of-range number into a different in-range one ("100" -> 1), and a
    // step without a number can gain one. A number argument must be the step's only number, written in digits.
    for (const char *key : {"altitude_m", "distance_m"}) {
        if (!arguments.contains(key)) continue;
        const std::regex number(R"([+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))");
        auto match = std::sregex_iterator(step.begin(), step.end(), number);
        const auto end = std::sregex_iterator();
        if (!arguments.at(key).is_number() || match == end) return aeronex_needle_refusal("number_not_in_step");
        const double said = std::stod(match->str());
        if (++match != end || said != arguments.at(key).get<double>()) return aeronex_needle_refusal("number_mismatch");
    }
    if (name == "get_status" && !std::regex_search(step,
            std::regex(R"(\b(status|telemetry|battery|health|altitude|position|location|report)\b)", std::regex::icase)))
        return aeronex_needle_refusal("status_unrelated");
    // Host eval regressions: Needle turned "disarm" and "climb 2 meters" into land and "fly to the left 2 meters" into
    // a takeoff. A takeoff, land or hold call must be named by its step.
    const char *named = name == "takeoff" ? R"(\b(take ?off|taking off|launch|lift ?off)\b)"
        : name == "land" ? R"(\b(land|landing|touch ?down)\b)"
        : name == "hold" ? R"(\b(hold|holding|hover|hovering|wait|stay|pause)\b)" : nullptr;
    if (named && !std::regex_search(step, std::regex(named, std::regex::icase)))
        return aeronex_needle_refusal("tool_not_in_step");
    return {{"call", {{"name", name}, {"arguments", arguments}}}, {"reason", ""}};
}
