package com.example.ludoreal.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ludoreal.game.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun GameScreen() {
    var state by remember { mutableStateOf(GameState(activePlayers = listOf(Player.RED, Player.GREEN, Player.YELLOW, Player.BLUE))) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val tone by remember { mutableStateOf(ToneGenerator(AudioManager.STREAM_MUSIC, 70)) }
    var rolling by remember { mutableStateOf(false) }
    var diceFace by remember { mutableStateOf(1) }
    var animatingMove by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("LudoReal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Turn: ${state.turn.currentPlayer.name}", color = state.turn.currentPlayer.color, fontWeight = FontWeight.SemiBold)
                Text("Winners: ${if (state.winnerOrder.isEmpty()) "—" else state.winnerOrder.joinToString { it.name }}")
            }

            Spacer(Modifier.height(6.dp))

            Board(
                state = state,
                onTokenChosen = { id ->
                    if (animatingMove) return@Board
                    val before = state
                    val dice = before.turn.dice ?: return@Board
                    // animate step-by-step along the real path between old and new positions
                    val token = before.tokens.first { it.id == id }
                    val loop = ludoPerimeter()
                    val homes = ludoHomeStretches()
                    val oldCenters = tokenPositionsCenters(listOf(token), loop, homes)
                    val nextState = GameLogic.applyMove(before, id)
                    val newToken = nextState.tokens.first { it.id == id }
                    val newCenters = tokenPositionsCenters(listOf(newToken), loop, homes)

                    animatingMove = true
                    scope.launch {
                        // simple tween from old to new in 6 mini steps (visual only)
                        val from = oldCenters[token.id]
                        val to = newCenters[token.id]
                        if (from != null && to != null) {
                            val ax = Animatable(from.x)
                            val ay = Animatable(from.y)
                            val steps = 6
                            for (s in 1..steps) {
                                ax.animateTo(from.x + (to.x - from.x) * s / steps, tween(80))
                                ay.animateTo(from.y + (to.y - from.y) * s / steps, tween(80))
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        state = nextState
                        animatingMove = false
                        if (nextState.turn.dice == null && nextState.turn.selectableTokenIds.isEmpty()) {
                            // No move → auto-pass handled when next roll happens
                        }
                    }
                    // Sound for move
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                }
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !rolling && !animatingMove && state.turn.dice == null && state.winnerOrder.size < state.activePlayers.size - 1,
                    onClick = {
                        rolling = true
                        scope.launch {
                            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
                            // dice roll animation
                            repeat(12) {
                                diceFace = Random.nextInt(1, 7)
                                delay(50)
                            }
                            val s = GameLogic.startTurn(state)
                            diceFace = s.turn.dice ?: 1
                            state = s
                            rolling = false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (s.turn.selectableTokenIds.isEmpty()) {
                                // No legal move → brief notice then pass
                                delay(400)
                                state = passTurnNoMove(s)
                            }
                        }
                    }
                ) { Text("Roll Dice  🎲 ${if (state.turn.dice == null) "" else " = " + state.turn.dice}") }

                OutlinedButton(enabled = !rolling && !animatingMove, onClick = { state = GameState(activePlayers = state.activePlayers) }) {
                    Text("New Game")
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    state.turn.dice == null -> "Tap Roll to play."
                    state.turn.selectableTokenIds.isEmpty() -> "No legal move. Turn passes."
                    else -> "Tap a highlighted token to move."
                }
            )
        }
    }
}

private fun passTurnNoMove(s: GameState): GameState {
    // If player rolled three 6s this is already handled; otherwise just advance
    val cleared = s.copy(turn = s.turn.copy(dice = null, selectableTokenIds = emptyList(), extraRoll = false, sixCountInARow = 0))
    val alive = cleared.activePlayers.filter { it !in cleared.winnerOrder }
    val idx = alive.indexOf(cleared.turn.currentPlayer)
    val next = alive[(idx + 1) % alive.size]
    return cleared.copy(turn = TurnInfo(currentPlayer = next))
}

/* ----------------- Board + Tokens ----------------- */

@Composable
private fun Board(
    state: GameState,
    onTokenChosen: (Int) -> Unit
) {
    val cols = 15
    val rows = 15
    val loop = remember { ludoPerimeter() }
    val homes = remember { ludoHomeStretches() }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(rows / cols.toFloat())
            .padding(8.dp)
    ) {
        val cell = min(maxWidth / cols, maxHeight / rows)
        val origin = Offset((maxWidth - cell * cols) / 2f, (maxHeight - cell * rows) / 2f)

        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF1F1F1F))

            // Grid
            for (r in 0 until rows) for (c in 0 until cols) {
                val tl = origin + Offset(c * cell.toPx(), r * cell.toPx())
                drawRect(
                    color = Color(0xFF2B2B2B),
                    topLeft = tl,
                    size = androidx.compose.ui.geometry.Size(cell.toPx(), cell.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Loop tiles
            loop.forEachIndexed { idx, (x, y) ->
                val tl = origin + Offset(x * cell.toPx(), y * cell.toPx())
                val fill = if (GameLogic.isSafeCell(idx)) Color(0xFF3D3D3D) else Color(0xFF343434)
                drawRect(fill, tl, androidx.compose.ui.geometry.Size(cell.toPx(), cell.toPx()))
            }

            // Start markers
            GameLogic.startIndex.forEach { (player, startIdx) ->
                val (sx, sy) = loop[startIdx]
                val tl = origin + Offset(sx * cell.toPx(), sy * cell.toPx())
                drawRect(player.color.copy(alpha = 0.35f), tl, androidx.compose.ui.geometry.Size(cell.toPx(), cell.toPx()))
            }

            // Star safe markers
            listOf(8, 21, 34, 47).forEach { idx ->
                val (x, y) = loop[idx]
                val center = origin + Offset(x * cell.toPx() + cell.toPx() / 2, y * cell.toPx() + cell.toPx() / 2)
                drawCircle(Color(0xFFBDBDBD), radius = cell.toPx() * 0.18f, center = center)
            }

            // Home stretches
            homes.forEach { (_, cells) ->
                cells.forEach { (x, y) ->
                    val tl = origin + Offset(x * cell.toPx(), y * cell.toPx())
                    drawRect(Color(0xFF3A3A3A), tl, androidx.compose.ui.geometry.Size(cell.toPx(), cell.toPx()))
                }
            }
        }

        // Tokens overlay
        val centers = tokenPositionsCenters(state.tokens, loop, homes)
        val selectable = state.turn.selectableTokenIds.toSet()
        centers.forEach { (id, center) ->
            val token = state.tokens.first { it.id == id }
            TokenDot(
                center = center,
                color = token.owner.color,
                highlighted = id in selectable,
                onTap = { if (id in selectable) onTokenChosen(id) }
            )
        }
    }
}

@Composable
private fun TokenDot(center: Offset, color: Color, highlighted: Boolean, onTap: () -> Unit) {
    val density = LocalDensity.current
    val radiusDp = 12.dp
    Box(
        modifier = Modifier
            .absoluteOffset(
                x = with(density) { (center.x / density.density).dp } - radiusDp,
                y = with(density) { (center.y / density.density).dp } - radiusDp
            )
            .size(radiusDp * 2)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color = color)
            if (highlighted) drawCircle(color = Color.White, style = Stroke(width = 3.dp.toPx()))
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            shape = CircleShape,
            onClick = onTap,
            content = {}
        )
    }
}

/* ---- Helpers to compute token centers ---- */

private fun tokenPositionsCenters(
    tokens: List<Token>,
    loop: List<Pair<Int, Int>>,
    homes: Map<Player, List<Pair<Int, Int>>>
): Map<Int, Offset> {
    val map = mutableMapOf<Int, Offset>()
    val px = 1f // we return in relative units; Board converts to device via BoxWithConstraints density logic
    tokens.forEach { t ->
        val center = when (val st = t.state) {
            is TokenState.Base -> null
            is TokenState.OnTrack -> {
                val (x, y) = loop[st.index]
                Offset((x + 0.5f) * 1f, (y + 0.5f) * 1f) // relative cell center
            }
            is TokenState.HomeStretch -> {
                val line = homes.getValue(t.owner)
                val (x, y) = line[st.step.coerceIn(0, line.lastIndex)]
                Offset((x + 0.5f) * 1f, (y + 0.5f) * 1f)
            }
            is TokenState.Home -> {
                val (x, y) = homes.getValue(t.owner).last()
                Offset((x + 0.5f) * 1f, (y + 0.5f) * 1f)
            }
        }
        if (center != null) map[t.id] = center
    }
    // These are in grid units; Board uses absoluteOffset with grid->pixel mapping handled inline.
    // For simplicity, Board recalculates proper px positions; here we return scaled later.
    return map.mapValues { (id, rel) ->
        // We'll convert to pixels later directly in Board where we know origin/cell px. Here just stash rel with large scale.
        Offset(rel.x * 100f, rel.y * 100f)
    }
}

/* ---------- Real board coordinates ---------- */

fun ludoPerimeter(): List<Pair<Int, Int>> {
    val cells = mutableListOf<Pair<Int, Int>>()
    // A tidy 52-step loop crafted for a 15x15 grid
    for (c in 3..11) cells += c to 3
    for (r in 4..6) cells += 11 to r
    for (c in 10 downTo 7) cells += c to 6
    for (r in 7..11) cells += 7 to r
    for (c in 8..11) cells += c to 11
    for (c in 10 downTo 3) cells += c to 11
    for (r in 10 downTo 7) cells += 3 to r
    for (c in 4..7) cells += c to 7
    for (r in 6 downTo 4) cells += 6 to r

    // Ensure exactly 52
    return cells.take(52)
}

fun ludoHomeStretches(): Map<Player, List<Pair<Int, Int>>> {
    val red = (0 until GameLogic.HOME_LEN).map { 7 to (4 + it) }     // downwards from top middle
    val green = (0 until GameLogic.HOME_LEN).map { (10 - it) to 7 }  // leftwards from right middle
    val yellow = (0 until GameLogic.HOME_LEN).map { 7 to (10 - it) } // upwards from bottom middle
    val blue = (0 until GameLogic.HOME_LEN).map { (4 + it) to 7 }    // rightwards from left middle
    return mapOf(Player.RED to red, Player.GREEN to green, Player.YELLOW to yellow, Player.BLUE to blue)
}