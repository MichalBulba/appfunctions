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

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentDictationTest {
    @Test
    fun `cursor insertion never sends automatically even when auto send is enabled`() {
        val original = TextFieldValue("Existing text", TextRange(9))
        val inserted =
            requireNotNull(
                insertDictation("thread", original, "thread", original, "dictated ", insertAtCursor = true),
            )
        val actions = mutableListOf<Pair<String, TextFieldValue>>()

        completeDictation(
            inserted,
            autoSend = true,
            onDraftChanged = { actions += "update" to it },
            onSend = { actions += "send" to it },
            insertAtCursor = true,
        )

        assertEquals(listOf("update" to inserted), actions)
        assertEquals("Existing dictated text", inserted.text)
    }

    @Test
    fun `focused dictation into empty input also waits for manual send`() {
        val draft = TextFieldValue("Dictated text")
        val actions = mutableListOf<String>()

        completeDictation(
            draft,
            autoSend = true,
            onDraftChanged = { actions += "update" },
            onSend = { actions += "send" },
            insertAtCursor = true,
        )

        assertEquals(listOf("update"), actions)
    }

    @Test
    fun `unfocused dictation replaces entire draft regardless of cursor`() {
        val draft = TextFieldValue("Previous message", TextRange(4, 8))

        val result =
            insertDictation(
                "thread",
                draft,
                "thread",
                draft,
                "New message",
                insertAtCursor = false,
            )

        assertEquals(TextFieldValue("New message", TextRange(11)), result)
    }

    @Test
    fun `unfocused dictation does not overwrite a changed draft`() {
        val draft = TextFieldValue("Previous message")

        assertNull(
            insertDictation(
                "thread",
                draft,
                "thread",
                TextFieldValue("Edited"),
                "New message",
                insertAtCursor = false,
            ),
        )
    }

    @Test
    fun `auto send sends replacement instead of previous unfocused draft`() {
        val draft = TextFieldValue("Previous message", TextRange(3))
        val replacement =
            insertDictation(
                "thread",
                draft,
                "thread",
                draft,
                "New message",
                insertAtCursor = false,
            )!!
        val sent = mutableListOf<String>()

        completeDictation(
            replacement,
            autoSend = true,
            onDraftChanged = {},
            onSend = { sent += it.text },
        )

        assertEquals(listOf("New message"), sent)
    }

    @Test
    fun `auto send submits resulting draft after updating input`() {
        val draft = TextFieldValue("@Notes dictated message", TextRange(24))
        val actions = mutableListOf<Pair<String, TextFieldValue>>()

        completeDictation(
            draft,
            autoSend = true,
            onDraftChanged = { actions += "update" to it },
            onSend = { actions += "send" to it },
        )

        assertEquals(listOf("update" to draft, "send" to draft), actions)
    }

    @Test
    fun `auto send disabled only updates draft`() {
        val draft = TextFieldValue("Dictated message")
        val actions = mutableListOf<String>()

        completeDictation(
            draft,
            autoSend = false,
            onDraftChanged = { actions += "update" },
            onSend = { actions += "send" },
        )

        assertEquals(listOf("update"), actions)
    }

    @Test
    fun `blank draft is never sent automatically`() {
        val actions = mutableListOf<String>()

        completeDictation(
            TextFieldValue(" "),
            autoSend = true,
            onDraftChanged = { actions += "update" },
            onSend = { actions += "send" },
        )

        assertEquals(listOf("update"), actions)
    }

    @Test
    fun `inserts at cursor and preserves surrounding text`() {
        val draft = TextFieldValue("@Notes  please", TextRange(7))

        val result = insertDictation("thread", draft, "thread", draft, "hello")

        assertEquals(TextFieldValue("@Notes hello please", TextRange(12)), result)
    }

    @Test
    fun `replaces reversed selection and moves cursor after recognized text`() {
        val draft = TextFieldValue("old message!", TextRange(11, 0))

        val result = insertDictation("thread", draft, "thread", draft, "new message")

        assertEquals(TextFieldValue("new message!", TextRange(11)), result)
    }

    @Test
    fun `inserts into empty draft`() {
        val draft = TextFieldValue()

        assertEquals(
            TextFieldValue("Hello", TextRange(5)),
            insertDictation("thread", draft, "thread", draft, "Hello"),
        )
    }

    @Test
    fun `ignores result from another conversation`() {
        val draft = TextFieldValue("draft")

        assertNull(insertDictation("old", draft, "new", draft, "Hello"))
    }

    @Test
    fun `ignores result if draft changed while recognition was open`() {
        val draft = TextFieldValue("draft")

        assertNull(
            insertDictation("thread", draft, "thread", TextFieldValue("edited draft"), "Hello"),
        )
    }

    @Test
    fun `ignores result if cursor changed while recognition was open`() {
        val draft = TextFieldValue("draft", TextRange(0))

        assertNull(
            insertDictation("thread", draft, "thread", draft.copy(selection = TextRange(5)), "Hello"),
        )
    }
}
