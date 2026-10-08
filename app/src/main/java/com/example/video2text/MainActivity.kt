package com.example.video2text

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.video2text.ui.theme.Video2textTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: TranscriptionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[TranscriptionViewModel::class.java]
        enableEdgeToEdge()
        setContent { Video2textTheme { TranscriptionScreen(viewModel) } }
        if (savedInstanceState == null) receiveSharedMedia(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) viewModel.refreshModels()
        (application as TranscriptionApplication).monetization.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedMedia(intent)
    }

    private fun receiveSharedMedia(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        try {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE || (intent.clipData?.itemCount ?: 0) > 1) {
                viewModel.report("Share one audio or video file at a time.")
                return
            }
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            if (uri == null) viewModel.report("Share a single audio or video file to transcribe.")
            else viewModel.importMedia(uri)
        } catch (_: RuntimeException) {
            viewModel.report("This shared file could not be opened. Try importing it with the file picker.")
        } finally {
            // Consuming a share once avoids a second transcription on activity recreation.
            // Clear extras without forcing a second unparcel of untrusted input. Retain the
            // routing fields so Android and ActivityScenario can still identify the activity.
            setIntent(Intent(intent).replaceExtras(Bundle()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptionScreen(viewModel: TranscriptionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pro by viewModel.pro.collectAsStateWithLifecycle()
    var showPro by rememberSaveable { mutableStateOf(false) }
    val format by viewModel.format.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedPage by viewModel.page.collectAsStateWithLifecycle()
    val selectedTranscriptId by viewModel.selectedTranscriptId.collectAsStateWithLifecycle()
    val resultReturn by viewModel.resultReturn.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val setupLocked = state.pendingJob?.options != null
    val page = when {
        state.isActive -> TranscriptionPage.PROCESSING
        selectedPage == TranscriptionPage.PROCESSING -> TranscriptionPage.IMPORT
        setupLocked && selectedPage in listOf(TranscriptionPage.LANGUAGE, TranscriptionPage.MODEL) -> TranscriptionPage.IMPORT
        else -> selectedPage
    }
    var modelsReturn by rememberSaveable { mutableStateOf(TranscriptionPage.IMPORT) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val pageScroll = rememberSaveable(page, saver = ScrollState.Saver) { ScrollState(0) }
    val back: () -> Unit = {
        viewModel.navigate(when (page) {
            TranscriptionPage.LANGUAGE -> TranscriptionPage.IMPORT
            TranscriptionPage.MODEL -> TranscriptionPage.LANGUAGE
            TranscriptionPage.MODELS -> modelsReturn
            TranscriptionPage.RESULT -> resultReturn
            else -> TranscriptionPage.IMPORT
        })
    }
    val canGoBack = page != TranscriptionPage.IMPORT && page != TranscriptionPage.PROCESSING
    BackHandler(enabled = canGoBack, onBack = back)
    LaunchedEffect(state.stage, state.history.firstOrNull()?.id) {
        when (state.stage) {
            ProcessingStage.COMPLETED -> viewModel.showCompletedResult()
            ProcessingStage.CANCELLED, ProcessingStage.FAILED -> viewModel.navigate(TranscriptionPage.IMPORT)
            else -> Unit
        }
    }
    var downloadModel by remember { mutableStateOf<SpeechModel?>(null) }
    var mobileData by remember { mutableStateOf(false) }
    var deleteModel by remember { mutableStateOf<SpeechModel?>(null) }
    val context = LocalContext.current
    val application = context.applicationContext as TranscriptionApplication
    val activity = context as? android.app.Activity
    val ads by application.ads.state.collectAsStateWithLifecycle()
    LaunchedEffect(pro.enabled, pro.checked, pro.isPro, state.isActive) {
        if (pro.enabled && pro.checked && !pro.isPro && !state.isActive && activity != null)
            application.ads.updateConsent(activity)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var search by rememberSaveable(selectedTranscriptId) { mutableStateOf("") }
    var historySearch by rememberSaveable { mutableStateOf("") }
    var deleteTranscriptId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Providers may offer a temporary grant only. */ }
            viewModel.importMedia(uri)
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), viewModel::save)
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is UiEvent.Message -> snackbar.showSnackbar(event.text)
                    is UiEvent.Share -> try { context.startActivity(Intent.createChooser(event.intent, "Share transcript")) }
                        catch (_: RuntimeException) { snackbar.showSnackbar("No app is available to share this transcript.") }
                }
            }
        }
    }
    val transcript = if (selectedTranscriptId == null) state.transcript
        else state.history.firstOrNull { it.id == selectedTranscriptId }?.transcript
    val text = remember(transcript, format) { transcript?.let { TranscriptFormatter.format(it, format) }.orEmpty() }
    val matches = remember(text, search) { searchMatches(text, search) }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    val displayed = remember(text, matches, highlight, highlightText) {
        buildAnnotatedString {
            append(text)
            matches.forEach { addStyle(SpanStyle(background = highlight, color = highlightText), it.first, it.last + 1) }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                actions = {
                    if (!state.isActive) TextButton(onClick = { showPro = true }) { Text(if (pro.isPro) "Pro active" else "Pro") }
                },
                title = { Text(when (page) {
                    TranscriptionPage.IMPORT -> "Video to text"
                    TranscriptionPage.LANGUAGE -> "Select language"
                    TranscriptionPage.MODEL -> "Select model"
                    TranscriptionPage.PROCESSING -> "Processing"
                    TranscriptionPage.RESULT -> "Result"
                    TranscriptionPage.MODELS -> "Models"
                    TranscriptionPage.HISTORY -> "Transcript history"
                }) },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (pro.enabled && pro.checked && !pro.isPro && ads.canRequest && !state.isActive &&
                page in listOf(TranscriptionPage.IMPORT, TranscriptionPage.HISTORY, TranscriptionPage.RESULT)) FreePlanBanner()
        },
    ) { padding ->
        if (page == TranscriptionPage.HISTORY) {
            val entries = remember(state.history, historySearch) {
                state.history.filter { entry ->
                    historySearch.isBlank() || entry.transcript.sourceName.contains(historySearch, ignoreCase = true) ||
                        TranscriptFormatter.format(entry.transcript, TranscriptFormat.PLAIN_TEXT).contains(historySearch, ignoreCase = true)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                contentPadding = PaddingValues(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.message?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) }
                        OutlinedTextField(value = historySearch, onValueChange = { historySearch = it },
                            label = { Text("Search history") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Saved on this phone. Android can back up completed transcripts to your backup account and transfer them to a new phone, depending on your system settings. Recordings, models and unfinished jobs are excluded. Backup is not immediate; save text files elsewhere for an additional copy.", style = MaterialTheme.typography.bodySmall)
                        if (entries.isEmpty()) Text(if (state.history.isEmpty()) "No saved transcripts yet." else "No matching transcripts.")
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.transcript.sourceName, style = MaterialTheme.typography.titleMedium)
                            Text(entry.completedAtMs?.let {
                                "Completed: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}"
                            } ?: "Completion date unavailable", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { viewModel.openTranscript(entry.id) }) { Text("Open transcript") }
                            TextButton(onClick = { deleteTranscriptId = entry.id }) { Text("Delete transcript") }
                        }
                    }
                }
                item { TextButton(onClick = { showLicenses = true }) { Text("Open-source licenses") } }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(pageScroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.message?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) }
            if (page == TranscriptionPage.IMPORT) {
                Text("Import audio or video, choose its language and model, then start offline transcription.")
                if (pro.enabled && !pro.isPro) Text("Free plan: recordings up to 5 minutes. Keep the app visible and the phone unlocked while transcribing. Pro removes ads and the length limit, and allows background transcription.", style = MaterialTheme.typography.bodySmall)
                if (models.loaded && models.installed.isEmpty()) Text("Download a model from Manage models before your first transcription. After installation, transcription works offline.")
                val pending = state.pendingJob
                if (pending != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(pending.sourceName, style = MaterialTheme.typography.titleMedium)
                            pending.sourceDurationMs?.let { Text("Duration: ${it / 1000} seconds") }
                            Text("Retained media: ${state.retainedBytes / (1024 * 1024)} MiB")
                            Text(if (pending.progressive) "Completed speech sections and any current audio window are saved." else if (pending.hasAudio) "Extracted audio is saved." else if (pending.sourceHasVideo) "Video is saved; audio will be extracted when you start." else "Audio source is saved; it will be prepared when you start.")
                            pending.options?.let { Text("Fixed settings: ${it.label}") }
                            pending.sections?.let { Text(if (pending.progressive && !pending.extractionComplete) "${pending.completedSections} speech sections saved; more audio remains" else "${pending.completedSections} of ${it.size} speech sections saved") }
                            if (pending.options != null) Text(pending.resumeExplanation())
                            if (pending.options == null) Button(onClick = { viewModel.navigate(TranscriptionPage.LANGUAGE) }) { Text("Continue") }
                            else {
                                Button(onClick = viewModel::start, enabled = models.loaded && (!pending.needsModel || pending.options.model in models.installed)) { Text("Resume") }
                                if (pending.needsModel && pending.options.model !in models.installed) Text("Download ${pending.options.model.label} to resume with the original settings.")
                            }
                            TextButton(onClick = { confirmDiscard = true }) { Text("Discard unfinished job") }
                        }
                    }
                } else if (state.retainedBytes > 0) {
                    Text("Retained media could not be opened. Discard it to import another file.")
                    TextButton(onClick = { confirmDiscard = true }) { Text("Discard unfinished job") }
                } else Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = state.loaded && models.loaded, modifier = Modifier.fillMaxWidth()) { Text("Import audio or video") }
                OutlinedButton(onClick = { viewModel.navigate(TranscriptionPage.HISTORY) }, enabled = state.loaded) { Text("Transcript history") }
                TextButton(onClick = { modelsReturn = page; viewModel.navigate(TranscriptionPage.MODELS) }) { Text("Manage models") }
                Text("One saved unfinished job is kept until success or Discard, including after stopping or pausing. Audio uses about 3.84 MB per minute.", style = MaterialTheme.typography.bodySmall)
            }
            if (page == TranscriptionPage.LANGUAGE) {
            Text("Language", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechLanguage.entries.forEach { choice ->
                    FilterChip(selected = language == choice,
                        onClick = { viewModel.selectLanguage(choice) },
                        enabled = models.loaded,
                        label = { Text(choice.label) })
                }
            }
            Text("Auto detects English and Mandarin, plus Cantonese when Turbo is selected. Cantonese requires Turbo.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { viewModel.navigate(TranscriptionPage.MODEL) }) { Text("Continue to models") }
            }
            if (page == TranscriptionPage.MODEL) {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechModel.entries.filter { it.supports(language) }.forEach { model ->
                    FilterChip(selected = models.options.model == model && model in models.installed, onClick = {
                        if (model in models.installed) {
                            viewModel.selectModel(model)
                        } else if (models.download != null && models.download?.error == null) {
                            viewModel.report("A model download is already in progress. Open Manage models to check progress or cancel it.")
                        } else {
                            mobileData = false
                            downloadModel = model
                        }
                    }, enabled = models.loaded, label = { Text("${model.label}${if (model in models.installed) " · Installed" else " · Download"}") })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SpeechModel.entries.filter { it.supports(language) }.forEach { model ->
                    Text("${model.label} · ${model.downloadMiB} MiB · ${model.languageSummary}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Tap a model to download if not installed. Larger models use more memory and processing time. Downloading does not select a model.", style = MaterialTheme.typography.bodySmall)
            Text(if (language == SpeechLanguage.CANTONESE) "Turbo is required for Cantonese." else "Base is recommended to start. Small and Turbo use more memory and processing time.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { modelsReturn = page; viewModel.navigate(TranscriptionPage.MODELS) }) { Text("Manage models") }
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                viewModel.start()
            }, enabled = state.pendingJob != null && models.loaded && models.options.model.supports(language) && models.options.model in models.installed) { Text("Start transcription") }
            }
            if (!state.loaded || !models.loaded) CircularProgressIndicator(Modifier.size(24.dp))
            if (!models.loaded) Text("Checking installed offline models…", style = MaterialTheme.typography.bodySmall)
            if (state.isActive) {
                Card(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.cancelling) state.stoppingLabel() else state.stage.label(), style = MaterialTheme.typography.titleMedium)
                        val percent = state.percent
                        if (percent == null || state.cancelling) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else {
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("$percent%")
                        }
                        state.activeOptions?.let { Text("Active settings: ${it.label}") }
                        Text(state.workVisibilityExplanation())
                        Text(state.stopExplanation(), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = viewModel::stop, enabled = !state.cancelling) { Text(state.stopActionLabel()) }
                    }
                }
            }
            if (page == TranscriptionPage.RESULT && transcript != null) {
                HorizontalDivider()
                Text(transcript.sourceName, style = MaterialTheme.typography.titleMedium)
                if (state.isActive) Text("Your previous result stays here until the new transcription succeeds.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = format == TranscriptFormat.PLAIN_TEXT, onClick = { viewModel.selectFormat(TranscriptFormat.PLAIN_TEXT) }, label = { Text("Plain text") })
                    FilterChip(selected = format == TranscriptFormat.TIMESTAMPS, onClick = { viewModel.selectFormat(TranscriptFormat.TIMESTAMPS) }, label = { Text("Timestamps") })
                }
                if (format == TranscriptFormat.TIMESTAMPS) Text("Times are approximate segment starts.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::copy) { Text("Copy") }
                    OutlinedButton(onClick = viewModel::share) { Text("Share") }
                }
                OutlinedButton(onClick = { viewModel.prepareSave()?.let { saver.launch(it) } }) { Text("Save as .txt") }
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search transcript") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (search.isNotEmpty()) Text("${matches.size} matches. Exports always contain the full transcript.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(displayed, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = { deleteTranscriptId = selectedTranscriptId ?: state.history.firstOrNull()?.id }, enabled = !state.isActive) { Text("Delete transcript") }
                OutlinedButton(onClick = { viewModel.navigate(TranscriptionPage.HISTORY) }) { Text("Transcript history") }
                Button(onClick = { viewModel.navigate(TranscriptionPage.IMPORT) }) { Text("Transcribe another file") }
            }
            if (page == TranscriptionPage.RESULT && transcript == null && state.loaded) {
                Text("This saved transcript is no longer available.")
                OutlinedButton(onClick = { viewModel.navigate(TranscriptionPage.HISTORY) }) { Text("Transcript history") }
            }
            models.message?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) }
            if (page == TranscriptionPage.MODELS) {
            HorizontalDivider()
            Text("Models download after installation. The shared speech detector (0.84 MiB) downloads with your first model.", style = MaterialTheme.typography.bodySmall)
            SpeechModel.entries.forEach { model ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val download = models.download?.takeIf { it.model == model }
                        Text("${model.label} · ${model.downloadMiB} MiB · ${if (model in models.installed) "Installed" else "Not installed"}")
                        Text(model.languageSummary, style = MaterialTheme.typography.bodyMedium)
                        if (download != null) {
                            Text(download.error ?: download.status)
                            if (download.error == null) {
                                download.percent?.let { percent ->
                                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                                    Text("$percent%")
                                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                                OutlinedButton(onClick = viewModel::cancelDownload) { Text("Cancel download") }
                            } else {
                                OutlinedButton(onClick = { mobileData = false; downloadModel = model }) { Text("Retry ${model.label}") }
                                TextButton(onClick = viewModel::cancelDownload) { Text("Cancel download") }
                            }
                        }
                        if (model in models.installed) {
                            OutlinedButton(onClick = { deleteModel = model }, enabled = models.inUse != model) { Text("Delete ${model.label}") }
                            if (models.inUse == model) Text("In use by the current transcription")
                        } else if (download == null) {
                            OutlinedButton(onClick = { mobileData = false; downloadModel = model }, enabled = models.loaded && (models.download == null || models.download?.error != null)) { Text("Download ${model.label}") }
                        }
                    }
                }
            }
            }
            TextButton(onClick = { showLicenses = true }) { Text("Open-source licenses") }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard unfinished job?") },
        text = { Text("This deletes retained source file, audio and section checkpoints. Your last successful transcript is kept.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discardJob() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } })
    downloadModel?.let { model ->
        AlertDialog(onDismissRequest = { downloadModel = null }, title = { Text("Download ${model.label}?") },
            text = { Column {
                Text("Languages: ${model.languageSummary}")
                Text("${model.downloadMiB} MiB. Wi-Fi is used by default. The download can continue after you leave the app.")
                Text("A shared speech detector (0.84 MiB) also downloads if needed. Transcription works offline once installation finishes.")
                Row(Modifier.fillMaxWidth().toggleable(value = mobileData, role = Role.Checkbox, onValueChange = { mobileData = it })) {
                    Checkbox(checked = mobileData, onCheckedChange = null)
                    Text("Allow mobile data for this download")
                }
            } },
            confirmButton = { TextButton(onClick = { viewModel.downloadModel(model, mobileData); downloadModel = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { downloadModel = null }) { Text("Cancel") } })
    }
    deleteModel?.let { model ->
        AlertDialog(onDismissRequest = { deleteModel = null }, title = { Text("Delete ${model.label} model?") },
            text = { Text("You can download it again. If selected, the default resets to Base and Cantonese resets to Auto. Transcription requires an installed model.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteModel(model); deleteModel = null }) { Text("Delete model") } },
            dismissButton = { TextButton(onClick = { deleteModel = null }) { Text("Cancel") } })
    }
    deleteTranscriptId?.let { id ->
        val name = state.history.firstOrNull { it.id == id }?.transcript?.sourceName.orEmpty()
        AlertDialog(onDismissRequest = { deleteTranscriptId = null }, title = { Text("Delete saved transcript?") },
            text = { Text("This removes $name from this app. Other transcripts and text files you saved elsewhere are kept.") },
            confirmButton = { TextButton(onClick = { deleteTranscriptId = null; viewModel.delete(id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTranscriptId = null }) { Text("Cancel") } })
    }
    if (showPro) {
        AlertDialog(onDismissRequest = { showPro = false }, title = { Text(if (pro.isPro) "Pro is active" else "Upgrade to Pro") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("One-time purchase. No ads or recording-length limit, plus background transcription. Processing stays on your phone; device memory, storage and Android background limits still apply.")
                    Text("Restore on another Android phone using the same Google Play account. Transcript history restores separately through Android backup.")
                    if (!pro.enabled) Text("Pro purchases are not available yet. All current transcription features remain available.")
                    pro.message?.let { Text(it) }
                    if (pro.busy) Text("Checking Google Play…")
                    if (!pro.isPro) Button(onClick = { activity?.let { application.monetization.buy(it) } },
                        enabled = pro.enabled && pro.checked && !pro.busy && !pro.pending && pro.price != null && activity != null) {
                        Text(pro.price?.let { "Unlock Pro — $it once" } ?: "Unlock Pro")
                    }
                    OutlinedButton(onClick = application.monetization::refresh, enabled = pro.enabled && !pro.busy) { Text("Restore purchases") }
                    if (ads.privacyOptionsRequired) TextButton(onClick = { activity?.let { application.ads.privacyOptions(it) } }, enabled = activity != null) { Text("Ad privacy choices") }
                }
            },
            confirmButton = { TextButton(onClick = { showPro = false }) { Text("Close") } })
    }
    if (showLicenses) {
        val licenses = remember { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("Open-source licenses") },
            text = { Text(licenses, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } })
    }
}

internal fun searchMatches(text: String, search: String): List<IntRange> {
    if (search.isEmpty()) return emptyList()
    val matches = mutableListOf<IntRange>()
    var offset = 0
    while (offset <= text.length - search.length) {
        val index = text.indexOf(search, offset, ignoreCase = true)
        if (index < 0) break
        matches += index until index + search.length
        offset = index + search.length
    }
    return matches
}
