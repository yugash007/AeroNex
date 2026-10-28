package com.aeronex.app.ai

/** Spec A5/A6 time budgets for the AI path. */
object AiBudget {
    /** The whole AI path of one utterance (gloss, plan, tool calls). */
    const val TOTAL_MS = 6_000L

    /** One planner run, including a lazy model load. */
    const val PLANNER_MS = 4_500

    /** One Needle tool call (one planner step). */
    const val NEEDLE_STEP_MS = 500L

    /** The planner model unloads after this long without use (and on onTrimMemory). */
    const val IDLE_UNLOAD_MS = 120_000L
}
