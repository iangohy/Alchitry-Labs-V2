@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.alchitry.labs2.ui.code_editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope
import androidx.compose.foundation.text.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.alchitry.labs2.Log
import com.alchitry.labs2.parsers.grammar.LucidLexer
import com.alchitry.labs2.parsers.notations.LineAction
import com.alchitry.labs2.parsers.notations.Notation
import com.alchitry.labs2.parsers.notations.NotationCollector
import com.alchitry.labs2.parsers.notations.NotationType
import com.alchitry.labs2.project.Languages
import com.alchitry.labs2.project.Project
import com.alchitry.labs2.project.files.ProjectFile
import com.alchitry.labs2.ui.code_editor.autocomplete.Autocomplete
import com.alchitry.labs2.ui.code_editor.autocomplete.LucidAutocomplete
import com.alchitry.labs2.ui.code_editor.styles.BasicIndenter
import com.alchitry.labs2.ui.code_editor.styles.BracketIndenter
import com.alchitry.labs2.ui.code_editor.styles.CodeStyler
import com.alchitry.labs2.ui.code_editor.styles.LineIndenter
import com.alchitry.labs2.ui.code_editor.tooltip.NotationTooltipProvider
import com.alchitry.labs2.ui.gestures.detectEditorActions
import com.alchitry.labs2.ui.theme.AlchitryColors
import com.alchitry.labs2.ui.theme.AlchitryTypography
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.log10
import kotlin.properties.Delegates

@Composable
fun rememberCodeEditorState(file: ProjectFile): CodeEditorState {
    return remember(file) {
        CodeEditorState(file)
    }
}

@Composable
private fun rememberCodeEditorStyle(): CodeEditorStyle {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val textColor = LocalTextStyle.current.color.takeOrElse {
        LocalContentColor.current
    }
    val selectionColor = AlchitryColors.current.SelectionColor
    return remember(density, textColor, fontFamilyResolver, selectionColor) {
        CodeEditorStyle(
            AlchitryTypography.editor.copy(color = textColor),
            density,
            fontFamilyResolver,
            textColor.copy(alpha = 0.7f),
            selectionColor
        )
    }
}

private val newLineRegex = Regex("(\\r\\n?)|\\n")

data class CodeEditorStyle(
    val style: TextStyle,
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
    val cursorColor: Color,
    val selectionColor: Color
)

class CodeEditorState(
    val file: ProjectFile
) {
    val readOnly = file.isReadOnly

    var style: CodeEditorStyle? = null
    var uiScope: CoroutineScope? = null
    val scope = CoroutineScope(Dispatchers.Main)
    var saveJob: Job? = null

    var lineTopOffset: Float = 0f

    val autocomplete: Autocomplete?

    val tooltipState = NotationTooltipProvider(this)
    var notations: List<Notation>? = null
    var lineActions: Map<Int, List<LineAction>>? = null

    var maxLineActions = 0

    val focusRequester = FocusRequester()
    val lines = ArrayList<CodeLineState>()
    private var gutterDigits = 0
    val scrollState = ScrollState(0)
    var gutterWidth = 0
        private set
    var softWrap by Delegates.observable(false) { _, _, _ -> invalidate() }
    private var invalidator: (() -> Unit)? = null
    var size: IntSize = IntSize.Zero
        private set
    private var scrollTarget: Int? = null

    var tokens: List<EditorToken> = emptyList()
        private set

    private var lineOffsetCache = mutableListOf<Int>()

    val selectionManager = SelectionManager(
        this,
        ::invalidate
    )
    private val undoManager = UndoManager(this, selectionManager)

    private val styler = CodeStyler(this)

    var clipboardManager: ClipboardManager? = null

    val lineIndenter: LineIndenter

    init {
        Project.current?.currentNotationCollectorForFile(file)?.let { collector ->
            updateNotations(collector)
        }
        scope.launch {
            setText(file.readText())
            styler.updateStyle()
        }

        autocomplete = when (file.language) {
            Languages.Lucid -> LucidAutocomplete(this)
            else -> null
        }

        lineIndenter = when (file.language) {
            Languages.ACF, Languages.Lucid, Languages.Verilog -> BracketIndenter(this)
            else -> BasicIndenter(this)
        }
    }

    /**
     * Updates the notations and line actions based on the given [notationCollector].
     *
     * @param notationCollector The notation collector from which to retrieve the notations and line actions.
     */
    private fun updateNotations(notationCollector: NotationCollector) {
        notations = notationCollector.getAllNotations()
        lineActions = notationCollector.getLineActions()
        maxLineActions = lineActions?.values?.maxOfOrNull { actions -> actions.size } ?: 0
    }

    fun getWindow() = Rect(
        0f,
        scrollState.value.toFloat(),
        size.width.toFloat(),
        (scrollState.value + size.height).toFloat()
    )

    fun onCaretChanged() {
        updateHighlightTokens()
        //autocomplete?.reset()
    }

    fun lineNotationLevel(line: Int): NotationType? {
        return notations
            ?.filter { (it.range.start.line..it.range.endInclusive.line).contains(line) }
            ?.minByOrNull { it.type.ordinal }?.type
    }

    private fun updateHighlightTokens() {
        lines.forEach { it.highlights.clear() }
        val token = textPositionToToken(selectionManager.caret) ?: return

        val tokens = when (token.token.type) {
            LucidLexer.Tokens.TYPE_ID.id, LucidLexer.Tokens.CONST_ID.id, LucidLexer.Tokens.SPACE_ID.id, LucidLexer.Tokens.FUNCTION_ID.id -> {
                tokens.mapNotNull { t ->
                    if (t.token.type == token.token.type && t.token.text == token.token.text) {
                        HighlightAnnotation(t.range, AlchitryColors.current.TokenHighlight)
                    } else {
                        null
                    }
                }
            }

            else -> emptyList()
        }

        tokens.forEach {
            if (it.range.start.line != it.range.endInclusive.line) {
                TODO("Multiline token highlighting not supported!")
            }
            lines[it.range.start.line].highlights.add(it)
        }
    }

    fun textPositionToScreenOffset(position: TextPosition): Offset? {
        val line = lines.getOrNull(position.line) ?: return null
        if (line.layoutResult == null)
            line.layout(Constraints())
        val layout = line.layoutResult ?: return null
        val rect = layout.getCursorRect(position.offset)
        return Offset(rect.left, offsetAtLineBottom(position.line).toFloat() - scrollState.value)
    }

    private fun screenToTextOffset(offset: Offset) = Offset(offset.x, offset.y + scrollState.value)

    fun screenOffsetToTextPosition(offset: Offset): TextPosition {
        val textOffset = screenToTextOffset(offset)
        if (lines.isEmpty())
            return TextPosition(0, 0)
        var lineBottom = 0
        val lineNumber = lines.indexOfFirst {
            lineBottom += it.lineHeight
            lineBottom > textOffset.y
        }.let { if (it < 0) lines.size - 1 else it }

        val line = lines[lineNumber]
        val charNum =
            line.layoutResult?.getOffsetForPosition(Offset(textOffset.x, textOffset.y - offsetAtLineTop(lineNumber)))
                ?: return TextPosition(0, 0)
        return TextPosition(lineNumber, charNum)
    }

    private fun textPositionToToken(textPosition: TextPosition): EditorToken? {
        return tokens.firstOrNull { it.range.contains(textPosition) }
    }

    /**
     * Converts a screen space offset to the nearest token.
     */
    fun offsetToToken(offset: Offset, excludeRight: Boolean = false): EditorToken? {
        if (lines.isEmpty())
            return null
        val textPosition = screenOffsetToTextPosition(offset)
        if (excludeRight) {
            val line = lines[textPosition.line]
            if (line.text.isEmpty())
                return null
            if (line.text.length == textPosition.offset) {
                if ((line.layoutResult?.getBoundingBox((textPosition.offset - 1).coerceAtLeast(0))?.right
                        ?: 0f) < offset.x
                ) {
                    return null
                }
            }
        }
        return textPositionToToken(textPosition)
    }

    @OptIn(InternalFoundationTextApi::class)
    @Composable
    internal fun subscribe(scope: RecomposeScope) {
        val style = rememberCodeEditorStyle()
        this.style = style
        this.uiScope = rememberCoroutineScope()

        lineTopOffset = remember(style) {
            TextDelegate(
                text = AnnotatedString("123"),
                style = style.style,
                density = style.density,
                fontFamilyResolver = style.fontFamilyResolver
            ).layout(Constraints(), LayoutDirection.Ltr, null).size.height * -0.07f
        }

        LaunchedEffect(style) {
            refreshStyling()
        }

        LaunchedEffect(scrollState.value) {
            tooltipState.hide()
            autocomplete?.reset()
        }

        val project by Project.currentFlow.collectAsState()
        LaunchedEffect(project) {
            project?.notationCollectorFlowForFile(file)?.collect {
                it?.let { updateNotations(it) }

                styler.updateStyle()
                invalidate()
            }
        }

        DisposableEffect(scope) {
            invalidator = {
                scope.invalidate()
            }
            onDispose {
                invalidator = null
            }
        }
    }

    fun getText(): String {
        return lines.joinToString("\n") { it.text.text }
    }

    fun onTextChange() {
        if (!file.isReadOnly)
            scope.launch {
                saveJob?.cancelAndJoin()
                saveJob = launch {
                    delay(100)
                    file.writeText(getText())
                    Project.current?.queueNotationsUpdate()
                }
            }
        lineOffsetCache.clear()
        lineOffsetCache.add(0)
        tokens = file.editorTokenizer.getTokens(lines.toCharStream())
        styler.updateStyle()
        updateHighlightTokens()
        invalidate()
    }

    internal fun invalidate() {
        invalidator?.invoke()
    }

    fun getSnapshot() =
        EditorSnapshot(
            lines.map { it.text }.toImmutableList(),
            selectionManager.caret,
            selectionManager.start,
            selectionManager.end
        )

    private fun TextPosition.charAt(): Char? {
        return lines.getOrNull(line)?.text?.getOrNull(offset)
    }

    internal fun newLineState(text: AnnotatedString) =
        CodeLineState(text, style?.density, mutableListOf(), style?.fontFamilyResolver, style?.style)

    /**
     * Replaces the text covered by range with newText.
     *
     * @param newText the new text to insert
     * @param range the range of text to replace
     *
     * @return the position of the end of the new text
     */
    fun replaceText(
        newText: String,
        range: OpenEndRange<TextPosition> = selectionManager.selectedRange,
        updateCaret: Boolean = true
    ) {
        if (readOnly)
            return

        val start = range.start.coerceInRange()
        val end = range.endExclusive.coerceInRange()

        val startLine = lines[start.line]
        val endLine = lines[end.line]

        val prefix = startLine.text.substring(0, start.offset)
        val suffix = endLine.text.substring(end.offset, endLine.text.length)

        // remove extra lines
        if (end.line != start.line)
            lines.subList(start.line + 1, end.line + 1).clear()

        val newTextLines = newText.split(newLineRegex)

        val newLines = newTextLines.mapIndexed { index, s ->
            val newLine = when (index) {
                0 -> if (newTextLines.size == 1) prefix + s + suffix else prefix + s
                newTextLines.size - 1 -> s + suffix
                else -> s
            }

            newLineState(AnnotatedString(newLine))
        }

        lines[start.line] = newLines.first()

        if (newLines.size > 1)
            lines.addAll(start.line + 1, newLines.subList(1, newLines.size))

        onTextChange()

        undoManager.queueSnapshot()

        if (updateCaret) {
            val prefixOffset = if (newTextLines.size == 1) prefix.length else 0
            val endPosition = TextPosition(start.line + newLines.size - 1, newTextLines.last().length + prefixOffset)

            selectionManager.caret = endPosition
            selectionManager.clearSelection()
        }
    }

    fun setText(text: String) {
        val lines = text.split(newLineRegex)
        this.lines.clear()
        lines.forEach { line ->
            this.lines.add(
                newLineState(AnnotatedString(line))
            )
        }
        undoManager.reset()
        onTextChange()
    }

    private fun refreshStyling() {
        val lines = this.lines.toImmutableList()
        this.lines.clear()
        lines.forEach {
            this.lines.add(
                newLineState(it.text)
            )
        }
        onTextChange()
    }

    fun lineHeight(line: Int): Int {
        return lines.getOrNull(line)?.lineHeight ?: 0
    }

    fun offsetAtLineTop(line: Int): Int {
        if (lineOffsetCache.isEmpty())
            lineOffsetCache.add(0)
        if (!lineOffsetCache.indices.contains(line)) {
            for (i in lineOffsetCache.size..line) {
                lineOffsetCache.add(i, lineOffsetCache[i - 1] + lineHeight(i))
            }
        }
        return lineOffsetCache[line]
    }

    fun offsetAtLineBottom(line: Int): Int =
        offsetAtLineTop(line + 1)

    fun showLine(line: Int) {
        scrollTarget = line
    }

    private fun startScrollToLine(line: Int) {
        val top = offsetAtLineTop(line)
        val bottom = top + lineHeight(line)

        val viewTop = scrollState.value
        val viewBottom = viewTop + size.height

        val destination = when {
            top < viewTop -> top
            bottom > viewBottom -> bottom - size.height
            else -> return
        }

        uiScope?.launch {
            scrollState.animateScrollTo(destination)
        }
    }

    fun isLineVisible(line: Int): Boolean {
        if (!lines.indices.contains(line))
            return false
        val top = offsetAtLineTop(line)
        val bottom = top + lines[line].lineHeight
        return bottom > scrollState.value || top < scrollState.value + size.height
    }

    fun DrawScope.draw() {
        with(selectionManager) {
            drawSelection()
        }
        drawIntoCanvas { canvas ->
            canvas.save()
            var currentY = -scrollState.value
            canvas.translate(dx = 0f, dy = currentY.toFloat())
            var wasVisible = false
            for (line in lines) {
                val layout = line.layoutResult ?: continue
                val margin = lineTopOffset
                val nextY = currentY + line.lineHeight
                val visible = nextY > 0 && currentY < size.height
                if (visible) {
                    wasVisible = true
                    line.drawHighlights()
                    canvas.translate(dx = 0f, dy = margin)
                    TextPainter.paint(canvas, layout)
                    canvas.translate(dx = 0f, dy = line.lineHeight.toFloat() - margin)
                } else {
                    canvas.translate(dx = 0f, dy = line.lineHeight.toFloat())
                    if (wasVisible)
                        break
                }
                currentY = nextY
            }
            canvas.restore()
        }

        with(selectionManager) {
            drawCaret()
        }
    }

    private fun Int.length() = when (this) {
        0 -> 1
        else -> log10(abs(toDouble())).toInt() + 1
    }

    private fun defaultLineHeight(): Int =
        newLineState(AnnotatedString("0")).apply { layout(Constraints()) }.lineHeight


    fun LazyLayoutMeasureScope.layout(constraints: Constraints) {
        size = IntSize(constraints.maxWidth, constraints.maxHeight)

        val lineHeight = lines.firstOrNull()?.lineHeight.let {
            if (it == null || it == 0) defaultLineHeight() else it
        }

        val maxDigits = lines.size.length()
        val placeables = measure(-maxDigits - 1, Constraints(maxHeight = lineHeight))
        gutterWidth = placeables.maxOf { it.width }
        gutterDigits = maxDigits

        val lineConstraints =
            if (softWrap)
                Constraints.fixedWidth((constraints.maxWidth - gutterWidth).coerceAtLeast(0))
            else
                Constraints()

        lines.forEach {
            it.layout(lineConstraints)
        }

        val totalHeight = lines.sumOf { it.lineHeight }

        @Suppress("INVISIBLE_SETTER")
        scrollState.maxValue = (totalHeight - constraints.maxHeight).coerceAtLeast(0)

        scrollTarget?.let { startScrollToLine(it) }
        scrollTarget = null

    }

    fun tapModifier() = Modifier
        .pointerInput(selectionManager) {
            detectEditorActions(
                onClick = { offset ->
                    selectionManager.onClick(offset)
                },
                onDoubleClick = { offset ->
                    val token = offsetToToken(offset) ?: return@detectEditorActions
                    selectionManager.selectRange(token.range)
                },
                onTripleClick = { offset ->
                    val position = screenOffsetToTextPosition(offset)
                    selectionManager.selectLine(position.line)
                },
                onDrag = { change ->
                    selectionManager.onDrag(change.position)
                }
            )
        }


    fun keyModifier() = Modifier
        .onKeyEvent {
            if (selectionManager.active) {
                return@onKeyEvent processKey(it)
            }
            return@onKeyEvent false
        }
        // must be before other focus modifiers
        .onFocusChanged {
            if (!it.hasFocus) {
                selectionManager.onFocusLost()
            }
        }
        .focusRequester(focusRequester)
        .focusable()

    /**
     * Returns a TextPosition that is the closest valid position for the
     * current text.
     */
    private fun TextPosition.coerceInRange() = coerceInRange(lines = lines)

    fun TextPosition.getPrevious(): TextPosition {
        val currentOffset = offset.coerceIn(0, lines[line].text.length)
        if (currentOffset > 0) return copy(offset = currentOffset - 1)
        if (line == 0) return this
        return TextPosition(line - 1, lines[line - 1].text.length)
    }

    fun TextPosition.getNext(): TextPosition {
        val thisLine = lines[line]
        if (offset < thisLine.text.length) return copy(offset = offset + 1)
        if (line == lines.size - 1) return this
        return TextPosition(line + 1, 0)
    }

    fun copy() {
        if (!selectionManager.hasSelection) {
            selectionManager.selectCurrentLine()
        }
        clipboardManager?.setText(selectionManager.getSelectedText())
    }

    fun paste() {
        clipboardManager?.getText()?.let {
            replaceText(it.text)
        }
    }

    fun cut() {
        copy()
        deleteIfSelectedOr { }
    }

    fun adjustIndents() {
        if (lines.isNotEmpty())
            replaceText( // use replaceText so this ends up on the undo/redo stack
                lineIndenter.indentAll(),
                TextPosition(0, 0)..<TextPosition(lines.size - 1, lines.last().text.length),
                false
            )
    }

    private fun processKey(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown &&
            event.isCtrlPressed &&
            event.isAltPressed &&
            !event.isShiftPressed &&
            !event.isMetaPressed
        ) {
            when (event.key) {
                Key.L -> {
                    adjustIndents()
                    return true
                }
            }
        }

        if (event.isTypedEvent) {
            val text = StringBuilder().appendCodePoint(event.utf16CodePoint).toString()

            val modifiedText = when (text) {
                "{" -> "{}"
                "(" -> "()"
                "[" -> "[]"
                else -> text
            }

            val lineNum = selectionManager.caret.line
            val line = lines.getOrNull(lineNum)?.text?.text
            replaceText(modifiedText)
            if (line?.isBlank() == true) {
                val current = lines[lineNum].text.text
                replaceText(
                    lineIndenter.getIndentFor(lineNum) + current.trim(),
                    TextPosition(lineNum, 0)..<TextPosition(lineNum, current.length)
                )
            }
            for (i in 1..(modifiedText.length - text.length)) {
                selectionManager.moveLeft()
            }

            autocomplete?.updateSuggestions()

            return true
        }

        if (event.type != KeyEventType.KeyDown) {
            return false
        }

        if (event.key == Key.Escape) {
            autocomplete?.reset()
        }

        // compose ignores the number-pad enter key so check for it here
        val command: KeyCommand = platformDefaultKeyMapping.map(event)
            ?: if (event.key.nativeKeyCode == java.awt.event.KeyEvent.VK_ENTER) KeyCommand.NEW_LINE else return false

        var consumed = true
        var resetAutocomplete = true
        //commandExecutionContext {
        when (command) {
            KeyCommand.COPY -> copy()
            KeyCommand.PASTE -> paste()
            KeyCommand.CUT -> cut()
            KeyCommand.LEFT_CHAR -> selectionManager.setCaretToSelectionOr(true) { moveLeft() }
            KeyCommand.RIGHT_CHAR -> selectionManager.setCaretToSelectionOr(false) { moveRight() }
//            KeyCommand.LEFT_WORD -> moveCursorLeftByWord()
//            KeyCommand.RIGHT_WORD -> moveCursorRightByWord()
//            KeyCommand.PREV_PARAGRAPH -> moveCursorPrevByParagraph()
//            KeyCommand.NEXT_PARAGRAPH -> moveCursorNextByParagraph()
            KeyCommand.UP -> {
                if (autocomplete?.selectionUp() != true)
                    selectionManager.setCaretToSelectionOr(true) { moveUp() }
                resetAutocomplete = false
            }

            KeyCommand.DOWN -> {
                if (autocomplete?.selectionDown() != true)
                    selectionManager.setCaretToSelectionOr(false) { moveDown() }
                resetAutocomplete = false
            }

//            KeyCommand.PAGE_UP -> moveCursorUpByPage()
//            KeyCommand.PAGE_DOWN -> moveCursorDownByPage()
//            KeyCommand.LINE_START -> moveCursorToLineStart()
//            KeyCommand.LINE_END -> moveCursorToLineEnd()
//            KeyCommand.LINE_LEFT -> moveCursorToLineLeftSide()
//            KeyCommand.LINE_RIGHT -> moveCursorToLineRightSide()
            KeyCommand.LINE_START -> {
                selectionManager.clearSelection()
                selectionManager.caret =
                    selectionManager.caret.copy(offset = 0) // TODO maybe make this jump to end of indent
            }

            KeyCommand.LINE_END -> {
                selectionManager.clearSelection()
                selectionManager.caret =
                    selectionManager.caret.copy(
                        offset = lines.getOrNull(selectionManager.caret.line)?.text?.length ?: 0
                    )
            }

            KeyCommand.DELETE_PREV_CHAR -> deleteIfSelectedOr {
                replaceText("", selectionManager.caret.getPrevious()..<selectionManager.caret)
                if (autocomplete?.active == true)
                    autocomplete.updateSuggestions()
                resetAutocomplete = false
            }

            KeyCommand.DELETE_NEXT_CHAR -> deleteIfSelectedOr {
                replaceText("", selectionManager.caret..<selectionManager.caret.getNext())
                if (autocomplete?.active == true)
                    autocomplete.updateSuggestions()
                resetAutocomplete = false
            }

//            KeyCommand.DELETE_PREV_WORD ->
//                deleteIfSelectedOr {
//                    getPreviousWordOffset()?.let {
//                        DeleteSurroundingTextCommand(selection.end - it, 0)
//                    }
//                }?.apply()
//
//            KeyCommand.DELETE_NEXT_WORD ->
//                deleteIfSelectedOr {
//                    getNextWordOffset()?.let {
//                        DeleteSurroundingTextCommand(0, it - selection.end)
//                    }
//                }?.apply()
//
            KeyCommand.DELETE_FROM_LINE_START -> deleteIfSelectedOr {
                val startOfLine = selectionManager.caret.copy(offset = 0)
                replaceText("", startOfLine..<selectionManager.caret)
            }

            KeyCommand.DELETE_TO_LINE_END -> deleteIfSelectedOr {
                val endOfLine = selectionManager.caret.copy(
                    offset = lines.getOrNull(selectionManager.caret.line)?.text?.length ?: 0
                )
                replaceText("", selectionManager.caret..<endOfLine)
            }

            KeyCommand.NEW_LINE -> {
                resetAutocomplete = false

                autocomplete?.select()?.let {
                    replaceText(it.nextText, it.range)
                    return true
                }

                val nextChar = selectionManager.caret.charAt()
                val prevChar = selectionManager.caret.getPrevious().charAt()
                if (nextChar == '}' && prevChar == '{') {
                    replaceText("\n\n")
                    replaceText(lineIndenter.getIndentFor(selectionManager.caret.line))
                    selectionManager.moveUp()
                    replaceText(lineIndenter.getIndentFor(selectionManager.caret.line))
                } else {
                    replaceText("\n")
                    replaceText(lineIndenter.getIndentFor(selectionManager.caret.line))
                }
            }

            KeyCommand.TAB -> {
                if (lines.getOrNull(selectionManager.caret.line)?.text?.isBlank() == true) {
                    replaceText(lineIndenter.getIndentFor(selectionManager.caret.line))
                } else {
                    replaceText(LineIndenter.INDENT_STRING)
                }
            }

            KeyCommand.SELECT_ALL -> selectionManager.selectAll()
            KeyCommand.SELECT_LEFT_CHAR -> selectionManager.moveLeft().selectMovement()
            KeyCommand.SELECT_RIGHT_CHAR -> selectionManager.moveRight().selectMovement()
//            KeyCommand.SELECT_LEFT_WORD -> moveCursorLeftByWord().selectMovement()
//            KeyCommand.SELECT_RIGHT_WORD -> moveCursorRightByWord().selectMovement()
//            KeyCommand.SELECT_PREV_PARAGRAPH -> moveCursorPrevByParagraph().selectMovement()
//            KeyCommand.SELECT_NEXT_PARAGRAPH -> moveCursorNextByParagraph().selectMovement()
//            KeyCommand.SELECT_LINE_START -> moveCursorToLineStart().selectMovement()
//            KeyCommand.SELECT_LINE_END -> moveCursorToLineEnd().selectMovement()
//            KeyCommand.SELECT_LINE_LEFT -> moveCursorToLineLeftSide().selectMovement()
//            KeyCommand.SELECT_LINE_RIGHT -> moveCursorToLineRightSide().selectMovement()
//            KeyCommand.SELECT_UP -> moveCursorUpByLine().selectMovement()
//            KeyCommand.SELECT_DOWN -> moveCursorDownByLine().selectMovement()
//            KeyCommand.SELECT_PAGE_UP -> moveCursorUpByPage().selectMovement()
//            KeyCommand.SELECT_PAGE_DOWN -> moveCursorDownByPage().selectMovement()
//            KeyCommand.SELECT_HOME -> moveCursorToHome().selectMovement()
//            KeyCommand.SELECT_END -> moveCursorToEnd().selectMovement()
//            KeyCommand.DESELECT -> deselect()
            KeyCommand.UNDO -> undoManager.undo()
            KeyCommand.REDO -> undoManager.redo()
//
//            KeyCommand.CHARACTER_PALETTE -> {
//                showCharacterPalette()
//            }
            //}
            else -> {
                consumed = false
                Log.warn("Command $command not implemented yet!")
            }
        }
        if (resetAutocomplete)
            autocomplete?.reset()
        //undoManager?.forceNextSnapshot()
        return consumed
    }

    private fun deleteIfSelectedOr(block: () -> Unit) {
        if (selectionManager.hasSelection) {
            //undoManager.takeSnapshot()
            replaceText("")
        } else {
            block()
        }
    }

}