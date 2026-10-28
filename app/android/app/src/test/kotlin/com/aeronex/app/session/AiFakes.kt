package com.aeronex.app.session

import com.aeronex.app.ai.AiAvailability
import com.aeronex.app.ai.PlannerResult
import com.aeronex.app.ai.PlannerStats
import com.aeronex.app.ai.ToolCallResult
import com.aeronex.app.command.model.FlightCommand
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted [AiRuntime]: [plans] answers each planner text, [calls] each Needle step. [hold] keeps the planner
 * "thinking" until released or (with [honourCancel]) cancelled, so a test can STOP, press or type in between.
 * [events] records warmUp, plan, unload and idleUnload in order; [warming] runs inside each warm-up.
 */
internal class FakeAiRuntime : AiRuntime {
    val events = CopyOnWriteArrayList<String>()
    val warmUps = AtomicInteger()
    @Volatile var warming: () -> Unit = {}
    /** [unloadIfIdle] unloads (as after 120 s without use). */
    @Volatile var idle = false
    @Volatile override var loaded = false
    val planned = CopyOnWriteArrayList<String>()
    val planTimeouts = CopyOnWriteArrayList<Int>()
    val called = CopyOnWriteArrayList<String>()
    val callBudgets = CopyOnWriteArrayList<Long>()
    val cancels = AtomicInteger()
    val unloads = AtomicInteger()
    val idleChecks = AtomicInteger()
    @Volatile var plans: (String) -> PlannerResult = { steps() }
    @Volatile var calls: (String) -> ToolCallResult = { ToolCallResult.NoCall("no_call", 1) }
    @Volatile var hold: CountDownLatch? = null
    @Volatile var honourCancel = true
    /** Counted down when the planner starts. */
    @Volatile var started = CountDownLatch(1)
    @Volatile private var cancelled = false

    override fun plan(text: String, timeoutMs: Int, cancelled: () -> Boolean): PlannerResult {
        this.cancelled = false
        events += "plan"
        loaded = true
        planned += text
        planTimeouts += timeoutMs
        started.countDown()
        hold?.let { gate ->
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!gate.await(5, TimeUnit.MILLISECONDS) && System.nanoTime() < until) {
                if (honourCancel && (cancelled() || this.cancelled)) return PlannerResult.Cancelled(1)
            }
        }
        if (honourCancel && cancelled()) return PlannerResult.Cancelled(1)
        return plans(text)
    }

    override fun call(step: String, cancelled: () -> Boolean, budgetMs: Long): ToolCallResult {
        called += step
        callBudgets += budgetMs
        if (cancelled()) return ToolCallResult.Cancelled(1)
        return calls(step)
    }

    override fun cancel() {
        cancels.incrementAndGet()
        cancelled = true
    }

    override fun warmUp() {
        warmUps.incrementAndGet()
        events += "warmUp"
        loaded = true
        warming()
    }

    override fun unload() {
        unloads.incrementAndGet()
        events += "unload"
        loaded = false
    }

    override fun unloadIfIdle(): Boolean {
        idleChecks.incrementAndGet()
        if (!idle || !loaded) return false
        events += "idleUnload"
        loaded = false
        return true
    }

    companion object {
        private val STATS = PlannerStats(0.0, 1.0, 2.0, 10, 5)
        fun steps(vararg steps: String): PlannerResult =
            if (steps.isEmpty()) PlannerResult.Refused("{\"steps\":[]}", STATS, 3)
            else PlannerResult.Steps(steps.toList(), "raw", STATS, 3)

        fun call(name: String, arguments: Map<String, Any?> = emptyMap()) =
            ToolCallResult.Call(FlightCommand.of(name, arguments), 2)
    }
}

/** An [AiConfig] over [runtime] with a switchable availability. */
internal class FakeAi(val runtime: FakeAiRuntime = FakeAiRuntime()) {
    @Volatile var availability = AiAvailability(true, null)
    val runtimesBuilt = AtomicInteger()
    val config = AiConfig(
        availability = { availability },
        runtime = { runtimesBuilt.incrementAndGet(); runtime },
        idleCheckMs = 50,
    )
}
