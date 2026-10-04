package com.example.video2text

// Share stop wording between the processing screen and foreground notification.
internal fun TranscriptionState.stopActionLabel(): String = when {
    activeOptions == null -> "Cancel import"
    pendingJob?.hasAudio == true -> "Pause transcription"
    else -> "Stop for now"
}

internal fun TranscriptionState.stoppingLabel(): String = when {
    activeOptions == null -> "Cancelling import…"
    pendingJob?.hasAudio == true -> "Pausing transcription…"
    else -> "Stopping…"
}

internal fun TranscriptionState.stopExplanation(): String = when {
    activeOptions == null -> "Cancelling discards the incomplete copy. You will need to import the file again."
    pendingJob?.hasAudio == true -> "Pausing keeps saved audio and completed speech sections. Resume repeats any unfinished section. Each section contains at most 30 seconds of audio."
    else -> "Stopping keeps the saved source file. Resume restarts audio extraction from the beginning."
}

internal fun TranscriptionJob.resumeExplanation(): String = when {
    !hasAudio -> "Resume restarts audio extraction from the beginning using the saved source file."
    sections == null -> "Resume reuses the saved audio and starts transcription."
    completedSections < sections.size -> "Resume continues with speech section ${completedSections + 1}, repeating any unfinished work in that section. Each section contains at most 30 seconds of audio."
    else -> "All speech sections are saved. Resume finishes saving the result without repeating transcription."
}

internal fun ProcessingStage.label(job: TranscriptionJob? = null): String = when (this) {
    ProcessingStage.IDLE -> "Ready to import"
    ProcessingStage.PREPARING -> "Preparing media or offline model"
    ProcessingStage.EXTRACTING -> if (job?.sourceHasVideo == false) "Preparing audio" else "Extracting audio"
    ProcessingStage.TRANSCRIBING -> "Transcribing speech"
    ProcessingStage.COMPLETED -> "Transcription complete"
    ProcessingStage.CANCELLED -> "Processing stopped"
    ProcessingStage.FAILED -> "Unable to finish transcription"
}
