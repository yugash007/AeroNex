package com.aeronex.app.ai

import com.aeronex.app.speech.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAvailabilityTest {
    private val arm64Phone = AiDevice(processIs64Bit = true, supported64BitAbis = listOf("arm64-v8a"))

    private fun check(
        device: AiDevice = arm64Phone,
        tier: DeviceTier = DeviceTier.M,
        modelsReady: Boolean = true,
        allowTierL: Boolean = false,
        nativeLoads: () -> Boolean = { true },
    ) = AiAvailability.check(device, tier, modelsReady, allowTierL, nativeLoads)

    @Test fun anArm64TierMOrHPhoneWithBothModelsHasAi() {
        for (tier in listOf(DeviceTier.M, DeviceTier.H)) {
            val availability = check(tier = tier)
            assertTrue(availability.available)
            assertNull(availability.reason)
        }
    }

    @Test fun aThirtyTwoBitPhoneNeedsA64BitPhone() {
        val armv7 = AiDevice(processIs64Bit = false, supported64BitAbis = emptyList())
        assertEquals(AiAvailability.NEEDS_64_BIT_PHONE, check(device = armv7).reason)
        assertEquals("Needs a 64-bit phone", AiAvailability.NEEDS_64_BIT_PHONE)
    }

    @Test fun theThirtyTwoBitAppOnA64BitPhoneSaysWhichAppToInstall() {
        val armv7App = AiDevice(processIs64Bit = false, supported64BitAbis = listOf("arm64-v8a"))
        assertEquals(AiAvailability.NEEDS_64_BIT_APP, check(device = armv7App).reason)
    }

    @Test fun x86EmulatorsHaveNoAi() {
        val emulator = AiDevice(processIs64Bit = true, supported64BitAbis = listOf("x86_64", "arm64-v8a"))
        assertEquals(AiAvailability.NEEDS_ARM64, check(device = emulator).reason)
    }

    @Test fun tierLIsRulesOnlyByDefault() {
        val tierL = check(tier = DeviceTier.L)
        assertFalse(tierL.available)
        assertEquals("Not enough memory on this phone", tierL.reason)
        assertTrue("an explicit opt-in", check(tier = DeviceTier.L, allowTierL = true).available)
    }

    @Test fun missingModelsPointToTheModelsScreen() {
        assertEquals("Download the AI models in Models", check(modelsReady = false).reason)
    }

    @Test fun theReasonsComeInOrderAbiThenMemoryThenModels() {
        val armv7 = AiDevice(processIs64Bit = false, supported64BitAbis = emptyList())
        assertEquals(AiAvailability.NEEDS_64_BIT_PHONE, check(armv7, DeviceTier.L, modelsReady = false).reason)
        assertEquals(AiAvailability.NOT_ENOUGH_MEMORY, check(tier = DeviceTier.L, modelsReady = false).reason)
    }

    @Test fun theNativeLibraryIsLoadedOnlyWhenEverythingElseIsFine() {
        var loads = 0
        check(tier = DeviceTier.L, nativeLoads = { loads++; true })
        check(modelsReady = false, nativeLoads = { loads++; true })
        assertEquals(0, loads)
        assertEquals(AiAvailability.NATIVE_FAILED, check(nativeLoads = { false }).reason)
    }

    @Test fun plannerThreadsAreOneToFour() {
        assertEquals(1, AiAvailability.plannerThreads(1))
        assertEquals(3, AiAvailability.plannerThreads(3))
        assertEquals(4, AiAvailability.plannerThreads(8))
    }
}
