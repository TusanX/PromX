// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.currencortex.music.data.radio.FreeRadioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FreeRadioUi(
    val query: String = "",
    val stations: List<FreeRadioRepository.Station> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class FreeRadioViewModel(
    private val repository: FreeRadioRepository = FreeRadioRepository(),
) : ViewModel() {
    private val mutable = MutableStateFlow(FreeRadioUi(loading = true))
    val state: StateFlow<FreeRadioUi> = mutable.asStateFlow()
    private var loadJob: Job? = null

    init { load("") }

    fun query(value: String) {
        mutable.value = mutable.value.copy(query = value)
    }

    fun submit() = load(mutable.value.query)

    fun refresh() = load(mutable.value.query)

    private fun load(query: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            val stations = repository.search(query).ifEmpty {
                if (query.isBlank()) repository.top() else emptyList()
            }
            mutable.value = mutable.value.copy(
                stations = stations,
                loading = false,
                error = if (stations.isEmpty()) "暂时没有找到可用电台，请检查网络或换一个关键词" else null,
            )
        }
    }
}
