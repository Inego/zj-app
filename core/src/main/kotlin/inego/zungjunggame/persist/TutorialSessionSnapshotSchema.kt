package inego.zungjunggame.persist

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.table.HighlightGroup
import inego.zungjunggame.render.table.HighlightWhere
import inego.zungjunggame.render.tiles.TileFocusGlow
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialFocus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * versioned binary codec for [TutorialSessionSnapshot], the `session.tutorial`
 * key.
 *
 * Pure JVM (no libGDX). Game-state field order reuses [SessionSnapshotSchema] so
 * board restore matches free-play hydrate. Not the authoring format for
 * scenarios.
 *
 * A payload written by an older version is not migrated: its embedded game state
 * used the old meld layout, so [decode] accepts only [version] and a mid-lesson
 * save from an older build falls back to the key's default.
 */
object TutorialSessionSnapshotSchema : Schema<TutorialSessionSnapshot> {
    /**
     * Bump when the layout **or** campaign-relevant payload meaning changes.
     * v1: lesson id + director progress + full [GameState].
     * v2: orient-tiles + TileTypes.
     * v3: [TutorialFocus] (target + optional callout) replaces bare spotlight string.
     * v4: compact TOC scroll + drawer open/closed.
     * v5: compact pull-tab vertical ratio ([TutorialSessionSnapshot.tocPullTabYFrac]).
     * v6: dynamic quiz hand tiles + quizAwaitingAdvance (Next / Try again).
     * v7: full [QuizInteractionState] (selection + Show answer / fail / pass flags).
     * v8: single [WallBuildDeal] for the hosted lesson.
     * v9: wall-build deals parked by lesson id (survive leaving the wall unit).
     * v10: [TutorialFocus.highlight] ([BoardHighlight] groups).
     * v11: GameState kong incidental flags (9.2 / 9.3) via [SessionSnapshotSchema] v4.
     * v12: GameState seabedPlayer + handEnded via [SessionSnapshotSchema] v5.
     * v13: the TOC scroll / drawer / pull-tab fields move to the shell route's UI
     *   state; a v12 payload still decodes, its TOC fields discarded.
     * v14: the embedded game state's player melds became an ordered exposure log
     *   ([SessionSnapshotSchema] v7); older payloads predate that layout and drop.
     */
    override val version: Int = 14

    private const val MAGIC: Int = 0x5A4A5431 // "ZJT1"

    override fun encode(value: TutorialSessionSnapshot): ByteArray {
        val out = ByteArrayOutputStream(4096)
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(version)
            writeUtf(d, value.lessonId)
            d.writeInt(value.stepIndex)
            d.writeInt(value.status.ordinal)
            d.writeBoolean(value.awaitingCoach)
            d.writeBoolean(value.awaitingQuiz)
            d.writeBoolean(value.quizFeedbackFail)
            writeNullableUtf(d, value.lastCoachKey)
            writeFocus(d, value.lastFocus)
            d.writeInt(value.lastDiagram.ordinal)
            writeNullableUtf(d, value.failureMessage)
            writeNullableUtf(d, value.pendingLinkPageId)
            SessionSnapshotSchema.writeGameState(d, value.game)
            writeIntArray(d, value.quizHandTiles)
            d.writeBoolean(value.quizAwaitingAdvance)
            writeQuizInteraction(d, value.quizInteraction)
            writeWallBuildDeals(d, value.wallBuildDeals)
        }
        return out.toByteArray()
    }

    /**
     * @return decoded snapshot, or null if the payload is corrupt, truncated, or
     *   not written by [version]
     */
    override fun decode(bytes: ByteArray): TutorialSessionSnapshot? {
        if (bytes.isEmpty()) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val magic = d.readInt()
                if (magic != MAGIC) return null
                if (d.readInt() != version) return null
                val lessonId = readUtf(d)
                if (lessonId.isBlank()) return null
                val stepIndex = d.readInt()
                val status = statusFromOrdinal(d.readInt()) ?: return null
                val awaitingCoach = d.readBoolean()
                val awaitingQuiz = d.readBoolean()
                val quizFeedbackFail = d.readBoolean()
                val lastCoachKey = readNullableUtf(d)
                val lastFocus = readFocus(d)
                val lastDiagram = diagramFromOrdinal(d.readInt()) ?: return null
                val failureMessage = readNullableUtf(d)
                val pendingLinkPageId = readNullableUtf(d)
                val game = SessionSnapshotSchema.readGameState(d)
                val quizHandTiles = readIntArray(d)
                val quizAwaitingAdvance = d.readBoolean()
                val quizInteraction = readQuizInteraction(d)
                val wallBuildDeals = readWallBuildDeals(d)
                TutorialSessionSnapshot(
                    lessonId = lessonId,
                    stepIndex = stepIndex,
                    status = status,
                    awaitingCoach = awaitingCoach,
                    awaitingQuiz = awaitingQuiz,
                    quizFeedbackFail = quizFeedbackFail,
                    lastCoachKey = lastCoachKey,
                    lastFocus = lastFocus,
                    lastDiagram = lastDiagram,
                    failureMessage = failureMessage,
                    pendingLinkPageId = pendingLinkPageId,
                    game = game,
                    quizHandTiles = quizHandTiles,
                    quizAwaitingAdvance = quizAwaitingAdvance,
                    quizInteraction = quizInteraction,
                    wallBuildDeals = wallBuildDeals,
                )
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun writeWallBuildDeals(d: DataOutputStream, deals: Map<String, WallBuildDeal>) {
        require(deals.size <= 32) { "too many wall deals ${deals.size}" }
        d.writeInt(deals.size)
        for (id in deals.keys.sorted()) {
            writeUtf(d, id)
            writeWallBuildDeal(d, deals.getValue(id))
        }
    }

    fun readWallBuildDeals(d: DataInputStream): Map<String, WallBuildDeal> {
        val n = d.readInt()
        require(n in 0..32) { "wall deals size $n" }
        if (n == 0) return emptyMap()
        val out = LinkedHashMap<String, WallBuildDeal>(n)
        repeat(n) {
            val id = readUtf(d)
            require(id.isNotBlank()) { "blank wall-deal lesson id" }
            val deal = readWallBuildDeal(d)
            require(deal != null) { "null wall deal for $id" }
            out[id] = deal
        }
        return out
    }

    fun writeWallBuildDeal(d: DataOutputStream, deal: WallBuildDeal?) {
        if (deal == null) {
            d.writeBoolean(false)
            return
        }
        d.writeBoolean(true)
        d.writeByte(deal.faceA)
        d.writeByte(deal.faceB)
        require(deal.slotToTile.size == WALL_SIZE) { "wall deal size" }
        for (v in deal.slotToTile) d.writeShort(v)
    }

    fun readWallBuildDeal(d: DataInputStream): WallBuildDeal? {
        if (!d.readBoolean()) return null
        val faceA = d.readUnsignedByte()
        val faceB = d.readUnsignedByte()
        val map = IntArray(WALL_SIZE) { d.readUnsignedShort() }
        return WallBuildDeal(faceA = faceA, faceB = faceB, slotToTile = map)
    }

    /** Encode optional full quiz attempt state (all kinds). */
    fun writeQuizInteraction(d: DataOutputStream, state: QuizInteractionState?) {
        if (state == null) {
            d.writeBoolean(false)
            return
        }
        d.writeBoolean(true)
        writeQuizAnswer(d, state.answer)
        writeNullableUtf(d, state.selectedForBinId)
        d.writeBoolean(state.failVisible)
        d.writeBoolean(state.answerRevealed)
        d.writeBoolean(state.showAnswerConsumed)
        d.writeBoolean(state.passed)
    }

    fun readQuizInteraction(d: DataInputStream): QuizInteractionState? {
        if (!d.readBoolean()) return null
        val answer = readQuizAnswer(d)
        val selectedForBinId = readNullableUtf(d)
        val failVisible = d.readBoolean()
        val answerRevealed = d.readBoolean()
        val showAnswerConsumed = d.readBoolean()
        val passed = d.readBoolean()
        return QuizInteractionState(
            answer = answer,
            selectedForBinId = selectedForBinId,
            failVisible = failVisible,
            answerRevealed = answerRevealed,
            showAnswerConsumed = showAnswerConsumed,
            passed = passed,
        )
    }

    private fun writeQuizAnswer(d: DataOutputStream, answer: QuizAnswer) {
        writeStringSet(d, answer.selectedIds)
        writeStringMap(d, answer.binByOptionId)
    }

    private fun readQuizAnswer(d: DataInputStream): QuizAnswer =
        QuizAnswer(
            selectedIds = readStringSet(d),
            binByOptionId = readStringMap(d),
        )

    private fun writeStringSet(d: DataOutputStream, values: Set<String>) {
        require(values.size <= 256) { "string set too long ${values.size}" }
        d.writeInt(values.size)
        // Stable order for deterministic blobs / easier tests.
        for (v in values.sorted()) writeUtf(d, v)
    }

    private fun readStringSet(d: DataInputStream): Set<String> {
        val n = d.readInt()
        require(n in 0..256) { "string set size $n" }
        if (n == 0) return emptySet()
        val out = LinkedHashSet<String>(n)
        repeat(n) {
            val s = readUtf(d)
            require(s.isNotBlank()) { "blank set entry" }
            out += s
        }
        return out
    }

    private fun writeStringMap(d: DataOutputStream, map: Map<String, String>) {
        require(map.size <= 256) { "string map too long ${map.size}" }
        d.writeInt(map.size)
        for (k in map.keys.sorted()) {
            writeUtf(d, k)
            writeUtf(d, map.getValue(k))
        }
    }

    private fun readStringMap(d: DataInputStream): Map<String, String> {
        val n = d.readInt()
        require(n in 0..256) { "string map size $n" }
        if (n == 0) return emptyMap()
        val out = LinkedHashMap<String, String>(n)
        repeat(n) {
            val k = readUtf(d)
            val v = readUtf(d)
            require(k.isNotBlank() && v.isNotBlank()) { "blank map entry" }
            out[k] = v
        }
        return out
    }

    private fun writeIntArray(d: DataOutputStream, values: IntArray?) {
        if (values == null) {
            d.writeInt(-1)
            return
        }
        require(values.size <= 256) { "int array too long ${values.size}" }
        d.writeInt(values.size)
        for (v in values) d.writeInt(v)
    }

    private fun readIntArray(d: DataInputStream): IntArray? {
        val n = d.readInt()
        if (n < 0) return null
        require(n <= 256) { "int array too long $n" }
        val out = IntArray(n)
        for (i in 0 until n) out[i] = d.readInt()
        return out
    }

    private fun statusFromOrdinal(ordinal: Int): TutorialDirector.Status? {
        val values = TutorialDirector.Status.entries
        if (ordinal !in values.indices) return null
        return values[ordinal]
    }

    private fun diagramFromOrdinal(ordinal: Int): TutorialDiagram? {
        val values = TutorialDiagram.entries
        if (ordinal !in values.indices) return null
        return values[ordinal]
    }

    private fun writeUtf(d: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_535) { "utf too long ${bytes.size}" }
        d.writeShort(bytes.size)
        d.write(bytes)
    }

    private fun readUtf(d: DataInputStream): String {
        val n = d.readUnsignedShort()
        val bytes = ByteArray(n)
        d.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeNullableUtf(d: DataOutputStream, value: String?) {
        if (value == null) {
            d.writeBoolean(false)
        } else {
            d.writeBoolean(true)
            writeUtf(d, value)
        }
    }

    private fun readNullableUtf(d: DataInputStream): String? {
        if (!d.readBoolean()) return null
        return readUtf(d)
    }

    private fun writeFocus(d: DataOutputStream, focus: TutorialFocus?) {
        if (focus == null) {
            d.writeBoolean(false)
        } else {
            d.writeBoolean(true)
            writeUtf(d, focus.targetId)
            writeNullableUtf(d, focus.calloutKey)
            writeHighlight(d, focus.highlight)
        }
    }

    private fun readFocus(d: DataInputStream): TutorialFocus? {
        if (!d.readBoolean()) return null
        val targetId = readUtf(d)
        if (targetId.isBlank()) throw IllegalArgumentException("blank focus target")
        val calloutKey = readNullableUtf(d)
        val highlight = readHighlight(d)
        return TutorialFocus(targetId = targetId, calloutKey = calloutKey, highlight = highlight)
    }

    private fun writeHighlight(d: DataOutputStream, highlight: BoardHighlight?) {
        if (highlight == null) {
            d.writeBoolean(false)
            return
        }
        d.writeBoolean(true)
        require(highlight.groups.size <= 16) { "highlight groups ${highlight.groups.size}" }
        d.writeByte(highlight.groups.size)
        for ((types, glow, where) in highlight.groups) {
            d.writeByte(glow.ordinal)
            d.writeByte(where.ordinal)
            require(types.size <= NUM_TILE_TYPES) { "highlight types ${types.size}" }
            d.writeByte(types.size)
            for (t in types) d.writeByte(t)
        }
    }

    private fun readHighlight(d: DataInputStream): BoardHighlight? {
        if (!d.readBoolean()) return null
        val n = d.readUnsignedByte()
        require(n in 0..16) { "highlight groups $n" }
        if (n == 0) return BoardHighlight.None
        val groups = ArrayList<HighlightGroup>(n)
        val glows = TileFocusGlow.entries
        val wheres = HighlightWhere.entries
        repeat(n) {
            val glowOrd = d.readUnsignedByte()
            require(glowOrd in glows.indices) { "glow ordinal $glowOrd" }
            val glow = glows[glowOrd]
            val whereOrd = d.readUnsignedByte()
            require(whereOrd in wheres.indices) { "where ordinal $whereOrd" }
            val where = wheres[whereOrd]
            val typeCount = d.readUnsignedByte()
            require(typeCount in 0..NUM_TILE_TYPES) { "highlight types $typeCount" }
            val types = List(typeCount) {
                val t = d.readUnsignedByte()
                require(t in 0 until NUM_TILE_TYPES) { "tile type $t" }
                t
            }
            groups += HighlightGroup(types = types, glow = glow, where = where)
        }
        return BoardHighlight(groups)
    }
}

/** Persisted mid-lesson tutorial session: namespace `session`, name `tutorial`. */
val TutorialSessionSnapshotKey: StateKey<TutorialSessionSnapshot> = StateKey(
    namespace = "session",
    name = "tutorial",
    schema = TutorialSessionSnapshotSchema,
)
