package com.alchitry.labs2.project.files

import com.alchitry.labs2.project.Language
import com.alchitry.labs2.ui.code_editor.styles.EditorTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CharStreams

sealed class ProjectFile(val file: FileProvider) {
    protected val lock = Mutex(false)

    val name: String get() = file.name
    val isLibFile: Boolean get() = file is FileProvider.ResourceFile
    val isReadOnly: Boolean get() = isLibFile
    abstract val language: Language

    val editorTokenizer: EditorTokenizer get() = language.tokenizer

    suspend fun readText(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            file.readText()
        }
    }

    suspend fun writeText(text: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            file.writeText(text)
        }
    }

    suspend fun toCharStream(): CharStream = CharStreams.fromString(readText(), name)
}