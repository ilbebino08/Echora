package com.echora.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import android.content.SharedPreferences
import android.content.SharedPreferences.Editor

class SettingsTest {

    @Test fun `clampMinutes clamps to 1 to 60`() {
        assertEquals(1, Settings.clampMinutes(0))
        assertEquals(1, Settings.clampMinutes(-5))
        assertEquals(10, Settings.clampMinutes(10))
        assertEquals(60, Settings.clampMinutes(999))
        assertEquals(60, Settings.clampMinutes(60))
    }

    @Test fun `bufferMinutes defaults to 10`() {
        val prefs = mock<SharedPreferences>()
        `when`(prefs.getInt("bufferMinutes", 10)).thenReturn(10)
        val settings = Settings(prefs)
        assertEquals(10, settings.bufferMinutes)
    }

    @Test fun `bufferMinutes clamps on read`() {
        val prefs = mock<SharedPreferences>()
        `when`(prefs.getInt("bufferMinutes", 10)).thenReturn(999)
        val settings = Settings(prefs)
        assertEquals(60, settings.bufferMinutes)
    }

    @Test fun `bufferMinutes clamps on write`() {
        val prefs = mock<SharedPreferences>()
        val editor = mock<Editor>()
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putInt("bufferMinutes", 60)).thenReturn(editor)

        val settings = Settings(prefs)
        settings.bufferMinutes = 999

        verify(editor).putInt("bufferMinutes", 60)
        verify(editor).apply()
    }

    @Test fun `listeningEnabled defaults to true`() {
        val prefs = mock<SharedPreferences>()
        `when`(prefs.getBoolean("listeningEnabled", true)).thenReturn(true)
        val settings = Settings(prefs)
        assertTrue(settings.listeningEnabled)
    }

    @Test fun `startOnBoot defaults to true`() {
        val prefs = mock<SharedPreferences>()
        `when`(prefs.getBoolean("startOnBoot", true)).thenReturn(true)
        val settings = Settings(prefs)
        assertTrue(settings.startOnBoot)
    }
}