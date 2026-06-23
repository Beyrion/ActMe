package com.actme.app.audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorderManager(
    private val context: Context
) {
    private val activity: Activity? = context as? Activity

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val REQUEST_RECORD_AUDIO = 1001
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private val isRecording = AtomicBoolean(false)

    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
               PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission() {
        val hostActivity = activity ?: run {
            onError?.invoke("Microphone permission required")
            return
        }
        ActivityCompat.requestPermissions(
            hostActivity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    fun startRecording(outputDir: File) {
        if (isRecording.get()) return

        if (!hasPermission()) {
            requestPermission()
            onError?.invoke("Microphone permission required")
            return
        }

        outputFile = File(outputDir, "voice_${System.currentTimeMillis()}.pcm")
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("Failed to initialize microphone")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            onRecordingStarted?.invoke()

            recordingThread = Thread {
                val buffer = ShortArray(bufferSize)
                val fos = FileOutputStream(outputFile)
                try {
                    while (isRecording.get()) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                        if (read > 0) {
                            val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until read) {
                                byteBuffer.putShort(buffer[i])
                            }
                            fos.write(byteBuffer.array(), 0, read * 2)
                        }
                    }
                } finally {
                    fos.close()
                }
            }
            recordingThread?.start()

        } catch (e: Exception) {
            onError?.invoke("Recording error: ${e.message}")
        }
    }

    fun stopRecording(): File? {
        if (!isRecording.getAndSet(false)) return null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        recordingThread?.join(1000)
        recordingThread = null

        val pcmFile = outputFile ?: return null
        val wavFile = File(pcmFile.parent, pcmFile.name.replace(".pcm", ".wav"))
        convertPcmToWav(pcmFile, wavFile)
        pcmFile.delete()

        onRecordingStopped?.invoke(wavFile)
        return wavFile
    }

    fun cancelRecording() {
        if (!isRecording.getAndSet(false)) return
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        recordingThread?.join(1000)
        recordingThread = null
        outputFile?.delete()
        outputFile = null
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File) {
        val pcmSize = pcmFile.length()
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8

        RandomAccessFile(wavFile, "rw").use { wav ->
            // RIFF header
            wav.writeBytes("RIFF")
            wav.writeIntLE((36 + pcmSize).toInt())
            wav.writeBytes("WAVE")
            // fmt subchunk
            wav.writeBytes("fmt ")
            wav.writeIntLE(16) // Subchunk1Size (PCM)
            wav.writeShortLE(1) // AudioFormat (PCM = 1)
            wav.writeShortLE(numChannels)
            wav.writeIntLE(SAMPLE_RATE)
            wav.writeIntLE(byteRate)
            wav.writeShortLE(blockAlign)
            wav.writeShortLE(bitsPerSample)
            // data subchunk
            wav.writeBytes("data")
            wav.writeIntLE(pcmSize.toInt())

            // Copy PCM data to WAV
            pcmFile.inputStream().use { pcm ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (pcm.read(buffer).also { bytesRead = it } != -1) {
                    wav.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
        writeByte((value shr 16) and 0xFF)
        writeByte((value shr 24) and 0xFF)
    }
}
