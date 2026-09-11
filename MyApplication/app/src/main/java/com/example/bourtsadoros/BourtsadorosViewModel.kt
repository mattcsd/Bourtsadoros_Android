package com.example.bourtsadoros

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bourtsadoros.audio.SoundTouchProcessor
import com.example.bourtsadoros.audio.WavLoader
import com.example.bourtsadoros.model.Chord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BourtsadorosViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val chords = listOf(
        Chord("Do", Color(0xFFE91E63), R.raw.chord_do),
        Chord("La", Color(0xFF2196F3), R.raw.chord_la),
        Chord("Sol", Color(0xFF4CAF50), R.raw.chord_sol),
        Chord("Re", Color(0xFFCDDC39), R.raw.chord_re)
    )

    private val _sequence = MutableStateFlow<List<Int>>(emptyList())
    val sequence: StateFlow<List<Int>> = _sequence.asStateFlow()

    private val _bpm = MutableStateFlow(120)
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    private val _loopCount = MutableStateFlow(1)
    val loopCount: StateFlow<Int> = _loopCount.asStateFlow()

    private val _infiniteLoop = MutableStateFlow(false)
    val infiniteLoop: StateFlow<Boolean> = _infiniteLoop.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPlayingIndex = MutableStateFlow<Int?>(null)
    val currentPlayingIndex: StateFlow<Int?> = _currentPlayingIndex.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val wavCache = mutableMapOf<Int, FloatArray>()
    private var sampleRate = 44100
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var progressJob: Job? = null

    // Cache rendered chord by (rawResId, bpm)
    private data class RenderedChord(val pcmBytes: ByteArray, val totalFrames: Int)
    private val renderedChordCache = mutableMapOf<Pair<Int, Int>, RenderedChord>()

    // Chunks in order they were written, for progress UI
    private data class ChordChunk(val startFrame: Long, val frameCount: Int, val sequenceIndex: Int)
    private val chunkQueue = ArrayDeque<ChordChunk>()

    init {
        try {
            var firstRate = 44100
            for (chord in chords) {
                val wav = WavLoader.load(app.resources, chord.rawResId)
                wavCache[chord.rawResId] = wav.data
                if (chord == chords.first()) firstRate = wav.sampleRate
            }
            sampleRate = firstRate
        } catch (e: Exception) {
            Log.e("Bourtsadoros", "WAV loading failed", e)
        }
    }

    fun addChord(chordIndex: Int) { _sequence.value = _sequence.value + chordIndex }
    fun removeChordAt(position: Int) {
        _sequence.value = _sequence.value.toMutableList().apply {
            if (position in indices) removeAt(position)
        }
    }
    fun clearSequence() { _sequence.value = emptyList() }
    fun setBpm(newBpm: Int) {
        val coerced = newBpm.coerceIn(50, 200)
        if (coerced == _bpm.value) return
        _bpm.value = coerced
        if (_isPlaying.value) restartPlayback()
    }
    fun setLoopCount(count: Int) { _loopCount.value = count.coerceIn(1, 99) }
    fun toggleInfiniteLoop() {
        val wasInfinite = _infiniteLoop.value
        _infiniteLoop.value = !wasInfinite
        if (wasInfinite && _isPlaying.value) stopPlayback()
    }

    fun togglePlay() { if (_isPlaying.value) stopPlayback() else startPlayback() }

    private fun startPlayback() {
        if (_sequence.value.isEmpty()) return
        _isPlaying.value = true
        _currentPlayingIndex.value = null
        _progress.value = 0f
        startPlaybackInternal()
    }

    private fun restartPlayback() {
        val oldTrack = audioTrack
        val oldPlay = playbackJob
        val oldProg = progressJob
        audioTrack = null
        playbackJob = null
        progressJob = null
        oldPlay?.cancel()
        oldProg?.cancel()
        try { oldTrack?.pause() } catch (_: Exception) {}
        try { oldTrack?.flush() } catch (_: Exception) {}
        try { oldTrack?.release() } catch (_: Exception) {}
        _currentPlayingIndex.value = null
        _progress.value = 0f
        // _isPlaying stays true
        startPlaybackInternal()
    }

    private fun startPlaybackInternal() {
        val myQueue = ArrayDeque<ChordChunk>()

        playbackJob = viewModelScope.launch(Dispatchers.Default) {
            val bufferSize = (sampleRate * 2).coerceAtLeast(16384)

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: Exception) {
                Log.e("Bourtsadoros", "AudioTrack build failed", e); null
            }
            if (track == null || !isActive) {
                try { track?.release() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    if (audioTrack == null) _isPlaying.value = false
                }
                return@launch
            }

            val installed = withContext(Dispatchers.Main) {
                if (_isPlaying.value && isActive && audioTrack == null) {
                    audioTrack = track
                    true
                } else false
            }
            if (!installed) {
                try { track.release() } catch (_: Exception) {}
                return@launch
            }

            track.play()

            try {
                var writtenFrames = 0L
                var position = 0
                var loopsDone = 0
                val maxLoops = if (_infiniteLoop.value) Int.MAX_VALUE else _loopCount.value

                while (isActive && _isPlaying.value && loopsDone < maxLoops) {
                    val seq = _sequence.value
                    if (seq.isEmpty()) break

                    if (position >= seq.size) {
                        position = 0
                        loopsDone++
                        if (loopsDone >= maxLoops) break
                        continue
                    }

                    val chordIndex = seq[position]
                    val chord = chords.getOrNull(chordIndex)
                    if (chord == null) { position++; continue }

                    val rendered = try { renderChord(chord.rawResId, _bpm.value) } catch (e: Exception) {
                        Log.e("Bourtsadoros", "render failed", e); null
                    }
                    if (rendered == null) { position++; continue }

                    synchronized(myQueue) {
                        myQueue.addLast(ChordChunk(writtenFrames, rendered.totalFrames, position))
                        while (myQueue.size > 64) myQueue.removeFirst()
                    }
                    writtenFrames += rendered.totalFrames

                    val bytes = rendered.pcmBytes
                    var offset = 0
                    val chunkSize = 4096
                    while (offset < bytes.size && isActive && _isPlaying.value) {
                        val toWrite = minOf(chunkSize, bytes.size - offset)
                        val written = track.write(bytes, offset, toWrite)
                        if (written <= 0) break
                        offset += written
                    }
                    position++
                }
            } catch (e: Exception) {
                Log.e("Bourtsadoros", "Playback error", e)
            } finally {
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                synchronized(myQueue) { myQueue.clear() }
                withContext(NonCancellable + Dispatchers.Main) {
                    if (audioTrack === track) {
                        audioTrack = null
                        _currentPlayingIndex.value = null
                        _progress.value = 0f
                        _isPlaying.value = false
                    }
                }
            }
        }

        progressJob = viewModelScope.launch {
            while (isActive && _isPlaying.value) {
                updateProgress(myQueue)
                delay(50)
            }
        }
    }

    private fun updateProgress(queue: ArrayDeque<ChordChunk>) {
        val track = audioTrack ?: return
        val head = track.playbackHeadPosition.toLong()
        if (head < 0) return

        var chunk: ChordChunk? = null
        synchronized(queue) {
            while (queue.size > 1) {
                val next = queue.elementAtOrNull(1) ?: break
                if (next.startFrame <= head) queue.removeFirst() else break
            }
            chunk = queue.firstOrNull()
        }
        val c = chunk ?: return
        if (head < c.startFrame) return

        _currentPlayingIndex.value = c.sequenceIndex
        val denom = c.frameCount.coerceAtLeast(1)
        _progress.value = ((head - c.startFrame).toFloat() / denom).coerceIn(0f, 1f)
    }

    private fun renderChord(rawResId: Int, bpm: Int): RenderedChord? {
        val key = rawResId to bpm
        synchronized(renderedChordCache) {
            renderedChordCache[key]?.let { return it }
        }

        val rawPcm = wavCache[rawResId] ?: return null
        val tempoRatio = bpm / 120f
        val processor = SoundTouchProcessor()
        try {
            processor.setSampleRate(sampleRate)
            processor.setChannels(1)
            processor.setTempo(tempoRatio)
            processor.putSamples(rawPcm)
            processor.flush()

            var out = FloatArray(8192)
            var outSize = 0
            val scratch = FloatArray(4096)
            while (true) {
                val received = processor.receiveSamplesInto(scratch)
                if (received <= 0) break
                if (outSize + received > out.size) {
                    var newCap = out.size * 2
                    while (newCap < outSize + received) newCap *= 2
                    out = out.copyOf(newCap)
                }
                System.arraycopy(scratch, 0, out, outSize, received)
                outSize += received
            }
            if (outSize == 0) return null

            val result = RenderedChord(convertFloatTo16Bit(out.copyOf(outSize)), outSize)
            synchronized(renderedChordCache) {
                if (renderedChordCache.size > 64) renderedChordCache.clear()
                renderedChordCache[key] = result
            }
            return result
        } finally {
            processor.destroy()
        }
    }

    private fun convertFloatTo16Bit(input: FloatArray): ByteArray {
        val buffer = ByteArray(input.size * 2)
        val view = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in input) {
            val intSample = (sample.coerceIn(-1f, 1f) * 32767).toInt()
            view.putShort(intSample.toShort())
        }
        return buffer
    }


    private fun stopPlayback() {
        if (!_isPlaying.value) return
        val track = audioTrack
        audioTrack = null
        playbackJob?.cancel()
        progressJob?.cancel()
        playbackJob = null
        progressJob = null
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        _isPlaying.value = false
        _currentPlayingIndex.value = null
        _progress.value = 0f
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}