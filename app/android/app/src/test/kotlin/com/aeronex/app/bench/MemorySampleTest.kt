package com.aeronex.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemorySampleTest {
    private val rollup = """
        5c0d4b8000-7fff7c5ff000 ---p 00000000 00:00 0                          [rollup]
        Rss:              412340 kB
        Pss:              301234 kB
        Pss_Dirty:        250000 kB
        Pss_Anon:         240000 kB
        Pss_File:          55000 kB
        Pss_Shmem:          6234 kB
        Shared_Clean:      90000 kB
        Shared_Dirty:       2000 kB
        Private_Clean:     60000 kB
        Private_Dirty:    260340 kB
        Referenced:       400000 kB
        Anonymous:        250000 kB
        Swap:               1024 kB
        SwapPss:             512 kB
        Locked:                0 kB
    """.trimIndent()

    @Test fun parsesSmapsRollupInKilobytes() {
        val fields = SmapsRollup.parse(rollup)
        assertEquals(412_340L, fields["Rss"])
        assertEquals(250_000L, fields["Anonymous"])
        assertEquals(512L, fields["SwapPss"])
        assertEquals(55_000L, fields["Pss_File"])
        assertEquals(15, fields.size)
    }

    @Test fun fileBackedResidentIsRssMinusAnonymous() {
        assertEquals(162_340L, SmapsRollup.fileBackedKb(SmapsRollup.parse(rollup)))
        assertNull(SmapsRollup.fileBackedKb(mapOf("Rss" to 1L)))
        assertNull(SmapsRollup.fileBackedKb(null))
        assertEquals(emptyMap<String, Long>(), SmapsRollup.parse("not a rollup\n"))
    }

    @Test fun theCappedMetricIsPrivateDirtyPlusSwap() {
        val sample = MemorySample("after_load", privateDirtyKb = 300_000, swapPssKb = 2_000, totalPssKb = 350_000,
            stats = mapOf("summary.native-heap" to 250_000L), rollupKb = SmapsRollup.parse(rollup))
        assertEquals(302_000L, sample.privateDirtyPlusSwapKb)
        assertEquals(162_340L, sample.fileBackedKb)
        val noSwap = sample.copy(swapPssKb = null, rollupKb = null)
        assertEquals(300_000L, noSwap.privateDirtyPlusSwapKb)
        assertNull(noSwap.fileBackedKb)
    }
}
