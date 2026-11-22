package io.github.currencortex.music.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.network.appResult
import io.github.currencortex.music.data.library.ArtistRelease
import io.github.currencortex.music.data.library.FollowedArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class FollowingFeedState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val artists: List<FollowedArtist> = emptyList(),
    val releases: List<ArtistRelease> = emptyList(),
    val totalArtists: Int = 0,
    val error: String? = null,
)

/** NetEase followed-artist releases. One failed artist must not hide the rest of the feed. */
class FollowingViewModel(val container: AppContainer) : ViewModel() {
    val state = MutableStateFlow(FollowingFeedState())
    private var task: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            container.ready.await()
            container.neteaseSessions.ready.await()
            combine(
                container.neteaseSessions.state,
                container.musicSettings.state,
            ) { account, settings -> account.loggedIn to settings.server }
                .distinctUntilChanged()
                .collect { reload() }
        }
    }

    fun reload() {
        task?.cancel()
        val ticket = ++generation
        val account = container.neteaseSessions.state.value
        if (!account.loggedIn || account.stale) {
            state.value = FollowingFeedState(loaded = true)
            return
        }
        val previous = state.value
        state.value = previous.copy(loading = true, error = null)
        task = viewModelScope.launch {
            try {
                when (val result = appResult { container.libraryRepository.followedArtists(limit = 50) }) {
                    is io.github.currencortex.music.core.network.AppResult.Failure -> {
                        if (ticket == generation) state.value = previous.copy(loading = false, loaded = true,
                            error = result.kind.message)
                    }
                    is io.github.currencortex.music.core.network.AppResult.Success -> {
                        val page = result.value
                        val semaphore = Semaphore(5)
                        val releases = coroutineScope {
                            page.artists.map { artist ->
                                async {
                                    semaphore.withPermit {
                                        runCatching {
                                            container.libraryRepository.artistAlbums(artist.id, 0, "time").first.firstOrNull()
                                        }.getOrNull()?.let { ArtistRelease(artist, it) }
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }.sortedWith(compareByDescending<ArtistRelease> { it.album.publishTime }
                            .thenByDescending { it.artist.name })
                        if (ticket == generation) state.value = FollowingFeedState(
                            loading = false,
                            loaded = true,
                            artists = page.artists,
                            releases = releases,
                            totalArtists = page.total.takeIf { it > 0 } ?: page.artists.size,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (ticket == generation) state.value = previous.copy(loading = false, loaded = true,
                    error = failure.message ?: "关注动态加载失败")
            }
        }
    }

    override fun onCleared() {
        task?.cancel()
        super.onCleared()
    }
}
