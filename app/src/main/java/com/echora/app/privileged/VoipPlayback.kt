package com.echora.app.privileged

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log

internal class VoipPlayback private constructor(
    val record: AudioRecord,
    private val unregister: () -> Unit
) {
    var receivedSignal = false

    fun close() {
        try {
            record.release()
        } finally {
            unregister()
        }
    }

    companion object {
        fun open(context: Context, sampleRate: Int): VoipPlayback {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val ruleBuilderClass = Class.forName(ruleClass.name + "\$Builder")
            val mixBuilderClass = Class.forName(mixClass.name + "\$Builder")
            val policyBuilderClass = Class.forName(policyClass.name + "\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val voice = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()
            val matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
                .invoke(ruleBuilder, voice, matchUsage)
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)
            val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            val route = mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, route)
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            val manager = context.getSystemService(AudioManager::class.java)
            val unregister = AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass)
            val registered = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
                .invoke(manager, policy)
            check(registered == 0) { "Policy VoIP rifiutata ($registered)" }
            fun removePolicy() {
                try {
                    unregister.invoke(manager, policy)
                } catch (e: Exception) {
                    Log.w("EchoraShell", "Disattivazione loopback VoIP", e)
                }
            }
            var record: AudioRecord? = null
            try {
                record = policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as? AudioRecord
                checkNotNull(record) { "Loopback VoIP non disponibile" }
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Loopback VoIP non inizializzato" }
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Loopback VoIP non attivo" }
                return VoipPlayback(record, ::removePolicy)
            } catch (e: Exception) {
                try {
                    record?.release()
                } finally {
                    removePolicy()
                }
                throw e
            }
        }
    }
}

internal fun mixVoicePcm(microphone: ByteArray, playback: ByteArray, byteCount: Int) {
    for (index in 0 until byteCount - 1 step 2) {
        val local = (microphone[index].toInt() and 0xff) or (microphone[index + 1].toInt() shl 8)
        val remote = (playback[index].toInt() and 0xff) or (playback[index + 1].toInt() shl 8)
        val mixed = (local + remote).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        microphone[index] = mixed.toByte()
        microphone[index + 1] = (mixed shr 8).toByte()
    }
}
