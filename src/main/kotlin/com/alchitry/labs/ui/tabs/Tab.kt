package com.alchitry.labs.ui.tabs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.alchitry.labs.project.files.FileProvider
import com.alchitry.labs.ui.code_editor.CodeEditor
import com.alchitry.labs.ui.code_editor.CodeEditorState
import com.alchitry.labs.ui.code_editor.styles.lucid.LucidTokenizer

interface Tab {
    @Composable
    fun label()

    @Composable
    fun content()

    fun onClose(): Boolean

    var parent: TabPanel
}

class FileTab(
    val file: FileProvider,
    override var parent: TabPanel
) : Tab {
    private val codeEditorState = CodeEditorState(LucidTokenizer())

    init {
        codeEditorState.setText(file.readText())
    }

    @Composable
    override fun label() {
        Text(file.name)
    }

    @Composable
    override fun content() {
        key(this) {
            CodeEditor(codeEditorState)
        }
    }

    override fun onClose(): Boolean {
        if (file is FileProvider.DiskFile)
            file.writeText(codeEditorState.getText())
        return true
    }

}