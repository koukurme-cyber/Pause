package ru.pauza.app.domain

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import ru.pauza.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShortVideoDiagnostics {
    private const val INTERNAL_FILE_NAME = "short-video-diagnostics.txt"
    private const val MAX_BYTES = 768 * 1024L
    private const val KEEP_LINES = 1800

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val exportFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun log(context: Context, tag: String, message: String) {
        Log.d(tag, message)

        val line = buildString {
            append(timestampFormat.format(Date()))
            append(" | ")
            append(tag)
            append(" | ")
            append(message.replace("\n", " "))
            append('\n')
        }

        synchronized(lock) {
            val file = file(context)
            runCatching {
                file.parentFile?.mkdirs()
                if (!file.exists() || file.length() == 0L) {
                    file.writeText(header(), Charsets.UTF_8)
                }
                file.appendText(line, Charsets.UTF_8)
                trimIfNeeded(file)
            }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            val file = file(context)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(header(), Charsets.UTF_8)
            }
        }
    }

    fun exportToDownloads(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        synchronized(lock) {
            val source = file(context)
            if (!source.exists()) {
                source.parentFile?.mkdirs()
                source.writeText(header(), Charsets.UTF_8)
            }

            val fileName = "pauza-short-video-${exportFormat.format(Date())}.txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Pauza"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null

            return runCatching {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: error("Cannot open diagnostics output")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                Environment.DIRECTORY_DOWNLOADS + "/Pauza/" + fileName
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
    }

    private fun file(context: Context): File =
        File(context.filesDir, INTERNAL_FILE_NAME)

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return

        val kept = file.readLines(Charsets.UTF_8).takeLast(KEEP_LINES)
        file.writeText(
            header() +
                "\n[older entries trimmed]\n" +
                kept.joinToString(separator = "\n", postfix = "\n"),
            Charsets.UTF_8
        )
    }

    private fun header(): String =
        buildString {
            appendLine("Pauza short-video diagnostics")
            appendLine("version=${BuildConfig.VERSION_NAME}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("created=${timestampFormat.format(Date())}")
        }
}
