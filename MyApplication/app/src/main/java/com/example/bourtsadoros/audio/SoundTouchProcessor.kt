package com.example.bourtsadoros.audio

class SoundTouchProcessor {
    private var handle: Long = 0

    init {
        System.loadLibrary("bourtsadoros_jni")
        handle = createInstance()
    }

    fun setSampleRate(sampleRate: Int) { setSampleRate(handle, sampleRate) }
    fun setChannels(channels: Int) { setChannels(handle, channels) }
    fun setTempo(tempo: Float) { setTempo(handle, tempo) }
    fun putSamples(samples: FloatArray) { putSamples(handle, samples, samples.size) }
    fun flush() { flush(handle) }
    fun receiveSamples(bufferSize: Int): FloatArray {
        val out = FloatArray(bufferSize)
        val received = receiveSamples(handle, out, bufferSize)
        return if (received > 0) out.copyOf(received) else floatArrayOf()
    }
    fun receiveSamplesInto(output: FloatArray): Int {
        return receiveSamples(handle, output, output.size)
    }
    fun destroy() { destroyInstance(handle); handle = 0 }

    private external fun createInstance(): Long
    private external fun destroyInstance(handle: Long)
    private external fun setSampleRate(handle: Long, sampleRate: Int)
    private external fun setChannels(handle: Long, channels: Int)
    private external fun setTempo(handle: Long, tempo: Float)
    private external fun putSamples(handle: Long, samples: FloatArray, count: Int)
    private external fun flush(handle: Long)
    private external fun receiveSamples(handle: Long, output: FloatArray, maxSamples: Int): Int
}