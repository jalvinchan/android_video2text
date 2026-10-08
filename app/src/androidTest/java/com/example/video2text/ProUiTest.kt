package com.example.video2text

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unconfiguredBuildExplainsProAndCannotChargeOrRestrictImport() {
        assumeTrue(!BuildConfig.MONETIZATION_ENABLED)
        compose.waitUntil(10_000) {
            val app = compose.activity.application as TranscriptionApplication
            app.repository.state.value.loaded && app.models.state.value.loaded
        }
        compose.onNodeWithText("Pro").performClick()
        compose.onNodeWithText("Upgrade to Pro").assertExists()
        compose.onNodeWithText("Pro purchases are not available yet. All current transcription features remain available.").assertExists()
        compose.onNodeWithText("Unlock Pro").assertIsNotEnabled()
        compose.onNodeWithText("Restore purchases").assertIsNotEnabled()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Import audio or video").assertIsEnabled()
    }
}
