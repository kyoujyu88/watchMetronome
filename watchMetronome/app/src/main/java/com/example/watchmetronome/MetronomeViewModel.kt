package com.example.watchmetronome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

private val Context.dataStore by preferencesDataStore(name = "metronome_prefs")

data class MetronomeUiState(
    val bpm: Int = 120,
    val isRunning: Boolean = false,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = false,
    val beatsPerMeasure: Int = 4,
    val currentBeat: Int = -1
) {
    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 240
    }
}

class MetronomeViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        private val BPM_KEY = intPreferencesKey("bpm")
        private val SOUND_KEY = booleanPreferencesKey("sound")
        private val VIBE_KEY = booleanPreferencesKey("vibration")
        private val BEATS_KEY = intPreferencesKey("beats")
    }

    private val engine = MetronomeEngine(app)

    private val _state = MutableStateFlow(
        MetronomeUiState(
            bpm = engine.bpm,
            soundEnabled = engine.soundEnabled,
            vibrationEnabled = engine.vibrationEnabled,
            beatsPerMeasure = engine.beatsPerMeasure
        )
    )

    init {
        viewModelScope.launch {
            app.dataStore.data.collect { prefs ->
                val bpm = prefs[BPM_KEY] ?: 120
                val sound = prefs[SOUND_KEY] ?: true
                val vibe = prefs[VIBE_KEY] ?: false
                val beats = prefs[BEATS_KEY] ?: 4
                
                engine.bpm = bpm
                engine.soundEnabled = sound
                engine.vibrationEnabled = vibe
                engine.beatsPerMeasure = beats
                
                _state.value = _state.value.copy(
                    bpm = bpm,
                    soundEnabled = sound,
                    vibrationEnabled = vibe,
                    beatsPerMeasure = beats
                )
            }
        }
    }

    private fun saveState() {
        viewModelScope.launch {
            app.dataStore.edit { prefs ->
                prefs[BPM_KEY] = _state.value.bpm
                prefs[SOUND_KEY] = _state.value.soundEnabled
                prefs[VIBE_KEY] = _state.value.vibrationEnabled
                prefs[BEATS_KEY] = _state.value.beatsPerMeasure
            }
        }
    }
    val state: StateFlow<MetronomeUiState> = _state.asStateFlow()

    fun setBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(MetronomeUiState.MIN_BPM, MetronomeUiState.MAX_BPM)
        engine.bpm = clamped
        _state.value = _state.value.copy(bpm = clamped)
        saveState()
    }

    fun adjustBpm(delta: Int) = setBpm(_state.value.bpm + delta)

    fun toggleSound() {
        val next = !_state.value.soundEnabled
        engine.soundEnabled = next
        _state.value = _state.value.copy(soundEnabled = next)
        saveState()
    }

    fun toggleVibration() {
        val next = !_state.value.vibrationEnabled
        engine.vibrationEnabled = next
        _state.value = _state.value.copy(vibrationEnabled = next)
        saveState()
    }

    fun toggleRunning() {
        if (_state.value.isRunning) stop() else start()
    }

    fun cycleTimeSignature() {
        val next = when (_state.value.beatsPerMeasure) {
            2 -> 3
            3 -> 4
            4 -> 6
            6 -> 2
            else -> 4
        }
        engine.beatsPerMeasure = next
        _state.value = _state.value.copy(beatsPerMeasure = next)
        saveState()
    }

    fun pause() {
        if (_state.value.isRunning) {
            stop()
        }
    }

    private fun start() {
        engine.start { beatIndex, _ ->
            viewModelScope.launch {
                _state.value = _state.value.copy(currentBeat = beatIndex)
            }
        }
        _state.value = _state.value.copy(isRunning = true)
    }

    private fun stop() {
        engine.stop()
        _state.value = _state.value.copy(isRunning = false, currentBeat = -1)
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
