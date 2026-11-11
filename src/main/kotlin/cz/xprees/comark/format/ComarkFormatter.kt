package cz.xprees.comark.format

import cz.xprees.comark.format.FormatterExpressions.getPropertyName
import cz.xprees.comark.format.FormatterExpressions.isArrayItem
import cz.xprees.comark.format.FormatterExpressions.isArrayProperty
import cz.xprees.comark.format.FormatterExpressions.isCodeBlockFence
import cz.xprees.comark.format.FormatterExpressions.isComponentEnd
import cz.xprees.comark.format.FormatterExpressions.isComponentStart
import cz.xprees.comark.format.FormatterExpressions.isEmptyProperty
import cz.xprees.comark.format.FormatterExpressions.isFlowArrayStart
import cz.xprees.comark.format.FormatterExpressions.isMultilineStringStart
import cz.xprees.comark.format.FormatterExpressions.isOrderedListItem
import cz.xprees.comark.format.FormatterExpressions.isPropertyLine
import cz.xprees.comark.format.FormatterExpressions.isTaskListItem
import cz.xprees.comark.format.FormatterExpressions.isUnorderedListItem
import cz.xprees.comark.format.FormatterExpressions.isYamlComment
import kotlin.math.max

/**
 * The Comark (MDC) formatter: a line-by-line port of the `formatter` of `@nuxtlabs/monarch-mdc` 0.10.0, the formatter the
 * VS Code Comark extension uses. Porting it (instead of writing an IntelliJ formatting model) means a document formatted in the
 * IDE and in VS Code comes out byte-identical, which matters for teams that mix editors. Equivalence with the JavaScript original
 * is checked differentially by `ComarkFormatterDifferentialTest`.
 *
 * What it does: it re-indents every line of an MDC document, leaving the text of each line alone.
 * - Block components are nested by `tabSize`: a component opener (`::name`) and its closer get the component's depth times
 *   `tabSize`, the component's content the indentation of its opener.
 * - YAML props blocks (`---`) are re-indented by property depth, including nested maps, block arrays, multiline strings
 *   (whose relative indentation is kept) and comments.
 * - Code fences keep their content's indentation relative to the opening fence.
 * - Markdown list items are re-indented to `tabSize` steps per nesting level.
 * - Trailing whitespace is removed (except inside YAML multiline strings), blank lines become empty and the result ends with
 *   exactly one line break. Line breaks are `\n`; a `\r` before them is treated as trailing whitespace.
 *
 * Every pass maps input line *n* to output line *n* (only a final empty line may be added), which callers rely on to apply
 * the result line by line. Passes are repeated until the output stops changing, at most [MAX_PASSES] times.
 *
 * The port keeps the original's JavaScript string semantics ([JsText]) and its quirks - for example a `---` thematic break
 * is taken for the start of a YAML block - including its one failure: where the original computes a negative indentation and
 * `" ".repeat()` throws a `RangeError`, this throws [ComarkFormatterException]. That happens for code fence content indented
 * less than its fence (beyond the component indentation) and for YAML array items indented less than the items above them.
 *
 * The IDE does not apply this directly but through [ComarkDocumentFormatter], which keeps what the original would change in a
 * document's content or structure (components inside list items, indented code blocks, tabs in code fences, hard line breaks)
 * and is byte-identical to it everywhere else.
 *
 * Ported from monarch-mdc, Copyright (c) 2021 NuxtLabs, MIT License (https://github.com/nuxtlabs/monarch-mdc).
 */
object ComarkFormatter {
    /** monarch-mdc's default `tabSize`. */
    const val DEFAULT_TAB_SIZE: Int = 2

    /** Safety limit of formatting passes, as in monarch-mdc. */
    const val MAX_PASSES: Int = 10

    /**
     * Formats [content].
     *
     * @param tabSize number of spaces per indentation level (the formatter always indents with spaces)
     * @param isFormatOnType format-on-type mode: whitespace-only lines keep (or get) indentation instead of becoming empty, so
     *   the line being typed on is not unindented
     * @param checkCanceled called once per line, so callers can make long runs cancellable
     *   (e.g. `ProgressManager::checkCanceled`); the formatter itself has no platform dependencies
     * @throws ComarkFormatterException where monarch-mdc throws
     */
    @JvmStatic
    @JvmOverloads
    fun format(
        content: String,
        tabSize: Int = DEFAULT_TAB_SIZE,
        isFormatOnType: Boolean = false,
        checkCanceled: () -> Unit = {},
    ): String {
        // Keep applying formatting passes until output stabilizes. As upstream, an empty input never enters the loop.
        var currentResult = content
        var previousResult = ""
        var passes = 0
        while (currentResult != previousResult && passes < MAX_PASSES) {
            previousResult = currentResult
            // Split input into lines
            currentResult = FormatPass(currentResult.split('\n'), tabSize, isFormatOnType, checkCanceled).run()
            passes++
        }
        return currentResult
    }

    /**
     * The formatter's view of line [line] of [lines] after one format-on-type pass over the lines before it: the
     * indentation it gives the line and the block structure the line is in. Used to indent new lines on Enter the way the
     * formatter would. Never throws: negative indentation (see [ComarkFormatterException]) counts as none. Only
     * `lines[0..line + 1]` are looked at.
     */
    internal fun lineContext(lines: List<String>, line: Int, tabSize: Int): LineContext {
        require(line in lines.indices) { "line $line out of ${lines.size}" }
        return FormatPass(lines, tabSize, isFormatOnType = true, checkCanceled = {}, strict = false).contextAt(line)
    }
}

/** See [ComarkFormatter.lineContext]. All fields describe the state before the line itself is processed, except [indent]. */
internal class LineContext(
    /** Indentation (spaces) the formatter gives the line. */
    val indent: Int,
    /** Number of block components open at the line. */
    val componentDepth: Int,
    /** Indentation the formatter gives plain content at the line: that of the innermost open component's opener. */
    val contentIndent: Int,
    val insideCodeBlock: Boolean,
    /** Inside what the formatter takes for a `---` YAML block: props, front matter - or text after a thematic break. */
    val insideYamlBlock: Boolean,
    val insideMultilineString: Boolean,
    /** Indentation the formatter gives the YAML block's top level properties, when [insideYamlBlock]. */
    val yamlIndent: Int?,
    /** The formatter considers the line part of a Markdown list. */
    val insideList: Boolean,
)

/**
 * The formatter failed the way the JavaScript original does: it computed a negative indentation ([indent]) for the 0-based
 * line [line] (`RangeError: Invalid count value` in JavaScript). The input is left unformatted.
 */
class ComarkFormatterException(val line: Int, val indent: Int) :
    RuntimeException("Cannot format line ${line + 1}: computed indentation $indent is negative")

/**
 * One `singleFormatPass` of monarch-mdc. The fields are the closure variables of the original, in its order, and [run] is its
 * loop body; comments are upstream's. Deliberately kept as close to the original as possible, so it can be compared with it
 * side by side - including state that never influences the output (`multilineMinimumIndent`, the [PropertyContext] details).
 */
private class FormatPass(
    private val lines: List<String>,
    private val tabSize: Int,
    private val isFormatOnType: Boolean,
    private val checkCanceled: () -> Unit,
    /** Throw [ComarkFormatterException] for negative indentation, as the original; otherwise treat it as none. */
    private val strict: Boolean = true,
) {
    /** Tracks YAML block state including base indentation and special handling for multiline string content. */
    private class YamlState {
        /** Base indentation level for YAML block. */
        var baseIndent: Int? = null

        /** Starting indent for multiline strings. */
        var multilineBaseIndent: Int? = null

        /** Minimum required indent for multiline content. */
        var multilineMinimumIndent: Int? = null
    }

    /** The state of a Markdown list in the document. */
    private class ListState(
        /** Parent component's indent level. */
        val componentLevel: Int,
        /** Original indent of first list item. */
        var baseIndent: Int,
        /** Track indent levels for hierarchy. */
        var nestingLevels: MutableList<Int>,
        /** Track which component owns this list. */
        val componentId: Int,
    )

    /** Tracks the indentation hierarchy of parent properties. */
    @Suppress("unused")
    private class PropertyContext(val name: String, val indent: Int, val isParent: Boolean, val parentDepth: Int)

    /** One level of array items in the hierarchy. */
    private class ArrayLevelInfo(val level: Int, val indent: Int, val itemIndent: Int)

    private val formattedLines = ArrayList<String>(lines.size + 1)

    // Tracks nested component indentation levels
    private val componentIndentStack = ArrayList<Int>()

    // Current indentation level
    private var currentIndent = 0

    // Whether we're inside YAML frontmatter
    private var insideYamlBlock = false

    // Whether we're inside a YAML multiline string
    private var insideMultilineString = false

    // Whether we're inside a markdown code block
    private var insideCodeBlock = false
    private var codeBlockBaseIndent: Int? = null
    private var codeBlockOriginalIndent: Int? = null

    private val yamlState = YamlState()

    private var listState: ListState? = null

    // Unique ID for each component level
    private var currentComponentId = 0

    // The YAML front matter's intended indentation, regardless of what's in the input
    private var yamlIntendedIndent: Int? = null

    // Stack of parent property contexts
    private val propertyStack = ArrayList<PropertyContext>()

    // Keep track of nesting depth for proper indentation
    private var currentPropertyDepth = 0

    // Keep track of the last property we processed
    private var lastPropertyLine = -1
    private var lastPropertyIndent = -1
    private var lastPropertyWasParent = false

    // Property indentation level for multiline strings
    private var multilinePropertyIndent = 0

    // Track whether we're inside an array
    private var insideArray = false

    // Track array indentation levels
    private var arrayBaseIndent = 0

    // Track array property indent level
    private var arrayPropertyIndent = 0

    // Track if we're processing properties inside an array item
    private var insideArrayItem = false

    // Track the indentation of the current array item start
    private var currentArrayItemIndent = 0

    // Track the nesting depth of arrays
    private var arrayNestingLevel = 0

    // Stack to keep track of parent array property indents
    private val arrayPropertyIndentStack = ArrayList<Int>()

    // Stack to track array item indentation at each nesting level
    private val arrayItemIndentStack = ArrayList<Int>()

    // Whether we're specifically inside an array property that has array values, and its indentation level
    private var insideArrayPropWithArrayValues = false
    private var arrayPropWithArrayValuesIndent = 0

    // Stack to track array levels - each entry represents a "level" of array items
    private val arrayLevels = ArrayList<ArrayLevelInfo>()

    /** Line being formatted, for [ComarkFormatterException]. */
    private var lineIndex = 0

    /** `getIndent(spaces)`; JavaScript's `" ".repeat()` throws for a negative count. */
    private fun getIndent(spaces: Int): String {
        if (spaces < 0) {
            if (strict) throw ComarkFormatterException(lineIndex, spaces)
            return ""
        }
        return " ".repeat(spaces)
    }

    private fun emit(line: String) {
        formattedLines += line
    }

    /** JavaScript's `null + n` is `n`: the original does arithmetic on nullable state. */
    private fun num(value: Int?): Int = value ?: 0

    fun run(): String {
        for (i in lines.indices) {
            checkCanceled()
            lineIndex = i
            formatLine(i)
        }
        // Files should end with a single newline character
        if (formattedLines.last() != "") formattedLines += ""
        return formattedLines.joinToString("\n")
    }

    /** Formats the lines before [line], then [line] itself, and reports the state in between (see [LineContext]). */
    fun contextAt(line: Int): LineContext {
        for (i in 0 until line) {
            lineIndex = i
            formatLine(i)
        }
        val componentDepth = componentIndentStack.size
        val contentIndent = componentIndentStack.lastOrNull() ?: 0
        val insideCode = insideCodeBlock
        val insideYaml = insideYamlBlock
        val insideMultiline = insideMultilineString
        val yamlIndent = yamlIntendedIndent
        val insideList = listState != null
        lineIndex = line
        formatLine(line)
        val indent = formattedLines.last().takeWhile { it == ' ' }.length
        return LineContext(indent, componentDepth, contentIndent, insideCode, insideYaml, insideMultiline, yamlIndent, insideList)
    }

    private fun formatLine(i: Int) {
        val line = lines[i]
        // Leading whitespace count
        val indent = JsText.leadingWhitespace(line)
        val trimmedContent = JsText.trim(line)
        // Current component's indent level
        val parentIndent = componentIndentStack.lastOrNull() ?: 0

        // Return empty lines without indentation if not formatting on-type (that would unindent the line being edited)
        if (trimmedContent == "" && !isFormatOnType) {
            emit("")
            return
        }

        // Handle code block markers (``` or ~~~)
        if (isCodeBlockFence(trimmedContent)) {
            insideCodeBlock = !insideCodeBlock
            if (insideCodeBlock) {
                codeBlockBaseIndent = parentIndent
                // Store first line's original indent as reference
                codeBlockOriginalIndent = indent
                emit(getIndent(parentIndent) + trimmedContent)
            } else {
                emit(getIndent(num(codeBlockBaseIndent)) + trimmedContent)
                codeBlockBaseIndent = null
                codeBlockOriginalIndent = null
            }
            return
        }

        // Handle code block content
        val codeBase = codeBlockBaseIndent
        if (insideCodeBlock && codeBase != null) {
            if (trimmedContent == "" && !isFormatOnType) {
                emit("")
                return
            }
            // Calculate relative indentation from original position and add it to the required base indentation
            val originalOffset = indent - (codeBlockOriginalIndent ?: 0)
            val finalIndent = codeBase + originalOffset
            emit(getIndent(finalIndent) + trimmedContent)
            return
        }

        // Handle YAML frontmatter markers
        if (trimmedContent == "---") {
            insideYamlBlock = !insideYamlBlock
            insideMultilineString = false
            yamlState.multilineBaseIndent = null
            yamlState.multilineMinimumIndent = null
            yamlState.baseIndent = if (insideYamlBlock) indent else null
            // Reset property tracking when entering/exiting YAML blocks
            if (!insideYamlBlock) propertyStack.clear()
            // But also track the intended indentation as the parent component's indent
            yamlIntendedIndent = if (insideYamlBlock) parentIndent else null
            // Reset property tracking
            lastPropertyLine = -1
            lastPropertyIndent = -1
            lastPropertyWasParent = false
            emit(getIndent(parentIndent) + "---")
            return
        }

        // Handle component opening tags (::component-name)
        if (isComponentStart(line)) {
            emit(getIndent(currentIndent) + trimmedContent)
            // Save current indent level for nested components
            componentIndentStack += currentIndent
            // Increase indent for component content
            currentIndent += tabSize
            currentComponentId++
            listState = null
            return
        }

        // Handle component closing tags (::)
        if (isComponentEnd(line)) {
            // Restore previous indent level
            currentIndent = componentIndentStack.removeLastOrNull() ?: 0
            emit(getIndent(currentIndent) + trimmedContent)
            listState = null
            return
        }

        // Process YAML block content
        if (insideYamlBlock) {
            if (trimmedContent.isNotEmpty()) {
                // Check if we're exiting a multiline string
                if (insideMultilineString && indent <= num(yamlState.multilineBaseIndent) && isPropertyLine(trimmedContent)) {
                    insideMultilineString = false
                    yamlState.multilineBaseIndent = null
                    yamlState.multilineMinimumIndent = null
                    // Continue processing this line as a normal property
                }

                // Handle array items first (higher priority than property lines)
                if (isArrayItem(trimmedContent)) {
                    formatArrayItem(indent, trimmedContent)
                    return
                } else if (insideArrayItem && isPropertyLine(trimmedContent) && !insideMultilineString) {
                    // A property on the same array item, or a new property outside the array?
                    if (indent <= arrayBaseIndent) {
                        // We've exited all arrays
                        insideArray = false
                        insideArrayItem = false
                        arrayNestingLevel = 0
                        arrayPropertyIndentStack.clear()
                        arrayItemIndentStack.clear()
                    } else {
                        // Properties within array items are indented one level deeper than the item marker
                        val propertyIndent = currentArrayItemIndent + tabSize
                        // A property ending with a colon followed by an array item starts a nested array
                        val isArrayProp = isArrayProperty(trimmedContent, lines, i)
                        val isArrayPropWithArrayValues = isArrayProp
                        if (isArrayProp) {
                            // This becomes a new array property parent
                            arrayPropertyIndentStack += arrayPropertyIndent
                            arrayPropertyIndent = propertyIndent
                            // Reset nesting level for this new array context
                            arrayNestingLevel = 0
                            if (isArrayPropWithArrayValues) {
                                insideArrayPropWithArrayValues = true
                                arrayPropWithArrayValuesIndent = propertyIndent
                            }
                        }
                        emit(getIndent(propertyIndent) + trimmedContent)
                        return
                    }
                } else if (!isArrayItem(trimmedContent) && (!isPropertyLine(trimmedContent) || !insideArrayItem)) {
                    // Not an array item or array item property: we must be outside of the array prop context
                    insideArrayPropWithArrayValues = false
                }

                // Handle YAML comment lines specially
                if (!insideMultilineString && isYamlComment(trimmedContent)) {
                    // First see if this is a child of a parent property
                    val isChildOfParent = lastPropertyIndent != -1 && lastPropertyWasParent && indent > lastPropertyIndent
                    var finalIndent: Int
                    if (isChildOfParent) {
                        finalIndent = num(yamlIntendedIndent) + currentPropertyDepth * tabSize
                        // If it's at a deeper level than the parent, add additional indentation
                        if (indent > lastPropertyIndent) finalIndent += tabSize
                    } else {
                        // For top-level comments, use the base YAML indent
                        finalIndent = num(yamlIntendedIndent)
                        // If the comment appears to be indented from base level, preserve that indentation
                        val baseIndent = yamlState.baseIndent ?: 0
                        if (indent > baseIndent) finalIndent += indent - baseIndent
                    }
                    emit(getIndent(finalIndent) + trimmedContent)
                    return
                }

                // Process property lines (including multiline string markers)
                if (!insideMultilineString && isPropertyLine(trimmedContent)) {
                    formatProperty(i, indent, trimmedContent)
                    return
                }

                // Handle content within multiline strings
                val multilineBase = yamlState.multilineBaseIndent
                if (insideMultilineString && multilineBase != null) {
                    // Base indentation for content is one level deeper than the multiline property
                    val baseContentIndent = multilinePropertyIndent + tabSize
                    // Calculate relative indentation to preserve internal structure
                    val relativeIndent = indent - multilineBase
                    val contentIndent = baseContentIndent + (if (relativeIndent > 0) relativeIndent - tabSize else 0)
                    emit(getIndent(contentIndent) + line.substring(indent))
                    return
                }

                // Handle non-property content
                if (!insideMultilineString) {
                    val contentIndent = num(yamlIntendedIndent) + (currentPropertyDepth + 1) * tabSize
                    emit(getIndent(contentIndent) + trimmedContent)
                    return
                }

                // For other content, use intended indentation with relative offsets
                val baseIndent = yamlState.baseIndent
                if (baseIndent != null) {
                    val relativeIndent = indent - baseIndent
                    emit(getIndent(num(yamlIntendedIndent) + max(0, relativeIndent)) + trimmedContent)
                    return
                }
            } else if (isFormatOnType) {
                // Indent empty lines at the same level as the previous line
                if (insideMultilineString) {
                    // If inside a multiline string, ensure minimum indentation
                    emit(getIndent(max(yamlState.multilineMinimumIndent ?: 0, indent)))
                } else {
                    // Otherwise, use the current line's indentation
                    emit(getIndent(indent))
                }
                return
            }
        }

        // Handle markdown lists
        if (isUnorderedListItem(trimmedContent) || isOrderedListItem(trimmedContent) || isTaskListItem(trimmedContent)) {
            val current = listState
            if (current == null || current.componentId != currentComponentId) {
                // Reset list state if we're in a different component context
                listState = ListState(parentIndent, indent, mutableListOf(indent), currentComponentId)
            } else if (indent <= current.baseIndent) {
                // Reset nesting levels for new root-level item in same component
                current.baseIndent = indent
                current.nestingLevels = mutableListOf(indent)
            } else if (indent !in current.nestingLevels) {
                current.nestingLevels += indent
                current.nestingLevels.sort()
            }
            val state = listState!!
            val nestLevel = state.nestingLevels.indexOf(indent)
            val finalIndent = state.componentLevel + nestLevel * tabSize
            emit(getIndent(finalIndent) + trimmedContent)
            return
        } else if (trimmedContent.isEmpty() && listState != null) {
            emit("")
            return
        } else {
            listState = null
        }

        emit(getIndent(parentIndent) + trimmedContent)
    }

    /** The array item branch of the YAML block handling. */
    private fun formatArrayItem(indent: Int, trimmedContent: String) {
        // Check if this array item is a direct child of an array-prop parent
        val isDirectChildOfArrayProp = insideArrayPropWithArrayValues && indent > arrayPropWithArrayValuesIndent

        // Check if this is a sibling array item (same level as previous items)
        val siblingLevel = arrayLevels.firstOrNull { it.indent == indent }

        if (!insideArray) {
            // The first array item: set up array state
            insideArray = true
            arrayBaseIndent = indent
            // Array item indent is one level deeper than the property
            arrayPropertyIndent = num(yamlIntendedIndent) + currentPropertyDepth * tabSize
            arrayNestingLevel = 0
            arrayPropertyIndentStack.clear()
            arrayItemIndentStack.clear()
            // Initialize array level tracking with the root level
            arrayLevels.clear()
            arrayLevels += ArrayLevelInfo(level = 0, indent = indent, itemIndent = arrayPropertyIndent + tabSize)
        } else if (siblingLevel != null) {
            // Siblings use the stored indentation of their level, so they stay at the same level rather than nesting
            arrayPropertyIndent = siblingLevel.itemIndent - tabSize
            arrayNestingLevel = siblingLevel.level
        } else if (arrayItemIndentStack.isNotEmpty()) {
            val lastArrayItemIndent = arrayItemIndentStack.last()
            if (indent > lastArrayItemIndent) {
                // A new nesting level: the parent array item becomes the new property parent
                arrayNestingLevel++
                arrayPropertyIndentStack += arrayPropertyIndent
                arrayPropertyIndent = currentArrayItemIndent
                arrayLevels += ArrayLevelInfo(level = arrayNestingLevel, indent = indent, itemIndent = currentArrayItemIndent + tabSize)
            } else if (indent < lastArrayItemIndent) {
                // Going back up in nesting: first check for a tracked level that matches this indent exactly
                var foundMatchingLevel = false
                for (level in arrayLevels) {
                    if (level.indent == indent) {
                        arrayNestingLevel = level.level
                        arrayPropertyIndent = level.itemIndent - tabSize
                        foundMatchingLevel = true
                        break
                    }
                }
                // If we didn't find a matching level, revert through the stack
                if (!foundMatchingLevel) {
                    while (arrayItemIndentStack.isNotEmpty() && indent < arrayItemIndentStack.last()) {
                        arrayItemIndentStack.removeAt(arrayItemIndentStack.lastIndex)
                        arrayNestingLevel--
                    }
                    // Restore the parent property indent from the stack
                    if (arrayPropertyIndentStack.isNotEmpty()) {
                        arrayPropertyIndent = arrayPropertyIndentStack.removeAt(arrayPropertyIndentStack.lastIndex)
                    }
                }
            }
        }

        // Calculate intended indentation based on property depth and array nesting
        val finalIndent = if (isDirectChildOfArrayProp) {
            // Array items that are children of an array property: the array prop's indent level plus one tab size
            arrayPropWithArrayValuesIndent + tabSize
        } else {
            arrayPropertyIndent + tabSize + arrayNestingLevel * tabSize
        }

        // Mark that we're inside an array item and track its indentation
        insideArrayItem = true
        currentArrayItemIndent = finalIndent

        // Store this array item's indent for future comparison
        if (indent !in arrayItemIndentStack) arrayItemIndentStack += indent

        // Reset property state within array items
        lastPropertyLine = -1
        lastPropertyIndent = -1
        lastPropertyWasParent = false

        emit(getIndent(finalIndent) + trimmedContent)
    }

    /** The property line branch of the YAML block handling. */
    private fun formatProperty(i: Int, indent: Int, trimmedContent: String) {
        val propertyName = getPropertyName(trimmedContent)

        // Determine if this is a parent property or multiline string start
        val isParentProp = isEmptyProperty(trimmedContent)
        val isMultilineStart = isMultilineStringStart(trimmedContent)

        // Check different forms of array properties (inline arrays like `prop: []` need no special handling)
        val isFlowArrayStart = isFlowArrayStart(trimmedContent)
        val isBlockArrayStart = isArrayProperty(trimmedContent, lines, i)

        // Check if it's a child of a parent property
        val sameIndentAsPrevious = indent == lastPropertyIndent
        val isChildOfParent = sameIndentAsPrevious && lastPropertyWasParent && lastPropertyLine >= 0

        // Treat children of parent props as if they were actually indented one level deeper
        val effectiveIndent = if (isChildOfParent) indent + 1 else indent

        // Manage property stack, remove properties at same or deeper levels
        while (propertyStack.isNotEmpty()) {
            val lastProp = propertyStack.last()
            if (lastProp.indent == effectiveIndent) {
                propertyStack.removeAt(propertyStack.lastIndex)
                break
            } else if (lastProp.indent > effectiveIndent) {
                propertyStack.removeAt(propertyStack.lastIndex)
            } else {
                break
            }
        }

        // Calculate property depth based on remaining stack, and the indentation level
        currentPropertyDepth = propertyStack.size
        val finalIndent = num(yamlIntendedIndent) + currentPropertyDepth * tabSize

        // Block arrays and parent properties need special indent handling for their children
        if (isParentProp || isBlockArrayStart) {
            propertyStack += PropertyContext(propertyName, effectiveIndent, isParent = true, parentDepth = currentPropertyDepth)
        }

        // Store property context for the next line
        lastPropertyLine = i
        lastPropertyIndent = indent
        lastPropertyWasParent = isParentProp || isBlockArrayStart

        // Prepare for block array items (dash notation) or a flow-style array continuing on the next lines
        if (isBlockArrayStart || isFlowArrayStart) arrayPropertyIndent = finalIndent

        // Now handle multiline string start if needed
        if (isMultilineStart) {
            insideMultilineString = true
            yamlState.multilineBaseIndent = indent
            multilinePropertyIndent = finalIndent
        }

        emit(getIndent(finalIndent) + trimmedContent)
    }
}
