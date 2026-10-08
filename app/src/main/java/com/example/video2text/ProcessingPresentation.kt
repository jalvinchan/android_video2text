package com.example.video2text

internal fun TranscriptionState.workVisibilityExplanation(): String = when {
    activeOptions == null -> "Importing media. Keep this app open until the copy is saved."
    !activeBackgroundAllowed -> "Keep this app visible and your phone unlocked. Free transcription pauses if you switch apps or lock the phone; saved progress is kept."
    else -> "Processing continues with the screen locked. You can leave this app."
}

// Share stop wording between the processing screen and foreground notification.
internal fun TranscriptionState.stopActionLabel(): String = when {
    activeOptions == null -> "Cancel import"
    pendingJob?.hasAudio == true || pendingJob?.progressive == true -> "Pause transcription"
    else -> "Stop for now"
}

internal fun TranscriptionState.stoppingLabel(): String = when {
    activeOptions == null -> "Cancelling import…"
    pendingJob?.hasAudio == true || pendingJob?.progressive == true -> "Pausing transcription…"
    else -> "Stopping…"
}

internal fun TranscriptionState.stopExplanation(): String = when {
    activeOptions == null -> "Cancelling discards the incomplete copy. You will need to import the file again."
    pendingJob?.progressive == true -> "Pausing keeps the source, the current audio window and completed speech sections. Resume reuses the window, repeats any unfinished section and decodes remaining audio. Each section contains at most 30 seconds of audio."
    pendingJob?.hasAudio == true -> "Pausing keeps saved audio and completed speech sections. Resume repeats any unfinished section. Each section contains at most 30 seconds of audio."
    else -> "Stopping keeps the saved source file. Resume restarts audio extraction from the beginning."
}

internal fun TranscriptionJob.resumeExplanation(): String = when {
    progressive && extractionComplete -> "All audio windows and speech sections are saved. Resume finishes saving the result without repeating transcription."
    progressive -> "Resume keeps completed speech sections and reuses any saved audio window, repeating an unfinished section. Decoding restarts from the saved source and skips already processed windows. Each section contains at most 30 seconds of audio."
    !hasAudio -> "Resume restarts audio extraction from the beginning using the saved source file."
    sections == null -> "Resume reuses the saved audio and starts transcription."
    completedSections < sections.size -> "Resume continues with speech section ${completedSections + 1}, repeating any unfinished work in that section. Each section contains at most 30 seconds of audio."
    else -> "All speech sections are saved. Resume finishes saving the result without repeating transcription."
}

internal fun ProcessingStage.label(): String = when (this) {
    ProcessingStage.IDLE -> "Ready to import"
    ProcessingStage.PREPARING -> "Preparing media or offline model"
    ProcessingStage.EXTRACTING, ProcessingStage.TRANSCRIBING -> "Transcribing speech"
    ProcessingStage.COMPLETED -> "Transcription complete"
    ProcessingStage.CANCELLED -> "Processing stopped"
    ProcessingStage.FAILED -> "Unable to finish transcription"
}
