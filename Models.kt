package com.example.ludoreal.game

import androidx.compose.ui.graphics.Color

enum class Player(val color: Color) {
    RED(Color(0xFFE53935)),
    GREEN(Color(0xFF43A047)),
    YELLOW(Color(0xFFFDD835)),
    BLUE(Color(0xFF1E88E5));
}

data class Token(
    val id: Int,
    val owner: Player,
    val state: TokenState
)

sealed class TokenState {
    data object Base : TokenState()
    data class OnTrack(val index: Int) : TokenState()            // 0..51
    data class HomeStretch(val step: Int) : TokenState()          // 0..5
    data object Home : TokenState()
}

data class TurnInfo(
    val currentPlayer: Player,
    val dice: Int? = null,
    val extraRoll: Boolean = false,
    val selectableTokenIds: List<Int> = emptyList(),
    val sixCountInARow: Int = 0
)

data class GameState(
    val players: List<Player> = listOf(Player.RED, Player.GREEN, Player.YELLOW, Player.BLUE),
    val activePlayers: List<Player> = players,
    val tokens: List<Token> = defaultTokens(players),
    val turn: TurnInfo = TurnInfo(currentPlayer = players.first()),
    val winnerOrder: List<Player> = emptyList()
) {
    companion object {
        fun defaultTokens(players: List<Player>) =
            players.flatMap { p -> (0 until 4).map { Token(idFor(p, it), p, TokenState.Base) } }

        fun idFor(p: Player, idx: Int) = p.ordinal * 10 + idx
    }
}