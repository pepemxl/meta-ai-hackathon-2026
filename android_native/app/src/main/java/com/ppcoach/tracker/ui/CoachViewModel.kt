package com.ppcoach.tracker.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ppcoach.tracker.AppGraph
import com.ppcoach.tracker.BuildConfig
import com.ppcoach.tracker.analysis.Work
import com.ppcoach.tracker.data.ApiClient
import com.ppcoach.tracker.data.ApiException
import com.ppcoach.tracker.data.Clip
import com.ppcoach.tracker.data.ClipAnalysis
import com.ppcoach.tracker.data.ClipKind
import com.ppcoach.tracker.data.ClipSource
import com.ppcoach.tracker.data.ClipStatus
import com.ppcoach.tracker.data.Consent
import com.ppcoach.tracker.data.Durable
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.Match
import com.ppcoach.tracker.data.CameraMode
import com.ppcoach.tracker.data.AnalysisReportParser
import com.ppcoach.tracker.data.ImportSummary
import com.ppcoach.tracker.data.Player
import com.ppcoach.tracker.data.Stat
import com.ppcoach.tracker.data.StatsCalc
import com.ppcoach.tracker.data.PitchMode
import com.ppcoach.tracker.data.PitchReference
import com.ppcoach.tracker.data.SavedPitch
import com.ppcoach.tracker.data.Stroke
import com.ppcoach.tracker.analysis.ReferenceImages
import androidx.core.content.FileProvider
import com.ppcoach.tracker.data.ProxyPaths
import com.ppcoach.tracker.data.Settings
import com.ppcoach.tracker.data.StorageKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate

sealed interface Screen {
    data object Matches : Screen
    data object Settings : Screen
    data class MatchDetail(val matchId: Int) : Screen
    data class Record(val matchId: Int) : Screen
    data class ClipDetail(val clipId: Int, val seekMs: Long = -1) : Screen
    data class Analysis(val clipId: Int) : Screen
    data class PitchTouches(val clipId: Int) : Screen
    data class PitchReferences(val matchId: Int, val clipId: Int) : Screen
    data class PaintReference(val matchId: Int, val refId: Int) : Screen
    data object Squad : Screen
    data class PlayerStats(val playerId: Int) : Screen
    data class MatchStats(val matchId: Int) : Screen
}

/** Progress of a clip's chain as the UI shows it. */
data class SendProgress(val phase: String, val pct: Int, val chunk: Int, val of: Int, val waiting: Boolean, val finished: Boolean, val failed: Boolean)

sealed interface AnalysisState {
    data object Loading : AnalysisState
    data class Loaded(val analysis: ClipAnalysis) : AnalysisState
    data class Missing(val reason: String) : AnalysisState
}

/** All the app's state: library, settings, the screen stack and one-line messages. */
class CoachViewModel(app: Application) : AndroidViewModel(app) {
    init {
        AppGraph.init(app)
    }

    val library: StateFlow<Library> = AppGraph.library
    var settings by mutableStateOf(AppGraph.settings.load())
        private set
    val stack = mutableStateListOf<Screen>(Screen.Matches)
    var message by mutableStateOf<String?>(null)
    val thumbnails = mutableStateMapOf<Int, Bitmap?>()
    /** Clip waiting for the consent dialog before being sent. */
    var consentFor by mutableStateOf<Int?>(null)
    /** Clip waiting for "Do we compute the pitch?" and whether to send it after the answer. */
    var pitchFor by mutableStateOf<Pair<Int, Boolean>?>(null)
    val analyses = mutableStateMapOf<Int, AnalysisState>()

    private val ctx get() = getApplication<Application>()
    val storage get() = AppGraph.storage(ctx)

    // ── navigation ───────────────────────────────────────────────────────────

    val top: Screen get() = stack.last()
    fun push(s: Screen) = stack.add(s)
    fun root(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    /** Back: pops a screen; on a root that is not Matches, goes to Matches. Returns false to leave the app. */
    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (top != Screen.Matches) {
            root(Screen.Matches)
            return true
        }
        return false
    }

    fun toast(text: String) {
        message = text
    }

    // ── settings ─────────────────────────────────────────────────────────────

    fun updateSettings(change: (Settings) -> Settings) {
        settings = change(settings)
        AppGraph.settings.save(settings)
    }

    // ── matches ──────────────────────────────────────────────────────────────

    fun saveMatch(existing: Match?, edited: Match): Match {
        if (existing != null) {
            AppGraph.commit { it.updateMatch(existing.id) { _ -> edited.copy(id = existing.id, createdAt = existing.createdAt) } }
            return edited
        }
        var created: Match? = null
        AppGraph.commit { lib ->
            val (next, m) = lib.addMatch { id -> edited.copy(id = id, createdAt = System.currentTimeMillis()) }
            created = m
            next
        }
        return created!!
    }

    fun newMatchTemplate() = Match(0, "", "", date = LocalDate.now().toString())

    fun deleteMatch(id: Int) {
        val lib = library.value
        val match = lib.match(id) ?: return
        lib.clipsOf(id).forEach { deleteClipFiles(match, it) }
        AppGraph.commit { it.removeMatch(id) }
        root(Screen.Matches)
    }

    // ── clips ────────────────────────────────────────────────────────────────

    /** Reserves a clip id (never reused) and returns its entry and target file in the current storage location. */
    fun newClipFile(matchId: Int): Pair<Clip, File>? {
        val match = library.value.match(matchId) ?: return null
        val kind = settings.storage.takeIf { it in storage.available() } ?: StorageKind.PHONE
        var id = 0
        AppGraph.commit { l ->
            id = l.nextClipId
            l.copy(nextClipId = id + 1)
        }
        val clip = Clip(id, matchId, "", storage = kind, createdAt = System.currentTimeMillis())
        val file = storage.videoFile(kind, match, clip) ?: return null
        file.parentFile?.mkdirs()
        return clip to file
    }

    /** Registers a recorded or imported file under its reserved id: measures it and adds it to the library. */
    fun addClip(matchId: Int, clipId: Int, file: File, source: ClipSource, storageKind: StorageKind) {
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { measure(file) }
            var created: Clip? = null
            AppGraph.commit { lib ->
                val n = lib.clipsOf(matchId).size + 1
                val kind = ClipKind.forDuration(m.durationMs)
                val s = com.ppcoach.tracker.ui.i18n.stringsFor(settings.language.ifEmpty { java.util.Locale.getDefault().language })
                val c = Clip(
                    id = clipId, matchId = matchId, title = "${s.kindLabel(kind.key)} $n", kind = kind, source = source,
                    createdAt = System.currentTimeMillis(), durationMs = m.durationMs, width = m.width, height = m.height,
                    fps = m.fps, sizeBytes = file.length(), storage = storageKind,
                )
                created = c
                lib.copy(clips = lib.clips + c, nextClipId = maxOf(lib.nextClipId, clipId + 1))
            }
            created?.let { loadThumbnail(it) }
        }
    }

    fun importVideo(matchId: Int, uri: Uri) {
        val (clip, file) = newClipFile(matchId) ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
                } catch (e: IOException) {
                    false
                }
            }
            if (ok) addClip(matchId, clip.id, file, ClipSource.IMPORT, clip.storage) else file.delete()
        }
    }

    fun updateClip(id: Int, change: (Clip) -> Clip) {
        AppGraph.commit { it.updateClip(id, change) }
    }

    fun deleteClip(id: Int) {
        val lib = library.value
        val clip = lib.clip(id) ?: return
        val match = lib.match(clip.matchId) ?: return
        deleteClipFiles(match, clip)
        AppGraph.commit { it.removeClip(id) }
        back()
    }

    private fun deleteClipFiles(match: Match, clip: Clip) {
        Work.cancelClip(ctx, clip.id)
        storage.videoFile(match, clip)?.delete()
        AppGraph.proxyDir(ctx, ProxyPaths.clipDir(match, clip)).deleteRecursively()
        ClipAnalysis.deleteCopies(ctx.filesDir, clip.id)
        thumbnails.remove(clip.id)
    }

    fun videoFile(clip: Clip): File? = library.value.match(clip.matchId)?.let { storage.videoFile(it, clip) }
    fun proxyDir(clip: Clip): File? = library.value.match(clip.matchId)?.let { AppGraph.proxyDir(ctx, ProxyPaths.clipDir(it, clip)) }

    fun deleteProxy(clip: Clip) {
        proxyDir(clip)?.deleteRecursively()
        updateClip(clip.id) { it.copy(chunkCount = 0, chunkSeconds = 0, overlapMs = 0, proxyHeight = 0) }
    }

    /** Copies every clip not in [to] there (copy, verify size, delete). Returns how many moved. */
    fun moveAllTo(to: StorageKind): Int {
        val lib = library.value
        var moved = 0
        for (clip in lib.clips.filter { it.storage != to }) {
            val match = lib.match(clip.matchId) ?: continue
            if (storage.move(match, clip, to)) {
                moved++
                AppGraph.commit { it.updateClip(clip.id) { c -> c.copy(storage = to) } }
            }
        }
        return moved
    }

    data class Measured(val durationMs: Long, val width: Int, val height: Int, val fps: Double)

    /** Duration and size from the container; fps measured from the video samples. */
    private fun measure(file: File): Measured {
        val r = MediaMetadataRetriever()
        var duration = 0L
        var w = 0
        var h = 0
        try {
            r.setDataSource(file.absolutePath)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) w = h.also { h = w }
        } catch (e: Exception) {
            // leave zeros: the clip is still listed
        } finally {
            r.release()
        }
        var fps = 0.0
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            if (track != null) {
                ex.selectTrack(track)
                var frames = 0
                var last = 0L
                while (ex.sampleTime >= 0) {
                    frames++
                    last = ex.sampleTime
                    ex.advance()
                }
                if (last > 0 && frames > 1) fps = (frames - 1) * 1_000_000.0 / last
            }
        } catch (e: Exception) {
            fps = 0.0
        } finally {
            ex.release()
        }
        return Measured(duration, w, h, Math.round(fps * 100) / 100.0)
    }

    fun loadThumbnail(clip: Clip) {
        if (thumbnails.containsKey(clip.id)) return
        thumbnails[clip.id] = null
        val file = videoFile(clip) ?: return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                } catch (e: Exception) {
                    null
                } finally {
                    r.release()
                }
            }
            thumbnails[clip.id] = bmp
        }
    }

    // ── sending ──────────────────────────────────────────────────────────────

    val hasServer: Boolean get() = settings.serverUrl.isNotBlank() && settings.loggedIn

    /** "Send for analysis": consent first (or when the accepted version is outdated). */
    fun send(clipId: Int) {
        if (hasServer && !Consent.isCurrent(settings.consentVersion)) {
            consentFor = clipId
            return
        }
        // "Do we compute the pitch?" once per match: the match's last answer applies to its other videos.
        val clip = library.value.clip(clipId) ?: return
        if (hasServer && clip.pitchMode.isEmpty()) {
            val fromMatch = library.value.match(clip.matchId)?.pitchMode.orEmpty()
            if (PitchMode.valid(fromMatch)) {
                updateClip(clipId) { it.copy(pitchMode = fromMatch) }
            } else {
                pitchFor = clipId to true
                return
            }
        }
        updateClip(clipId) { it.copy(error = "") }
        Work.send(ctx, clipId, upload = hasServer)
    }

    fun answerPitch(mode: String, forMatch: Boolean) {
        val (clipId, sendAfter) = pitchFor ?: return
        pitchFor = null
        val clip = library.value.clip(clipId) ?: return
        updateClip(clipId) { it.copy(pitchMode = mode) }
        if (forMatch) AppGraph.commit { it.updateMatch(clip.matchId) { m -> m.copy(pitchMode = mode) } }
        if (sendAfter) send(clipId)
    }

    /** The "Compute the pitch" switch: off, or back to the match's answer (or asked when sending). */
    fun setPitchEnabled(clipId: Int, on: Boolean) {
        val clip = library.value.clip(clipId) ?: return
        val fromMatch = library.value.match(clip.matchId)?.pitchMode.orEmpty()
        updateClip(clipId) { it.copy(pitchMode = if (!on) PitchMode.OFF else fromMatch.takeIf { m -> m == PitchMode.LINES || m == PitchMode.NO_LINES } ?: "") }
    }

    /** "The camera stays still the whole video": the clip's answer, and the match's for its next videos. */
    fun setCameraStatic(clipId: Int, static: Boolean) {
        val clip = library.value.clip(clipId) ?: return
        val mode = if (static) CameraMode.STATIC else CameraMode.MOVING
        AppGraph.commit { it.updateClip(clipId) { c -> c.copy(camera = mode) }.updateMatch(clip.matchId) { m -> m.copy(camera = mode) } }
    }

    fun saveTouches(clipId: Int, touches: com.ppcoach.tracker.data.PitchTouches?) {
        val kept = touches?.takeIf { t -> !t.isEmpty }
        val clip = library.value.clip(clipId) ?: return
        val pitchId = touches?.pitchId ?: 0
        AppGraph.commit { lib ->
            var next = lib.updateClip(clipId) { it.copy(touches = kept) }
            // The match remembers its pitch and the pitch moves to the top of the list.
            lib.pitch(pitchId)?.let { p ->
                next = next.updateMatch(clip.matchId) { it.copy(pitchId = p.id) }.savePitch(p.copy(lastUsedAt = System.currentTimeMillis())).first
            }
            next
        }
    }

    // ── saved pitches ────────────────────────────────────────────────────────

    /** Saves a new pitch (id 0) or the edited one; returns it with its id. */
    fun savePitch(p: SavedPitch): SavedPitch {
        var saved = p
        AppGraph.commit { lib ->
            val (next, sp) = lib.savePitch(p.copy(createdAt = lib.pitch(p.id)?.createdAt ?: System.currentTimeMillis()))
            saved = sp
            next
        }
        return saved
    }

    fun deletePitch(id: Int) {
        AppGraph.commit { it.removePitch(id) }
    }

    /**
     * Where a clip's marks start: its own, else on the match's pitch, else on
     * the most recently used one, else an unsaved 11-a-side pitch.
     */
    fun startTouches(clip: Clip): com.ppcoach.tracker.data.PitchTouches {
        val lib = library.value
        clip.touches?.let { t ->
            // On a saved pitch, with its meters as they are now.
            val p = lib.pitch(t.pitchId)
            return if (p != null && p.size == t.size) t.on(p) else t
        }
        val p = lib.pitch(lib.match(clip.matchId)?.pitchId ?: 0) ?: lib.pitchesByUse.firstOrNull()
        return com.ppcoach.tracker.data.PitchTouches(tMs = minOf(1000L, clip.durationMs / 2)).on(p, p?.size ?: 11)
    }

    // ── reference pictures of the pitch ─────────────────────────────────────

    fun referenceFile(match: Match, ref: PitchReference): File = AppGraph.referenceFile(ctx, match, ref)

    /** A file and its content URI for the camera app's photo; the reference is added by [addReferencePhoto]. */
    fun newReferencePhoto(matchId: Int): Pair<PitchReference, Uri>? {
        val match = library.value.match(matchId) ?: return null
        if (match.references.size >= PitchReference.MAX) return null
        val ref = PitchReference(match.nextReferenceId, PitchReference.PHOTO, createdAt = System.currentTimeMillis())
        val file = referenceFile(match, ref)
        file.parentFile?.mkdirs()
        return ref to FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    }

    /** The camera app answered: the photo is scaled and kept, or its file dropped. */
    fun addReferencePhoto(matchId: Int, ref: PitchReference, taken: Boolean) {
        val match = library.value.match(matchId) ?: return
        val file = referenceFile(match, ref)
        viewModelScope.launch {
            val ok = taken && withContext(Dispatchers.IO) { ReferenceImages.normalizePhoto(file) }
            if (!ok) {
                file.delete()
                return@launch
            }
            AppGraph.commit { it.updateMatch(matchId) { m -> m.copy(references = m.references + ref) } }
            push(Screen.PaintReference(matchId, ref.id))
        }
    }

    /** A frame of a clip of the match as a reference (where the lines are seen best). */
    fun addReferenceFrame(matchId: Int, clip: Clip, tMs: Long) {
        val match = library.value.match(matchId) ?: return
        if (match.references.size >= PitchReference.MAX) return
        val ref = PitchReference(match.nextReferenceId, PitchReference.FRAME, tMs = tMs, clipId = clip.id, createdAt = System.currentTimeMillis())
        viewModelScope.launch {
            val bmp = frameAt(clip, tMs, ReferenceImages.MAX_SIDE) ?: return@launch
            withContext(Dispatchers.IO) { ReferenceImages.save(bmp, referenceFile(match, ref)) }
            AppGraph.commit { it.updateMatch(matchId) { m -> m.copy(references = m.references + ref) } }
            push(Screen.PaintReference(matchId, ref.id))
        }
    }

    fun saveReferenceStrokes(matchId: Int, refId: Int, strokes: List<Stroke>) {
        AppGraph.commit { it.updateMatch(matchId) { m -> m.copy(references = m.references.map { r -> if (r.id == refId) r.copy(strokes = strokes) else r }) } }
    }

    fun deleteReference(matchId: Int, refId: Int) {
        val match = library.value.match(matchId) ?: return
        match.references.firstOrNull { it.id == refId }?.let { referenceFile(match, it).delete() }
        AppGraph.commit { it.updateMatch(matchId) { m -> m.copy(references = m.references.filterNot { r -> r.id == refId }) } }
    }

    suspend fun loadReference(match: Match, ref: PitchReference, maxSide: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        val file = referenceFile(match, ref)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A frame of the clip's video at [tMs], scaled to [maxWidth]. */
    suspend fun frameAt(clip: Clip, tMs: Long, maxWidth: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        val file = videoFile(clip) ?: return@withContext null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = clip.width.takeIf { it > 0 } ?: 1280
            val h = clip.height.takeIf { it > 0 } ?: 720
            val scale = minOf(1.0, maxWidth.toDouble() / w)
            r.getScaledFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, (w * scale).toInt(), (h * scale).toInt())
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    fun acceptConsent() {
        val at = Consent.isoNow()
        updateSettings { it.copy(consentVersion = Consent.VERSION, consentAt = at) }
        Work.reportConsent(ctx, "accept", Consent.VERSION, at)
        val clip = consentFor
        consentFor = null
        if (clip != null) send(clip)
    }

    fun withdrawConsent() {
        val version = settings.consentVersion.ifEmpty { Consent.VERSION }
        Work.reportConsent(ctx, "withdraw", version, Consent.isoNow())
        Work.cancelUploads(ctx)
        updateSettings { it.copy(consentVersion = "", consentAt = "") }
    }

    fun progress(clipId: Int): Flow<List<WorkInfo>> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(Work.clipChain(clipId))

    fun toProgress(infos: List<WorkInfo>): SendProgress? {
        if (infos.isEmpty()) return null
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val enqueued = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
        val failed = infos.any { it.state == WorkInfo.State.FAILED }
        val finished = infos.all { it.state.isFinished }
        val p = running?.progress
        val phase = p?.getString("phase") ?: if (enqueued != null && enqueued.tags.contains(com.ppcoach.tracker.analysis.UploadWorker.TAG)) "upload" else "prepare"
        return SendProgress(
            phase = phase,
            pct = p?.getInt("pct", 0) ?: 0,
            chunk = p?.getInt("chunk", 0) ?: 0,
            of = p?.getInt("of", 0) ?: 0,
            waiting = running == null && enqueued != null && phase == "upload",
            finished = finished,
            failed = failed,
        )
    }

    /** GET /clips/:remoteId; on failure keeps the last known state. */
    fun refreshRemote(clipId: Int) {
        val clip = library.value.clip(clipId) ?: return
        if (clip.remoteId == 0 || !hasServer) return
        val token = AppGraph.settings.token() ?: return
        viewModelScope.launch {
            val remote = withContext(Dispatchers.IO) { runCatching { ApiClient(settings.serverUrl, token).clip(clip.remoteId) }.getOrNull() } ?: return@launch
            val status = ClipStatus.fromRemote(remote.status) ?: return@launch
            if (status == ClipStatus.UPLOADING) return@launch // our own upload decides
            updateClip(clipId) { it.copy(status = status, error = if (status == ClipStatus.FAILED) remote.jobError ?: it.error else "") }
        }
    }

    // ── session ──────────────────────────────────────────────────────────────

    suspend fun login(server: String, email: String, password: String): String? = withContext(Dispatchers.IO) {
        val s = com.ppcoach.tracker.ui.i18n.stringsFor(settings.language.ifEmpty { java.util.Locale.getDefault().language })
        try {
            val url = ApiClient.normalizeUrl(server)
            val session = ApiClient(url).login(email, password)
            AppGraph.settings.saveSession(session)
            withContext(Dispatchers.Main) {
                updateSettings { it.copy(serverUrl = url) }
                settings = AppGraph.settings.load()
            }
            null
        } catch (e: ApiException) {
            when (e.code) {
                401 -> s.wrongPassword
                429 -> s.tooManyAttempts
                else -> e.message ?: s.connectionFailed
            }
        } catch (e: Exception) {
            s.connectionFailed
        }
    }

    suspend fun testConnection(server: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { ApiClient(server).health().optBoolean("ok") }.getOrDefault(false)
    }

    fun logout() {
        AppGraph.settings.clearSession()
        settings = AppGraph.settings.load()
    }

    // ── squad and stats ──────────────────────────────────────────────────────

    /** Saves a player; false (with a message) when the number is out of range or taken. */
    fun savePlayer(p: Player): Boolean {
        val s = strings()
        val lib = library.value
        if (p.number !in Player.NUMBERS) return false.also { toast(s.numberInvalid) }
        if (lib.players.any { it.number == p.number && it.id != p.id }) return false.also { toast(s.numberTaken) }
        AppGraph.commit { it.savePlayer(if (p.id == 0) p.copy(createdAt = System.currentTimeMillis()) else p).first }
        return true
    }

    fun deletePlayer(id: Int) = AppGraph.commit { it.removePlayer(id) }
    fun addToMatch(matchId: Int, playerId: Int) = AppGraph.commit { it.addToMatch(matchId, playerId) }
    fun removeFromMatch(matchId: Int, playerId: Int) = AppGraph.commit { it.removeFromMatch(matchId, playerId) }
    fun setStat(matchId: Int, playerId: Int, stat: Stat, value: Int?) = AppGraph.commit { it.setStat(matchId, playerId, stat, value) }

    /** Imports an analysis report (JSON) into a match: nothing changes when it is invalid. */
    fun importReport(matchId: Int, uri: Uri) {
        val s = strings()
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val parsed = try {
            AnalysisReportParser.parse(text ?: "")
        } catch (e: AnalysisReportParser.ReportException) {
            toast(s.reportError(e.message ?: ""))
            return
        }
        var summary: ImportSummary? = null
        AppGraph.commit { lib -> lib.applyReport(matchId, parsed, "file", System.currentTimeMillis()).also { summary = it.second }.first }
        summary?.let { toast(s.reportImported(it.players, it.addedNumbers, it.unknownKeys, it.coachKeysIgnored)) }
    }

    /** Links a track of a clip's analysis to a squad number, or unlinks it (null). */
    fun tagTrack(clipId: Int, trackId: Int, number: Int?) = AppGraph.commit { lib ->
        lib.updateClip(clipId) { c -> c.copy(trackTags = if (number == null) c.trackTags - trackId else c.trackTags + (trackId to number)) }
    }

    /** The tagged tracks' seconds into this clip, then every clip's minutes into the match's stats. */
    fun tagsToMatch(clipId: Int, analysis: ClipAnalysis) {
        val s = strings()
        val clip = library.value.clip(clipId) ?: return
        val seconds = StatsCalc.secondsByNumber(analysis.tracks, clip.trackTags)
        var summary: ImportSummary? = null
        AppGraph.commit { lib ->
            lib.updateClip(clipId) { it.copy(playerSeconds = seconds) }
                .syncVideoMinutes(clip.matchId, analysis.segmenter, System.currentTimeMillis())
                .also { summary = it.second }.first
        }
        summary?.let { toast(s.minutesSynced(it.players, it.addedNumbers)) }
    }

    private fun strings() = com.ppcoach.tracker.ui.i18n.stringsFor(settings.language.ifEmpty { java.util.Locale.getDefault().language })

    // ── analysis ─────────────────────────────────────────────────────────────

    /** Shows the local copy at once, then refreshes it from the API when online. */
    fun loadAnalysis(clipId: Int) {
        val clip = library.value.clip(clipId) ?: return
        val s = com.ppcoach.tracker.ui.i18n.stringsFor(settings.language.ifEmpty { java.util.Locale.getDefault().language })
        val copy = ClipAnalysis.copyFile(ctx.filesDir, clip)
        if (copy.exists()) runCatching { ClipAnalysis.parse(copy.readText()) }.getOrNull()?.let { analyses[clipId] = AnalysisState.Loaded(it) }
        if (analyses[clipId] !is AnalysisState.Loaded) analyses[clipId] = AnalysisState.Loading
        val token = AppGraph.settings.token()
        if (clip.remoteId == 0 || settings.serverUrl.isBlank()) {
            if (analyses[clipId] !is AnalysisState.Loaded) analyses[clipId] = AnalysisState.Missing(s.analysisNotYet)
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (token == null) throw ApiException(401, "no session")
                    val text = ApiClient(settings.serverUrl, token).analysis(clip.remoteId)
                    val parsed = ClipAnalysis.parse(text)
                    Durable.writeText(copy, text)
                    AnalysisState.Loaded(parsed)
                } catch (e: ApiException) {
                    AnalysisState.Missing(if (e.code == 401) s.analysisSessionExpired else s.analysisNotYet)
                } catch (e: IOException) {
                    AnalysisState.Missing(s.analysisOffline)
                } catch (e: IllegalArgumentException) {
                    AnalysisState.Missing(s.analysisNotYet)
                }
            }
            if (result is AnalysisState.Loaded || analyses[clipId] !is AnalysisState.Loaded) analyses[clipId] = result
        }
    }

    val appVersion: String = BuildConfig.VERSION_NAME
}
