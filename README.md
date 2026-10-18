# Video to text

Android 7.0+ app for offline English, Mandarin, and Cantonese transcription from
audio and video. Import one file through the file picker or Android Share menu.
A foreground service extracts its first audio track and runs CPU Whisper
inference. Pro (or unconfigured builds) can continue while backgrounded or locked;
configured free runs pause when the app is hidden or the screen is locked.

Follow **Import audio or video → Select language → Select model → Start transcription →
Result**. Import and Android Share copy one source file into private durable storage;
neither starts inference. Keep the app open until the source copy is saved; an
interrupted import must be repeated. The import page shows filename and duration.
Cantonese can be chosen before Turbo is installed; its model page offers only
Turbo, with download access. Model management returns to the same setup choices.
Settings become immutable when Start is pressed. The processing page shows them.

The picker and Android Share accept audio and video. Import inspects actual tracks
in the private copy and checks that Android can configure a decoder for the first
audio track; filenames and Share MIME types do not determine validity. Files with
no audio track are rejected. WAV and compressed formats such as MP3, M4A and AAC
use the same streaming decode and mono 16 kHz PCM conversion, subject to device
codec support. Audio extraction and speech recognition alternate per audio
window under one **Converting to text** status in the screen and notification;
the screen adds the current step (**Extracting audio…**, **Reading audio…** for
audio-only sources, or **Recognizing speech…**) and progress follows transcribed
time. Full sample validation and silence checks happen at Start.
Existing video jobs and saved transcripts remain readable.

Processing controls reflect what can be retained: **Cancel import** discards an
incomplete copy; **Pause transcription** keeps the saved source, current audio
window and completed speech sections. Resume reuses the saved window and repeats
an unfinished section, containing at most 30 seconds of audio. Decoding then
restarts from the source and skips processed windows without repeating completed
inference. The processing screen and notification explain this. The retained-job
card offers **Resume**, shows saved progress and fixed settings, and explains
where processing continues. **Discard unfinished job** requires confirmation and
keeps the previous successful transcript. Older full-PCM jobs reuse their saved
audio and original section plan.

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

The APK contains no model weights. Before the first transcription, download Base,
Small, or Turbo from the model selection or Models page. The shared detector
downloads automatically as part of that explicit request if missing. Start stays
disabled until both files are verified and installed. Existing verified private
models from earlier app versions are reused.

The Models page provides status, progress, Cancel, Retry, and Delete. Downloads
start only after the user requests them, use Wi-Fi by default, and offer an
explicit mobile-data choice for each request. Roaming is disabled. Downloading a
model does not select it. One download may run at a time, independently of the app
process, through Android DownloadManager. Transcription can use an installed
model while a download runs. A model in use cannot be deleted. Deleting a selected
model resets the saved model default to Base; Base must also be installed before
starting. Base can be deleted and downloaded again. The small shared detector
remains cached for other models. A Cantonese setup still
requires selecting installed Turbo; a saved Turbo job requires downloading Turbo
again to resume unfinished inference.
Without monetization deployment values, internet is used only for requested
model downloads. Configured builds also use Google Play for purchases and AdMob
for consent-gated free-plan ads. Recordings and transcripts are never sent to
those SDKs. Installed models run in airplane mode. The app requests no microphone or broad storage permission.

Results default to **Plain text**. **Timestamps** formats the same stored segments
without running inference again. Search highlights matches. Copy, Share, and Save
as `.txt` export the entire selected format. Share uses a UTF-8 file to avoid
Binder text size limits. **Transcript history** on the import and result pages
lists every successful result, newest first, with filename and completion date.
Search history by filename or transcript text, then open a result to search its
contents or use Copy/Share/Save in either format. Individual deletion requires
confirmation and keeps other transcripts, unfinished work and exported files.

History stores text, timestamps, filenames and completion dates on the phone.
Android can back up completed history to the user's system backup account and
transfer it to another phone, according to system settings. Cloud backup requires
client-side encryption on Android 9+; Android 7/8 use their system backup
protection. Backup is scheduled by Android, has a 25 MB per-app cloud quota and
is not immediate or a cross-device sync service. Clearing app data/uninstalling
removes the local copy; recovery depends on an available system backup. Keep
exported text files elsewhere as an additional copy. Models, original media,
unfinished work, preferences and signed Pro credentials are excluded.
A failed, paused or discarded job keeps completed history. The original saved
result migrates automatically, preserving its text, timestamps and job ID; its
saved-file modification date supplies the legacy completion date when available.

## Build and tests

No model weights are needed to build. Users download models
inside the installed app.

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
push `ggml-base.bin`, `ggml-silero-v5.1.2.bin`, `ggml-small.bin`, and
`ggml-large-v3-turbo-q5_0.bin` there. The Base and detector fixtures exercise
first-download recovery, reuse, corruption, and deletion in isolated storage.
Ensure the fixture directory is readable by the app (ADB-created directories may
need their permissions adjusted). Use the pinned URLs and checksums below. The native tests verify those hashes again before loading.

`ModelDownloadIntegrationTest.freshBaseAndDetectorDownloadAndAcquire` runs with
`modelDownloadPhase=base` and downloads/verifies Base and the detector into fresh
isolated storage. It skips unless explicitly requested.

`ModelDownloadIntegrationTest` also has explicit `modelDownloadPhase=start` and
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
file. Only verified files are selectable. Startup checks installed speech models
and the shared detector and reconciles persisted DownloadManager IDs; foreground
entry also reconciles. Each request persists the active speech/detector transfer and advances
to the next missing file after verification. Reopening continues an interrupted
request, and retries reuse already verified files. Verification interrupted by
process death retries on reopening.
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

`MediaAudioDecoder`, `PcmConversion`, and `PcmResampler` stream mono 16 kHz PCM,
retaining audio presentation-time gaps and the first offset. `PcmWindows` groups
samples into disjoint 30-second cores with up to one second of detector context
on each side. Each window is written/synced and its record committed before JNI
maps it. Its bounded section plan is saved before inference; completed sections,
including empty ones, are saved atomically with boundaries, original timestamps
and job ID. Decoding blocks while the window is consumed, so no queued PCM can
grow. The first window is transcribed before extraction finishes. A final window
may be shorter, and sources shorter than a core are decoded before inference.

Resume reuses retained window PCM and skips committed sections. After every
section of a window is saved, its processed-sample boundary is committed before
PCM deletion. Auto windows without speech also advance that boundary. Resume
then decodes the source from the beginning, dropping already processed cores.
End-of-stream/sample validation and the finished record are committed before
source deletion. Legacy full-PCM jobs retain their existing recovery path.
Settings include the model hash and engine/section revision. Partial text remains
separate from completed transcript history. Auto uses Silero threshold 0.5,
minimum speech/silence 250 ms, and padding 30 ms. Detected spans are clipped to
ordered, disjoint cores, with inference sections limited to 30 seconds. Long spans
are split without overlapping inference input or dropping audio at a boundary.

Each section detects its highest-probability eligible language. Cantonese is
excluded for Base/Small, and its token is validated for Turbo. Transcription
context resets between sections. One Whisper model and a lazily loaded Auto
detector remain loaded per run and are released on stop/failure/completion.
Native timestamps include both the speech section offset and original audio
offset. Progress reflects decoding or transcription of the current window within
the source timeline; unavailable durations show indeterminate progress. Native abort
callbacks remain active during transcription; model loading, language detection,
and bounded detector passes check cancellation between calls.

Allow storage for the private model files, staged source file, one bounded PCM window of at most 2.048 MB
(plus a temporary file while committing it), and both staged/private copies of downloaded models during installation.
Target a recent ARM64 phone with at least 6 GB RAM. Larger models increase memory
and processing cost; speed and accuracy are not guaranteed. Native resources,
streams and codecs are released on terminal paths. One unfinished job is retained
outside cache and Android backup, including after cancellation or failure.
Reopening offers explicit Resume or Discard and never restarts inference automatically.
Committed windows and sections are reused; decoding restarts from the retained
source and skips processed cores. Older full-PCM jobs reuse completed extraction. After a successful result commit, or explicit Discard, retained media and
section checkpoints are removed. Completed transcript history survives failure,
cancellation and Discard. Shared exports older than one day are cleaned
on startup.

`Transcript` remains the canonical result. `TranscriptFormatter` owns both
formats and export names. `SavedTranscript` adds an ID and completion date.
`TranscriptStore` atomically rewrites `filesDir/transcripts/transcript-history.json`
when appending or deleting a result; this simple local store loads the history
into memory and rewrites its text on changes. The repository serializes storage
operations. Cancellation and successful commits share a commit boundary, with
explicit sync and readback checks before job cleanup. Existing job IDs prevent
retrying a finished job from duplicating a history entry. Migration commits the
history before removing `latest-transcript.json`; unreadable history is retained
and cannot be overwritten by a new result. The view model saves the selected
history ID so rotation and exports keep the opened result.

## Verification limits

Emulator verification covers builds for both ABIs, unit tests, lint, native
fixtures, persistence/exports, download policy and recovery, and atomic model
installation. Current audio-import, progressive-pipeline, transcript-history,
UI/export and legacy-recovery checks passed on the ARM64 API 37 emulator, along
with explicit process-death phases and processing Pause/Resume UI checks.
MP3/raw AAC inputs, physical-device codec backpressure and the transient import
cancellation UI remain unverified; no speed improvement has been measured.

Cantonese accuracy beyond the included fixture and larger-model performance on
physical phones remain unverified. The physical-phone benchmark was not resumed.
Rapid switching within a phrase, noisy speech, long-media resource use, API 24/34
service behavior, and hardware codec differences require broader device testing
before release.

## License

Application code is available under the [MIT License](LICENSE).
Vendored libraries and model weights retain their respective licenses; see
[third-party notices](app/src/main/assets/THIRD_PARTY_NOTICES.txt).
Test recordings have separate terms and attribution in
[fixture licenses](app/src/androidTest/assets/FIXTURE_LICENSES.txt).

## Free plan and Pro

The one-time `pro_unlock` purchase allows background transcription, removes ads
and removes the free limit of
5 minutes per recording, with the app visible and the phone unlocked. Saved history, exports and supported languages/models
are available to both plans. Metadata is checked before inference; decoded
sample counts enforce the limit even when duration metadata is absent or wrong.
Limits never truncate a recording into a successful partial transcript. The
source and saved checkpoints survive a limit failure; Pro can resume them.
Each run takes an access snapshot at Start, so a purchase-status refresh does not
change its limits. Free runs pause through the existing cancellation/checkpoint
path when the app is hidden or the phone locks. Returning does not restart work
automatically: tap Resume. Rotation and transient focus changes do not pause
work. Android process lifecycle has a short delay when detecting backgrounding;
native cancellation stops at an existing safe cancellation point. Pro removes the commercial duration restriction,
not device memory/storage/codec or Android foreground-service limits.

Purchases and free limits activate only when all deployment values are supplied.
Unconfigured builds retain the current unrestricted offline behavior and make no
ad requests. Debug ad requests use
Google's test banner, never a live ad unit. Billing is verified against the Play
RSA public key, acknowledged before unlocking, never consumed and restored from
Google Play on a new installation. A signed device-local cache preserves Pro
offline; an online ownership refresh handles revocations. This uses client-side
verification; there is no billing backend or cross-platform entitlement service.
