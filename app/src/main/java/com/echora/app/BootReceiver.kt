package com.echora.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echora.app.capture.CaptureService
import com.echora.app.capture.Settings

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            val settings = Settings.create(context)
            if (settings.listeningEnabled && settings.startOnBoot) {
                Log.d("EchoraBoot", "Avvio servizio su boot")
                CaptureService.start(context)
            } else {
                Log.d("EchoraBoot", "Avvio su boot disabilitato dalle impostazioni")
            }
        } catch (e: Exception) {
            Log.e("EchoraBoot", "Errore avvio su boot", e)
        }
    }
}