package dev.mtproxypilot

import dev.mtproxypilot.data.PilotApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PilotApiTest {
    @Test fun normalizesServerUrl() {
        assertEquals("https://pilot.example", PilotApi.normalizeServerUrl(" https://pilot.example/// "))
    }

    @Test fun rejectsUrlWithoutScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            PilotApi.normalizeServerUrl("pilot.example")
        }
    }
}

