package inego.zungjunggame.engine

/** Decision the current player must make. */
enum class DecisionType {
    /**
     * Act on another player's discard: chow / pung / kong / hu / pass.
     * Also the kong-rob window ([GameState.kongRobWindow]): Hu / Pass only.
     */
    ReactToDiscard,

    /** Act on own 14-tile hand: promote kong, declare hu, or discard. */
    ActOnHand,
}

/** Type of action represented by a [Choice]. */
enum class ChoiceKind {
    Pass,
    Discard,
    Hu,
    Chow,
    Pung,
    Kong,
    PromoteKong,
    ConcealedKong,
    /**
     * Decline declaring a concealed kong of [payload] tile type for this ActOnHand
     * stint. First-class engine choice (not UI-only): sequential per four-of-a-kind
     * before discards are offered.
     */
    Keep,
}

/**
 * A single choice a player can make at a decision point.
 *
 * [payload] is a tile type for discard/pung/kong/promote/concealed/keep, a chow
 * first-tile for chow, or a hand score for hu.
 */
data class Choice(
    val kind: ChoiceKind = ChoiceKind.Pass,
    val payload: Int = 0,
) {
    fun tile(): Int {
        check(
            kind == ChoiceKind.Discard ||
                kind == ChoiceKind.Pung ||
                kind == ChoiceKind.Kong ||
                kind == ChoiceKind.PromoteKong ||
                kind == ChoiceKind.ConcealedKong ||
                kind == ChoiceKind.Keep,
        ) { "tile() only valid for discard/pung/kong/keep choices, got $kind" }
        return payload
    }

    fun huScore(): Int {
        check(kind == ChoiceKind.Hu) { "huScore() only valid for Hu, got $kind" }
        return payload
    }

    fun chowFirstTile(): Int {
        check(kind == ChoiceKind.Chow) { "chowFirstTile() only valid for Chow, got $kind" }
        return payload
    }

    companion object {
        fun pass(): Choice = Choice()

        fun discard(tile: Int): Choice = make(ChoiceKind.Discard, tile)

        fun hu(score: Int): Choice = make(ChoiceKind.Hu, score)

        fun chow(firstTile: Int): Choice = make(ChoiceKind.Chow, firstTile)

        fun pung(tile: Int): Choice = make(ChoiceKind.Pung, tile)

        fun kong(tile: Int): Choice = make(ChoiceKind.Kong, tile)

        fun promoteKong(tile: Int): Choice = make(ChoiceKind.PromoteKong, tile)

        fun concealedKong(tile: Int): Choice = make(ChoiceKind.ConcealedKong, tile)

        /** Decline concealed kong of [tile] (must currently hold four of that type). */
        fun keep(tile: Int): Choice = make(ChoiceKind.Keep, tile)

        private fun make(kind: ChoiceKind, payload: Int): Choice {
            require(payload in 0..0xFFFF) { "payload out of range: $payload" }
            return Choice(kind, payload)
        }
    }
}

/** Compact fixed-capacity list of legal choices. */
class ChoiceList : Iterable<Choice> {
    private val entries = Array(MAX_LEGAL_CHOICES) { Choice.pass() }
    var count: Int = 0
        private set

    val size: Int get() = count
    fun isEmpty(): Boolean = count == 0

    operator fun get(index: Int): Choice {
        require(index in 0 until count) { "index $index out of range 0..${count - 1}" }
        return entries[index]
    }

    override fun iterator(): Iterator<Choice> = object : Iterator<Choice> {
        private var index = 0

        override fun hasNext(): Boolean = index < count

        override fun next(): Choice {
            if (index >= count) throw NoSuchElementException()
            return entries[index++]
        }
    }

    fun pushBack(choice: Choice) {
        check(count < entries.size) { "ChoiceList full" }
        entries[count++] = choice
    }

    /** Drop all entries without reallocating the backing array (MC playout hot path). */
    fun clear() {
        count = 0
    }

    fun asList(): List<Choice> = List(count) { entries[it] }
}
