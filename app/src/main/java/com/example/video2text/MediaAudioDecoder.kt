package com.example.video2text

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal data class DecodedAudio(val file: File, val offsetMs: Long, val durationMs: Long)
internal data class DecodedTimeline(val offsetMs: Long, val samples: Int)
internal data class SourceMedia(val hasVideo: Boolean, val durationMs: Long?)

internal class MediaAudioDecoder {
    /** Inspect copied bytes, never the provider's MIME type or filename. */
    fun inspect(source: File, checkCancelled: () -> Unit): SourceMedia {
        val extractor = MediaExtractor()
        try {
            checkCancelled()
            try { extractor.setDataSource(source.absolutePath) }
            catch (_: java.io.IOException) { throw ProcessingException("The file is damaged or uses an unsupported media format. Try another file.") }
            val track = audioTrack(extractor)
            // Check platform codec support before setup; full sample validation happens at Start.
            openDecoder(extractor.getTrackFormat(track)).release()
            checkCancelled()
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            return SourceMedia(
                hasVideo = formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true },
                durationMs = formats.mapNotNull { format ->
                    if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null
                }.maxOrNull()?.div(1_000),
            )
        } finally { extractor.release() }
    }

    private fun audioTrack(extractor: MediaExtractor): Int = requireNotNull((0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }) { "This file has no audio track. Choose audio or video with audible speech." }

    private fun openDecoder(format: MediaFormat): MediaCodec {
        var codec: MediaCodec? = null
        try {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            return decoder
        } catch (error: Throwable) {
            runCatching { codec?.release() }
            if (error is Exception) throw ProcessingException("This phone cannot decode this file's audio codec. Try an AAC or MP3 audio file.")
            throw error
        }
    }

    fun decode(source: File, destination: File, checkCancelled: () -> Unit, progress: (Int?) -> Unit): DecodedAudio {
        val bytes = ByteBuffer.allocate(64 * 1_024).order(ByteOrder.LITTLE_ENDIAN)
        val timeline = BufferedOutputStream(FileOutputStream(destination), 64 * 1_024).use { output ->
            fun flushPcm() {
                if (destination.parentFile!!.usableSpace < bytes.position() + 1024L * 1024) {
                    throw ProcessingException("Not enough free storage. Free some space and select the file again.")
                }
                output.write(bytes.array(), 0, bytes.position())
                bytes.clear()
            }
            val result = decodeSamples(source, checkCancelled, progress) { sample, _ ->
                bytes.putFloat(sample)
                if (!bytes.hasRemaining()) flushPcm()
            }
            flushPcm()
            result
        }
        return DecodedAudio(destination, timeline.offsetMs, timeline.samples.toLong() * 1_000 / PCM_RATE)
    }

    /** Emits converted samples synchronously; a window consumer supplies backpressure. */
    fun decodeSamples(source: File, checkCancelled: () -> Unit, progress: (Int?) -> Unit, emit: (Float, Long) -> Unit): DecodedTimeline {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            checkCancelled()
            extractor.setDataSource(source.absolutePath)
            val audioTrack = audioTrack(extractor)
            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoder = openDecoder(inputFormat)
            codec = decoder
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var conversion: PcmConversion? = null
            var outputRate = 0
            var outputChannels = 0
            var outputEncoding: PcmEncoding? = null
            var samples = 0L
            var energy = 0.0
            var lastActivity = SystemClock.elapsedRealtime()
            while (!outputEnded) {
                checkCancelled()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: error("Audio decoder input is unavailable.")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                                "Protected audio cannot be transcribed."
                            }
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = when (if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT) {
                            AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.INT8
                            AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.INT16
                            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT32
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.INT24
                            AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.INT32
                            else -> throw ProcessingException("This audio decoder uses an unsupported sample format.")
                        }
                        if (conversion != null) {
                            require(rate == outputRate && channels == outputChannels && encoding == outputEncoding) {
                                "The audio format changes inside this file. Choose a video with a single audio format."
                            }
                        } else {
                            outputRate = rate
                            outputChannels = channels
                            outputEncoding = encoding
                            conversion = PcmConversion(rate, channels, encoding, checkCancelled) { sample ->
                                require(samples < Int.MAX_VALUE) { "This file is too long to transcribe." }
                                samples++
                                energy += sample.toDouble() * sample
                                emit(sample, (conversion?.firstPresentationTimeUs ?: 0L) / 1_000)
                            }
                        }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                    index >= 0 -> {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = decoder.getOutputBuffer(index) ?: error("Audio decoder output is unavailable.")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                (conversion ?: error("Audio decoder did not provide a sample format.")).accept(buffer, info.presentationTimeUs)
                                progress(if (durationUs > 0) (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 99) else null)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                }
                if (SystemClock.elapsedRealtime() - lastActivity > 30_000) {
                    throw ProcessingException("The audio decoder stopped responding. Try another file or codec.")
                }
            }
            checkCancelled()
            conversion?.finish()
            require(samples > 0) { "This file contains no decodable audio." }
            require(sqrt(energy / samples) >= 0.0001) { "The audio is silent. Select a file with audible speech." }
            progress(100)
            return DecodedTimeline((conversion?.firstPresentationTimeUs ?: 0L) / 1_000, samples.toInt())
        } finally {
            // release() also stops the codec; this path is safe if configure/start failed.
            try { codec?.release() } finally { extractor.release() }
        }
    }
}
