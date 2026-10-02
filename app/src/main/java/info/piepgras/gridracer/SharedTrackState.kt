package info.piepgras.gridracer

import info.piepgras.gridracer.engine.TrackDraft
import info.piepgras.gridracer.multiplayer.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the app knows about the server's shared-track library.
 *
 * Seven flows and nine functions that only ever talk to each other, lifted out of
 * [GameViewModel] where they sat among race orchestration, screen transitions and account
 * handling. The ViewModel still owns one of these and exposes its flows, so nothing above it
 * changed - but the browse/download/upload/rate/report cycle can now be read, and reasoned
 * about, without the rest of the app in the way.
 *
 * It holds no connection of its own: [bind] attaches it to whichever [MultiplayerClient] is
 * live, and [reset] detaches it when that client goes away. That mirrors how the connection
 * actually behaves - it is rebuilt whenever the server URL changes.
 */
class SharedTrackState(
    private val scope: CoroutineScope,
    private val customTracks: CustomTrackRepository,
) {
    private val _list = MutableStateFlow<ServerMessage.TrackList?>(null)
    /** The shared-track page last fetched, or null; see [refresh]. */
    val list: StateFlow<ServerMessage.TrackList?> = _list.asStateFlow()

    private val _downloaded = MutableStateFlow<ServerMessage.TrackDownloaded?>(null)
    /** The shared track last downloaded, until saved or dismissed. */
    val downloaded: StateFlow<ServerMessage.TrackDownloaded?> = _downloaded.asStateFlow()

    private val _uploadResult = MutableStateFlow<ServerMessage.TrackUploaded?>(null)
    /** The server's confirmation of the last upload, until cleared. */
    val uploadResult: StateFlow<ServerMessage.TrackUploaded?> = _uploadResult.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    /** Why the last upload failed, until cleared. */
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    /** Why the last download failed, until cleared. */
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    private val _rated = MutableStateFlow<ServerMessage.TrackRated?>(null)
    /** The server's confirmation of the last rating, with the new average, until cleared. */
    val rated: StateFlow<ServerMessage.TrackRated?> = _rated.asStateFlow()

    private val _rateError = MutableStateFlow<String?>(null)
    /** Why the last rating failed, until cleared. */
    val rateError: StateFlow<String?> = _rateError.asStateFlow()

    private var client: MultiplayerClient? = null

    /** Mirrors [client]'s track-sharing flows into this holder. Called when a client connects. */
    fun bind(client: MultiplayerClient) {
        this.client = client
        scope.launch { client.sharedTrackList.collect { _list.value = it } }
        scope.launch { client.downloadedTrack.collect { _downloaded.value = it } }
        scope.launch { client.trackUploadResult.collect { _uploadResult.value = it } }
        scope.launch { client.trackUploadError.collect { _uploadError.value = it } }
        scope.launch { client.trackDownloadError.collect { _downloadError.value = it } }
        scope.launch { client.trackRated.collect { _rated.value = it } }
        scope.launch { client.trackRateError.collect { _rateError.value = it } }
    }

    /** Detaches from the client and clears everything it produced. */
    fun reset() {
        client = null
        _list.value = null
        _downloaded.value = null
        _uploadResult.value = null
        _uploadError.value = null
        _downloadError.value = null
        _rated.value = null
        _rateError.value = null
    }

    /** Ask the server for the catalogue. The result arrives on [list]. */
    fun refresh() {
        client?.listTracks()
    }

    /** Fetch one track's draft and compiled form. The result arrives on [downloaded]. */
    fun download(trackId: String) {
        client?.downloadTrack(trackId)
    }

    /**
     * Saves the most recently downloaded track into the local library.
     *
     * Deduplicated by the *shared* id, not the name: downloading the same community track
     * twice should not leave two copies in the library.
     */
    fun saveDownloaded() {
        val downloaded = _downloaded.value ?: return
        val sharedTrackId = downloaded.summary.trackId
        if (!customTracks.existsBySharedTrackId(sharedTrackId)) {
            customTracks.save(null, downloaded.summary.name, downloaded.draft, downloaded.track, sharedTrackId)
        }
        _downloaded.value = null
    }

    /** Publish a track. Requires an authenticated account; the server refuses otherwise. */
    fun upload(name: String, draft: TrackDraft) {
        client?.uploadTrack(name, draft)
    }

    /** Rate a track 1..5. Re-rating replaces the previous score. */
    fun rate(trackId: String, rating: Int) {
        client?.rateTrack(trackId, rating)
    }

    /** Flag a track for operator review. */
    fun report(trackId: String, reason: String) {
        client?.reportTrack(trackId, reason)
    }

    /** Clears one-shot feedback so a stale toast is not shown again on the next visit. */
    fun clearUploadFeedback() {
        _uploadResult.value = null
        _uploadError.value = null
    }

    /** Clears [downloadError], so a stale error is not shown again. */
    fun clearDownloadError() {
        _downloadError.value = null
    }

    /** Clears [rated] and [rateError], so stale feedback is not shown again. */
    fun clearRateFeedback() {
        _rated.value = null
        _rateError.value = null
    }
}
