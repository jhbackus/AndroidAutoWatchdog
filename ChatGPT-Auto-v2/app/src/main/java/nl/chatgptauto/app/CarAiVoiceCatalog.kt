package nl.chatgptauto.app

/** The user-facing Alaive voice catalogue, shared by phone and Android Auto. */
object CarAiVoiceCatalog {
    data class Voice(val label: String, val realtimeVoice: String, val direction: String)

    val voices = listOf(
        Voice("Emma Natural", "coral", "zacht, warm en natuurlijk"),
        Voice("Fenna", "marin", "helder en levendig"),
        Voice("Colette", "shimmer", "warm, rustig en zwoel"),
        Voice("Maarten Donker", "cedar", "diep en zwaar"),
        Voice("Cenobiet", "cedar", "zeer laag, koud en dreigend, maar goed verstaanbaar"),
        Voice("Coco", "marin", "vrolijk, speels en warm"),
        Voice("Mijn stem", "shimmer", "persoonlijk, rustig en natuurlijk"),
        Voice("Gwendolien", "marin", "helder en natuurlijk"),
        Voice("Ruth", "shimmer", "warm en rustig"),
        Voice("Paultje", "cedar", "zeer laag en sterk schor"),
        Voice("Dikkie dik", "cedar", "zwaar en vol")
    )

    fun selected(label: String?, legacyVoice: String?): Voice =
        voices.firstOrNull { it.label == label }
            ?: voices.firstOrNull { it.realtimeVoice == legacyVoice }
            ?: voices.first()
}
