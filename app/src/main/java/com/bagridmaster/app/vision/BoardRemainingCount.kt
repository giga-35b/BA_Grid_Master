package com.bagridmaster.app.vision

data class BoardRemainingCountEvidence(
    val remaining: Int,
    val total: Int,
    val confidence: Double,
    val rawText: String,
)

/** Parses only the board-sized n/45 token. Arbitrary header numbers never become a count prior. */
internal fun parseBoardRemainingCount(text: String): BoardRemainingCountEvidence? {
    val normalized = buildString(text.length) {
        text.forEach { character -> append(when (character) {
            in '０'..'９' -> '0' + (character - '０')
            '／' -> '/'
            else -> character
        }) }
    }
    val matches = Regex("(?<![0-9])([0-9]{1,2})\\s*/\\s*45(?![0-9])").findAll(normalized).toList()
    if (matches.size != 1) return null
    val remaining = matches.single().groupValues[1].toIntOrNull() ?: return null
    if (remaining !in 0..45) return null
    val exact = normalized.trim() == matches.single().value
    val confidence = if (exact) 0.92 else 0.82
    return BoardRemainingCountEvidence(remaining, 45, confidence, text.take(80))
}
