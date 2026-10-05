package com.example.video2text

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val transcript = Transcript("example.mp4", listOf(
        TranscriptSegment(0, 1_000, "Hello, world! 你好，廣東話！"), TranscriptSegment(12_345, 14_000, "A second sentence. 普通话。")))

    @Before fun seedResult() = runBlocking(Dispatchers.IO) {
        val repository = (compose.activity.application as TranscriptionApplication).repository
        repository.ready.await()
        repository.discardJob()
        repository.state.value.history.forEach { repository.delete(it.id) }
        repository.complete(transcript, ProcessingCancellation())
    }

    @Test fun formatSearchAndRotationKeepTheSameFullResult() {
        compose.onNodeWithText("Plain text").assertIsSelected()
        compose.onNodeWithText("Hello, world! 你好，廣東話！ A second sentence. 普通话。").assertExists()
        compose.onNodeWithText("Search transcript").performScrollTo().performTextInput("HELLO")
        compose.onNodeWithText("1 matches. Exports always contain the full transcript.").assertExists()
        compose.onNodeWithText("Timestamps").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("[00:00] Hello, world! 你好，廣東話！\n[00:12] A second sentence. 普通话。").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Timestamps").assertIsSelected()
        compose.onNodeWithText("[00:00] Hello, world! 你好，廣東話！\n[00:12] A second sentence. 普通话。").assertExists()
    }

    @Test fun copyUsesSelectedFormatAndIgnoresSearch() {
        compose.onNodeWithText("Search transcript").performScrollTo().performTextInput("Hello")
        for ((label, expected) in listOf(
            "Plain text" to "Hello, world! 你好，廣東話！ A second sentence. 普通话。",
            "Timestamps" to "[00:00] Hello, world! 你好，廣東話！\n[00:12] A second sentence. 普通话。")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithText("Copy").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                var copied = ""
                compose.runOnUiThread {
                    copied = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.getItemAt(0)?.text.toString()
                }
                copied == expected
            }
        }
    }

    @Test fun deleteRequiresConfirmationAndClearsTheSavedResult() {
        compose.onNodeWithText("Delete transcript").performScrollTo().performClick()
        compose.onNodeWithText("Delete saved transcript?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { (compose.activity.application as TranscriptionApplication).repository.state.value.transcript == null }
        compose.onNodeWithText("Back").performClick()
        compose.onAllNodesWithText("Import audio or video")[0].assertExists()
    }

    @Test fun historySearchReopensOlderResultAcrossRotationAndDeletesOnlyThatEntry() {
        val repository = (compose.activity.application as TranscriptionApplication).repository
        val newer = Transcript("newer.wav", listOf(TranscriptSegment(0, 1_000, "Only the newer result.")))
        runBlocking(Dispatchers.IO) { repository.complete(newer, ProcessingCancellation()) }
        compose.waitUntil(5_000) {
            androidx.lifecycle.ViewModelProvider(compose.activity)[TranscriptionViewModel::class.java].selectedTranscriptId.value == repository.state.value.history.first().id
        }
        compose.onNodeWithText("Transcript history").performScrollTo().performClick()
        compose.onNodeWithText("Search history").performTextInput("EXAMPLE")
        compose.onNodeWithText("example.mp4").assertExists()
        compose.onNodeWithText("newer.wav").assertDoesNotExist()
        compose.onNodeWithText("Search history").performTextClearance()
        compose.onNodeWithText("Search history").performTextInput("廣東話")
        compose.onNodeWithText("Open transcript").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("example.mp4").assertExists()
        compose.onNodeWithText("Hello, world! 你好，廣東話！ A second sentence. 普通话。").assertExists()
        compose.onNodeWithText("Copy").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            var copied = ""
            compose.runOnUiThread {
                copied = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.text.toString()
            }
            copied == TranscriptFormatter.format(transcript, TranscriptFormat.PLAIN_TEXT)
        }
        compose.onNodeWithText("Delete transcript").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        org.junit.Assert.assertEquals(2, repository.state.value.history.size)
        compose.onNodeWithText("Delete transcript").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { repository.state.value.history.size == 1 }
        org.junit.Assert.assertEquals(newer, repository.state.value.history.single().transcript)
        compose.onNodeWithText("Search history").performTextClearance()
        compose.onNodeWithText("newer.wav").assertExists()
    }
    @Test fun downloadDialogRequiresAnExplicitMobileChoiceForEachRequest() {
        compose.onNodeWithText("Transcribe another file").performScrollTo().performClick()
        compose.onNodeWithText("Manage models").performScrollTo().performClick()
        compose.onNodeWithText("Download Small").performScrollTo().performClick()
        compose.onNodeWithText("Download Small?").assertExists()
        compose.onNodeWithText("Allow mobile data for this download").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Cancel").performClick()
        org.junit.Assert.assertNull((compose.activity.application as TranscriptionApplication).models.state.value.download)
        compose.onNodeWithText("Download Small").performScrollTo().performClick()
        compose.onNodeWithText("Allow mobile data for this download").assertIsOff()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun setupWaitsForStartAndModelManagementRetainsChoices() {
        val app = compose.activity.application as TranscriptionApplication
        val file = java.io.File(app.cacheDir, "exports/ui-fixture/speech.mp4").apply { parentFile!!.mkdirs() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("speech.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.exports", file)
        compose.runOnUiThread { androidx.lifecycle.ViewModelProvider(compose.activity)[TranscriptionViewModel::class.java].importMedia(uri) }
        compose.waitUntil(10000) { app.repository.state.value.pendingJob != null && !app.repository.state.value.isActive }
        compose.onNodeWithText("Cantonese").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("Continue to models").performScrollTo().performClick()
        compose.onNodeWithText("Turbo is required for Cantonese.").assertExists()
        compose.onNodeWithText("Start transcription").assertIsNotEnabled()
        compose.onNodeWithText("Base · Installed").assertDoesNotExist()
        compose.onNodeWithText("Manage models").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Turbo is required for Cantonese.").assertExists()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Cantonese").assertIsSelected()
        org.junit.Assert.assertNull(app.repository.state.value.pendingJob!!.options)
        org.junit.Assert.assertFalse(app.repository.jobs.audio.exists())
        runBlocking(Dispatchers.IO) { app.repository.discardJob() }
        file.parentFile!!.deleteRecursively()
    }
}
