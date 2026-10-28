package com.aeronex.app.session

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import com.aeronex.app.safety.StopPhase
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToneGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@Suppress("UNCHECKED_CAST")
class FlightSessionServiceTest {
    private var vehicle: FakeArduCopter? = null
    private var controller = SessionController()

    @Before fun install() = SessionHub.install(controller)

    @After fun tearDown() {
        SessionHub.install(null)
        controller.close()
        vehicle?.close()
    }

    private fun start(action: String) =
        Robolectric.buildService(FlightSessionService::class.java, Intent().setAction(action)).create().startCommand(0, 1)

    private fun idleMain(forMs: Long = 0) {
        val looper = shadowOf(Looper.getMainLooper())
        if (forMs == 0L) looper.idle() else looper.idleFor(Duration.ofMillis(forMs))
    }

    @Test fun runsInTheForegroundWithTheLinkLineAndAStopAction() {
        val service = start(FlightSessionService.ACTION_START).get()
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull("startForeground was called", notification)
        assertEquals(SessionNotificationText.TITLE, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Not connected", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf(SessionNotificationText.STOP_ACTION), notification.actions.map { it.title.toString() })
        assertTrue("ongoing", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(controller.foregroundService)
        assertEquals(true, (controller.snapshot()["link"] as Map<*, *>)["foregroundService"])
    }

    @Test fun theStopActionIsANotificationStop() {
        val service = start(FlightSessionService.ACTION_START).get()
        service.onStartCommand(Intent().setAction(FlightSessionService.ACTION_STOP), 0, 2)
        waitFor("STOP decided") { controller.supervisorState().stop != null }
        val stop = controller.supervisorState().stop!!
        assertEquals(StopTrigger.NOTIFICATION, stop.trigger)
        assertFalse("never an override", stop.override)
    }

    @Test fun aStaleStopWithoutASessionStopsTheServiceAndDestroyClearsTheFlag() {
        val stale = start(FlightSessionService.ACTION_STOP).get()
        assertTrue(shadowOf(stale).isStoppedBySelf)
        assertFalse(controller.foregroundService)
        val running = start(FlightSessionService.ACTION_START)
        assertTrue(controller.foregroundService)
        running.destroy()
        assertFalse(controller.foregroundService)
    }

    @Test fun aRefusedForegroundStartKeepsTheSessionRunningWithoutTheFlagAndLogsIt() {
        val built = Robolectric.buildService(FlightSessionService::class.java,
            Intent().setAction(FlightSessionService.ACTION_START)).create()
        shadowOf(built.get()).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("startForeground() not allowed"))
        val service = built.startCommand(0, 1).get()
        assertFalse("the app is not crashed and the service not stopped", shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertFalse(controller.foregroundService)
        assertEquals(false, (controller.snapshot()["link"] as Map<*, *>)["foregroundService"])
        assertTrue("logged", ShadowLog.getLogsForTag(FlightSessionService.TAG).any { it.throwable is ForegroundServiceStartNotAllowedException })
        // It still follows the session: a STOP from its notification action reaches the supervisor.
        service.onStartCommand(Intent().setAction(FlightSessionService.ACTION_STOP), 0, 2)
        waitFor("STOP decided") { controller.supervisorState().stop?.trigger == StopTrigger.NOTIFICATION }
        built.destroy()
        assertFalse(controller.foregroundService)
    }

    @Test fun anUnconfirmedStopSoundsTheAlarmUntilItIsAcknowledged() {
        // A live session with an airborne vehicle that never acknowledges a mode change.
        val copter = FakeArduCopter().also { vehicle = it }
        controller.close()
        controller = SessionController(transports = { TransportLease(copter.transport()) }, timingFor = { FAST_TIMING })
        SessionHub.install(controller)
        controller.connect(SessionConfig(LinkType.UDP, "127.0.0.1", 14550, 0))
        waitFor("ready") { controller.vehicle().let { it.positionReady && it.home != null } }
        assertTrue(controller.setExecution(true))
        val token = controller.submitPlan(mapOf("steps" to listOf(mapOf("name" to "takeoff",
            "arguments" to mapOf("altitude_m" to 3), "source" to "rules")), "source" to "tap"))["token"] as String
        assertEquals(mapOf("accepted" to true), controller.confirm(token))
        waitFor("airborne", 10_000) { (controller.snapshot()["run"] as Map<String, Any?>?)?.get("phase") == "FINISHED" }
        copter.lose[176] = AtomicInteger(1_000)
        val service = start(FlightSessionService.ACTION_START)
        ShadowToneGenerator.reset()

        controller.stop(StopTrigger.BUTTON)
        waitFor("STOP NOT CONFIRMED", 5_000) { controller.supervisorState().stop?.phase == StopPhase.NOT_CONFIRMED }
        assertTrue(controller.supervisorState().stop!!.alarm)
        idleMain()
        assertEquals("sounds at once", 1, ShadowToneGenerator.getPlayedTones().size)
        idleMain(forMs = 3_500)
        assertEquals("and every second while raised", 4, ShadowToneGenerator.getPlayedTones().size)

        controller.acknowledgeAlarm()
        waitFor("acknowledged") { controller.supervisorState().stop?.alarm == false }
        idleMain()
        val heard = ShadowToneGenerator.getPlayedTones().size
        idleMain(forMs = 3_000)
        assertEquals("silent after Got it", heard, ShadowToneGenerator.getPlayedTones().size)
        service.destroy()
    }

    @Test fun theMicrophoneTypeIsAddedOnlyWithTheMicrophonePermission() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, FlightSessionService.serviceTypes(microphone = false))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            FlightSessionService.serviceTypes(microphone = true))
    }

    @Test fun voiceStartsWithTheServiceWhenTheMicrophoneIsAllowedAndAfterTheFirstTalkOtherwise() {
        val captures = QueueCaptures()
        controller.close()
        controller = SessionController(voice = VoiceConfig(captures, ScriptedEngines()))
        SessionHub.install(controller)
        val service = start(FlightSessionService.ACTION_START)
        assertFalse("no RECORD_AUDIO: connectedDevice only, voice off", controller.voice!!.running)
        assertEquals(VoiceReason.NO_MICROPHONE, controller.voice!!.status().reason)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        service.get().onStartCommand(Intent().setAction(FlightSessionService.ACTION_MICROPHONE), 0, 2)
        assertTrue("upgraded when Talk is first used", controller.voice!!.running)
        service.destroy()
        assertFalse("voice ends with the service", controller.voice!!.running)
        start(FlightSessionService.ACTION_START).also {
            assertTrue("with the permission it starts at once", controller.voice!!.running)
            it.destroy()
        }
    }
}
