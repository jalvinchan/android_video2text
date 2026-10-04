# Video to text

Android 7.0+ app for offline English, Mandarin, and Cantonese video transcription.
Import one video through the file picker or Android Share menu. A foreground
service extracts its first audio track and runs CPU Whisper inference while the
app is backgrounded or the screen is locked.

Follow **Import video → Select language → Select model → Start transcription →
Result**. Import and Android Share copy one video into private durable storage;
neither starts inference. Keep the app open until the source copy is saved; an
interrupted import must be repeated. The import page shows filename and duration.
Cantonese can be chosen before Turbo is installed; its model page offers only
Turbo, with download access. Model management returns to the same setup choices.
Settings become immutable when Start is pressed. The processing page shows them.

Processing controls reflect what can be retained: **Cancel import** discards an
incomplete copy; **Stop for now** during extraction keeps the source video but
restarts extraction on Resume; **Pause transcription** keeps saved audio and
completed speech sections. Resume repeats the unfinished section, containing at
most 30 seconds of audio. The processing screen and notification explain this.
The retained-job card offers **Resume**, shows saved progress and fixed settings,
and explains where processing continues. **Discard unfinished job** remains a
separate action with confirmation and keeps the previous successful transcript.

| Language | Behavior |
| --- | --- |
| Auto (default) | Detects English/Mandarin per speech section with Base or Small; also Cantonese with Turbo |
| English | Guides transcription with `en` |
| Chinese (Mandarin) | Guides transcription with `zh` |
| Cantonese | Guides transcription with `yue`; requires installed, selected Turbo |

Translation is disabled. Chinese characters are retained exactly as emitted by
Whisper, without simplified/traditional conversion. Rapid language changes within
one phrase remain a recognition limitation.

| Model | Delivery | Download size | Tradeoff |
| --- | --- | --- | --- |
| Multilingual Base (default) | Download after install | 142 MiB | English and Mandarin |
| Multilingual Small | Download after install | 465 MiB | Higher accuracy with more memory and processing |
| Large v3 Turbo q5 | Download after install | 547 MiB | Cantonese, English, and Mandarin |
| Silero v5.1.2 detector | Shared first-download helper | 0.84 MiB | Speech boundaries for Auto |

The APK contains no model weights. Download a speech model after installation;
the shared detector downloads with the first model if needed. Transcription
requires both files to be verified and installed and then works offline.

The Models page provides status, progress, Cancel, Retry, and Delete. Downloads
start only after the user requests them, use Wi-Fi by default, and offer an
explicit mobile-data choice for each request. Roaming is disabled. Downloading a
model does not select it. One download may run at a time, independently of the app
process, through Android DownloadManager. Transcription can use an installed
model while a download runs. A model in use cannot be deleted. Deleting a selected
model resets the saved model default to Base. Base must also be downloaded before use. A Cantonese setup still
requires selecting installed Turbo; a saved Turbo job requires downloading Turbo
again to resume unfinished inference.
Internet is used only for requested downloads. Installed models run in airplane
mode. The app requests no microphone or broad storage permission.

Results default to **Plain text**. **Timestamps** formats the same stored segments
without running inference again. Search highlights matches. Copy, Share, and Save
as `.txt` export the entire selected format. Share uses a UTF-8 file to avoid
Binder text size limits. Only the latest successful transcript is retained. A
failed or cancelled job preserves the previous result. Transcript storage remains
compatible with the original English app.

## Build and tests

Use Android Studio's JDK 25 and the Android SDK matching `compileSdk`, with NDK
`27.2.12479018` and CMake `3.22.1`.

```sh
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Both `arm64-v8a` and `x86_64` are built with optimized CPU inference and 16 KiB
native page alignment. Release APKs are unsigned until signing is configured.
Keep the emulator unlocked during UI checks. Download Base in the app before
running native pipeline tests, then use airplane mode for offline checks.

Optional-model native and deletion tests use verified files supplied to the test
harness, and skip with an explanation if those large files are absent. They are
never bundled into production or test APKs. Before running them, install the debug
APK, create `/sdcard/Android/data/com.example.video2text/files/verification`, and
push `ggml-small.bin` and `ggml-large-v3-turbo-q5_0.bin` there. Use the pinned URLs
and checksums below. The native tests verify those hashes again before loading.

`ModelDownloadIntegrationTest` has explicit `modelDownloadPhase=start` and
`modelDownloadPhase=resume` instrumentation arguments. On an emulator without
Small installed, enable Wi-Fi, run `startSmallDownload`, terminate the target
process, then run `reopeningInstallsVerifiedDownloadAndDoesNotSelectIt` with the
resume argument. This tests a real DownloadManager transfer, startup
reconciliation, checksum verification, and unchanged selection. The separate `networkFailureLeavesModelsSettingsAndTranscriptIntact` check uses
`modelDownloadPhase=failure` and a small HTTP 404 fixture. These online
checks skip in the default offline suite.

Test media include public-domain English speech and CC BY 4.0 Google FLEURS
Mandarin/Cantonese recordings. `app/src/androidTest/assets/FIXTURE_LICENSES.txt`
provides provenance and attribution, and `references.json` retains reference
sentences. Mixed fixtures include pauses between languages; the continuous
fixture repeats speech across a 30-second boundary. Test assets are excluded from
production APKs.

## Pinned assets and storage

whisper.cpp remains vendored at **v1.7.6**. Original source archive SHA-256:
`166140e9a6d8a36f787a2bd77f8f44dd64874f12dd8359ff7c1f4f9acb86202e`.
CMake never downloads sources or models.

Speech models come from `https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/<filename>`.
The detector comes from `https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v5.1.2.bin`.

| Filename | Bytes | SHA-256 |
| --- | ---: | --- |
| ggml-base.bin | 147951465 | 60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe |
| ggml-small.bin | 487601967 | 1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b |
| ggml-large-v3-turbo-q5_0.bin | 574041195 | 394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2 |
| ggml-silero-v5.1.2.bin | 885098 | 29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf |

`ModelRepository` owns the catalog, preferences, installed-file validation,
download IDs, progress, and model-use protection. Models live in
`noBackupFilesDir/models`. Transfers stage in app-specific external
storage, then stream through size/SHA-256 verification into an atomic private
file. Only verified files are selectable. Startup checks installed models and the shared detector and reconciles persisted DownloadManager IDs; foreground entry also
reconciles. Verification interrupted by process death retries on reopening.
Corruption, cancellation, network errors, inaccessible staging, unavailable
DownloadManager, and insufficient storage produce recoverable errors. Failed
installation preserves any previous installed file and transcript.

All models download from the pinned URLs above and are verified again when
a job acquires them. Model preparation never commits the transcript's cancellation
token. MIT notices for Whisper, ggml, OpenAI models, and Silero are bundled in
`THIRD_PARTY_NOTICES.txt` and accessible in the app.

## Ownership and resource limits

`TranscriptionService` owns one job, cancellation, wake lock, and progress
notification. The immutable Start options snapshot passes through ViewModel, service,
pipeline, and JNI; Kotlin and JNI validate model/language compatibility. The
service uses `mediaProcessing` on API 35+, `specialUse` on API 34, `dataSync` on
API 29–33, and an untyped foreground service on API 24–28. Notification denial
does not prevent transcription.

`VideoAudioDecoder`, `PcmConversion`, and `PcmResampler` stream mono 16 kHz PCM to
disk, retaining audio presentation-time gaps and the first offset. JNI maps PCM;
Kotlin never buffers the entire audio track. Both guided and Auto inference use
ordered, disjoint sections of at most 30 seconds. The complete section plan is
saved before inference. Each completed section, including a section with no text,
is saved atomically with its boundaries, original audio timestamps and job ID.
Resume skips completed sections and repeats only an unfinished section. Fixed
settings include the model hash and engine/section revision. Partial text remains
separate from the latest successful transcript. Auto scans 30-second cores with a
one-second detector context on either side. Silero uses threshold 0.5, minimum
speech/silence 250 ms, and padding 30 ms. Detected spans are clipped to ordered,
disjoint cores, with inference sections limited to 30 seconds. Long spans are
split without overlapping input or dropping audio at a core boundary.

Each section detects its highest-probability eligible language. Cantonese is
excluded for Base/Small, and its token is validated for Turbo. Transcription
context resets between sections. One Whisper model remains loaded per job.
Native timestamps include both the speech section offset and original audio
offset. Progress aggregates monotonically across the mapped audio. Native abort
callbacks remain active during transcription; model loading, language detection,
and bounded detector passes check cancellation between calls.

Allow storage for the private model files, staged video, about 3.84 MB of PCM per
minute, and both staged/private copies of downloaded models during installation.
Target a recent ARM64 phone with at least 6 GB RAM. Larger models increase memory
and processing cost; speed and accuracy are not guaranteed. Native resources,
streams and codecs are released on terminal paths. One unfinished job is retained
outside cache and Android backup, including after cancellation or failure.
Reopening offers explicit Resume or Discard and never restarts inference automatically.
A completed extraction is reused; interrupted extraction restarts from the retained
video. After a successful result commit, or explicit Discard, retained media and
section checkpoints are removed. The last successful transcript survives failure,
cancellation and Discard. Shared exports older than one day are cleaned
on startup.

`Transcript` remains the canonical result. `TranscriptFormatter` owns both
formats and export names. `TranscriptStore` atomically replaces the latest result
with a cancellation check at the commit boundary.

## Verification limits

Emulator verification covers builds for both ABIs, unit tests, lint, native
fixtures, persistence/exports, download policy and recovery, and atomic model
installation. See [docs/VERIFICATION.md](docs/VERIFICATION.md) for measured results.

Cantonese accuracy beyond the included fixture and larger-model performance on
physical phones remain unverified. The physical-phone benchmark was not resumed.
Rapid switching within a phrase, noisy speech, long-video resource use, API 24/34
service behavior, and hardware codec differences require broader device testing
before release.
