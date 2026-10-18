/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.appfunctions.agent.ui.screens.agentdemo

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.appfunctions.agent.R
import com.example.appfunctions.agent.data.db.entities.MessageEntity
import com.example.appfunctions.agent.data.db.entities.MessageProcessingStatus
import com.example.appfunctions.agent.data.db.entities.MessageRole
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import java.util.Locale

/** Speech playback state and controls exposed to the Agent Demo UI. */
internal data class AgentSpeechControls(
    /** True while replies are queued, waiting for engine initialization, or being spoken. */
    val isSpeaking: Boolean,
    /** Cancels current and queued speech. Already announced replies are never replayed. */
    val stop: () -> Unit,
    val read: (MessageEntity) -> Unit = {},
)

/**
 * Speaks new assistant replies when [autoReadEnabled], and supports manual message playback.
 *
 * Messages present when history first loads, when the thread changes, or when the screen resumes
 * form a baseline that is never spoken. Playback stops on `ON_PAUSE`, on thread changes, and when
 * this composable leaves the composition (which also shuts down the engine).
 *
 * @param onError Receives a string resource describing a speech failure. User cancellation is not
 *   reported.
 */
@Composable
internal fun rememberAgentSpeech(
    uiState: AgentUiState.Loaded,
    onError: (Int) -> Unit,
    latestUiState: () -> AgentUiState = { uiState },
    autoReadEnabled: Boolean = true,
): AgentSpeechControls {
    if (LocalInspectionMode.current) {
        return remember { AgentSpeechControls(isSpeaking = false, stop = {}) }
    }

    val appContext = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val currentLocale by rememberUpdatedState(configuration.locales.get(0) ?: Locale.getDefault())
    val currentOnError by rememberUpdatedState(onError)
    val currentStateProvider by rememberUpdatedState(latestUiState)
    val currentConfirmationText by
        rememberUpdatedState(stringResource(R.string.agent_demo_action_confirmation_needed))

    var isSpeaking by remember { mutableStateOf(false) }
    val player =
        remember(appContext) {
            val mainHandler = Handler(Looper.getMainLooper())
            AgentSpeechPlayer(
                engineFactory = AndroidTextToSpeechEngineFactory(appContext),
                localeProvider = { currentLocale },
                mainExecutor = { mainHandler.post(it) },
                onError = { error -> currentOnError(error.messageRes) },
                onSpeakingChanged = { isSpeaking = it },
            )
        }
    val tracker = remember { AgentSpeechMessageTracker() }
    DisposableEffect(player) { onDispose { player.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, player, tracker) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        // Compose may still hold the last foreground snapshot at this point.
                        val latest = currentStateProvider() as? AgentUiState.Loaded
                        if (latest == null) {
                            tracker.invalidate()
                            player.stop()
                        } else {
                            tracker.resume(
                                threadId = latest.currentThread.threadId,
                                isHistoryLoaded = latest.isHistoryLoaded,
                                messages = latest.messages,
                            )
                        }
                        isResumed = true
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        isResumed = false
                        tracker.invalidate()
                        player.stop()
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.invalidate()
            player.stop()
        }
    }

    SideEffect {
        val latest = currentStateProvider() as? AgentUiState.Loaded
        if (latest == null) {
            tracker.invalidate()
            player.stop()
        } else {
            val update =
                tracker.update(
                    threadId = latest.currentThread.threadId,
                    isHistoryLoaded = latest.isHistoryLoaded,
                    isActive = isResumed,
                    messages = latest.messages,
                    autoReadEnabled = autoReadEnabled,
                )
            if (update.stopPlayback) player.stop()
            val texts = update.newReplies.mapNotNull { agentSpeechTextFor(it, currentConfirmationText) }
            if (texts.isNotEmpty()) player.enqueue(texts)
        }
    }

    return AgentSpeechControls(
        isSpeaking = isSpeaking,
        stop = player::stop,
        read = { message ->
            val latest = currentStateProvider() as? AgentUiState.Loaded
            val currentMessage = latest?.messages?.find { it.messageId == message.messageId }
            if (
                isResumed &&
                latest?.currentThread?.threadId == message.threadId &&
                currentMessage != null
            ) {
                agentSpeechTextFor(currentMessage, currentConfirmationText)?.let { text ->
                    player.read(text)
                }
            }
        },
    )
}

/** Speech failures surfaced to the user. */
internal enum class AgentSpeechError(
    @param:StringRes val messageRes: Int,
) {
    ENGINE_UNAVAILABLE(R.string.agent_demo_speech_unavailable),
    LANGUAGE_UNAVAILABLE(R.string.agent_demo_speech_language_unavailable),
    PLAYBACK_FAILED(R.string.agent_demo_speech_failed),
}

/** Result of feeding a UI snapshot into [AgentSpeechMessageTracker]. */
internal data class AgentSpeechUpdate(
    val stopPlayback: Boolean,
    val newReplies: List<MessageEntity>,
)

/**
 * Detects assistant replies that became ready while the screen was actively observing a thread.
 *
 * Not thread-safe; use from the main thread.
 */
internal class AgentSpeechMessageTracker {
    private var trackedThreadId: String? = null
    private var wasAutoReadEnabled = true

    /** Message IDs that must never be spoken (baseline, user, failed or already announced). */
    private var handledIds: MutableSet<String>? = null

    /** Drops the baseline so the next ready snapshot is treated as history. */
    fun invalidate() {
        handledIds = null
    }

    /** Seeds the foreground baseline immediately, before any later message snapshot is handled. */
    fun resume(
        threadId: String,
        isHistoryLoaded: Boolean,
        messages: List<MessageEntity>,
    ) {
        invalidate()
        update(threadId, isHistoryLoaded, isActive = true, messages = messages)
    }

    fun update(
        threadId: String,
        isHistoryLoaded: Boolean,
        isActive: Boolean,
        messages: List<MessageEntity>,
        autoReadEnabled: Boolean = true,
    ): AgentSpeechUpdate {
        val autoReadDisabled = wasAutoReadEnabled && !autoReadEnabled
        wasAutoReadEnabled = autoReadEnabled
        val threadChanged = trackedThreadId != null && trackedThreadId != threadId
        if (trackedThreadId != threadId) {
            trackedThreadId = threadId
            handledIds = null
        }
        val stopPlayback = threadChanged || !isActive || autoReadDisabled
        // Messages from another thread mean the list is stale for this thread; never seed from it.
        val isReady = isHistoryLoaded && isActive && messages.all { it.threadId == threadId }
        if (!isReady) {
            handledIds = null
            return AgentSpeechUpdate(stopPlayback, emptyList())
        }

        val handled = handledIds
        if (handled == null) {
            handledIds = messages.mapTo(HashSet()) { it.messageId }
            return AgentSpeechUpdate(stopPlayback, emptyList())
        }

        val newReplies = mutableListOf<MessageEntity>()
        for (message in messages.sortedBy { it.timestamp }) {
            if (message.messageId in handled) continue
            when {
                message.role != MessageRole.ASSISTANT -> handled += message.messageId
                message.processingStatus == MessageProcessingStatus.PROCESSED -> {
                    handled += message.messageId
                    if (autoReadEnabled) newReplies += message
                }
                message.processingStatus == MessageProcessingStatus.FAILED ->
                    handled += message.messageId
                // Not ready yet; stays eligible until it is processed or fails.
                else -> Unit
            }
        }
        return AgentSpeechUpdate(stopPlayback, newReplies)
    }
}

/**
 * Returns the plain text to speak for [message], or null if it has nothing speakable (for example
 * an image-only reply). Confirmation-only replies speak [confirmationText] like the UI shows.
 */
internal fun agentSpeechTextFor(
    message: MessageEntity,
    confirmationText: String,
): String? {
    val source =
        if (message.textContent.isBlank() && message.pendingIntentId != null) {
            confirmationText
        } else {
            message.textContent
        }
    return agentMarkdownToSpeechText(source).takeIf { it.isNotBlank() }
}

/**
 * Converts Markdown into text suitable for speech: formatting markers, images, HTML, link
 * destinations and raw URLs are dropped; link labels, code and table cells are kept.
 */
internal fun agentMarkdownToSpeechText(markdown: String): String {
    if (markdown.isBlank()) return ""
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    val rendered = SpeechMarkdownRenderer(markdown).render(tree)
    return normalizeSpeechText(rendered)
}

/**
 * Splits [text] into chunks no longer than [maxLength], preferring line, sentence and word
 * boundaries and never splitting a surrogate pair.
 */
internal fun chunkAgentSpeechText(
    text: String,
    maxLength: Int,
): List<String> {
    val limit = maxLength.coerceAtLeast(MIN_CHUNK_LENGTH)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        while (start < text.length && text[start].isWhitespace()) start++
        if (start >= text.length) break
        if (text.length - start <= limit) {
            chunks += text.substring(start).trimEnd()
            break
        }
        val end = findChunkEnd(text, start, start + limit)
        val chunk = text.substring(start, end).trim()
        if (chunk.isNotEmpty()) chunks += chunk
        start = end
    }
    return chunks
}

private const val MIN_CHUNK_LENGTH = 2
private const val SENTENCE_TERMINATORS = ".!?\u2026\u3002\uFF01\uFF1F"
private const val BLOCK_TERMINATORS = ".!?:;\u2026\u3002\uFF01\uFF1F"
private val RAW_URL_REGEX = Regex("""(?i)\b(?:https?://|ftp://|www\.)[^\s<>()\[\]]+""")
private val MARKDOWN_ESCAPE_REGEX = Regex("""\\([\\`*_{}\[\]()#+\-.!|~>])""")
private val LEFTOVER_MARKER_REGEX = Regex("""\*\*|__|~~""")
private val EMPTY_BRACKETS_REGEX = Regex("""\(\s*\)|\[\s*]""")
private val HORIZONTAL_SPACE_REGEX = Regex("""[ \t\u00A0]+""")
private val SPACE_BEFORE_PUNCTUATION_REGEX = Regex(""" +([.,!?;:])""")

/** Returns an exclusive end index in (start, hardEnd] for the next chunk. */
private fun findChunkEnd(
    text: String,
    start: Int,
    hardEnd: Int,
): Int {
    val preferredMin = start + (hardEnd - start) / 2
    for (i in minOf(hardEnd, text.length - 1) downTo preferredMin + 1) {
        if (text[i] == '\n') return i
    }
    for (i in hardEnd - 1 downTo preferredMin) {
        if (text[i] in SENTENCE_TERMINATORS && (i + 1 >= text.length || text[i + 1].isWhitespace())) {
            return i + 1
        }
    }
    for (i in minOf(hardEnd, text.length - 1) downTo start + 1) {
        if (text[i].isWhitespace()) return i
    }
    var end = hardEnd
    if (Character.isHighSurrogate(text[end - 1]) && end < text.length && Character.isLowSurrogate(text[end])) {
        end--
    }
    return end
}

private fun normalizeSpeechText(text: String): String {
    val cleaned =
        text
            .replace(RAW_URL_REGEX, "")
            .replace(MARKDOWN_ESCAPE_REGEX, "$1")
            .replace(LEFTOVER_MARKER_REGEX, "")
            .replace(EMPTY_BRACKETS_REGEX, "")
    return cleaned
        .lineSequence()
        .map { line ->
            line
                .replace(HORIZONTAL_SPACE_REGEX, " ")
                .replace(SPACE_BEFORE_PUNCTUATION_REGEX, "$1")
                .trim()
        }.filter { line -> line.any { !it.isWhitespace() && !(it.code < 128 && !it.isLetterOrDigit()) } }
        .joinToString("\n")
}

/** Walks a JetBrains Markdown AST producing speakable text. */
private class SpeechMarkdownRenderer(
    private val source: String,
) {
    private val out = StringBuilder()
    private var codeDepth = 0

    fun render(root: ASTNode): String {
        visit(root, parentType = null)
        return out.toString()
    }

    private fun visit(
        node: ASTNode,
        parentType: IElementType?,
    ) {
        if (node.children.isEmpty()) {
            visitLeaf(node)
            return
        }
        when (node.type) {
            MarkdownElementTypes.IMAGE,
            MarkdownElementTypes.LINK_DEFINITION,
            MarkdownElementTypes.HTML_BLOCK,
            MarkdownElementTypes.AUTOLINK,
            -> Unit
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            -> node.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.let(::visitLabel)
            MarkdownElementTypes.SHORT_REFERENCE_LINK ->
                node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.let(::visitLabel)
            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4,
            MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1,
            MarkdownElementTypes.SETEXT_2,
            -> block(terminate = true) { visitChildren(node) }
            MarkdownElementTypes.LIST_ITEM -> block(terminate = true) { visitListItem(node) }
            MarkdownElementTypes.PARAGRAPH ->
                block(terminate = parentType == MarkdownElementTypes.LIST_ITEM) { visitChildren(node) }
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            -> {
                codeDepth++
                block(terminate = false) { visitChildren(node) }
                codeDepth--
            }
            GFMElementTypes.HEADER,
            GFMElementTypes.ROW,
            -> block(terminate = true) { visitRow(node) }
            MarkdownElementTypes.BLOCK_QUOTE,
            MarkdownElementTypes.UNORDERED_LIST,
            MarkdownElementTypes.ORDERED_LIST,
            GFMElementTypes.TABLE,
            -> block(terminate = false) { visitChildren(node) }
            else -> visitChildren(node)
        }
    }

    private fun visitChildren(node: ASTNode) {
        node.children.forEach { visit(it, node.type) }
    }

    /** Keeps the item's first paragraph on the same line as its marker, e.g. "1. One.". */
    private fun visitListItem(item: ASTNode) {
        var leadingParagraph = true
        item.children.forEach { child ->
            if (leadingParagraph && child.type == MarkdownElementTypes.PARAGRAPH) {
                leadingParagraph = false
                val start = out.length
                visitChildren(child)
                terminateSentence(start)
                breakLine()
            } else {
                visit(child, item.type)
            }
        }
    }

    private fun visitLabel(label: ASTNode) {
        label.children
            .filter { it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET }
            .forEach { visit(it, label.type) }
    }

    private fun visitRow(row: ASTNode) {
        var first = true
        row.children
            .filter { it.type == GFMTokenTypes.CELL }
            .forEach { cell ->
                if (!first) out.append(", ")
                first = false
                if (cell.children.isEmpty()) {
                    out.append(cell.getTextInNode(source))
                } else {
                    visitChildren(cell)
                }
            }
    }

    private fun visitLeaf(node: ASTNode) {
        when (node.type) {
            MarkdownTokenTypes.EOL -> out.append(if (codeDepth > 0) '\n' else ' ')
            MarkdownTokenTypes.HARD_LINE_BREAK -> out.append('\n')
            MarkdownTokenTypes.WHITE_SPACE -> out.append(' ')
            MarkdownTokenTypes.LIST_NUMBER -> out.append(node.getTextInNode(source)).append(' ')
            in SKIPPED_TOKENS -> Unit
            else -> out.append(node.getTextInNode(source))
        }
    }

    private inline fun block(
        terminate: Boolean,
        content: () -> Unit,
    ) {
        breakLine()
        val start = out.length
        content()
        if (terminate) terminateSentence(start)
        breakLine()
    }

    private fun breakLine() {
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    private fun terminateSentence(blockStart: Int) {
        var last = out.length - 1
        while (last >= blockStart && out[last].isWhitespace()) last--
        if (last < blockStart || out[last] in BLOCK_TERMINATORS) return
        out.insert(last + 1, '.')
    }

    private companion object {
        val SKIPPED_TOKENS =
            setOf(
                MarkdownTokenTypes.EMPH,
                MarkdownTokenTypes.BACKTICK,
                MarkdownTokenTypes.ESCAPED_BACKTICKS,
                MarkdownTokenTypes.ATX_HEADER,
                MarkdownTokenTypes.SETEXT_1,
                MarkdownTokenTypes.SETEXT_2,
                MarkdownTokenTypes.LIST_BULLET,
                MarkdownTokenTypes.BLOCK_QUOTE,
                MarkdownTokenTypes.HORIZONTAL_RULE,
                MarkdownTokenTypes.CODE_FENCE_START,
                MarkdownTokenTypes.CODE_FENCE_END,
                MarkdownTokenTypes.FENCE_LANG,
                MarkdownTokenTypes.URL,
                MarkdownTokenTypes.AUTOLINK,
                MarkdownTokenTypes.EMAIL_AUTOLINK,
                MarkdownTokenTypes.HTML_TAG,
                MarkdownTokenTypes.HTML_BLOCK_CONTENT,
                MarkdownTokenTypes.LINK_ID,
                MarkdownTokenTypes.LINK_TITLE,
                GFMTokenTypes.TILDE,
                GFMTokenTypes.TABLE_SEPARATOR,
                GFMTokenTypes.GFM_AUTOLINK,
                GFMTokenTypes.CHECK_BOX,
                GFMTokenTypes.ALERT_TITLE,
            )
    }
}

/** Minimal text-to-speech engine abstraction. Implementations may call back on any thread. */
internal interface AgentSpeechEngine {
    val maxSpeechInputLength: Int

    /** Applies [locale]; returns false if the engine cannot speak it. */
    fun setLanguage(locale: Locale): Boolean

    /** Appends [text] to the engine queue; returns false if it was rejected. */
    fun speak(
        text: String,
        utteranceId: String,
    ): Boolean

    fun stop()

    fun shutdown()
}

internal interface AgentSpeechEngineCallbacks {
    fun onUtteranceDone(utteranceId: String)

    fun onUtteranceError(utteranceId: String)

    fun onUtteranceStopped(utteranceId: String)
}

internal fun interface AgentSpeechEngineFactory {
    /** Creates an engine; [onInit] reports initialization success, possibly asynchronously. */
    fun create(
        onInit: (Boolean) -> Unit,
        callbacks: AgentSpeechEngineCallbacks,
    ): AgentSpeechEngine
}

internal class AndroidTextToSpeechEngineFactory(
    private val context: Context,
) : AgentSpeechEngineFactory {
    override fun create(
        onInit: (Boolean) -> Unit,
        callbacks: AgentSpeechEngineCallbacks,
    ): AgentSpeechEngine {
        val textToSpeech = TextToSpeech(context) { status -> onInit(status == TextToSpeech.SUCCESS) }
        return AndroidTextToSpeechEngine(textToSpeech, callbacks)
    }
}

private class AndroidTextToSpeechEngine(
    private val textToSpeech: TextToSpeech,
    callbacks: AgentSpeechEngineCallbacks,
) : AgentSpeechEngine {
    init {
        textToSpeech.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let(callbacks::onUtteranceDone)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let(callbacks::onUtteranceError)
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) {
                    utteranceId?.let(callbacks::onUtteranceError)
                }

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) {
                    utteranceId?.let(callbacks::onUtteranceStopped)
                }
            },
        )
    }

    override val maxSpeechInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()

    override fun setLanguage(locale: Locale): Boolean {
        if (textToSpeech.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return false
        return textToSpeech.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    override fun speak(
        text: String,
        utteranceId: String,
    ): Boolean = textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS

    override fun stop() {
        textToSpeech.stop()
    }

    override fun shutdown() {
        textToSpeech.shutdown()
    }
}

/**
 * Ordered, cancellable speech queue over a lazily created [AgentSpeechEngine].
 *
 * All public methods must be called on the thread backing [mainExecutor]; engine callbacks are
 * re-dispatched there. Callbacks for utterances cancelled by [stop] or from a replaced engine are
 * ignored.
 */
internal class AgentSpeechPlayer(
    private val engineFactory: AgentSpeechEngineFactory,
    private val localeProvider: () -> Locale,
    private val mainExecutor: (Runnable) -> Unit,
    private val onError: (AgentSpeechError) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
) {
    private enum class EngineState { NONE, INITIALIZING, READY }

    private var engine: AgentSpeechEngine? = null
    private var engineState = EngineState.NONE
    private var engineToken: Any? = null
    private var earlyInitResult: Boolean? = null
    private var appliedLocale: Locale? = null
    private val pendingTexts = ArrayDeque<String>()
    private val activeUtterances = LinkedHashSet<String>()
    private var nextUtteranceId = 0L
    private var released = false

    var isSpeaking: Boolean = false
        private set

    /** Replaces current and queued speech with a manually selected message. */
    fun read(text: String) {
        stop()
        enqueue(listOf(text))
    }

    /** Queues each text (one reply each) after anything already queued. */
    fun enqueue(texts: List<String>) {
        if (released) return
        val speakable = texts.filter { it.isNotBlank() }
        if (speakable.isEmpty()) return
        pendingTexts.addAll(speakable)
        when (engineState) {
            EngineState.NONE -> startEngine()
            EngineState.INITIALIZING -> Unit
            EngineState.READY -> drainPending()
        }
        updateSpeaking()
    }

    /** Cancels pending and current speech. Initialization, if running, continues silently. */
    fun stop() {
        if (released) return
        pendingTexts.clear()
        val hadActive = activeUtterances.isNotEmpty()
        activeUtterances.clear()
        if (hadActive && engineState == EngineState.READY) engine?.stop()
        updateSpeaking()
    }

    /** Stops speech and shuts down the engine. The player is unusable afterwards. */
    fun release() {
        if (released) return
        stop()
        released = true
        val current = engine
        engine = null
        engineToken = null
        engineState = EngineState.NONE
        current?.stop()
        current?.shutdown()
    }

    private fun startEngine() {
        val token = Any()
        engineToken = token
        engineState = EngineState.INITIALIZING
        earlyInitResult = null
        val callbacks =
            object : AgentSpeechEngineCallbacks {
                override fun onUtteranceDone(utteranceId: String) {
                    mainExecutor { onUtteranceFinished(token, utteranceId, failed = false) }
                }

                override fun onUtteranceError(utteranceId: String) {
                    mainExecutor { onUtteranceFinished(token, utteranceId, failed = true) }
                }

                override fun onUtteranceStopped(utteranceId: String) {
                    mainExecutor { onUtteranceFinished(token, utteranceId, failed = false) }
                }
            }
        val created =
            try {
                engineFactory.create(
                    onInit = { success -> mainExecutor { onEngineInit(token, success) } },
                    callbacks = callbacks,
                )
            } catch (_: IllegalStateException) {
                null
            } catch (_: SecurityException) {
                null
            }
        if (engineToken !== token) {
            // Released (or failed) while the factory was running.
            created?.shutdown()
            return
        }
        if (created == null) {
            onEngineInit(token, success = false)
            return
        }
        engine = created
        earlyInitResult?.let { onEngineInit(token, it) }
    }

    private fun onEngineInit(
        token: Any,
        success: Boolean,
    ) {
        if (released || token !== engineToken || engineState != EngineState.INITIALIZING) return
        val current = engine
        if (current == null && success) {
            // Reported synchronously from inside the factory; applied once the engine is stored.
            earlyInitResult = true
            return
        }
        if (!success || current == null) {
            engine = null
            engineToken = null
            engineState = EngineState.NONE
            current?.shutdown()
            val hadPending = pendingTexts.isNotEmpty()
            pendingTexts.clear()
            updateSpeaking()
            if (hadPending) onError(AgentSpeechError.ENGINE_UNAVAILABLE)
            return
        }
        engineState = EngineState.READY
        appliedLocale = null
        drainPending()
        updateSpeaking()
    }

    private fun drainPending() {
        val current = engine ?: return
        if (pendingTexts.isEmpty()) return
        if (!applyLocale(current)) {
            pendingTexts.clear()
            updateSpeaking()
            onError(AgentSpeechError.LANGUAGE_UNAVAILABLE)
            return
        }
        val maxLength = current.maxSpeechInputLength
        while (pendingTexts.isNotEmpty()) {
            val text = pendingTexts.removeFirst()
            for (chunk in chunkAgentSpeechText(text, maxLength)) {
                val utteranceId = "agent-speech-${nextUtteranceId++}"
                activeUtterances += utteranceId
                if (!current.speak(chunk, utteranceId)) {
                    fail(AgentSpeechError.PLAYBACK_FAILED)
                    return
                }
            }
        }
    }

    private fun applyLocale(current: AgentSpeechEngine): Boolean {
        val locale = localeProvider()
        if (locale == appliedLocale) return true
        val supported = current.setLanguage(locale)
        appliedLocale = if (supported) locale else null
        return supported
    }

    private fun onUtteranceFinished(
        token: Any,
        utteranceId: String,
        failed: Boolean,
    ) {
        if (released || token !== engineToken) return
        if (!activeUtterances.remove(utteranceId)) return
        if (failed) {
            fail(AgentSpeechError.PLAYBACK_FAILED)
        } else {
            updateSpeaking()
        }
    }

    private fun fail(error: AgentSpeechError) {
        stop()
        onError(error)
    }

    private fun updateSpeaking() {
        val speaking = !released && (pendingTexts.isNotEmpty() || activeUtterances.isNotEmpty())
        if (speaking != isSpeaking) {
            isSpeaking = speaking
            onSpeakingChanged(speaking)
        }
    }
}
