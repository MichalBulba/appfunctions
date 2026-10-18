/*
 * Copyright 2026 BlbM
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

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResult
import com.example.appfunctions.agent.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentDictationResultTest {
    @Test
    fun `cancel leaves draft unchanged without reporting error`() {
        var draft = "original"
        val errors = mutableListOf<Int>()

        handleDictationResult(
            ActivityResult(Activity.RESULT_CANCELED, null),
            onText = { draft = it },
            onError = errors::add,
        )

        assertEquals("original", draft)
        assertEquals(emptyList<Int>(), errors)
    }

    @Test
    fun `successful recognition uses first nonblank alternative`() {
        val data =
            Intent().putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf(" ", "recognized", "alternative"),
            )
        val texts = mutableListOf<String>()
        val errors = mutableListOf<Int>()

        handleDictationResult(
            ActivityResult(Activity.RESULT_OK, data),
            onText = texts::add,
            onError = errors::add,
        )

        assertEquals(listOf("recognized"), texts)
        assertEquals(emptyList<Int>(), errors)
    }

    @Test
    fun `successful response without words reports a usable error`() {
        val texts = mutableListOf<String>()
        val errors = mutableListOf<Int>()

        handleDictationResult(
            ActivityResult(Activity.RESULT_OK, null),
            onText = texts::add,
            onError = errors::add,
        )

        assertEquals(emptyList<String>(), texts)
        assertEquals(listOf(R.string.agent_demo_dictation_empty), errors)
    }

    @Test
    fun `recognizer failure reports error without changing text`() {
        val texts = mutableListOf<String>()
        val errors = mutableListOf<Int>()

        handleDictationResult(
            ActivityResult(RecognizerIntent.RESULT_AUDIO_ERROR, null),
            onText = texts::add,
            onError = errors::add,
        )

        assertEquals(emptyList<String>(), texts)
        assertEquals(listOf(R.string.agent_demo_dictation_failed), errors)
    }
}
