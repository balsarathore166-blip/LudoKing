package com.example.ludoreal.game

import kotlin.random.Random

object GameLogic {
    const val TRACK_LEN = 52
    const val HOME_LEN = 6

    val startIndex = mapOf(
        Player.RED to 0,
        Player.GREEN to 13,
        Player.YELLOW to 26,
        Player.BLUE to 39
    )

    private val entryIndex = mapOf(
        Player.RED to 51,
        Player.GREEN to 12,
        Player.YELLOW to 25,
        Player.BLUE to 38
    )

    private val starSafe = setOf(8, 21, 34, 47)

    fun isSafeCell(idx: Int): Boolean = idx in starSafe || idx in startIndex.values

    fun rollDice() = Random.nextInt(1, 7)

    fun startTurn(state: GameState): GameState {
        val dice = rollDice()
        val sixCount = if (dice == 6) state.turn.sixCountInARow + 1 else 0

        if (sixCount >= 3) {
            return advanceTurn(noMove(state))
        }

        val selectable = legalMoves(state, dice)
        return state.copy(
            turn = state.turn.copy(
                dice = dice,
                extraRoll = dice == 6,
                sixCountInARow = sixCount,
                selectableTokenIds = selectable.map { it.id }
            )
        )
    }

    private fun noMove(state: GameState): GameState = state.copy(
        turn = TurnInfo(
            currentPlayer = state.turn.currentPlayer,
            dice = null,
            extraRoll = false,
            selectableTokenIds = emptyList(),
            sixCountInARow = 0
        )
    )

    fun legalMoves(state: GameState, dice: Int): List<Token> {
        val p = state.turn.currentPlayer
        val my = state.tokens.filter { it.owner == p }
        val canSpawn = dice == 6 && my.any { it.state is TokenState.Base } && isStartSpawnAllowed(state, p)
        val movable = my.filter { canAdvance(state, it, dice) }

        return buildList {
            if (canSpawn) addAll(my.filter { it.state is TokenState.Base })
            addAll(movable)
        }.distinct()
    }

    private fun isStartSpawnAllowed(state: GameState, player: Player): Boolean {
        val sIdx = startIndex.getValue(player)
        val occupants = state.tokens.filter { it.state is TokenState.OnTrack && it.state.index == sIdx }
        if (occupants.isEmpty()) return true
        if (occupants.all { it.owner == player }) return true
        val byOwner = occupants.groupBy { it.owner }
        val hasOppBlock = byOwner.any { (owner, list) -> owner != player && list.size >= 2 }
        return !hasOppBlock && occupants.size == 1
    }

    private fun canAdvance(state: GameState, token: Token, dice: Int): Boolean {
        return when (val st = token.state) {
            is TokenState.Base -> false
            is TokenState.OnTrack -> canAdvanceFromTrack(state, token, st.index, dice)
            is TokenState.HomeStretch -> st.step + dice <= (HOME_LEN - 1)
            is TokenState.Home -> false
        }
    }

    private fun canAdvanceFromTrack(state: GameState, token: Token, from: Int, dice: Int): Boolean {
        val entry = entryIndex.getValue(token.owner)
        val distToEntry = (entry - from + TRACK_LEN) % TRACK_LEN

        if (dice <= distToEntry) {
            val path = (1..dice).map { (from + it) % TRACK_LEN }
            return !path.any { isBlockingSquare(state, it, null) }
        } else {
            val stepsOnTrack = distToEntry
            val onTrackPath = (1..stepsOnTrack).map { (from + it) % TRACK_LEN }
            if (onTrackPath.any { isBlockingSquare(state, it, null) }) return false

            val stepsInHome = dice - stepsOnTrack - 1
            return stepsInHome in 0..HOME_LEN
        }
    }

    private fun isBlockingSquare(state: GameState, idx: Int, blockerOwner: Player?): Boolean {
        val here = state.tokens.filter { it.state is TokenState.OnTrack && it.state.index == idx }
        if (here.size < 2) return false
        val grouped = here.groupBy { it.owner }
        val blockOwner = grouped.entries.firstOrNull { it.value.size >= 2 }?.key
        return blockOwner != null && (blockerOwner == null || blockOwner != blockerOwner)
    }

    fun applyMove(state: GameState, tokenId: Int): GameState {
        val dice = state.turn.dice ?: return state
        val token = state.tokens.first { it.id == tokenId }

        val moved = when (val st = token.state) {
            is TokenState.Base -> token.copy(state = TokenState.OnTrack(startIndex.getValue(token.owner)))
            is TokenState.OnTrack -> moveFromTrack(state, token, st.index, dice)
            is TokenState.HomeStretch -> {
                val target = st.step + dice
                token.copy(state = if (target >= (HOME_LEN - 1)) TokenState.Home else TokenState.HomeStretch(target))
            }
            is TokenState.Home -> token
        }

        val afterCapture = captureIfAny(state, moved)

        val winnersNow = state.winnerOrder.toMutableList()
        state.activePlayers.forEach { p ->
            val allHome = afterCapture.count { it.owner == p && it.state is TokenState.Home } == 4
            if (allHome && p !in winnersNow) winnersNow += p
        }

        val gotCapture = didCapture(state.tokens, afterCapture, moved)
        val keepTurn = (dice == 6) or gotCapture

        val nextState = state.copy(
            tokens = afterCapture,
            winnerOrder = winnersNow,
            turn = state.turn.copy(
                dice = null,
                selectableTokenIds = emptyList(),
                extraRoll = false,
                sixCountInARow = if (keepTurn) state.turn.sixCountInARow else 0
            )
        )
        return if (keepTurn) nextState else advanceTurn(nextState)
    }

    private fun moveFromTrack(state: GameState, token: Token, from: Int, dice: Int): Token {
        val entry = entryIndex.getValue(token.owner)
        val distToEntry = (entry - from + TRACK_LEN) % TRACK_LEN
        return if (dice <= distToEntry) {
            val to = (from + dice) % TRACK_LEN
            token.copy(state = TokenState.OnTrack(to))
        } else {
            val stepsInHome = dice - distToEntry - 1
            val toHome = stepsInHome - 1
            token.copy(state = if (toHome >= (HOME_LEN - 1)) TokenState.Home else TokenState.HomeStretch(toHome))
        }
    }

    private fun captureIfAny(prevState: GameState, moved: Token): List<Token> {
        val ms = moved.state
        val current = prevState.tokens.map { if (it.id == moved.id) moved else it }

        if (ms !is TokenState.OnTrack) return current
        val idx = ms.index
        if (isSafeCell(idx)) return current

        val here = current.filter { it.id != moved.id && it.state is TokenState.OnTrack && it.state.index == idx }
        if (here.isEmpty()) return current

        val grouped = here.groupBy { it.owner }
        val blockPresent = grouped.values.any { it.size >= 2 }
        if (blockPresent) return current

        return current.map {
            if (here.any { e -> e.id == it.id && e.owner != moved.owner }) it.copy(state = TokenState.Base) else it
        }
    }

    private fun didCapture(before: List<Token>, after: List<Token>, moved: Token): Boolean {
        val ms = moved.state
        if (ms !is TokenState.OnTrack) return false
        val idx = ms.index
        if (isSafeCell(idx)) return false
        val countBeforeOpp = before.count { it.owner != moved.owner && it.state is TokenState.OnTrack && it.state.index == idx }
        val countAfterOpp = after.count { it.owner != moved.owner && it.state is TokenState.OnTrack && it.state.index == idx }
        return countBeforeOpp > 0 && countAfterOpp == 0
    }

    private fun advanceTurn(state: GameState): GameState {
        val alive = state.activePlayers.filter { it !in state.winnerOrder }
        if (alive.size <= 1) return state
        val idx = alive.indexOf(state.turn.currentPlayer)
        val next = alive[(idx + 1) % alive.size]
        return state.copy(turn = TurnInfo(currentPlayer = next))
    }
}