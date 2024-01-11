package com.alchitry.labs.ui.tabs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.alchitry.labs.project.files.ProjectFile
import com.alchitry.labs.ui.code_editor.CodeEditor
import com.alchitry.labs.ui.code_editor.CodeEditorState
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FileTab(
    val file: ProjectFile,
    override var parent: TabPanel
) : Tab {
    private val codeEditorState = CodeEditorState(file)

    @Composable
    override fun label() {
        Text(file.name)
    }

    @Composable
    override fun content() {
        CodeEditor(codeEditorState)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onClose(): Boolean {
        if (!file.isReadOnly)
            GlobalScope.launch { file.writeText(codeEditorState.getText()) }
        return true
    }

}