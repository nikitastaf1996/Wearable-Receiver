package com.example.wearablereceiver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.IOException

/**
 * Manages the logging of heart rate data to a CSV file in the device's Downloads directory.
 *
 * This class handles file creation, writing data in a CSV format, batching writes for efficiency,
 * and closing file resources. It operates on a provided [Handler] to ensure file I/O
 * is performed off the main thread. Status updates and errors are reported via a callback.
 *
 * @param context The application context, used for accessing content resolver and resources.
 * @param fileIoHandler The handler on which file I/O operations will be posted.
 * @param statusCallback A callback function to report status messages or errors.
 */
class HeartRateFileLogger(
    private val context: Context,
    private val fileIoHandler: Handler,
    private val statusCallback: (String) -> Unit
) {
    private var writer: BufferedWriter? = null
    private var currentFileUri: Uri? = null
    private var writeCount = 0

    companion object {
        private const val TAG = "HeartRateFileLogger"
        private const val FLUSH_THRESHOLD = 10
    }

    /**
     * Starts recording heart rate data to a new CSV file.
     *
     * A new file is created in the Downloads directory with a timestamped name.
     * If already recording, a warning is issued via the status callback.
     *
     * @param fileNamePrefix Optional prefix for the generated file name.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startRecording(fileNamePrefix: String = "HeartRate_Data_") {
        fileIoHandler.post {
            if (writer != null) {
                Log.w(TAG, "Start recording called but already recording.")
                statusCallback("Warning: Already recording.")
                return@post
            }

            val fileName = "${fileNamePrefix}${System.currentTimeMillis()}.csv"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = context.contentResolver
            try {
                currentFileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (currentFileUri == null) {
                    Log.e(TAG, "Failed to create new MediaStore entry for $fileName.")
                    statusCallback("Error: Could not create file for recording.")
                    return@post
                }

                resolver.openOutputStream(currentFileUri!!)?.let { outputStream ->
                    writer = BufferedWriter(OutputStreamWriter(outputStream))
                    writer?.appendLine("Timestamp,HeartRate") // CSV Header
                    writer?.flush()
                    Log.i(TAG, "Started recording to: $fileName (URI: $currentFileUri)")
                    statusCallback("Recording started: $fileName")
                } ?: run {
                    Log.e(TAG, "Failed to open output stream for $currentFileUri.")
                    statusCallback("Error: Could not open file for recording.")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting file recording for $fileName. URI: $currentFileUri", e)
                statusCallback("Error: File recording failed to start due to exception.")
                currentFileUri = null
            }
        }
    }

    /**
     * Logs a heart rate data point to the currently open file.
     *
     * Data is written as a new line in "timestamp,heartRate" format.
     * Writes are batched and flushed periodically based on [FLUSH_THRESHOLD].
     *
     * @param timestamp The timestamp of the heart rate data point.
     * @param heartRate The heart rate value.
     */
    fun logData(timestamp: Long, heartRate: Int) {
        fileIoHandler.post {
            if (writer == null) {
                // Log.w(TAG, "Log data called but writer is not initialized.") // Can be noisy
                return@post
            }
            try {
                writer?.appendLine("$timestamp,$heartRate")
                writeCount++
                if (writeCount >= FLUSH_THRESHOLD) {
                    writer?.flush()
                    writeCount = 0
                    Log.d(TAG, "Flushed heart rate data to file.")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing heart rate data to file", e)
                statusCallback("Error: Failed to write data to file.")
                // Consider stopping recording or handling error more robustly
            }
        }
    }

    /**
     * Stops the current recording session.
     *
     * Flushes any buffered data, closes the file writer, and resets internal state.
     *
     * @param notifySuccess If true, a success message including the file name will be sent
     *                      via the status callback.
     */
    fun stopRecording(notifySuccess: Boolean = true) {
        fileIoHandler.post {
            if (writer == null && currentFileUri == null) {
                Log.i(TAG, "Stop recording called but was not recording.")
                return@post
            }
            try {
                writer?.flush()
                writer?.close()
                Log.i(TAG, "Stopped recording. File was: $currentFileUri")
                if (notifySuccess && currentFileUri != null) {
                    val fileName = currentFileUri?.let { uri ->
                        context.contentResolver.query(
                            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                            } else null
                        }
                    } ?: "UnknownFile.csv"
                    statusCallback("Recording stopped. Saved to Downloads/$fileName")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error stopping file recording. File URI was: $currentFileUri", e)
                statusCallback("Error: Failed to stop recording properly.")
            } finally {
                writer = null
                currentFileUri = null
                writeCount = 0 // Reset count
            }
        }
    }
    
    
    /**
     * Closes any open file resources.
     *
     * This method should be called when the logger is no longer needed, for example,
     * when the service using it is destroyed. It attempts a graceful stop of any
     * ongoing recording without notifying the user.
     */
    fun close() {
        fileIoHandler.post {
             Log.d(TAG, "Closing HeartRateFileLogger resources.")
            stopRecording(false)
        }
    }
}
