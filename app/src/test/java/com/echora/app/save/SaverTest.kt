package com.echora.app.save

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SaverTest {

    @Test fun `file name carries date and minutes`() {
        val n = Saver.fileName(10, Instant.parse("2026-11-24T18:03:00Z"))
        assertTrue(n.startsWith("Echora_20261124_180300_ultimi10m.wav"))
    }
}