package com.aeronex.app.memory

import com.aeronex.app.vehicle.FrameOrigin
import com.aeronex.app.vehicle.Ned
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/** The flight-memory file: a round trip, strict reading, and atomic coalesced writes. */
class MemoryCodecTest {
    @get:Rule val folder = TemporaryFolder()

    private val state = MemoryState(
        home = Ned(0.0, -0.5, 0.25),
        trail = listOf(Waypoint(Ned(0.0, 0.0, -3.0), "Takeoff", 1_730_000_000_000), Waypoint(Ned(2.5, 1.0, -3.0), "Move \"left\"\n2 m", 7)),
        points = mapOf(3 to Waypoint(Ned(-1.0, 2.0, -4.0), "Point 3", 9), 1 to Waypoint(Ned(1.0, 1.0, -2.0), "Point 1", 8)),
        history = listOf(
            CommandRecord(1, 1, 5, "move_relative", mapOf("direction" to "left", "distance_m" to 2.5), "Move left 2.5 m", "typed", "COMPLETED"),
            CommandRecord(2, 2, 6, "takeoff", mapOf("altitude_m" to 3), "Take off to 3 m", "phone", "NOT_SENT"),
        ),
        nextId = 3,
        notice = "Home moved, so the flown path and saved points were forgotten.",
    )

    @Test fun aMemoryRoundTripsExactly() {
        assertEquals(state, MemoryCodec.decode(MemoryCodec.encode(state)))
        assertEquals(MemoryState(), MemoryCodec.decode(MemoryCodec.encode(MemoryState())))
    }

    @Test fun integersStayIntegersAndDecimalsStayDecimals() {
        val history = MemoryCodec.decode(MemoryCodec.encode(state)).history
        assertEquals(2.5, history[0].arguments["distance_m"])
        assertEquals(3, history[1].arguments["altitude_m"])
    }

    @Test fun malformedOrUnexpectedContentIsRefused() {
        val good = MemoryCodec.encode(state)
        for (bad in listOf("", "{", "[]", "null", good + "x", good.replace("\"version\":1", "\"version\":2"),
            good.replace("\"version\":1", "\"version\":\"1\""), good.replace("\"x\":0.0", "\"x\":\"0\""),
            good.replace("\"3\":{", "\"12\":{"), good.replace("\"label\":\"Takeoff\"", "\"label\":7"),
            good.replace("\"nextId\":3", "\"nextId\":3.5"), "{\"version\":1}", "[".repeat(100))) {
            assertThrows(bad.take(60), IllegalArgumentException::class.java) { MemoryCodec.decode(bad) }
        }
    }

    @Test fun nonFiniteNumbersAreNeverReadBackAsPositions() {
        val broken = state.copy(home = Ned(Double.NaN, 0.0, 0.0))
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.decode(MemoryCodec.encode(broken)) }
    }

    @Test fun jsonEscapesRoundTrip() {
        val text = "quote \" backslash \\ tab \t control \u0001 hindi वापस"
        assertEquals(mapOf("k" to text), Json.read(Json.write(mapOf("k" to text))))
        assertEquals(listOf(1L, -2L, 2.5, true, false, null, "x"), Json.read("[1,-2,2.5,true,false,null,\"x\"]"))
        assertEquals(mapOf("a" to "é"), Json.read("{\"a\":\"\\u00e9\"}"))
    }

    @Test fun theFileStoreWritesAtomicallyAndLoadsWhatItWrote() {
        val file = File(folder.root, "memory/flight_memory.json")
        val store = FileJournalStore(file, Executor { it.run() })
        assertNull(store.load())
        store.save(state)
        assertEquals(state, store.load())
        assertFalse(File(file.parentFile, "flight_memory.json.tmp").exists())
        file.writeText("{damaged")
        assertNull(store.load())
        assertEquals(0L, store.writeErrors)
    }

    @Test fun aBurstOfSavesWritesOnlyTheNewestState() {
        val file = File(folder.root, "flight_memory.json")
        val queued = mutableListOf<Runnable>()
        val store = FileJournalStore(file, Executor { queued += it })
        store.save(state.copy(nextId = 10))
        store.save(state.copy(nextId = 11))
        store.save(state.copy(nextId = 12))
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(12L, store.load()!!.nextId)
        store.save(state.copy(nextId = 13))
        assertEquals(2, queued.size)
        queued.last().run()
        assertEquals(13L, store.load()!!.nextId)
    }

    @Test fun aJournalSurvivesARestartThroughTheFileStore() {
        val file = File(folder.root, "flight_memory.json")
        val journal = FlightJournal(FileJournalStore(file, Executor { it.run() })) { 42 }
        journal.observeHome(Ned(0.0, 0.0, 0.0), FrameOrigin(-353632621.0, 1491652374.0, 584.0))
        journal.takeoffCompleted(Ned(0.0, 0.0, -3.0))
        journal.markPoint(4, Ned(2.0, 2.0, -3.0))
        journal.saveMission(SavedMission("Perimeter", listOf(
            SavedStage("typed", listOf(SavedStep("takeoff", mapOf("altitude_m" to 3), "Take off to 3 m"),
                SavedStep("move_relative", mapOf("direction" to "forward", "distance_m" to 2.5), "Move forward 2.5 m"))),
            SavedStage("tap", listOf(SavedStep("land", emptyMap(), "Land"))))))
        val restored = FlightJournal(FileJournalStore(file, Executor { it.run() }))
        assertEquals(journal.state, restored.state)
    }
}
