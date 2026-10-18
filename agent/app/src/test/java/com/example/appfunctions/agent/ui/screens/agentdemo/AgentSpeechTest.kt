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
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.appfunctions.agent.data.db.entities.MessageAttachment
import com.example.appfunctions.agent.data.db.entities.MessageEntity
import com.example.appfunctions.agent.data.db.entities.MessageProcessingStatus
import com.example.appfunctions.agent.data.db.entities.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

private const val THREAD_A = "thread-a"
private const val THREAD_B = "thread-b"

private fun message(
    id: String,
    threadId: String = THREAD_A,
    role: MessageRole = MessageRole.ASSISTANT,
    status: MessageProcessingStatus = MessageProcessingStatus.PROCESSED,
    text: String = "Reply $id",
    timestamp: Long = id.filter { it.isDigit() }.toLongOrNull() ?: 0L,
    pendingIntentId: String? = null,
    attachments: List<MessageAttachment> = emptyList(),
) = MessageEntity(
    messageId = id,
    threadId = threadId,
    role = role,
    textContent = text,
    timestamp = timestamp,
    processingStatus = status,
    pendingIntentId = pendingIntentId,
    attachments = attachments,
)

private fun AgentSpeechMessageTracker.ready(
    messages: List<MessageEntity>,
    threadId: String = THREAD_A,
) = update(threadId = threadId, isHistoryLoaded = true, isActive = true, messages = messages)

private fun List<MessageEntity>.ids() = map { it.messageId }

class AgentSpeechTest {
    @Test
    fun tracker_disablingAutoReadStopsOnceAndDoesNotReplaySkippedReplies() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(emptyList())
        assertEquals(listOf("a1"), tracker.ready(listOf(message("a1"))).newReplies.ids())

        val disabled =
            tracker.update(
                THREAD_A,
                isHistoryLoaded = true,
                isActive = true,
                messages = listOf(message("a1"), message("a2")),
                autoReadEnabled = false,
            )
        assertTrue(disabled.stopPlayback)
        assertTrue(disabled.newReplies.isEmpty())

        val stillDisabled =
            tracker.update(
                THREAD_A,
                isHistoryLoaded = true,
                isActive = true,
                messages = listOf(message("a1"), message("a2"), message("a3")),
                autoReadEnabled = false,
            )
        // Manual playback must not be stopped by ordinary recomposition while auto-read is off.
        assertFalse(stillDisabled.stopPlayback)
        assertTrue(stillDisabled.newReplies.isEmpty())
        assertTrue(tracker.ready(listOf(message("a1"), message("a2"), message("a3"))).newReplies.isEmpty())
        assertEquals(
            listOf("a4"),
            tracker.ready(listOf(message("a1"), message("a2"), message("a3"), message("a4"))).newReplies.ids(),
        )
    }

    @Test
    fun tracker_initiallyDisabledDoesNotReadHistoryOrAccumulateReplies() {
        val tracker = AgentSpeechMessageTracker()
        tracker.update(
            THREAD_A,
            isHistoryLoaded = true,
            isActive = true,
            messages = listOf(message("a1")),
            autoReadEnabled = false,
        )
        tracker.update(
            THREAD_A,
            isHistoryLoaded = true,
            isActive = true,
            messages = listOf(message("a1"), message("a2")),
            autoReadEnabled = false,
        )

        assertTrue(tracker.ready(listOf(message("a1"), message("a2"))).newReplies.isEmpty())
    }

    @Test
    fun player_manualReadReplacesQueueAndCanReplaySameMessage() {
        val harness = PlayerHarness().ready()
        harness.player.enqueue(listOf("Automatic reply", "Queued reply"))

        harness.player.read("Selected message")

        assertEquals(1, harness.engine.stopCount)
        assertTrue(harness.player.isSpeaking)
        harness.engine.finish(0)
        harness.engine.finish(1)
        assertTrue(harness.player.isSpeaking)
        harness.engine.finish(2)
        assertFalse(harness.player.isSpeaking)

        harness.player.read("Selected message")
        assertEquals(
            listOf("Automatic reply", "Queued reply", "Selected message", "Selected message"),
            harness.engine.spokenTexts(),
        )
        assertTrue(harness.player.isSpeaking)
    }

    @Test
    fun player_manualReadDuringInitializationReplacesPendingQueue() {
        val harness = PlayerHarness()
        harness.player.enqueue(listOf("Automatic reply"))

        harness.player.read("Selected message")
        harness.factory.completeInit(true)

        assertEquals(listOf("Selected message"), harness.engine.spokenTexts())
    }

    // region Message tracking

    @Test
    fun tracker_initialHistoryIsNotSpoken() {
        val tracker = AgentSpeechMessageTracker()
        val history = listOf(message("u1", role = MessageRole.USER), message("a2"))

        assertTrue(tracker.ready(history).newReplies.isEmpty())
        assertTrue(tracker.ready(history).newReplies.isEmpty())
    }

    @Test
    fun tracker_notReadySnapshotsDoNotSeedBaseline() {
        val tracker = AgentSpeechMessageTracker()

        // Initial empty state before the DAO emitted must not become the baseline.
        val beforeLoad = tracker.update(THREAD_A, isHistoryLoaded = false, isActive = true, messages = emptyList())
        assertTrue(beforeLoad.newReplies.isEmpty())
        val partial =
            tracker.update(THREAD_A, isHistoryLoaded = false, isActive = true, messages = listOf(message("a1")))
        assertTrue(partial.newReplies.isEmpty())

        assertTrue(tracker.ready(listOf(message("a1"), message("a2"))).newReplies.isEmpty())
        assertEquals(listOf("a3"), tracker.ready(listOf(message("a1"), message("a2"), message("a3"))).newReplies.ids())
    }

    @Test
    fun tracker_newProcessedReplyIsReportedOnce() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(emptyList())
        val messages = listOf(message("u1", role = MessageRole.USER), message("a2"))

        val first = tracker.ready(messages)
        assertEquals(listOf("a2"), first.newReplies.ids())
        assertFalse(first.stopPlayback)
        assertTrue(tracker.ready(messages).newReplies.isEmpty())
    }

    @Test
    fun tracker_pendingReplyIsReportedWhenProcessed() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(emptyList())

        val pending = message("a1", status = MessageProcessingStatus.PENDING_AGENT_RESPONSE)
        assertTrue(tracker.ready(listOf(pending)).newReplies.isEmpty())
        assertEquals(listOf("a1"), tracker.ready(listOf(message("a1"))).newReplies.ids())
    }

    @Test
    fun tracker_excludesUserAndFailedMessages() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(emptyList())

        val update =
            tracker.ready(
                listOf(
                    message("u1", role = MessageRole.USER),
                    message("a2", status = MessageProcessingStatus.FAILED),
                ),
            )
        assertTrue(update.newReplies.isEmpty())
        // A failed reply never becomes speakable later.
        assertTrue(tracker.ready(listOf(message("u1", role = MessageRole.USER), message("a2"))).newReplies.isEmpty())
    }

    @Test
    fun tracker_multipleNewRepliesAreOrderedAndDeduplicated() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(listOf(message("a1")))

        val update = tracker.ready(listOf(message("a1"), message("a3"), message("a2")))
        assertEquals(listOf("a2", "a3"), update.newReplies.ids())
        assertEquals(
            listOf("a4"),
            tracker.ready(listOf(message("a1"), message("a2"), message("a3"), message("a4"))).newReplies.ids(),
        )
    }

    @Test
    fun tracker_threadChangeStopsAndReseedsWithoutSpeakingOtherThreads() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(listOf(message("a1")))

        // Thread switched; list still holds stale thread A messages and a new one.
        val switching =
            tracker.update(
                THREAD_B,
                isHistoryLoaded = false,
                isActive = true,
                messages = listOf(message("a1"), message("a2")),
            )
        assertTrue(switching.stopPlayback)
        assertTrue(switching.newReplies.isEmpty())

        val historyB = listOf(message("b3", threadId = THREAD_B), message("b4", threadId = THREAD_B))
        assertTrue(tracker.ready(historyB, THREAD_B).newReplies.isEmpty())
        assertEquals(
            listOf("b5"),
            tracker.ready(historyB + message("b5", threadId = THREAD_B), THREAD_B).newReplies.ids(),
        )
    }

    @Test
    fun tracker_staleMessagesFromOtherThreadNeverSeedOrPlay() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(emptyList(), THREAD_A)

        // Even if readiness is (incorrectly) true, mixed thread data is ignored.
        val mixed = tracker.ready(listOf(message("a1"), message("b2", threadId = THREAD_B)), THREAD_B)
        assertTrue(mixed.stopPlayback)
        assertTrue(mixed.newReplies.isEmpty())

        val historyB = listOf(message("b2", threadId = THREAD_B), message("b3", threadId = THREAD_B))
        assertTrue(tracker.ready(historyB, THREAD_B).newReplies.isEmpty())
    }

    @Test
    fun tracker_messagesArrivingWhileBackgroundedAreNotSpoken() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(listOf(message("a1")))

        val background =
            tracker.update(
                THREAD_A,
                isHistoryLoaded = true,
                isActive = false,
                messages = listOf(message("a1"), message("a2")),
            )
        assertTrue(background.stopPlayback)
        assertTrue(background.newReplies.isEmpty())

        assertTrue(tracker.ready(listOf(message("a1"), message("a2"))).newReplies.isEmpty())
        assertEquals(
            listOf("a3"),
            tracker.ready(listOf(message("a1"), message("a2"), message("a3"))).newReplies.ids(),
        )
    }

    @Test
    fun tracker_invalidateReseedsOnNextReadySnapshot() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(emptyList())
        tracker.invalidate()

        assertTrue(tracker.ready(listOf(message("a1"))).newReplies.isEmpty())
    }

    @Test
    fun tracker_resumeSeedsLatestSnapshotWithoutSuppressingNextForegroundReply() {
        val tracker = AgentSpeechMessageTracker()
        tracker.ready(listOf(message("a1")))
        tracker.invalidate()

        val latest = listOf(message("a1"), message("a2"))
        tracker.resume(THREAD_A, isHistoryLoaded = true, messages = latest)

        assertTrue(tracker.ready(latest).newReplies.isEmpty())
        assertEquals(listOf("a3"), tracker.ready(latest + message("a3")).newReplies.ids())
        assertTrue(tracker.ready(latest + message("a3")).newReplies.isEmpty())
    }

    @Test
    fun tracker_resumeBeforeHistoryIsReadyWaitsForActualSnapshot() {
        val tracker = AgentSpeechMessageTracker()
        tracker.resume(THREAD_A, isHistoryLoaded = false, messages = emptyList())

        assertTrue(tracker.ready(listOf(message("a1"))).newReplies.isEmpty())
        assertEquals(
            listOf("a2"),
            tracker.ready(listOf(message("a1"), message("a2"))).newReplies.ids(),
        )
    }

    // endregion

    // region Speech text

    @Test
    fun speechText_skipsImageOnlyReplies() {
        val imageOnly =
            message("a1", text = "", attachments = listOf(MessageAttachment("content://image/1", "image/png")))
        assertNull(agentSpeechTextFor(imageOnly, "Confirm?"))
        assertNull(agentSpeechTextFor(message("a2", text = "![chart](https://example.com/c.png)"), "Confirm?"))
    }

    @Test
    fun speechText_usesConfirmationForActionOnlyReplies() {
        val action = message("a1", text = "", pendingIntentId = "intent")
        assertEquals("Should I proceed?", agentSpeechTextFor(action, "Should I proceed?"))
        assertEquals("Done", agentSpeechTextFor(message("a2", text = "Done", pendingIntentId = "i"), "Confirm?"))
    }

    @Test
    fun markdown_removesInlineFormatting() {
        assertEquals(
            "Bold and italic and code and old",
            agentMarkdownToSpeechText("**Bold** and _italic_ and `code` and ~~old~~"),
        )
    }

    @Test
    fun markdown_headingsAndListsBecomeSentences() {
        assertEquals(
            "Title.\nFirst item.\nSecond item!",
            agentMarkdownToSpeechText("# Title\n\n- First item\n- Second item!"),
        )
        assertEquals("1. One.\n2. Two.", agentMarkdownToSpeechText("1. One\n2. Two"))
        assertEquals("Parent.\nChild.", agentMarkdownToSpeechText("- Parent\n  - Child"))
        assertEquals("Quoted text", agentMarkdownToSpeechText("> Quoted text"))
    }

    @Test
    fun markdown_keepsLinkLabelsAndDropsUrlsAndImages() {
        assertEquals(
            "See the docs or now.",
            agentMarkdownToSpeechText("See [the docs](https://example.com/docs) or https://example.com/raw now."),
        )
        assertEquals("Here it is", agentMarkdownToSpeechText("Here ![cat](https://example.com/cat.png) it is"))
        assertEquals("Visit", agentMarkdownToSpeechText("Visit <https://example.com>"))
        assertEquals("", agentMarkdownToSpeechText("www.example.com"))
    }

    @Test
    fun markdown_keepsCodeContentAndTableCells() {
        assertEquals("val x = 1", agentMarkdownToSpeechText("```kotlin\nval x = 1\n```"))
        assertEquals(
            "Name, Age.\nAda, 36.",
            agentMarkdownToSpeechText("| Name | Age |\n|------|-----|\n| Ada | 36 |"),
        )
    }

    @Test
    fun markdown_dropsHtmlAndJoinsSoftLineBreaks() {
        assertEquals("Hello world", agentMarkdownToSpeechText("<b>Hello</b>\nworld"))
        assertEquals("", agentMarkdownToSpeechText("   "))
    }

    // endregion

    // region Chunking

    @Test
    fun chunk_shortTextIsUnchanged() {
        assertEquals(listOf("Hello there."), chunkAgentSpeechText("Hello there.", 4000))
        assertTrue(chunkAgentSpeechText("   ", 10).isEmpty())
    }

    @Test
    fun chunk_prefersSentenceBoundaries() {
        val chunks = chunkAgentSpeechText("One two three. Four five six. Seven.", 20)
        assertEquals(listOf("One two three.", "Four five six.", "Seven."), chunks)
    }

    @Test
    fun chunk_respectsLimitAndKeepsAllWords() {
        val text = (1..200).joinToString(" ") { "word$it" }
        val chunks = chunkAgentSpeechText(text, 37)
        assertTrue(chunks.all { it.length <= 37 })
        assertEquals(text.split(" "), chunks.flatMap { it.split(" ") })
    }

    @Test
    fun chunk_hardSplitNeverBreaksSurrogatePairs() {
        val text = "\uD83D\uDE00".repeat(10)
        val chunks = chunkAgentSpeechText(text, 5)
        assertTrue(chunks.all { it.length <= 5 })
        assertTrue(chunks.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
        assertEquals(text, chunks.joinToString(""))
    }

    // endregion

    // region Player

    @Test
    fun player_initializesLazilyAndReportsPendingInitAsSpeaking() {
        val harness = PlayerHarness()
        assertEquals(0, harness.factory.created.size)

        harness.player.enqueue(listOf("Hello"))
        assertEquals(1, harness.factory.created.size)
        assertTrue(harness.player.isSpeaking)
        assertTrue(harness.engine.spoken.isEmpty())

        harness.factory.completeInit(true)
        assertEquals(listOf("Hello"), harness.engine.spokenTexts())
        assertEquals(Locale.US, harness.engine.language)
        assertTrue(harness.player.isSpeaking)

        harness.engine.finishAll()
        assertFalse(harness.player.isSpeaking)
        assertEquals(listOf(true, false), harness.speakingChanges)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun player_speaksMultipleRepliesInOrder() {
        val harness = PlayerHarness().ready()
        harness.player.enqueue(listOf("First", "Second"))
        harness.player.enqueue(listOf("Third"))

        assertEquals(listOf("First", "Second", "Third"), harness.engine.spokenTexts())
        harness.engine.finish(0)
        harness.engine.finish(1)
        assertTrue(harness.player.isSpeaking)
        harness.engine.finish(2)
        assertFalse(harness.player.isSpeaking)
    }

    @Test
    fun player_stopIgnoresStaleCallbacksAndAllowsLaterReplies() {
        val harness = PlayerHarness().ready()
        harness.player.enqueue(listOf("First", "Second"))

        harness.player.stop()
        assertFalse(harness.player.isSpeaking)
        assertEquals(1, harness.engine.stopCount)

        harness.engine.stopAll()
        harness.engine.error(0)
        assertTrue(harness.errors.isEmpty())
        assertFalse(harness.player.isSpeaking)

        harness.player.enqueue(listOf("Later"))
        assertEquals(listOf("First", "Second", "Later"), harness.engine.spokenTexts())
        assertTrue(harness.player.isSpeaking)
    }

    @Test
    fun player_stopDuringInitDropsQueuedReplies() {
        val harness = PlayerHarness()
        harness.player.enqueue(listOf("Hello"))

        harness.player.stop()
        assertFalse(harness.player.isSpeaking)
        harness.factory.completeInit(true)

        assertTrue(harness.engine.spoken.isEmpty())
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun player_initFailureReportsUnavailableAndRetriesLater() {
        val harness = PlayerHarness()
        harness.player.enqueue(listOf("Hello"))
        harness.factory.completeInit(false)

        assertEquals(listOf(AgentSpeechError.ENGINE_UNAVAILABLE), harness.errors)
        assertFalse(harness.player.isSpeaking)
        assertTrue(harness.factory.created.single().isShutdown)

        harness.player.enqueue(listOf("Again"))
        assertEquals(2, harness.factory.created.size)
    }

    @Test
    fun player_factoryExceptionReportsUnavailable() {
        val harness = PlayerHarness(factoryThrows = true)
        harness.player.enqueue(listOf("Hello"))

        assertEquals(listOf(AgentSpeechError.ENGINE_UNAVAILABLE), harness.errors)
        assertFalse(harness.player.isSpeaking)
    }

    @Test
    fun player_unsupportedLanguageFailsExplicitly() {
        val harness = PlayerHarness(locale = Locale.JAPAN)
        harness.player.enqueue(listOf("Hello"))
        harness.factory.completeInit(true)

        assertEquals(listOf(AgentSpeechError.LANGUAGE_UNAVAILABLE), harness.errors)
        assertTrue(harness.engine.spoken.isEmpty())
        assertFalse(harness.player.isSpeaking)
    }

    @Test
    fun player_utteranceErrorStopsAndReports() {
        val harness = PlayerHarness().ready()
        harness.player.enqueue(listOf("First", "Second"))

        harness.engine.error(0)
        assertEquals(listOf(AgentSpeechError.PLAYBACK_FAILED), harness.errors)
        assertFalse(harness.player.isSpeaking)
        assertEquals(1, harness.engine.stopCount)
    }

    @Test
    fun player_rejectedSpeakReportsFailure() {
        val harness = PlayerHarness().ready()
        harness.engine.speakResult = false
        harness.player.enqueue(listOf("Hello"))

        assertEquals(listOf(AgentSpeechError.PLAYBACK_FAILED), harness.errors)
        assertFalse(harness.player.isSpeaking)
    }

    @Test
    fun player_splitsLongRepliesByEngineLimit() {
        val harness = PlayerHarness(maxLength = 12).ready()
        harness.player.enqueue(listOf("Alpha beta. Gamma delta."))

        assertEquals(listOf("Alpha beta.", "Gamma delta."), harness.engine.spokenTexts())
        assertTrue(harness.engine.spoken.all { it.first.length <= 12 })
    }

    @Test
    fun player_releaseShutsDownAndIgnoresFurtherWork() {
        val harness = PlayerHarness().ready()
        harness.player.enqueue(listOf("Hello"))

        harness.player.release()
        assertTrue(harness.engine.isShutdown)
        assertFalse(harness.player.isSpeaking)

        harness.engine.finishAll()
        harness.player.enqueue(listOf("Ignored"))
        assertEquals(listOf("Hello"), harness.engine.spokenTexts())
        assertEquals(1, harness.factory.created.size)
    }

    @Test
    fun player_releaseDuringInitShutsDownEngine() {
        val harness = PlayerHarness()
        harness.player.enqueue(listOf("Hello"))
        harness.player.release()
        harness.factory.completeInit(true)

        assertTrue(harness.engine.isShutdown)
        assertTrue(harness.engine.spoken.isEmpty())
    }

    @Test
    fun trackerAndPlayer_stoppedReplyIsNotReplayed() {
        val tracker = AgentSpeechMessageTracker()
        val harness = PlayerHarness().ready()
        tracker.ready(emptyList())

        val messages = listOf(message("a1"))
        harness.player.enqueue(tracker.ready(messages).newReplies.mapNotNull { agentSpeechTextFor(it, "") })
        harness.player.stop()
        harness.player.enqueue(tracker.ready(messages).newReplies.mapNotNull { agentSpeechTextFor(it, "") })

        assertEquals(listOf("Reply a1"), harness.engine.spokenTexts())
        assertFalse(harness.player.isSpeaking)
    }

    // endregion
}

private class FakeSpeechEngine(
    private val callbacks: AgentSpeechEngineCallbacks,
    override val maxSpeechInputLength: Int,
) : AgentSpeechEngine {
    val spoken = mutableListOf<Pair<String, String>>()
    var supportedLocales = setOf(Locale.US)
    var speakResult = true
    var language: Locale? = null
    var stopCount = 0
    var isShutdown = false

    fun spokenTexts() = spoken.map { it.first }

    fun finish(index: Int) = callbacks.onUtteranceDone(spoken[index].second)

    fun error(index: Int) = callbacks.onUtteranceError(spoken[index].second)

    fun finishAll() = spoken.forEach { callbacks.onUtteranceDone(it.second) }

    fun stopAll() = spoken.forEach { callbacks.onUtteranceStopped(it.second) }

    override fun setLanguage(locale: Locale): Boolean {
        if (locale !in supportedLocales) return false
        language = locale
        return true
    }

    override fun speak(
        text: String,
        utteranceId: String,
    ): Boolean {
        if (!speakResult) return false
        spoken += text to utteranceId
        return true
    }

    override fun stop() {
        stopCount++
    }

    override fun shutdown() {
        isShutdown = true
    }
}

private class FakeSpeechEngineFactory(
    private val maxLength: Int,
    private val throws: Boolean,
) : AgentSpeechEngineFactory {
    val created = mutableListOf<FakeSpeechEngine>()
    private var pendingInit: ((Boolean) -> Unit)? = null

    fun completeInit(success: Boolean) {
        val init = checkNotNull(pendingInit)
        pendingInit = null
        init(success)
    }

    override fun create(
        onInit: (Boolean) -> Unit,
        callbacks: AgentSpeechEngineCallbacks,
    ): AgentSpeechEngine {
        if (throws) throw IllegalStateException("No engine")
        pendingInit = onInit
        return FakeSpeechEngine(callbacks, maxLength).also { created += it }
    }
}

private class PlayerHarness(
    locale: Locale = Locale.US,
    maxLength: Int = 4000,
    factoryThrows: Boolean = false,
) {
    val factory = FakeSpeechEngineFactory(maxLength, factoryThrows)
    val errors = mutableListOf<AgentSpeechError>()
    val speakingChanges = mutableListOf<Boolean>()
    val player =
        AgentSpeechPlayer(
            engineFactory = factory,
            localeProvider = { locale },
            mainExecutor = { it.run() },
            onError = { errors += it },
            onSpeakingChanged = { speakingChanges += it },
        )

    val engine: FakeSpeechEngine
        get() = factory.created.last()

    /** Creates and initializes the engine without leaving anything queued. */
    fun ready(): PlayerHarness {
        player.enqueue(listOf("warm up"))
        player.stop()
        factory.completeInit(true)
        speakingChanges.clear()
        return this
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTextToSpeechEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingCallbacks : AgentSpeechEngineCallbacks {
        val done = mutableListOf<String>()

        override fun onUtteranceDone(utteranceId: String) {
            done += utteranceId
        }

        override fun onUtteranceError(utteranceId: String) = Unit

        override fun onUtteranceStopped(utteranceId: String) = Unit
    }

    @Test
    fun adapter_mapsInitLanguageSpeechAndLifecycle() {
        val callbacks = RecordingCallbacks()
        var initResult: Boolean? = null
        val engine = AndroidTextToSpeechEngineFactory(context).create({ initResult = it }, callbacks)
        val shadow = Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals(true, initResult)

        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        assertTrue(engine.setLanguage(Locale.US))
        assertEquals(Locale.US, shadow.currentLanguage)
        assertFalse(engine.setLanguage(Locale.JAPAN))
        assertEquals(TextToSpeech.getMaxSpeechInputLength(), engine.maxSpeechInputLength)

        assertTrue(engine.speak("Hello", "utterance-1"))
        assertEquals("Hello", shadow.lastSpokenText)
        assertEquals(TextToSpeech.QUEUE_ADD, shadow.queueMode)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("utterance-1"), callbacks.done)

        engine.stop()
        assertTrue(shadow.isStopped)
        engine.shutdown()
        assertTrue(shadow.isShutdown)
    }

    @Test
    fun adapter_reportsInitFailure() {
        var initResult: Boolean? = null
        AndroidTextToSpeechEngineFactory(context).create({ initResult = it }, RecordingCallbacks())

        Shadows
            .shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
            .onInitListener
            .onInit(TextToSpeech.ERROR)
        assertEquals(false, initResult)
    }

    @Test
    fun player_withAndroidEngineSpeaksAndCompletes() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val handler = Handler(Looper.getMainLooper())
        val errors = mutableListOf<AgentSpeechError>()
        val player =
            AgentSpeechPlayer(
                engineFactory = AndroidTextToSpeechEngineFactory(context),
                localeProvider = { Locale.US },
                mainExecutor = { handler.post(it) },
                onError = { errors += it },
                onSpeakingChanged = {},
            )

        player.enqueue(listOf("Hello **world**").map(::agentMarkdownToSpeechText))
        val shadow = Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("Hello world"), shadow.spokenTextList)
        assertFalse(player.isSpeaking)
        assertTrue(errors.isEmpty())

        player.release()
        assertTrue(shadow.isShutdown)
    }
}
