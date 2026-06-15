package com.customblocker.domain.engine

/** A captured notification reduced to the fields the suggester / preview need. */
data class NotifSample(val packageName: String, val title: String, val text: String)

/** A suggested filter pattern derived from recurring text in an app's notifications. */
data class PatternSuggestion(
    /** Human-readable phrase the regex matches (lowercased, punctuation-stripped). */
    val phrase: String,
    /** A valid regex (the phrase as an escaped literal). */
    val regex: String,
    /** Which field the phrase was found in. */
    val target: NotifMatchTarget,
    /** How many of the app's recent notifications this phrase matches. */
    val matchCount: Int,
)

/**
 * Suggests regex patterns by mining recurring phrases from an app's recent notifications. The point:
 * a user gets many similar notifications (e.g. Snapchat — title is a name, content is "sent you a
 * snap"); the varying part (the name) appears once, the shared part ("sent you a snap") recurs, so
 * the recurring phrase is exactly what a "block all of these" filter should match.
 *
 * Pure + deterministic so it can be unit-tested and run on the compose thread (inputs are small:
 * ≤200 short strings).
 */
object NotificationPatternSuggester {

    /** Word-sequences up to this many words are considered as candidate phrases. */
    private const val MAX_PHRASE_WORDS = 6

    /** Tiny words that aren't useful as a standalone suggestion. */
    private val STOPWORDS = setOf(
        "a", "an", "the", "you", "your", "to", "of", "in", "on", "for", "and", "or", "is", "are",
        "was", "it", "me", "my", "i", "we", "at", "be", "has", "have", "new",
    )

    /**
     * Phrase suggestions for [packageName], best first. When [focus] is given, only phrases that also
     * appear in that example notification are returned (so suggestions match the one the user tapped);
     * otherwise the strongest recurring phrases across the app are returned.
     */
    fun suggest(
        samples: List<NotifSample>,
        packageName: String,
        focus: NotifSample? = null,
        max: Int = 6,
    ): List<PatternSuggestion> {
        val pool = samples.filter { it.packageName == packageName }
        if (pool.size < 2) return emptyList()

        val candidates = buildList {
            addAll(candidatesFor(pool, focus, NotifMatchTarget.CONTENT) { it.text })
            addAll(candidatesFor(pool, focus, NotifMatchTarget.TITLE) { it.title })
        }

        // Drop a phrase if a longer phrase with the same coverage subsumes it (word-subsequence).
        val kept = candidates
            .sortedWith(compareByDescending<PatternSuggestion> { it.matchCount }.thenByDescending { it.phrase.length })
            .filter { cand ->
                candidates.none { other ->
                    other !== cand &&
                        other.target == cand.target &&
                        other.matchCount >= cand.matchCount &&
                        other.phrase.length > cand.phrase.length &&
                        other.phrase.contains(cand.phrase)
                }
            }

        return kept.distinctBy { it.target to it.phrase }.take(max)
    }

    private fun candidatesFor(
        pool: List<NotifSample>,
        focus: NotifSample?,
        target: NotifMatchTarget,
        field: (NotifSample) -> String,
    ): List<PatternSuggestion> {
        // Document frequency: count of distinct notifications whose field contains each phrase.
        val df = HashMap<String, Int>()
        pool.forEach { sample ->
            phrasesOf(field(sample)).forEach { phrase -> df[phrase] = (df[phrase] ?: 0) + 1 }
        }
        val focusPhrases = focus?.let { phrasesOf(field(it)).toSet() }
        return df.entries
            .asSequence()
            .filter { (phrase, count) ->
                val words = phrase.split(' ')
                count >= 2 &&                              // recurs across notifications
                    words.first() !in STOPWORDS &&         // not a fragment that starts on a stopword
                    words.last() !in STOPWORDS &&          // ...or ends on one ("sent you a")
                    (words.size >= 2 || phrase.length >= 5) && // single words must be substantial
                    (focusPhrases == null || phrase in focusPhrases)
            }
            .map { (phrase, count) ->
                // Words are letters/digits only (punctuation was stripped), so join them with \W+ to
                // tolerate the punctuation/whitespace that separated them in the original text.
                val regex = phrase.split(' ').joinToString("""\W+""")
                PatternSuggestion(phrase = phrase, regex = regex, target = target, matchCount = count)
            }
            .toList()
    }

    /** Lowercase, strip punctuation/emoji to spaces, then emit every 1..N-word contiguous run. */
    private fun phrasesOf(text: String): Set<String> {
        val words = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (start in words.indices) {
            val end = minOf(start + MAX_PHRASE_WORDS, words.size)
            for (stop in start + 1..end) {
                out += words.subList(start, stop).joinToString(" ")
            }
        }
        return out
    }
}
