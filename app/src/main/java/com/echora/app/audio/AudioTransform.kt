package com.echora.app.audio

object AudioTransform {
    /** Downmix 48kHz stereo PCM to 16kHz mono. Takes 1 frame every 3 and averages L+R. */
    fun downmixStereo48kToMono16k(src: ShortArray, frames: Int): ShortArray {
        val outFrames = frames / 3
        if (outFrames <= 0) return ShortArray(0)
        val out = ShortArray(outFrames)
        for (i in 0 until outFrames) {
            val idx = i * 3 * 2 // 2 shorts per stereo frame
            if (idx + 1 < src.size) {
                val left = src[idx].toInt()
                val right = src[idx + 1].toInt()
                out[i] = ((left + right) / 2).toShort()
            }
        }
        return out
    }

    /** Mix two PCM tracks with saturation instead of wrapping. */
    fun mix(a: ShortArray, b: ShortArray): ShortArray {
        val size = minOf(a.size, b.size)
        val out = ShortArray(size)
        for (i in 0 until size) {
            val sum = a[i].toInt() + b[i].toInt()
            out[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}