package com.example.friendsreels.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process bus that carries the state of the batch URL enrichment
 * feature (Settings → "Preparar URLs em lote", added in s38).
 *
 * The [InstagramReaderService] writes progress here as it processes each
 * Reel; the `SettingsViewModel` collects it to render a progress bar and
 * enable/disable the "Cancelar" button. We use a plain singleton
 * because the accessibility service and every UI component live in the
 * same app process — no IPC needed.
 *
 * The state is intentionally minimal: it never persists across process
 * death. If the OS reboots the a11y service mid-batch we treat the
 * batch as lost; the user only needs to tap "Preparar todos" again and
 * the DAO will report the still-pending count.
 */
object BatchEnrichmentBus {

    /**
     * Snapshot of the last outcome the service reported. Held for
     * informational display in the settings screen ("Última execução:
     * X preparados, Y falharam"). Null while no batch has ever run.
     */
    data class LastResult(
        val succeeded: Int,
        val failed: Int,
        val cancelled: Boolean,
    )

    /**
     * Union type covering both "idle" and "running" cases.
     *
     * - [running] is true while a batch is being processed.
     * - [currentIndex] is 1-based within [total] (`0/total` means "not
     *   started yet"). Once done, both stay at their last value; use
     *   [running] to gate UI.
     * - [lastResult] is set at the end of a run, cleared to null when a
     *   fresh run starts.
     */
    data class State(
        val running: Boolean = false,
        val currentIndex: Int = 0,
        val total: Int = 0,
        val lastResult: LastResult? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(new: State) {
        _state.value = new
    }
}
