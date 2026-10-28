// Host test of app/android/app/src/main/cpp/needle_result.h (the Needle answer -> one tool call mapping).
#include <iostream>
#include "needle_result.h"

using nlohmann::json;

static int failures = 0;
static void expect(bool ok, const char *what) {
    if (!ok) {
        std::cerr << "FAIL: " << what << '\n';
        ++failures;
    }
}
static std::string reason(const json &result, const std::string &step) {
    return aeronex_needle_result(result, step).at("reason").get<std::string>();
}
static bool throws(const json &result) {
    try { aeronex_needle_result(result, "hold"); } catch (const std::runtime_error &) { return true; }
    return false;
}

int main() {
    const auto valid = json::parse(R"({"type":"call","success":true,
        "function_calls":[{"name":"takeoff","arguments":{"altitude_m":3}}],"suppressed_calls":[],"confidence":0.8,
        "validation":{"ungrounded":[],"negation":false}})");
    auto mapped = aeronex_needle_result(valid, "take off to 3 meters");
    expect(mapped.at("call") == valid.at("function_calls").at(0), "an explicit call survives");
    expect(mapped.at("reason") == "", "an accepted call has no reason");

    auto negated = valid;
    negated["validation"]["negation"] = true;
    expect(reason(negated, "do not take off to 3 meters") == "negated", "negation is refused");
    auto ungrounded = valid;
    ungrounded["validation"]["ungrounded"] = {"takeoff.altitude_m"};
    expect(reason(ungrounded, "take off") == "ungrounded", "Needle's ungrounded values are refused");
    auto suppressed = valid;
    suppressed["suppressed_calls"] = valid["function_calls"];
    expect(reason(suppressed, "take off to 3 meters") == "suppressed", "suppressed calls are refused");
    auto several = valid;
    several["function_calls"].push_back({{"name", "land"}, {"arguments", json::object()}});
    expect(reason(several, "take off to 3 meters and land") == "several_calls", "one step is one call");
    auto none = valid;
    none["function_calls"] = json::array();
    expect(reason(none, "fly") == "no_call", "no call is no call");
    auto text = valid;
    text["type"] = "text";
    expect(reason(text, "fly") == "no_call", "a text answer is no call");
    auto failed = valid;
    failed["success"] = false;
    expect(reason(failed, "fly") == "no_call", "an unsuccessful answer is no call");

    // Real-model regressions (v0.2.0): schema-constrained decoding turned 100 into 1, a weather question became
    // get_status.
    auto altered = valid;
    altered["function_calls"][0]["arguments"]["altitude_m"] = 1;
    expect(reason(altered, "take off to 100 meters") == "number_mismatch", "the model cannot change a number");
    altered["function_calls"][0]["arguments"]["altitude_m"] = 5;
    expect(reason(altered, "take off to .5 meters") == "number_mismatch", "a leading decimal keeps its dot");
    expect(reason(altered, "take off to -.5 meters") == "number_mismatch", "a negative keeps its sign");
    expect(reason(altered, "take off to five meters") == "number_not_in_step", "a number must be in digits");
    expect(reason(altered, "take off to 5 meters then 6") == "number_mismatch", "a step has one number");
    expect(reason(altered, "take off to 5 meters") == "", "the same number is accepted");
    auto decimal = json::parse(R"({"type":"call","success":true,"suppressed_calls":[],
        "function_calls":[{"name":"move_relative","arguments":{"direction":"up","distance_m":2.5}}],
        "validation":{"ungrounded":[],"negation":false}})");
    expect(reason(decimal, "move up 2.5 meters") == "", "decimals are compared as numbers");
    expect(reason(decimal, "move up 25 meters") == "number_mismatch", "a lost decimal point is refused");
    auto status = valid;
    status["function_calls"] = json::array({{{"name", "get_status"}, {"arguments", json::object()}}});
    expect(reason(status, "what is the weather in delhi") == "status_unrelated", "weather is not telemetry");
    expect(reason(status, "status") == "", "an explicit status request works");
    // Real-model regressions (v0.4.0 host eval): Needle mapped steps to tools they do not name.
    auto land = valid;
    land["function_calls"] = json::array({{{"name", "land"}, {"arguments", json::object()}}});
    expect(reason(land, "disarm") == "tool_not_in_step", "disarm is not land");
    expect(reason(land, "climb 2 meters") == "tool_not_in_step", "climb is not land");
    expect(reason(land, "land") == "", "land is land");
    expect(reason(land, "touch down now") == "", "touch down is land");
    auto hold = valid;
    hold["function_calls"] = json::array({{{"name", "hold"}, {"arguments", json::object()}}});
    expect(reason(hold, "stand") == "tool_not_in_step", "stand is not hold");
    expect(reason(hold, "hover") == "", "hover is hold");
    auto takeoff = valid;
    takeoff["function_calls"][0]["arguments"]["altitude_m"] = 2;
    expect(reason(takeoff, "fly to the left 2 meters") == "tool_not_in_step", "a move is not a takeoff");
    expect(reason(takeoff, "lift off to 2 meters") == "", "lift off is a takeoff");
    auto malformed = valid;
    malformed["function_calls"][0]["arguments"] = "altitude 3";
    expect(reason(malformed, "take off to 3 meters") == "malformed", "arguments must be an object");

    expect(throws(json::object()), "an empty object is unreadable");
    expect(throws(json{{"success", false}}), "a missing call list is unreadable");
    expect(throws(json{{"success", true}, {"function_calls", "invalid"}}), "a non-list call list is unreadable");
    auto incomplete = valid;
    incomplete.erase("validation");
    expect(throws(incomplete), "a call without validation is unreadable");

    if (failures) return 1;
    std::cout << "needle_result.h: all mapping checks passed\n";
    return 0;
}
