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
package com.example.appfunctions.agent.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.appfunctions.agent.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Unit tests for [DataStoreSettingsRepository] using a temporary file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreSettingsRepository
    private var storeJob = SupervisorJob()
    private lateinit var settingsFile: File

    @Before
    fun setUp() {
        settingsFile = File(temporaryFolder.root, "test_settings.preferences_pb")
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + storeJob),
                produceFile = { settingsFile },
            )
        repository = DataStoreSettingsRepository(dataStore)
    }

    @After
    fun tearDown() =
        runBlocking {
            storeJob.cancelAndJoin()
        }

    @Test
    fun voiceSettings_defaultToAutoReadWithoutAutoSend() =
        runTest {
            assertEquals(VoiceSettings(true, false), repository.voiceSettings.first())
        }

    @Test
    fun voiceSettings_areRestoredAfterDataStoreReopens() =
        runTest {
            repository.setAutoReadEnabled(false)
            repository.setAutoSendDictationEnabled(true)

            storeJob.cancelAndJoin()
            storeJob = SupervisorJob()
            val reopened =
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + storeJob),
                    produceFile = { settingsFile },
                )
            val restored = DataStoreSettingsRepository(reopened)
            assertEquals(VoiceSettings(false, true), restored.voiceSettings.first())

            restored.setAutoReadEnabled(true)
            assertEquals(VoiceSettings(true, true), restored.voiceSettings.first())
            restored.setAutoSendDictationEnabled(false)
            assertEquals(VoiceSettings(true, false), restored.voiceSettings.first())
        }

    @Test
    fun setGeminiApiKey_persistsValue() =
        runTest {
            repository.setGeminiApiKey("gemini_key")
            val value = repository.geminiApiKey.first()
            if (BuildConfig.IS_RETAIL) {
                assertEquals(BuildConfig.GEMINI_API_KEY, value)
            } else {
                assertEquals("gemini_key", value)
            }
        }

    @Test
    fun setSelectedProvider_persistsValue() =
        runTest {
            repository.setSelectedProvider(LlmProviderName.GEMINI)
            val value = repository.selectedProvider.first()
            assertEquals(LlmProviderName.GEMINI, value)
        }

    @Test
    fun serviceTier_defaultsToPriority() =
        runTest {
            val value = repository.serviceTier.first()
            assertEquals(ServiceTier.PRIORITY, value)
        }

    @Test
    fun setServiceTier_persistsValue() =
        runTest {
            repository.setServiceTier(ServiceTier.PRIORITY)
            val value = repository.serviceTier.first()
            assertEquals(ServiceTier.PRIORITY, value)
        }
}
