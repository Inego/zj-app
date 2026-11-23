package inego.zungjunggame.persist

import inego.zungjunggame.engine.BotLevel
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout
import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.engine.HandScoreRow
import inego.zungjunggame.engine.HintMode
import inego.zungjunggame.engine.NUM_CHOW_TYPES
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerLayout
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.WALL_SIZE
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import inego.zungjunggame.state.Migration
import inego.zungjunggame.state.MigrationResult
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Versioned binary codec for [SessionSnapshot], the `session.freeplay` key.
 *
 * Game-state restore builds a [GameStateLayout] and calls [GameState.hydrateFrom]
 * (shared with tutorial [GameState.fromLayout]). This is **not** the authoring
 * format for tutorial scenarios.
 *
 * A payload written by a version with no migration falls back to the key's
 * default. The one migration is v7→v8, which appends the bot level; it is
 * possible because that field is the payload's tail.
 *
 * Pure JVM (no libGDX) so unit tests can round-trip without a backend.
 */
object SessionSnapshotSchema : Schema<SessionSnapshot> {
    /**
     * Bump when the layout changes.
     * v2: game rounds + allowChickenHands.
     * v3: declinedConcealedKong flags (sequential concealed-kong Keep).
     * v4: lastDrawWasKongSupplement + kongRobWindow (9.2 / 9.3).
     * v5: seabedPlayer + handEnded (explicit end of hand; 9.1.1 / 9.1.2).
     * v6: per-hand scored patterns (the hands table's Patterns column).
     * v7: the player's exposed melds became an ordered log (exposure order).
     * v8: bot level, the payload's tail field (v7 migrates as Standard).
     * v9: hint mode, the payload's tail field (v8 migrates as Standard bot).
     */
    override val version: Int = 9

    private const val MAGIC: Int = 0x5A4A5331 // "ZJS1"

    override fun encode(value: SessionSnapshot): ByteArray {
        val out = ByteArrayOutputStream(4096)
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(version)
            d.writeInt(value.nextEastSeat)
            d.writeBoolean(value.currentHandRecorded)
            d.writeLong(value.dealSeed)
            // rounds: -1 = unlimited
            d.writeInt(value.rounds ?: -1)
            d.writeBoolean(value.game.allowChickenHands)
            writeScoreRows(d, value.scoreRows)
            writeGameState(d, value.game)
            d.writeUTF(value.botLevel.id)
            // Tail field: this is what lets the v8→v9 migration be an append.
            d.writeUTF(value.hintMode.id)
        }
        return out.toByteArray()
    }

    /**
     * Step migrations (`baseVersion = 7`):
     * - v7→v8 appends [BotLevel.DEFAULT] and updates the embedded version int to 8.
     * - v8→v9 appends [HintMode.DEFAULT] and updates the embedded version int to 9.
     */
    override val migrations: List<Migration> = listOf(
        Migration { fromVersion, bytes ->
            if (fromVersion != 7 || bytes.size < 8) {
                MigrationResult.Drop
            } else {
                appendTailUtf(bytes, BotLevel.DEFAULT.id, targetVersion = 8)
            }
        },
        Migration { fromVersion, bytes ->
            if (fromVersion != 8 || bytes.size < 8) {
                MigrationResult.Drop
            } else {
                appendTailUtf(bytes, HintMode.DEFAULT.id, targetVersion = 9)
            }
        },
    )

    private fun appendTailUtf(bytes: ByteArray, value: String, targetVersion: Int): MigrationResult {
        val out = ByteArrayOutputStream(bytes.size + 2 + value.length)
        out.write(bytes)
        DataOutputStream(out).use { it.writeUTF(value) }
        val migrated = out.toByteArray()
        ByteBuffer.wrap(migrated).putInt(4, targetVersion)
        return MigrationResult.Migrated(migrated)
    }

    /**
     * @return decoded snapshot, or null if the payload is corrupt, truncated, or
     *   not written by [version]
     */
    override fun decode(bytes: ByteArray): SessionSnapshot? {
        if (bytes.isEmpty()) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val magic = d.readInt()
                if (magic != MAGIC) return null
                if (d.readInt() != version) return null
                val nextEastSeat = d.readInt()
                val currentHandRecorded = d.readBoolean()
                val dealSeed = d.readLong()
                val roundsRaw = d.readInt()
                val rounds = if (roundsRaw < 0) null else roundsRaw
                val allowChickenHands = d.readBoolean()
                val scoreRows = readScoreRows(d)
                val game = readGameState(d)
                game.allowChickenHands = allowChickenHands
                val botLevel = BotLevel.fromId(d.readUTF())
                val hintMode = HintMode.fromId(d.readUTF())
                SessionSnapshot(
                    scoreRows = scoreRows,
                    game = game,
                    nextEastSeat = nextEastSeat,
                    currentHandRecorded = currentHandRecorded,
                    dealSeed = dealSeed,
                    rounds = rounds,
                    botLevel = botLevel,
                    hintMode = hintMode,
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

    private fun writeScoreRows(d: DataOutputStream, rows: List<HandScoreRow>) {
        d.writeInt(rows.size)
        for (row in rows) {
            d.writeInt(row.handNumber)
            d.writeInt(row.handValue)
            d.writeInt(row.winnerSeat)
            d.writeInt(row.discarderSeat)
            writeIntArray(d, row.deltas)
            writeIntArray(d, row.runningTotals)
            writePatterns(d, row.patterns)
        }
    }

    private fun readScoreRows(d: DataInputStream): List<HandScoreRow> {
        val n = d.readInt()
        require(n in 0..10_000) { "score row count $n" }
        val rows = ArrayList<HandScoreRow>(n)
        repeat(n) {
            val handNumber = d.readInt()
            val handValue = d.readInt()
            val winnerSeat = d.readInt()
            val discarderSeat = d.readInt()
            val deltas = readIntArray(d, NUM_PLAYERS)
            val runningTotals = readIntArray(d, NUM_PLAYERS)
            rows += HandScoreRow(
                handNumber = handNumber,
                handValue = handValue,
                winnerSeat = winnerSeat,
                discarderSeat = discarderSeat,
                deltas = deltas,
                runningTotals = runningTotals,
                patterns = readPatterns(d),
            )
        }
        return rows
    }

    /** [HandScoreComponent.formatArgs] are engine ints; persist and rebuild them as such. */
    private fun writePatterns(d: DataOutputStream, patterns: List<HandScoreComponent>) {
        d.writeInt(patterns.size)
        for (c in patterns) {
            d.writeUTF(c.id)
            d.writeInt(c.points)
            d.writeInt(c.formatArgs.size)
            for (arg in c.formatArgs) d.writeInt((arg as? Int) ?: 0)
        }
    }

    private fun readPatterns(d: DataInputStream): List<HandScoreComponent> {
        val n = d.readInt()
        require(n in 0..1_000) { "pattern count $n" }
        val patterns = ArrayList<HandScoreComponent>(n)
        repeat(n) {
            val id = d.readUTF()
            val points = d.readInt()
            val argN = d.readInt()
            require(argN in 0..1_000) { "pattern arg count $argN" }
            val args = ArrayList<Any>(argN)
            repeat(argN) { args += d.readInt() }
            patterns += HandScoreComponent(id = id, points = points, formatArgs = args)
        }
        return patterns
    }

    /** Shared with [TutorialSessionSnapshotSchema] — same field order as free-play hands. */
    internal fun writeGameState(d: DataOutputStream, g: GameState) {
        writeIntArray(d, g.wall)
        d.writeInt(g.liveWallSize)
        for (p in 0 until NUM_PLAYERS) {
            writePlayer(d, g.players[p])
            writeIntArray(d, g.unknownTileCountsByPlayer[p])
            d.writeInt(g.handRevisions[p])
            writeIntList(d, g.discards[p])
        }
        d.writeInt(g.lastDiscarder)
        d.writeInt(g.lastDiscarded)
        d.writeInt(g.lastDrawnTile)
        d.writeInt(g.lastDrawnPlayer)
        d.writeInt(g.eastSeat)
        d.writeInt(g.currentPlayer)
        d.writeInt(g.decisionType.ordinal)
        d.writeInt(g.handScore)
        for (p in 0 until NUM_PLAYERS) {
            writeChoice(d, g.pendingReactions[p])
            d.writeBoolean(g.hasPendingReaction[p])
        }
        d.writeInt(g.pendingReactionCount)
        for (t in 0 until NUM_TILE_TYPES) {
            d.writeBoolean(g.declinedConcealedKong[t])
        }
        d.writeBoolean(g.lastDrawWasKongSupplement)
        d.writeBoolean(g.kongRobWindow)
        d.writeInt(g.seabedPlayer)
        d.writeBoolean(g.handEnded)
    }

    /**
     * @return the decoded game state. The payload's layout is [version]; a payload
     *   from an older version is not migrated (see the schema docs).
     */
    internal fun readGameState(d: DataInputStream): GameState {
        val layout = readGameStateLayout(d)
        val g = GameState()
        // Shared hydrate with [GameState.fromLayout]; skip multiset validation so
        // codec round-trips stay field-faithful (including test fixtures).
        g.hydrateFrom(layout)
        return g
    }

    /**
     * Binary field order matches [writeGameState] (per-seat player then
     * unknowns/revisions/discards).
     */
    private fun readGameStateLayout(d: DataInputStream): GameStateLayout {
        val wallBuf = IntArray(WALL_SIZE)
        readIntArrayInto(d, wallBuf)
        val liveWallSize = d.readInt()
        val players = arrayOfNulls<PlayerLayout>(NUM_PLAYERS)
        val unknown = Array(NUM_PLAYERS) { IntArray(NUM_TILE_TYPES) }
        val handRevisions = IntArray(NUM_PLAYERS)
        val discardLists = Array(NUM_PLAYERS) { emptyList<Int>() }
        for (p in 0 until NUM_PLAYERS) {
            players[p] = readPlayerLayout(d)
            readIntArrayInto(d, unknown[p])
            handRevisions[p] = d.readInt()
            discardLists[p] = readIntList(d)
        }
        val lastDiscarder = d.readInt()
        val lastDiscarded = d.readInt()
        val lastDrawnTile = d.readInt()
        val lastDrawnPlayer = d.readInt()
        val eastSeat = d.readInt()
        val currentPlayer = d.readInt()
        val decisionType = decisionTypeFromOrdinal(d.readInt())
        val handScore = d.readInt()
        val pendingReactions = Array(NUM_PLAYERS) { Choice.pass() }
        val hasPendingReaction = BooleanArray(NUM_PLAYERS)
        for (p in 0 until NUM_PLAYERS) {
            pendingReactions[p] = readChoice(d)
            hasPendingReaction[p] = d.readBoolean()
        }
        val pendingReactionCount = d.readInt()
        val declinedConcealedKong = BooleanArray(NUM_TILE_TYPES)
        for (t in 0 until NUM_TILE_TYPES) {
            declinedConcealedKong[t] = d.readBoolean()
        }
        val lastDrawWasKongSupplement = d.readBoolean()
        val kongRobWindow = d.readBoolean()
        val seabedPlayer = d.readInt()
        val handEnded = d.readBoolean()
        return GameStateLayout(
            wall = wallBuf,
            liveWallSize = liveWallSize,
            players = Array(NUM_PLAYERS) { players[it]!! },
            unknownTileCountsByPlayer = unknown,
            handRevisions = handRevisions,
            discards = discardLists,
            lastDiscarder = lastDiscarder,
            lastDiscarded = lastDiscarded,
            lastDrawnTile = lastDrawnTile,
            lastDrawnPlayer = lastDrawnPlayer,
            eastSeat = eastSeat,
            currentPlayer = currentPlayer,
            decisionType = decisionType,
            handScore = handScore,
            allowChickenHands = false, // set by [decode] from session header
            pendingReactions = pendingReactions,
            hasPendingReaction = hasPendingReaction,
            pendingReactionCount = pendingReactionCount,
            declinedConcealedKong = declinedConcealedKong,
            lastDrawWasKongSupplement = lastDrawWasKongSupplement,
            kongRobWindow = kongRobWindow,
            seabedPlayer = seabedPlayer,
            handEnded = handEnded,
        )
    }

    private fun writePlayer(d: DataOutputStream, ps: PlayerState) {
        writeIntArray(d, ps.concealed)
        writeIntArray(d, ps.openChows)
        writeIntArray(d, ps.pungsKongs)
        writeIntArray(d, ps.pungKongCalledFrom)
        d.writeInt(ps.meldCount)
        writeIntArray(d, ps.meldKind)
        writeIntArray(d, ps.meldValue)
        writeIntArray(d, ps.meldClaimTile)
        writeIntArray(d, ps.meldFrom)
    }

    private fun readPlayerLayout(d: DataInputStream): PlayerLayout {
        val concealed = IntArray(NUM_TILE_TYPES)
        val openChows = IntArray(NUM_CHOW_TYPES)
        val pungsKongs = IntArray(NUM_TILE_TYPES)
        val pungKongCalledFrom = IntArray(NUM_TILE_TYPES)
        readIntArrayInto(d, concealed)
        readIntArrayInto(d, openChows)
        readIntArrayInto(d, pungsKongs)
        readIntArrayInto(d, pungKongCalledFrom)
        val meldCount = d.readInt()
        val meldKind = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldValue = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldClaimTile = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldFrom = IntArray(PlayerState.MAX_OPEN_MELDS)
        readIntArrayInto(d, meldKind)
        readIntArrayInto(d, meldValue)
        readIntArrayInto(d, meldClaimTile)
        readIntArrayInto(d, meldFrom)
        return PlayerLayout(
            concealed = concealed,
            openChows = openChows,
            pungsKongs = pungsKongs,
            pungKongCalledFrom = pungKongCalledFrom,
            meldCount = meldCount,
            meldKind = meldKind,
            meldValue = meldValue,
            meldClaimTile = meldClaimTile,
            meldFrom = meldFrom,
        )
    }

    private fun writeChoice(d: DataOutputStream, c: Choice) {
        d.writeInt(c.kind.ordinal)
        d.writeInt(c.payload)
    }

    private fun readChoice(d: DataInputStream): Choice {
        val kind = choiceKindFromOrdinal(d.readInt())
        val payload = d.readInt()
        return Choice(kind, payload)
    }

    private fun decisionTypeFromOrdinal(ordinal: Int): DecisionType {
        val values = DecisionType.entries
        require(ordinal in values.indices) { "decisionType $ordinal" }
        return values[ordinal]
    }

    private fun choiceKindFromOrdinal(ordinal: Int): ChoiceKind {
        val values = ChoiceKind.entries
        require(ordinal in values.indices) { "choiceKind $ordinal" }
        return values[ordinal]
    }

    private fun writeIntArray(d: DataOutputStream, arr: IntArray) {
        d.writeInt(arr.size)
        for (v in arr) d.writeInt(v)
    }

    private fun readIntArray(d: DataInputStream, expectedSize: Int): IntArray {
        val n = d.readInt()
        require(n == expectedSize) { "int array size $n expected $expectedSize" }
        return IntArray(n) { d.readInt() }
    }

    private fun readIntArrayInto(d: DataInputStream, dest: IntArray) {
        val n = d.readInt()
        require(n == dest.size) { "int array size $n expected ${dest.size}" }
        for (i in 0 until n) dest[i] = d.readInt()
    }

    private fun writeIntList(d: DataOutputStream, list: List<Int>) {
        d.writeInt(list.size)
        for (v in list) d.writeInt(v)
    }

    private fun readIntList(d: DataInputStream): List<Int> {
        val n = d.readInt()
        require(n in 0..WALL_SIZE) { "int list size $n" }
        return List(n) { d.readInt() }
    }
}

/** Persisted free-play session: namespace `session`, name `freeplay`. */
val SessionSnapshotKey: StateKey<SessionSnapshot> = StateKey(
    namespace = "session",
    name = "freeplay",
    schema = SessionSnapshotSchema,
)
