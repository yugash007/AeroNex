package com.aeronex.app.session

import com.aeronex.app.ai.AiAvailability
import com.aeronex.app.ai.PlannerResult
import com.aeronex.app.command.ParseResult
import com.aeronex.app.command.PlanParser
import com.aeronex.app.observability.Trace
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The [AiRouter] alone, on a scripted clock: the warm-up within the A5 budget and the A6 availability re-checks. */
class AiRouterTest {
    private val ai = FakeAi()
    private val runtime get() = ai.runtime
    @Volatile private var now = 0L
    private val rules = (PlanParser.parseText(SAID, "en-IN") as ParseResult.Rejected).message

    private fun router(config: AiConfig = ai.config) =
        AiRouter(config, Trace(), { now }, changed = {}, deliver = { _, _ -> AiDelivery.Shown })

    /** Routes [SAID] and waits for its answer. */
    private fun answer(router: AiRouter): AiStatus {
        val routed = router.route(SAID, rules, 0) as AiRouter.Routed.Thinking
        waitFor("answered") { router.status().let { it.request == routed.request && it.state == AiState.IDLE } }
        return router.status()
    }

    @Test fun aColdPlannerLoadsWithinTheBudgetOrSaysItWasLoading() {
        router().use { router ->
            waitFor("warmed up once AI is available") { runtime.warmUps.get() == 1 }
            // A cold load on a slow phone that takes the whole 6 s: no plan, a plain "loading" answer.
            router.trimMemory()
            runtime.warming = { now += 7_000 }
            assertEquals(AiRouter.LOADING, answer(router).error)
            assertTrue(runtime.planned.isEmpty())
            // Loaded now: the next request plans at once with the full planner budget.
            runtime.warming = {}
            assertEquals(AiRouter.REFUSED, answer(router).error)
            assertEquals(listOf(4_500), runtime.planTimeouts)
            // A load that leaves part of the budget: the planner gets the rest; timing out there is "loading" too.
            router.trimMemory()
            runtime.warming = { now += 5_000 }
            runtime.plans = { PlannerResult.TimedOut(1_000) }
            assertEquals(AiRouter.LOADING, answer(router).error)
            assertEquals(listOf(4_500, 1_000), runtime.planTimeouts)
            assertEquals(3, runtime.warmUps.get())
        }
    }

    @Test fun aRequestWhileAiIsOffChecksAgainSoModelsThatArriveAnotherWayAreFound() {
        val availability = AtomicReference(AiAvailability(false, AiAvailability.DOWNLOAD_MODELS))
        router(AiConfig({ availability.get() }, { runtime }, idleCheckMs = 600_000)).use { router ->
            waitFor("checked") { router.status().reason == AiAvailability.DOWNLOAD_MODELS }
            availability.set(AiAvailability(true, null)) // e.g. pushed into filesDir/models
            assertTrue(router.route(SAID, rules, 0) is AiRouter.Routed.Unavailable)
            waitFor("found") { router.status().available }
            assertTrue(router.route(SAID, rules, 0) is AiRouter.Routed.Thinking)
        }
    }

    @Test fun theIdleCheckFindsModelsThatArriveAnotherWay() {
        ai.availability = AiAvailability(false, AiAvailability.DOWNLOAD_MODELS)
        router().use { router ->
            waitFor("checked") { router.status().reason == AiAvailability.DOWNLOAD_MODELS }
            ai.availability = AiAvailability(true, null)
            waitFor("found") { router.status().available }
            waitFor("warmed up") { runtime.warmUps.get() == 1 }
            assertEquals(1, ai.runtimesBuilt.get())
        }
    }

    @Test fun anErrorOnTheAiThreadEndsTheRequestInsteadOfLeavingItThinking() {
        // Audit S11: only RuntimeExceptions were caught, so an Error left the AI "thinking" until the next request.
        router().use { router ->
            waitFor("warmed up once AI is available") { runtime.warmUps.get() == 1 }
            runtime.plans = { throw OutOfMemoryError("Failed to allocate a 943718400 byte allocation") }
            assertEquals(AiRouter.OUT_OF_MEMORY, answer(router).error)
            runtime.plans = { throw LinkageError("a class failed to load") }
            assertEquals(AiRouter.GENERIC, answer(router).error)
            runtime.plans = { throw IllegalStateException("decode failed") }
            assertEquals("decode failed", answer(router).error)
            // The AI thread is still there for the next command.
            runtime.plans = { FakeAiRuntime.steps() }
            assertEquals(AiRouter.REFUSED, answer(router).error)
        }
    }

    @Test fun anErrorInTheIdleCheckDoesNotEndTheChecks() {
        // A periodic task that throws never runs again: an Error there ended the A6 re-checks and the idle unload.
        val checks = AtomicInteger()
        val availability = AtomicReference(AiAvailability(false, AiAvailability.DOWNLOAD_MODELS))
        val config = AiConfig({
            if (checks.incrementAndGet() == 2) throw LinkageError("a class failed to load")
            availability.get()
        }, { runtime }, idleCheckMs = 20)
        router(config).use { router ->
            waitFor("checks go on after the Error") { checks.get() >= 4 }
            availability.set(AiAvailability(true, null))
            waitFor("found") { router.status().available }
        }
    }

    private companion object {
        const val SAID = "circle the tree"
    }
}
