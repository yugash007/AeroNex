#pragma once

// The Needle tool schema and system prompt. The Android runtime (cactus_jni.cpp) and the host evaluation
// (tools/ai-eval) compile this same header. Names and argument limits match command/model/FlightCommand.kt.
static constexpr const char *aeronex_needle_tools = R"json([
 {"name":"get_status","description":"Read current drone telemetry.","parameters":{"type":"object","properties":{},"additionalProperties":false}},
 {"name":"land","description":"Land the drone.","parameters":{"type":"object","properties":{},"additionalProperties":false}},
 {"name":"hold","description":"Hold position. Never shut off motors.","parameters":{"type":"object","properties":{},"additionalProperties":false}},
 {"name":"takeoff","description":"Take off to an explicitly requested altitude in meters.","parameters":{"type":"object","properties":{"altitude_m":{"type":"number","minimum":1,"maximum":10}},"required":["altitude_m"],"additionalProperties":false}},
 {"name":"move_relative","description":"Move relative to drone heading by an explicit distance in meters.","parameters":{"type":"object","properties":{"direction":{"type":"string","enum":["forward","backward","left","right","up","down"]},"distance_m":{"type":"number","exclusiveMinimum":0,"maximum":10}},"required":["direction","distance_m"],"additionalProperties":false}}
])json";

// One step at a time: the LFM planner has already split the request into single-command steps.
static constexpr const char *aeronex_needle_system =
    "Preview one drone action only when explicit and complete. Never invent altitude, distance or direction. "
    "Negated and ambiguous requests have no tool calls. Units are meters. This is a dry run.";

// Generation budget for one answer: Needle writes a short reasoning (about 25-55 tokens) before the call.
static constexpr int aeronex_needle_max_tokens = 128;
