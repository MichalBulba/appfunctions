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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.appfunctions.agent.R

internal data class AgentDictationControls(
    val isListening: Boolean,
    val start: () -> Unit,
)

@Composable
internal fun rememberAgentDictation(
    threadId: String,
    draft: TextFieldValue,
    enabled: Boolean,
    onDraftChanged: (TextFieldValue) -> Unit,
    onStart: () -> Unit,
    onError: (Int) -> Unit,
): AgentDictationControls {
    val currentThreadId by rememberUpdatedState(threadId)
    val currentDraft by rememberUpdatedState(draft)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnDraftChanged by rememberUpdatedState(onDraftChanged)
    val currentOnError by rememberUpdatedState(onError)
    var requestThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestDraft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val locale = LocalConfiguration.current.locales[0]
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val origin = requestThreadId
            requestThreadId = null
            if (
                origin != null &&
                origin == currentThreadId &&
                requestDraft == currentDraft &&
                currentEnabled
            ) {
                handleDictationResult(
                    result = result,
                    onText = { recognized ->
                        insertDictation(
                            requestThreadId = origin,
                            requestDraft = requestDraft,
                            currentThreadId = currentThreadId,
                            currentDraft = currentDraft,
                            recognizedText = recognized,
                        )?.let(currentOnDraftChanged)
                    },
                    onError = currentOnError,
                )
            }
        }

    return AgentDictationControls(
        isListening = requestThreadId != null,
        start = {
            if (enabled && requestThreadId == null) {
                onStart()
                requestThreadId = threadId
                requestDraft = draft
                val intent =
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                try {
                    launcher.launch(intent)
                } catch (error: ActivityNotFoundException) {
                    requestThreadId = null
                    Log.w("AgentDictation", "No speech recognition activity", error)
                    onError(R.string.agent_demo_dictation_unavailable)
                } catch (error: SecurityException) {
                    requestThreadId = null
                    Log.w("AgentDictation", "Speech recognition activity denied", error)
                    onError(R.string.agent_demo_dictation_failed)
                }
            }
        },
    )
}

internal fun handleDictationResult(
    result: ActivityResult,
    onText: (String) -> Unit,
    onError: (Int) -> Unit,
) {
    when (result.resultCode) {
        Activity.RESULT_CANCELED -> Unit
        Activity.RESULT_OK -> {
            val text =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull { it.isNotBlank() }
            if (text == null) {
                onError(R.string.agent_demo_dictation_empty)
            } else {
                onText(text)
            }
        }

        else -> onError(R.string.agent_demo_dictation_failed)
    }
}

internal fun insertDictation(
    requestThreadId: String,
    requestDraft: TextFieldValue,
    currentThreadId: String,
    currentDraft: TextFieldValue,
    recognizedText: String,
): TextFieldValue? {
    if (requestThreadId != currentThreadId || requestDraft != currentDraft) {
        return null
    }
    val start = currentDraft.selection.min
    val end = currentDraft.selection.max
    val text = currentDraft.text.replaceRange(start, end, recognizedText)
    return TextFieldValue(text, selection = TextRange(start + recognizedText.length))
}
