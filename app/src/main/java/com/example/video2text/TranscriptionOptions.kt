package com.example.video2text

enum class SpeechLanguage(val label: String, val code: String) {
    AUTO("Auto", "auto"), ENGLISH("English", "en"), MANDARIN("Chinese (Mandarin)", "zh"), CANTONESE("Cantonese", "yue");
}

enum class SpeechModel(val label: String, val fileName: String, val bytes: Long, val sha256: String, val downloadMiB: Int) {
    BASE("Base", "ggml-base.bin", 147951465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", 142),
    SMALL("Small", "ggml-small.bin", 487601967, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", 465),
    TURBO("Turbo", "ggml-large-v3-turbo-q5_0.bin", 574041195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", 547);

    fun supports(language: SpeechLanguage): Boolean = language != SpeechLanguage.CANTONESE || supportsCantonese

    val supportsCantonese: Boolean get() = this == TURBO
    val languageSummary: String get() = if (supportsCantonese) "English, Mandarin and Cantonese" else "English and Mandarin"
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/$fileName"
}

data class TranscriptionOptions(val language: SpeechLanguage = SpeechLanguage.AUTO, val model: SpeechModel = SpeechModel.BASE) {
    fun validate() {
        require(model.supports(language)) { "Cantonese requires the installed Turbo model." }
    }
    val label: String get() = "${language.label} · ${model.label}"
}
