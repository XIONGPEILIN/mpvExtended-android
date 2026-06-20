package app.marlboroadvance.mpvex.ui.player

import android.util.Log
import app.marlboroadvance.mpvex.database.entities.NetworkTrackProfileEntity
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.delay

/**
 * Handles automatic track selection based on user preferences.
 * Combines an intelligent multi-pass Context Engine with optimized data structures.
 *
 * **Performance Optimization:**
 * To minimize expensive JNI calls to MPV, all track properties are read exactly once 
 * upon file load and cached into a list of `Track` objects. The selection logic 
 * evaluates this cached list.
 *
 * **State Management (Watch-Later):**
 * If a file is resumed (`hasState = true`), any previously saved track selections—or 
 * a manually saved "subtitles off" state—are strictly respected, completely bypassing 
 * the auto-selection engine.
 *
 * **Audio Selection Strategy (Highest to Lowest Priority):**
 * 1. **Preferred Clean Audio:** Matches the user's preferred language while explicitly 
 * filtering out non-main tracks (e.g., commentary, ADH, descriptions).
 * 2. **Fallback Clean Audio:** Selects the first available track that does not contain 
 * ignored keywords.
 *
 * **Subtitle Selection Strategy (Highest to Lowest Priority):**
 * Subtitle selection is highly dependent on the auto-detected media context (Anime vs. Live-Action).
 * - **Pass 00 (External Override):** Automatically prioritizes manually loaded external subtitle files.
 * - **Pass A0 (Anime Only - Native Default):** If exactly *one* subtitle track is flagged 
 * as default and it is Japanese, it is selected. This protects against muxing errors 
 * where multiple tracks are incorrectly flagged as default by the encoder.
 * - **Pass A (Anime Only - Smart Dialogue):** Prioritizes tracks matching the preferred 
 * language that contain keywords like "dialogue", "full", or "script".
 * - **Pass B (Clean Match):** Finds the preferred language but aggressively strips out 
 * secondary tracks like "signs", "songs", "lyrics", "sdh", or "forced".
 * - **Pass C (Last Resort):** Selects the first available track matching the preferred language.
 */
 
class TrackSelector(
  private val audioPreferences: AudioPreferences,
  private val subtitlesPreferences: SubtitlesPreferences,
) {
  companion object {
    private const val TAG = "TrackSelector"

    // Identifies the directory/playlist the current session memory belongs to, so a
    // remembered choice only follows within the same folder/playlist and does not bleed
    // into an unrelated standalone file opened later in the same app session.
    @Volatile var sessionScope: String? = null

    // Session memory to remember last manual selections
    @Volatile var lastManualAudioLang: String? = null
    @Volatile var lastManualAudioTitle: String? = null
    @Volatile var lastManualAudioId: Int? = null
    @Volatile var lastManualAudioTrackNumber: Int? = null
    @Volatile var lastManualSubLang: String? = null
    @Volatile var lastManualSubTitle: String? = null
    @Volatile var lastManualSubId: Int? = null
    @Volatile var lastManualSubTrackNumber: Int? = null
    @Volatile var lastManualSubIsExternal: Boolean? = null
  }

  // The Data Class for massively improved performance.
  private data class Track(
    val id: Int,
    val type: String,
    val trackNumber: Int,
    val lang: String,
    val title: String,
    val isDefault: Boolean,
    val forced: Boolean,
    val hearing: Boolean,
    val external: Boolean,
    val image: Boolean
  )

  suspend fun onFileLoaded(hasState: Boolean = false, scope: String? = null) {
    // If we've moved to a different directory/playlist, drop the carried-over session
    // choice so it can't override an unrelated file's own saved/auto selection.
    if (scope != sessionScope) {
      Log.d(TAG, "Smart Tracks: scope changed ($sessionScope -> $scope); resetting session track memory")
      resetManualSelectionMemory()
      sessionScope = scope
    }

    var attempts = 0
    val maxAttempts = 20
    
    while (attempts < maxAttempts) {
      val count = MPVLib.getPropertyInt("track-list/count") ?: 0
      if (count > 0) break
      delay(50)
      attempts++
    }

    val trackCount = MPVLib.getPropertyInt("track-list/count") ?: 0
    if (trackCount == 0) return

    // Read all tracks once
    val tracks = readTracks(trackCount)

    if (!isVideoFile(tracks)) {
      Log.d(TAG, "Smart Tracks: Audio/Image file detected. Script disabled.")
      return
    }
  
    ensureAudioTrackSelected(tracks, hasState)
    ensureSubtitleTrackSelected(tracks, hasState)
  }

  /**
   * Re-runs subtitle selection only. Used after external subtitle files have been
   * autoloaded asynchronously (network shares / sibling files), so that a remembered
   * external/track choice can finally match a track that did not exist during the
   * initial [onFileLoaded] pass.
   */
  suspend fun reselectSubtitlesNow(hasState: Boolean) {
    val trackCount = MPVLib.getPropertyInt("track-list/count") ?: 0
    if (trackCount == 0) return
    val tracks = readTracks(trackCount)
    if (!isVideoFile(tracks)) return
    ensureSubtitleTrackSelected(tracks, hasState)
  }

  fun saveCurrentTrackSelection() {
    try {
      val trackCount = MPVLib.getPropertyInt("track-list/count") ?: 0
      if (trackCount == 0) return

      val tracks = readTracks(trackCount)
      val currentAid = MPVLib.getPropertyInt("aid") ?: -1
      val currentSid = MPVLib.getPropertyInt("sid") ?: -1

      val selectedAudio = tracks.find { it.type == "audio" && it.id == currentAid }
      val selectedSub = tracks.find { it.type == "sub" && it.id == currentSid }

      if (selectedAudio != null) {
        lastManualAudioLang = selectedAudio.lang
        lastManualAudioTitle = selectedAudio.title
        lastManualAudioId = selectedAudio.id
        lastManualAudioTrackNumber = selectedAudio.trackNumber
        Log.d(TAG, "Saved last selected audio track: lang=${selectedAudio.lang}, title=${selectedAudio.title}, id=${selectedAudio.id}")
      } else if (currentAid == -1) {
        lastManualAudioId = -1
        lastManualAudioTrackNumber = null
        lastManualAudioLang = null
        lastManualAudioTitle = null
      }

      if (selectedSub != null) {
        lastManualSubLang = selectedSub.lang
        lastManualSubTitle = selectedSub.title
        lastManualSubId = selectedSub.id
        lastManualSubTrackNumber = selectedSub.trackNumber
        lastManualSubIsExternal = selectedSub.external
        Log.d(TAG, "Saved last selected sub track: lang=${selectedSub.lang}, title=${selectedSub.title}, id=${selectedSub.id}, external=${selectedSub.external}")
      } else if (currentSid == -1) {
        lastManualSubId = -1
        lastManualSubTrackNumber = null
        lastManualSubLang = null
        lastManualSubTitle = null
        lastManualSubIsExternal = null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save current track selection", e)
    }
  }

  fun hydrateFromProfile(profile: NetworkTrackProfileEntity) {
    lastManualAudioTrackNumber = profile.audioTrackNumber
    lastManualAudioLang = profile.audioLang?.lowercase()
    lastManualAudioTitle = profile.audioTitle?.lowercase()
    lastManualAudioId = null

    lastManualSubTrackNumber = profile.subtitleTrackNumber
    lastManualSubLang = profile.subtitleLang?.lowercase()
    lastManualSubTitle = profile.subtitleTitle?.lowercase()
    lastManualSubId = if (profile.subtitleMode == "off") -1 else null
    lastManualSubIsExternal = profile.subtitleIsExternal
  }

  fun resetManualSelectionMemory() {
    lastManualAudioLang = null
    lastManualAudioTitle = null
    lastManualAudioId = null
    lastManualAudioTrackNumber = null
    lastManualSubLang = null
    lastManualSubTitle = null
    lastManualSubId = null
    lastManualSubTrackNumber = null
    lastManualSubIsExternal = null
  }

  fun buildProfile(connectionId: Long, directoryPath: String): NetworkTrackProfileEntity? {
    return try {
      val trackCount = MPVLib.getPropertyInt("track-list/count") ?: 0
      if (trackCount == 0) return null
      val tracks = readTracks(trackCount)
      val currentAid = MPVLib.getPropertyInt("aid") ?: -1
      val currentSidRaw = MPVLib.getPropertyString("sid")
      val currentSid = MPVLib.getPropertyInt("sid") ?: -1

      val selectedAudio = tracks.find { it.type == "audio" && it.id == currentAid }
      val selectedSub = tracks.find { it.type == "sub" && it.id == currentSid }
      val subtitleMode = when {
        currentSidRaw == null || currentSidRaw == "no" || currentSid <= 0 -> "off"
        selectedSub?.external == true -> "external"
        selectedSub != null -> "internal"
        else -> "unset"
      }

      NetworkTrackProfileEntity(
        connectionId = connectionId,
        directoryPath = directoryPath,
        audioTrackNumber = selectedAudio?.trackNumber,
        audioLang = selectedAudio?.lang,
        audioTitle = selectedAudio?.title,
        subtitleMode = subtitleMode,
        subtitleTrackNumber = selectedSub?.trackNumber,
        subtitleLang = selectedSub?.lang,
        subtitleTitle = selectedSub?.title,
        subtitleIsExternal = selectedSub?.external,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to build network track profile", e)
      null
    }
  }

  private fun readTracks(count: Int): List<Track> {
    val list = mutableListOf<Track>()
    var audioTrackNumber = 0
    var subtitleTrackNumber = 0
    for (i in 0 until count) {
      val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
      val type = MPVLib.getPropertyString("track-list/$i/type") ?: continue
      val trackNumber = when (type) {
        "audio" -> ++audioTrackNumber
        "sub" -> ++subtitleTrackNumber
        else -> 0
      }

      list.add(
        Track(
          id = id,
          type = type,
          trackNumber = trackNumber,
          lang = (MPVLib.getPropertyString("track-list/$i/lang") ?: "").lowercase(),
          title = (MPVLib.getPropertyString("track-list/$i/title") ?: "").lowercase(),
          isDefault = MPVLib.getPropertyBoolean("track-list/$i/default") ?: false,
          forced = MPVLib.getPropertyBoolean("track-list/$i/forced") ?: false,
          hearing = MPVLib.getPropertyBoolean("track-list/$i/hearing-impaired") ?: false,
          external = MPVLib.getPropertyBoolean("track-list/$i/external") ?: false,
          image = MPVLib.getPropertyBoolean("track-list/$i/image") ?: false
        )
      )
    }
    return list
  }

  // ==================================================
  // AUTO-DETECTION HELPERS
  // ==================================================

  private fun isVideoFile(tracks: List<Track>): Boolean {
    return tracks.any { it.type == "video" && !it.image }
  }

  /**
   * Returns the trailing "language tag" of a subtitle filename/title for matching the
   * same subtitle variant across episodes, e.g. "Show.S01E02.cht.ass" -> "cht.ass".
   */
  private fun subtitleTag(title: String): String =
    title.substringAfterLast('/').substringAfterLast('\\').split('.').takeLast(2).joinToString(".")

  private fun isAnimeFolder(path: String?): Boolean {
    if (path == null) return false
    val p = path.lowercase()
    return p.contains("/anime/") || p.contains("\\anime\\") ||
           p.contains("donghua") || p.contains("cartoon") ||
           p.contains("animation") || p.contains("3d_anime")
  }

  private fun isLiveAction(path: String?, title: String?): Boolean {
    val searchStr = "${path ?: ""} ${title ?: ""}".lowercase()
    return searchStr.contains("live action") || searchStr.contains("live-action") ||
           searchStr.contains("liveaction") || searchStr.contains("drama") ||
           searchStr.contains("real person")
  }

  private fun detectAnimeContext(tracks: List<Track>): Boolean {
    val path = MPVLib.getPropertyString("path") ?: ""
    val title = MPVLib.getPropertyString("media-title") ?: ""
    val filename = MPVLib.getPropertyString("filename") ?: ""

    val signalFolder = isAnimeFolder(path)
    val signalLiveAction = isLiveAction(path, title)
    
    val syntaxRegex = Regex("\\[.*\\]")
    val signalSyntax = syntaxRegex.containsMatchIn(title)

    val crcRegex = Regex("\\[[0-9a-fA-F]{8}\\]")
    val signalCrc = crcRegex.containsMatchIn(filename) || crcRegex.containsMatchIn(title)

    val signalAudio = tracks.any { it.type == "audio" && (it.lang == "jpn" || it.lang == "ja") }

    if (signalLiveAction) return false
    if (signalCrc) return true
    if (signalFolder || signalAudio || signalSyntax) return true
    
    return false
  }

  // ==================================================
  // 1. AUDIO SELECTION LOGIC (Multi-Pass Preserved)
  // ==================================================

  private suspend fun ensureAudioTrackSelected(tracks: List<Track>, hasState: Boolean) {
    try {
      val currentAid = MPVLib.getPropertyInt("aid")
      if (hasState) {
        Log.d(TAG, "Smart Audio: Resuming from saved state. Respecting current choice.")
        return
      }

      val audioTracks = tracks.filter { it.type == "audio" }

      // Session Priority 1: Restore last manually selected track in current session
      if (lastManualAudioId == -1) {
        Log.d(TAG, "Smart Audio: Restoring manual 'audio off' state from session")
        MPVLib.setPropertyString("aid", "no")
        return
      }

      val manualAudioTrackNumber = lastManualAudioTrackNumber
      if (manualAudioTrackNumber != null && manualAudioTrackNumber > 0) {
        val match = audioTracks.find { it.trackNumber == manualAudioTrackNumber }
        if (match != null) {
          Log.d(TAG, "Smart Audio: Inheriting session choice by track number (id=${match.id})")
          MPVLib.setPropertyInt("aid", match.id)
          return
        }
      }

      if (lastManualAudioLang != null) {
        val match = audioTracks.find { 
          it.lang == lastManualAudioLang && 
          (lastManualAudioTitle == null || it.title == lastManualAudioTitle) 
        } ?: audioTracks.find { it.lang == lastManualAudioLang }

        if (match != null) {
          Log.d(TAG, "Smart Audio: Inheriting session choice by language/title (id=${match.id})")
          MPVLib.setPropertyInt("aid", match.id)
          return
        }
      }

      val manualAudioId = lastManualAudioId
      if (manualAudioId != null && manualAudioId > 0) {
        val match = audioTracks.find { it.id == manualAudioId }
        if (match != null) {
          Log.d(TAG, "Smart Audio: Inheriting session choice by track ID (id=${match.id})")
          MPVLib.setPropertyInt("aid", match.id)
          return
        }
      }

      // Default Priority 2: Preferred clean audio
      val preferredLangs = audioPreferences.preferredLanguages.get()
        .split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

      val ignoreKeywords = listOf("commentary", "description", "adh", "comment", "extra")

      if (preferredLangs.isNotEmpty()) {
        for (prefLang in preferredLangs) {
          for (track in audioTracks) {
            if (track.lang == prefLang || track.lang.startsWith(prefLang)) {
              if (ignoreKeywords.none { track.title.contains(it) }) {
                if (currentAid == track.id) {
                  Log.d(TAG, "Smart Audio: Selected ${track.lang} (id=${track.id}) [Already Active. Skipping Change.]")
                } else {
                  Log.d(TAG, "Smart Audio: Selected ${track.lang} (id=${track.id}) [Applied]")
                  MPVLib.setPropertyInt("aid", track.id)
                }
                return
              }
            }
          }
        }
      }

      // Default Priority 3: Fallback MPV default
      if (currentAid != null && currentAid > 0) return

      // Default Priority 4: First available clean audio track
      for (track in audioTracks) {
        if (ignoreKeywords.none { track.title.contains(it) }) {
          if (currentAid == track.id) {
            Log.d(TAG, "Smart Audio: Fallback (id=${track.id}) [Already Active. Skipping Change.]")
          } else {
            Log.d(TAG, "Smart Audio: Fallback (id=${track.id}) [Applied]")
            MPVLib.setPropertyInt("aid", track.id)
          }
          return
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Audio selection failed", e)
    }
  }

  // ==================================================
  // 2. SUBTITLE SELECTION LOGIC (Multi-Pass Preserved)
  // ==================================================

  /**
   * Applies a subtitle choice carried over from earlier in this play session.
   * Returns true if a choice was applied (so the caller skips saved-state/auto logic).
   *
   * This deliberately runs BEFORE the saved-state branch so that a subtitle the user
   * picked (or that was active on the previous playlist item) follows them across the
   * playlist, instead of being overridden by a stale per-file saved track or the
   * autoloaded first external subtitle.
   */
  private fun applySessionSubtitleChoice(subTracks: List<Track>): Boolean {
    // Restore manual "subtitles off" state from the session.
    if (lastManualSubId == -1) {
      Log.d(TAG, "Smart Sub: Restoring manual 'subtitles off' state from session")
      MPVLib.setPropertyString("sid", "no")
      return true
    }

    if (lastManualSubIsExternal == true) {
      // The remembered choice was an external (sidecar) subtitle. Its track number is
      // not stable across videos, so match by "is external" first. If this video has no
      // external sub yet (autoload may have found none), fall through to language/title.
      val externals = subTracks.filter { it.external }
      if (externals.isNotEmpty()) {
        val rememberedLang = lastManualSubLang
        val rememberedTitle = lastManualSubTitle
        // Match the previously chosen external as closely as possible, so e.g. a
        // Traditional-Chinese (.cht) choice isn't replaced by Simplified (.chs) on the
        // next episode where only the episode number differs in the filename.
        val match =
          // 1. Identical language + title (same filename – rare across episodes)
          externals.find { !rememberedLang.isNullOrBlank() && it.lang == rememberedLang && it.title == rememberedTitle }
          // 2. Same language tag detected by mpv (cht / chs / eng / jpn ...)
            ?: externals.find { !rememberedLang.isNullOrBlank() && it.lang == rememberedLang }
          // 3. Same trailing filename tag, e.g. "cht.ass" == "cht.ass"
            ?: rememberedTitle?.let { t -> externals.find { subtitleTag(it.title) == subtitleTag(t) } }
          // 4. Fall back to the first external subtitle
            ?: externals.first()
        Log.d(TAG, "Smart Sub: Prioritizing external subtitle as requested by last manual selection (id=${match.id}, lang=${match.lang}, title=${match.title})")
        MPVLib.setPropertyInt("sid", match.id)
        return true
      }
    } else {
      val manualSubTrackNumber = lastManualSubTrackNumber
      if (manualSubTrackNumber != null && manualSubTrackNumber > 0) {
        val match = subTracks.find { it.trackNumber == manualSubTrackNumber && !it.external }
        if (match != null) {
          Log.d(TAG, "Smart Sub: Inheriting session choice by track number (id=${match.id})")
          MPVLib.setPropertyInt("sid", match.id)
          return true
        }
      }
    }

    if (lastManualSubLang != null) {
      val match = subTracks.find {
        it.lang == lastManualSubLang &&
        (lastManualSubTitle == null || it.title == lastManualSubTitle)
      } ?: subTracks.find { it.lang == lastManualSubLang }

      if (match != null) {
        Log.d(TAG, "Smart Sub: Inheriting session choice by language/title (id=${match.id})")
        MPVLib.setPropertyInt("sid", match.id)
        return true
      }
    }

    val manualSubId = lastManualSubId
    if (manualSubId != null && manualSubId > 0) {
      val match = subTracks.find { it.id == manualSubId }
      if (match != null) {
        Log.d(TAG, "Smart Sub: Inheriting session choice by track ID (id=${match.id})")
        MPVLib.setPropertyInt("sid", match.id)
        return true
      }
    }

    return false
  }

  private suspend fun ensureSubtitleTrackSelected(tracks: List<Track>, hasState: Boolean) {
    try {
      val subTracks = tracks.filter { it.type == "sub" }

      // Session inheritance takes priority over the per-file saved state for the subtitle
      // TRACK choice, so a subtitle picked earlier follows across the playlist.
      if (applySessionSubtitleChoice(subTracks)) return

      val currentSid = MPVLib.getPropertyInt("sid") ?: 0

      if (hasState) {
        // No in-session choice to inherit: respect the per-file saved selection.
        val realSid = MPVLib.getPropertyString("sid")
        if (realSid == null || realSid == "no" || currentSid <= 0) {
          Log.d(TAG, "Smart Sub: Resuming from saved state. Subtitles were disabled. Respecting choice.")
          MPVLib.setPropertyString("sid", "no")
          return
        }
        Log.d(TAG, "Smart Sub: Resuming from saved state. Respecting current subtitle track: $currentSid")
        return
      }

      val isAnimeContext = detectAnimeContext(tracks)
      Log.d(TAG, "Smart Tracks: Context defined by Internal Auto-Detection -> $isAnimeContext")

      var preferredLangs = subtitlesPreferences.preferredLanguages.get()
        .split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

      if (preferredLangs.isEmpty()) {
        preferredLangs = (MPVLib.getPropertyString("slang") ?: "")
          .split(",")
          .map { it.trim().lowercase() }
          .filter { it.isNotEmpty() }
      }
      if (preferredLangs.isEmpty()) preferredLangs = listOf("eng", "en")

      val ignoreSubs = listOf("signs", "songs", "lyrics", "forced", "sdh", "colored", "karaoke")

      // PASS 00: EXTERNAL TRACK OVERRIDE (Protects manually loaded subtitle files)
      for (track in subTracks) {
        if (track.external) {
          if (currentSid == track.id) {
            Log.d(TAG, "Smart Sub: External Subtitle Detected (id=${track.id}) [Already Active. Skipping Change.]")
          } else {
            Log.d(TAG, "Smart Sub: External Subtitle Detected (id=${track.id}) [Applied]")
            MPVLib.setPropertyInt("sid", track.id)
          }
          return
        }
      }

      // PASS A0: KEEP FILE'S NATIVE DEFAULT JAPANESE SUBS FOR ANIME
      if (isAnimeContext) {
        val defaultCount = subTracks.count { it.isDefault }

        if (defaultCount == 1) {
          for (track in subTracks) {
            if (track.isDefault) {
              if (track.lang == "jpn" || track.lang == "ja" || track.lang == "jp") {
                if (currentSid == track.id) {
                  Log.d(TAG, "Smart Sub: Native File Default Japanese Sub (id=${track.id}) [Already Active. Skipping Change.]")
                } else {
                  Log.d(TAG, "Smart Sub: Native File Default Japanese Sub (id=${track.id}) [Applied]")
                  MPVLib.setPropertyInt("sid", track.id)
                }
                return
              }
            }
          }
        } else if (defaultCount > 1) {
          Log.d(TAG, "Smart Sub: Multiple default tracks detected (Muxing error). Ignoring.")
        }
      }

      // PASS A: SMART ANIME DIALOGUE
      if (isAnimeContext) {
        for (prefLang in preferredLangs) {
          for (track in subTracks) {
            if (track.lang == prefLang || track.lang.startsWith(prefLang)) {
              if (track.title.contains("dialogue") || track.title.contains("full") || track.title.contains("script")) {
                if (currentSid == track.id) {
                  Log.d(TAG, "Smart Sub: Anime Dialogue matched (id=${track.id}) [Already Active. Skipping Change.]")
                } else {
                  Log.d(TAG, "Smart Sub: Anime Dialogue matched (id=${track.id}) [Applied]")
                  MPVLib.setPropertyInt("sid", track.id)
                }
                return
              }
            }
          }
        }
      }

      // PASS B: CLEAN LANGUAGE MATCH
      for (prefLang in preferredLangs) {
        for (track in subTracks) {
          if (track.lang == prefLang || track.lang.startsWith(prefLang)) {
            if (ignoreSubs.none { track.title.contains(it) } && !track.forced && !track.hearing) {
              if (currentSid == track.id) {
                Log.d(TAG, "Smart Sub: Clean Match (id=${track.id}) [Already Active. Skipping Change.]")
              } else {
                Log.d(TAG, "Smart Sub: Clean Match (id=${track.id}) [Applied]")
                MPVLib.setPropertyInt("sid", track.id)
              }
              return
            }
          }
        }
      }
      
      // PASS C: LAST RESORT MATCHING
      for (prefLang in preferredLangs) {
        for (track in subTracks) {
          if (track.lang == prefLang || track.lang.startsWith(prefLang)) {
            if (currentSid == track.id) {
              Log.d(TAG, "Smart Sub: Fallback Match (id=${track.id}) [Already Active. Skipping Change.]")
            } else {
              Log.d(TAG, "Smart Sub: Fallback Match (id=${track.id}) [Applied]")
              MPVLib.setPropertyInt("sid", track.id)
            }
            return
          }
        }
      }

    } catch (e: Exception) {
      Log.e(TAG, "Subtitle selection failed", e)
    }
  }
}
