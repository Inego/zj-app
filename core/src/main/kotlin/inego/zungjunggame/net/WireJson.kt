package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/** JSON parsing and serialization for the multiplayer wire protocol. */
object WireJson {
    fun parseServerMessage(jsonString: String): ServerMessageWire {
        val root = JsonReader().parse(jsonString)
            ?: throw IllegalArgumentException("empty server message")
        val type = root.getString("type")
            ?: throw IllegalArgumentException("missing message type")
        return when (type) {
            "table_joined" -> {
                val players = ArrayList<TablePlayerWire>()
                val playersVal = root.get("players")
                if (playersVal != null) {
                    var child = playersVal.child
                    while (child != null) {
                        players.add(
                            TablePlayerWire(
                                seat = child.getInt("seat"),
                                name = child.getString("name", ""),
                                isBot = child.getBoolean("is_bot", false),
                            )
                        )
                        child = child.next
                    }
                }
                ServerMessageWire.TableJoined(
                    tableId = root.getString("table_id"),
                    seat = root.getInt("seat"),
                    rounds = root.getString("rounds"),
                    allowChicken = root.getBoolean("allow_chicken"),
                    players = players,
                    gameId = root.getString("game_id", ""),
                    scoreRows = parseScoreRows(root.get("score_rows")),
                    running = root.getBoolean("running", false),
                )
            }
            "seat_snapshot" -> ServerMessageWire.SeatSnapshotMsg(
                snapshot = parseSeatSnapshot(root)
            )
            "decision_prompt" -> {
                val choicesVal = root.get("choices")
                val choices = ArrayList<ChoiceWire>()
                if (choicesVal != null) {
                    var child = choicesVal.child
                    while (child != null) {
                        choices.add(
                            ChoiceWire(
                                kind = child.getString("kind"),
                                payload = child.getInt("payload", 0)
                            )
                        )
                        child = child.next
                    }
                }
                val deadlineMs = if (root.has("deadline_ms") && !root.get("deadline_ms").isNull) {
                    root.getLong("deadline_ms")
                } else {
                    null
                }
                val slackMs = if (root.has("slack_ms") && !root.get("slack_ms").isNull) {
                    root.getLong("slack_ms")
                } else {
                    null
                }
                val baseMs = if (root.has("base_ms") && !root.get("base_ms").isNull) {
                    root.getLong("base_ms")
                } else {
                    null
                }
                val msUntilThinkStarts =
                    if (root.has("ms_until_think_starts") && !root.get("ms_until_think_starts").isNull) {
                        root.getLong("ms_until_think_starts")
                    } else {
                        null
                    }
                val msUntilTimeout = if (root.has("ms_until_timeout") && !root.get("ms_until_timeout").isNull) {
                    root.getLong("ms_until_timeout")
                } else {
                    null
                }
                ServerMessageWire.DecisionPrompt(
                    decisionId = root.getLong("decision_id"),
                    decisionType = root.getString("decision_type"),
                    choices = choices,
                    deadlineMs = deadlineMs,
                    slackMs = slackMs,
                    baseMs = baseMs,
                    msUntilThinkStarts = msUntilThinkStarts,
                    msUntilTimeout = msUntilTimeout,
                )
            }
            "decision_time_left" -> ServerMessageWire.DecisionTimeLeft(
                decisionId = root.getLong("decision_id"),
                decisionType = root.getString("decision_type"),
                remainingMs = root.getLong("remaining_ms", 0L),
            )
            "choice_acknowledged" -> ServerMessageWire.ChoiceAcknowledged(
                requestId = root.getString("request_id"),
            )
            "choice_rejected" -> ServerMessageWire.ChoiceRejected(
                requestId = root.getString("request_id"),
                reason = root.getString("reason", ""),
            )
            "hand_settlement" -> {
                val winnerSeat = if (root.has("winner_seat") && !root.get("winner_seat").isNull) {
                    root.getInt("winner_seat")
                } else null
                ServerMessageWire.HandSettlement(
                    handNumber = root.getInt("hand_number"),
                    handScore = root.getInt("hand_score"),
                    winnerSeat = winnerSeat,
                    deltas = parseIntList(root.get("deltas")),
                    runningTotals = parseIntList(root.get("running_totals")),
                    patterns = parseScoreComponents(root.get("patterns")),
                )
            }
            "next_hand_prompt" -> {
                val deadlineMs = if (root.has("deadline_ms") && !root.get("deadline_ms").isNull) {
                    root.getLong("deadline_ms")
                } else {
                    null
                }
                ServerMessageWire.NextHandPrompt(
                    handNumber = root.getInt("hand_number"),
                    deadlineMs = deadlineMs,
                    ready = root.getBoolean("ready", false),
                )
            }
            "next_hand_time_left" -> ServerMessageWire.NextHandTimeLeft(
                remainingMs = root.getLong("remaining_ms", 0L),
            )
            "game_finished" -> {
                val ratingUpdates = ArrayList<PlayerRatingUpdateWire>()
                val updatesVal = root.get("rating_updates")
                if (updatesVal != null) {
                    var child = updatesVal.child
                    while (child != null) {
                        ratingUpdates.add(
                            PlayerRatingUpdateWire(
                                seat = child.getInt("seat"),
                                userId = child.getString("user_id"),
                                oldRating = child.getDouble("old_rating"),
                                newRating = child.getDouble("new_rating"),
                                delta = child.getDouble("delta"),
                            )
                        )
                        child = child.next
                    }
                }
                ServerMessageWire.GameFinished(
                    runningTotals = parseIntList(root.get("running_totals")),
                    ratingUpdates = ratingUpdates,
                    startedAt = optionalString(root, "started_at"),
                    finishedAt = optionalString(root, "finished_at"),
                )
            }
            "challenge_score_breakdown" -> {
                val comparisons = ArrayList<ChallengeComparisonWire>()
                val comparisonsVal = root.get("comparisons")
                if (comparisonsVal != null) {
                    var child = comparisonsVal.child
                    while (child != null) {
                        val opponentName = if (child.has("opponent_name") && !child.get("opponent_name").isNull) {
                            child.getString("opponent_name")
                        } else {
                            null
                        }
                        comparisons.add(
                            ChallengeComparisonWire(
                                opponentName = opponentName,
                                playerScore = child.getInt("player_score", 0),
                                opponentScore = child.getInt("opponent_score", 0),
                                opponentRating = child.getDouble("opponent_rating", 0.0),
                                s = child.getDouble("s", 0.0),
                                delta = child.getDouble("delta", 0.0),
                            )
                        )
                        child = child.next
                    }
                }
                ServerMessageWire.ChallengeScoreBreakdown(
                    publicId = root.getString("public_id", ""),
                    totalScore = root.getInt("total_score", 0),
                    ratingBefore = root.getDouble("rating_before", 0.0),
                    ratingAfter = root.getDouble("rating_after", 0.0),
                    ratingDelta = root.getDouble("rating_delta", 0.0),
                    comparisons = comparisons,
                )
            }
            "table_aborted" -> ServerMessageWire.TableAborted(
                reason = root.getString("reason", ""),
            )
            "server_shutdown" -> {
                val message = if (root.has("message") && !root.get("message").isNull) {
                    root.getString("message")
                } else {
                    null
                }
                ServerMessageWire.ServerShutdown(
                    phase = root.getString("phase", "warning"),
                    remainingMs = root.getLong("remaining_ms", 0L),
                    message = message,
                )
            }
            "server_shutdown_withdrawn" -> ServerMessageWire.ServerShutdownWithdrawn
            "pong" -> ServerMessageWire.Pong
            else -> throw IllegalArgumentException("unknown server message type: $type")
        }
    }

    private fun parseSeatSnapshot(root: JsonValue): SeatSnapshotWire {
        val lastDiscarder = if (root.has("last_discarder") && !root.get("last_discarder").isNull) {
            root.getInt("last_discarder")
        } else null

        val lastDiscarded = if (root.has("last_discarded") && !root.get("last_discarded").isNull) {
            root.getInt("last_discarded")
        } else null

        val winnerSeat = if (root.has("winner_seat") && !root.get("winner_seat").isNull) {
            root.getInt("winner_seat")
        } else null

        val winSource = if (root.has("win_source") && !root.get("win_source").isNull) {
            root.getInt("win_source")
        } else null

        val scoreDeltas = if (root.has("score_deltas") && !root.get("score_deltas").isNull) {
            parseIntList(root.get("score_deltas"))
        } else null

        val seatsVal = root.get("seats")
            ?: throw IllegalArgumentException("missing seats in snapshot")
        val seats = ArrayList<SeatSnapshotViewWire>()
        var seatChild = seatsVal.child
        while (seatChild != null) {
            seats.add(parseSeatSnapshotView(seatChild))
            seatChild = seatChild.next
        }

        val events = ArrayList<PresentationEventWire>()
        val eventsVal = root.get("events")
        if (eventsVal != null) {
            var evChild = eventsVal.child
            while (evChild != null) {
                parsePresentationEvent(evChild)?.let { events.add(it) }
                evChild = evChild.next
            }
        }

        return SeatSnapshotWire(
            handNumber = root.getInt("hand_number"),
            mySeat = root.getInt("my_seat"),
            eastSeat = root.getInt("east_seat"),
            liveWallSize = root.getInt("live_wall_size"),
            currentPlayer = root.getInt("current_player"),
            decisionType = root.getString("decision_type"),
            lastDiscarder = lastDiscarder,
            lastDiscarded = lastDiscarded,
            isPendingDiscard = root.getBoolean("is_pending_discard", false),
            isKongRobWindow = root.getBoolean("is_kong_rob_window", false),
            handScore = root.getInt("hand_score", 0),
            winnerSeat = winnerSeat,
            winSource = winSource,
            isTerminal = root.getBoolean("is_terminal", false),
            scoreDeltas = scoreDeltas,
            seats = seats,
            events = events,
        )
    }

    private fun parsePresentationEvent(node: JsonValue): PresentationEventWire? {
        val type = node.getString("type", "")
        return when (type) {
            "draw" -> {
                val seat = node.getInt("seat")
                val tile = if (node.has("tile") && !node.get("tile").isNull) node.getInt("tile") else null
                PresentationEventWire.Draw(seat, tile)
            }
            "discard" -> {
                val seat = node.getInt("seat")
                val tile = node.getInt("tile")
                val slotIndex = if (node.has("slot_index") && !node.get("slot_index").isNull) node.getInt("slot_index") else null
                val isDrawn = node.getBoolean("is_drawn", false)
                PresentationEventWire.Discard(seat, tile, slotIndex, isDrawn)
            }
            "claim" -> {
                val seat = node.getInt("seat")
                val sourceSeat = node.getInt("source_seat")
                val kind = node.getString("kind", "")
                val tiles = parseIntList(node.get("tiles"))
                val bidders = parseBidders(node.get("bidders"))
                PresentationEventWire.Claim(seat, sourceSeat, kind, tiles, bidders)
            }
            "reaction_hu" -> {
                val seat = node.getInt("seat")
                val sourceSeat = node.getInt("source_seat")
                val tiles = parseIntList(node.get("tiles"))
                val bidders = parseBidders(node.get("bidders"))
                PresentationEventWire.ReactionHu(seat, sourceSeat, tiles, bidders)
            }
            "kong" -> {
                val seat = node.getInt("seat")
                val kind = node.getString("kind", "")
                val tiles = parseIntList(node.get("tiles"))
                PresentationEventWire.Kong(seat, kind, tiles)
            }
            "hand_end" -> {
                val winnerSeat = if (node.has("winner_seat") && !node.get("winner_seat").isNull) node.getInt("winner_seat") else null
                val winSource = if (node.has("win_source") && !node.get("win_source").isNull) node.getInt("win_source") else null
                PresentationEventWire.HandEnd(winnerSeat, winSource)
            }
            else -> null
        }
    }

    private fun parseSeatSnapshotView(node: JsonValue): SeatSnapshotViewWire {
        val concealedTiles = if (node.has("concealed_tiles") && !node.get("concealed_tiles").isNull) {
            parseIntList(node.get("concealed_tiles"))
        } else null

        val meldsVal = node.get("melds")
        val melds = ArrayList<MeldWire>()
        if (meldsVal != null) {
            var mChild = meldsVal.child
            while (mChild != null) {
                val tilesVal = mChild.get("tiles")
                val tiles = ArrayList<MeldTileWire>()
                if (tilesVal != null) {
                    var tChild = tilesVal.child
                    while (tChild != null) {
                        tiles.add(
                            MeldTileWire(
                                tileType = tChild.getInt("tile_type"),
                                open = tChild.getBoolean("open"),
                                rotated = tChild.getBoolean("rotated", false),
                            )
                        )
                        tChild = tChild.next
                    }
                }
                melds.add(MeldWire(tiles))
                mChild = mChild.next
            }
        }

        val river = if (node.has("river") && !node.get("river").isNull) {
            parseIntList(node.get("river"))
        } else emptyList()

        val name = if (node.has("name") && !node.get("name").isNull) node.getString("name") else ""
        val isBot = node.has("is_bot") && !node.get("is_bot").isNull && node.getBoolean("is_bot")

        return SeatSnapshotViewWire(
            seat = node.getInt("seat"),
            name = name,
            isBot = isBot,
            concealedCount = node.getInt("concealed_count"),
            concealedTiles = concealedTiles,
            drawnTile = node.getInt("drawn_tile", -1),
            hasDrawnTile = node.getBoolean("has_drawn_tile", false),
            melds = melds,
            river = river,
            seatWindTile = node.getInt("seat_wind_tile", 0),
        )
    }

    internal fun parseScoreRows(value: JsonValue?): List<HandScoreRowWire> {
        if (value == null || value.isNull) return emptyList()
        val list = ArrayList<HandScoreRowWire>()
        var child = value.child
        while (child != null) {
            val winnerSeat = if (child.has("winner_seat") && !child.get("winner_seat").isNull) {
                child.getInt("winner_seat")
            } else {
                null
            }
            val discarderSeat = if (child.has("discarder_seat") && !child.get("discarder_seat").isNull) {
                child.getInt("discarder_seat")
            } else {
                null
            }
            list.add(
                HandScoreRowWire(
                    handNumber = child.getInt("hand_number", 0),
                    handValue = child.getInt("hand_value", 0),
                    winnerSeat = winnerSeat,
                    discarderSeat = discarderSeat,
                    deltas = parseIntList(child.get("deltas")),
                    runningTotals = parseIntList(child.get("running_totals")),
                    patterns = parseScoreComponents(child.get("patterns")),
                ),
            )
            child = child.next
        }
        return list
    }

    private fun parseIntList(value: JsonValue?): List<Int> {
        if (value == null || value.isNull) return emptyList()
        val list = ArrayList<Int>()
        var child = value.child
        while (child != null) {
            list.add(child.asInt())
            child = child.next
        }
        return list
    }

    /**
     * The `bidders` list of a resolved reaction window. Omitted by the server
     * when nobody contested the window (and by a pre-change server always), which
     * reads as an empty list: a lone bidder is not a contest.
     */
    private fun parseBidders(value: JsonValue?): List<ClaimBidderWire> {
        if (value == null || value.isNull) return emptyList()
        val list = ArrayList<ClaimBidderWire>()
        var child = value.child
        while (child != null) {
            list.add(
                ClaimBidderWire(
                    seat = child.getInt("seat"),
                    kind = child.getString("kind", ""),
                )
            )
            child = child.next
        }
        return list
    }

    /** The field when present and non-null, else null (an older server omits it). */
    private fun optionalString(root: JsonValue, name: String): String? {
        val value = root.get(name) ?: return null
        return if (value.isNull) null else value.asString()
    }

    private fun parseScoreComponents(value: JsonValue?): List<ScoreComponentWire> {
        if (value == null || value.isNull) return emptyList()
        val list = ArrayList<ScoreComponentWire>()
        var child = value.child
        while (child != null) {
            list.add(
                ScoreComponentWire(
                    id = child.getString("id", ""),
                    points = child.getInt("points", 0),
                    formatArgs = parseIntList(child.get("format_args")),
                )
            )
            child = child.next
        }
        return list
    }

    fun serializeClientMessage(msg: ClientMessageWire): String =
        when (msg) {
            is ClientMessageWire.SubmitChoice -> buildString {
                append("{\"type\":\"submit_choice\",")
                append("\"table_id\":").append(AuthJson.quote(msg.tableId)).append(',')
                append("\"hand_id\":").append(msg.handId).append(',')
                append("\"decision_id\":").append(msg.decisionId).append(',')
                append("\"request_id\":").append(AuthJson.quote(msg.requestId)).append(',')
                if (msg.slotIndex != null) {
                    append("\"slot_index\":").append(msg.slotIndex).append(',')
                }
                if (msg.isDrawn != null) {
                    append("\"is_drawn\":").append(msg.isDrawn).append(',')
                }
                append("\"choice\":{\"kind\":").append(AuthJson.quote(msg.choice.kind)).append(',')
                append("\"payload\":").append(msg.choice.payload).append("}}")
            }
            is ClientMessageWire.ReadyForNextHand -> buildString {
                append("{\"type\":\"ready_for_next_hand\",")
                append("\"table_id\":").append(AuthJson.quote(msg.tableId)).append("}")
            }
            is ClientMessageWire.LeaveGame -> buildString {
                append("{\"type\":\"leave_game\",")
                append("\"table_id\":").append(AuthJson.quote(msg.tableId)).append("}")
            }
            is ClientMessageWire.SetAway -> buildString {
                append("{\"type\":\"set_away\",")
                append("\"away\":").append(msg.away).append("}")
            }
            ClientMessageWire.Ping -> "{\"type\":\"ping\"}"
        }
}
