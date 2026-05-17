package com.example.watchmetronome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.watchmetronome.MetronomeUiState
import com.example.watchmetronome.MetronomeViewModel
import kotlin.math.abs

@Composable
fun MetronomeScreen(viewModel: MetronomeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val rotaryAccumulator = remember { RotaryAccumulator(thresholdPx = 48f) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .onRotaryScrollEvent { event ->
                val steps = rotaryAccumulator.add(event.verticalScrollPixels)
                if (steps != 0) viewModel.adjustBpm(steps)
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        // 上段: 音・振動トグル
        TopToggles(
            soundEnabled = state.soundEnabled,
            vibrationEnabled = state.vibrationEnabled,
            onToggleSound = viewModel::toggleSound,
            onToggleVibration = viewModel::toggleVibration,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
        )

        // 中段: BPM 表示 + ±ボタン + 拍ドット
        BpmControl(
            state = state,
            onMinus = { viewModel.adjustBpm(-1) },
            onPlus = { viewModel.adjustBpm(1) },
            onAdjustBpm = viewModel::adjustBpm,
            onLongPressBeats = viewModel::cycleTimeSignature,
            modifier = Modifier.align(Alignment.Center)
        )

        // 下段: 再生 / 停止
        StartStopButton(
            isRunning = state.isRunning,
            onToggle = viewModel::toggleRunning,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 上段トグル
// ---------------------------------------------------------------------------

@Composable
private fun TopToggles(
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    onToggleSound: () -> Unit,
    onToggleVibration: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleIconButton(
            active = soundEnabled,
            icon = if (soundEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
            contentDescription = if (soundEnabled) "Sound on" else "Sound off",
            onClick = onToggleSound
        )
        ToggleIconButton(
            active = vibrationEnabled,
            icon = Icons.Filled.Vibration,
            contentDescription = if (vibrationEnabled) "Vibration on" else "Vibration off",
            onClick = onToggleVibration
        )
    }
}

@Composable
private fun ToggleIconButton(
    active: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val bg = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.surface
    val fg = if (active) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
    Button(
        onClick = onClick,
        modifier = Modifier.size(28.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = bg, contentColor = fg)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------------------
// 中段 BPM コントロール
// ---------------------------------------------------------------------------

@Composable
private fun BpmControl(
    state: MetronomeUiState,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onAdjustBpm: (Int) -> Unit,
    onLongPressBeats: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(icon = Icons.Filled.Remove, contentDescription = "BPM -1", onClick = onMinus)
            Spacer(Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragAccumulator = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulator += dragAmount
                            
                            // 上スクロール(dragAmount < 0)でBPM増加
                            val sensitivity = 10f
                            val steps = (-dragAccumulator / sensitivity).toInt()
                            if (steps != 0) {
                                onAdjustBpm(steps)
                                dragAccumulator += steps * sensitivity
                            }
                        }
                    )
                }
            ) {
                Text(
                    text = state.bpm.toString(),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
                Text(
                    text = "BPM",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.width(12.dp))
            StepButton(icon = Icons.Filled.Add, contentDescription = "BPM +1", onClick = onPlus)
        }
        Spacer(Modifier.height(6.dp))
        BeatDots(
            currentBeat = state.currentBeat,
            isRunning = state.isRunning,
            beatsPerMeasure = state.beatsPerMeasure,
            onLongPress = onLongPressBeats
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = ButtonDefaults.secondaryButtonColors()
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BeatDots(
    currentBeat: Int,
    isRunning: Boolean,
    beatsPerMeasure: Int,
    onLongPress: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { onLongPress() })
        }
    ) {
        for (i in 0 until beatsPerMeasure) {
            val active = isRunning && i == currentBeat
            val color = when {
                active && i == 0 -> MaterialTheme.colors.primary
                active          -> MaterialTheme.colors.onBackground
                else            -> MaterialTheme.colors.onBackground.copy(alpha = 0.25f)
            }
            Box(
                modifier = Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 下段 スタート / ストップ
// ---------------------------------------------------------------------------

@Composable
private fun StartStopButton(isRunning: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (isRunning) Color(0xFFE57373) else MaterialTheme.colors.primary
    Button(
        onClick = onToggle,
        modifier = modifier.size(48.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = bg, contentColor = MaterialTheme.colors.onPrimary)
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isRunning) "Stop" else "Start",
            modifier = Modifier.size(24.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 回転入力アキュムレータ
// ---------------------------------------------------------------------------

/**
 * ベゼル（Galaxy Watch 離散クリック）もクラウン（Pixel Watch 連続スクロール）も
 * 同じ感覚で BPM を 1 ずつ変えられるよう、閾値 [thresholdPx] を超えたら
 * 整数ステップを返す。時計回り = BPM 増加になるよう符号を反転している。
 */
private class RotaryAccumulator(private val thresholdPx: Float) {
    private var accumulated: Float = 0f

    fun add(deltaPx: Float): Int {
        accumulated += deltaPx
        if (abs(accumulated) < thresholdPx) return 0
        val steps = (accumulated / thresholdPx).toInt()
        accumulated -= steps * thresholdPx
        return -steps  // 下スクロール(正値) → BPM 減、上スクロール(負値) → BPM 増
    }
}
