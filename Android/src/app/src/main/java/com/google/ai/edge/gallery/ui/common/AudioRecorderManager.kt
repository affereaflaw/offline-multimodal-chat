/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorderManager {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val _amplitude = MutableStateFlow(0)
    val amplitude = _amplitude.asStateFlow()
    
    // Config
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    
    // Ring Buffer: 15 seconds * 16000 samples/sec * 2 bytes/sample
    private val MAX_DURATION_SEC = 15
    private val RING_BUFFER_SIZE = SAMPLE_RATE * 2 * MAX_DURATION_SEC
    private val ringBuffer = ByteArray(RING_BUFFER_SIZE)
    private var writeIndex = 0
    private var isBufferFull = false

    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission") // Caller must ensure permission
    fun startRecording() {
        if (isRecording.get()) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            MIN_BUFFER_SIZE
        )

        audioRecord?.startRecording()
        isRecording.set(true)
        writeIndex = 0
        isBufferFull = false
        // Reset amplitudes or allow them to be live

        recordingThread = Thread {
            val buffer = ByteArray(MIN_BUFFER_SIZE)
            while (isRecording.get()) {
                val read = audioRecord?.read(buffer, 0, MIN_BUFFER_SIZE) ?: 0
                if (read > 0) {
                    // Write to ring buffer
                    for (i in 0 until read) {
                        ringBuffer[writeIndex] = buffer[i]
                        writeIndex++
                        if (writeIndex >= RING_BUFFER_SIZE) {
                            writeIndex = 0
                            isBufferFull = true
                        }
                    }
                    
                    // Calculate amplitude (RMS) for VAD
                    var sum = 0.0
                    for (i in 0 until read / 2) {
                        val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
                        sum += sample * sample
                    }
                    val rms = Math.sqrt(sum / (read / 2))
                    _amplitude.value = rms.toInt()
                }
            }
        }
        recordingThread?.start()
    }

    fun stopRecording(): ByteArray {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recordingThread?.join()
        audioRecord = null
        
        // Extract linear audio from ring buffer
        return if (!isBufferFull && writeIndex == 0) {
            ByteArray(0)
        } else if (!isBufferFull) {
            ringBuffer.copyOfRange(0, writeIndex)
        } else {
            // Buffer is full (or has wrapped). content is [writeIndex...end] + [0...writeIndex]
            val result = ByteArray(RING_BUFFER_SIZE)
            System.arraycopy(ringBuffer, writeIndex, result, 0, RING_BUFFER_SIZE - writeIndex)
            System.arraycopy(ringBuffer, 0, result, RING_BUFFER_SIZE - writeIndex, writeIndex)
            result
        }
    }
    
    fun getSampleRate(): Int = SAMPLE_RATE
}
