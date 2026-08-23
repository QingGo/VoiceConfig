package com.voiceconfig.app.ai

import java.io.DataInputStream
import java.io.FileInputStream

/**
 * Shared WAV reader used by offline ASR engines.
 *
 * Supports RIFF/WAVE PCM, downmixes multi-channel input to mono, and
 * resamples non-16 kHz files to the 16 kHz mono float32 format expected
 * by all ASR backends in this project.
 */
object AsrWavReader {

    fun readToMono16k(path: String): FloatArray {
        val input = DataInputStream(FileInputStream(path))
        try {
            require(readString(input, 4) == "RIFF")
            readLeInt(input)
            require(readString(input, 4) == "WAVE")
            var channels = 1
            var sampleRate = 16_000
            var bitsPerSample = 16
            var data: ByteArray? = null
            while (input.available() > 0) {
                val id = readString(input, 4)
                val size = readLeInt(input)
                when (id) {
                    "fmt " -> {
                        readLeShort(input)
                        channels = readLeShort(input)
                        sampleRate = readLeInt(input)
                        readLeInt(input)
                        readLeShort(input)
                        bitsPerSample = readLeShort(input)
                        if (size > 16) input.skipBytes(size - 16)
                    }
                    "data" -> {
                        data = ByteArray(size)
                        input.readFully(data)
                    }
                    else -> input.skipBytes(size)
                }
            }
            val bytes = data ?: error("no data chunk")
            val bytesPerSample = bitsPerSample / 8
            val samples = ArrayList<Float>(bytes.size / bytesPerSample)
            var i = 0
            while (i + bytesPerSample <= bytes.size) {
                val low = bytes[i].toInt() and 0xff
                val high = if (bytesPerSample > 1) bytes[i + 1].toInt() else 0
                val sample = (high shl 8 or low).toShort().toFloat() / 32768f
                if (channels == 1) {
                    samples.add(sample)
                } else if ((i / bytesPerSample) % channels == 0) {
                    samples.add(sample)
                }
                i += bytesPerSample
            }
            return resample(samples.toFloatArray(), sampleRate, 16_000)
        } finally {
            input.close()
        }
    }

    private fun readLeInt(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLeShort(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readString(input: DataInputStream, len: Int): String {
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outSize = (input.size / ratio).toInt()
        val out = FloatArray(outSize)
        for (i in out.indices) {
            val src = (i * ratio).toInt()
            out[i] = input[src.coerceIn(input.indices)]
        }
        return out
    }
}
