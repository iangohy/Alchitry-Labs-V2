package com.alchitry.labs2.project.builders

import com.alchitry.labs2.Log
import com.alchitry.labs2.project.Project
import com.alchitry.labs2.ui.theme.AlchitryColors
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.CharBuffer

sealed class ProjectBuilder {
    abstract suspend fun buildProject(
        project: Project,
        topModuleName: String,
        sourceFiles: List<File>,
        constraintFiles: List<File>
    )

    protected suspend fun startStreamPrinter(process: Process, s: InputStream, red: Boolean) =
        withContext(Dispatchers.IO) {
            val color = if (red) AlchitryColors.current.Error else null
            val stringBuffer = StringBuffer(512)
            val buffer = CharBuffer.allocate(512)
            InputStreamReader(s).use { stream ->
                try {
                    while (process.isAlive) {
                        while (stream.ready()) {
                            val ct = stream.read(buffer)
                            buffer.flip()
                            if (ct == -1) return@withContext
                            val newText = buffer.toString()
                            stringBuffer.append(newText)
                            if (newText.contains("\n")) {
                                val lines = stringBuffer.toString().split("\\r?\\n".toRegex())
                                lines.forEachIndexed { i, it -> if (i != lines.size - 1) Log.println(it, color) }
                                stringBuffer.delete(0, stringBuffer.length - lines.last().length)
                            }
                        }
                        delay(100)
                    }
                    while (stream.ready()) {
                        val ct = stream.read(buffer)
                        buffer.flip()
                        if (ct == -1) return@withContext
                        Log.print(buffer.toString(), color)
                    }
                } catch (e: IOException) {
                    Log.printlnError("Failed to monitor stream!", e)
                }
            }
        }

    protected fun getSanitizedPath(f: File): String {
        return getSanitizedPath(f.absolutePath)
    }

    protected fun getSanitizedPath(f: String): String {
        return f.replace("\\", "/").replace(" ", "\\ ")
    }

    protected fun StringBuilder.addSpacedList(list: Collection<File>): StringBuilder {
        for (s in list) {
            append("\"").append(getSanitizedPath(s.absolutePath)).append("\" ")
        }
        return this
    }

    protected suspend fun runProcess(cmd: List<String>, scope: CoroutineScope, errorsRed: Boolean = true): Int? {
        val builder = ProcessBuilder(cmd)
        val process = try {
            withContext(Dispatchers.IO) {
                builder.start()
            }
        } catch (e: Exception) {
            Log.printlnError("Failed to start ${cmd.first()}", e)
            return null
        }

        val inputStreamJob = scope.launch {
            startStreamPrinter(process, process.inputStream, false)
        }
        val errorStreamJob = scope.launch {
            startStreamPrinter(process, process.errorStream, errorsRed)
        }

        try {
            while (process.isAlive)
                delay(100)

            inputStreamJob.join()
            errorStreamJob.join()
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        }
        return process.exitValue()
    }
}