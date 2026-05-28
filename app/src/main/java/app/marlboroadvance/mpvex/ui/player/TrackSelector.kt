package app.marlboroadvance.mpvex.ui.player

import android.util.Log
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

    // Session memory to remember last manual selections
    @Volatile var lastManualAudioLang: String? = null
    @Volatile var lastManualAudioTitle: String? = null
    @Volatile var lastManualAudioId: Int? = null
    @Volatile var lastManualSubLang: String? = null
    @Volatile var lastManualSubTitle: String? = null
    @Volatile var lastManualSubId: Int? = null
    @Volatile var lastManualSubIsExternal: Boolean? = null
  }

  // The Data Class for massively improved performance.
  private data class Track(
    val id: Int,
    val type: String,
    val lang: String,
    val title: String,
    val isDefault: Boolean,
    val forced: Boolean,
    val hearing: Boolean,
    val external: Boolean,
    val image: Boolean
  )

  suspend fun onFileLoaded(hasState: Boolean = false) {
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
        Log.d(TAG, "Saved last selected audio track: lang=${selectedAudio.lang}, title=${selectedAudio.title}, id=${selectedAudio.id}")
      } else if (currentAid == -1) {
        lastManualAudioId = -1
        lastManualAudioLang = null
        lastManualAudioTitle = null
      }

      if (selectedSub != null) {
        lastManualSubLang = selectedSub.lang
        lastManualSubTitle = selectedSub.title
        lastManualSubId = selectedSub.id
        lastManualSubIsExternal = selectedSub.external
        Log.d(TAG, "Saved last selected sub track: lang=${selectedSub.lang}, title=${selectedSub.title}, id=${selectedSub.id}, external=${selectedSub.external}")
      } else if (currentSid == -1) {
        lastManualSubId = -1
        lastManualSubLang = null
        lastManualSubTitle = null
        lastManualSubIsExternal = null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save current track selection", e)
    }
  }

  private fun readTracks(count: Int): List<Track> {
    val list = mutableListOf<Track>()
    for (i in 0 until count) {
      val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
      val type = MPVLib.getPropertyString("track-list/$i/type") ?: continue

      list.add(
        Track(
          id = id,
          type = type,
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

  private suspend fun ensureSubtitleTrackSelected(tracks: List<Track>, hasState: Boolean) {
    try {
      val currentSid = MPVLib.getPropertyInt("sid") ?: 0

      if (hasState) {
        // Respect manual "Subtitles Off" state (sid = 0 or -1 in MPV)
        val realSid = MPVLib.getPropertyString("sid")
        if (realSid == null || realSid == "no" || currentSid <= 0) {
          Log.d(TAG, "Smart Sub: Resuming from saved state. Subtitles were disabled. Respecting choice.")
          MPVLib.setPropertyString("sid", "no")
          return
        }
        Log.d(TAG, "Smart Sub: Resuming from saved state. Respecting current subtitle track: $currentSid")
        return
      }

      val subTracks = tracks.filter { it.type == "sub" }

      // Session Priority 1: Restore last manually selected track in current session
      if (lastManualSubId == -1) {
        Log.d(TAG, "Smart Sub: Restoring manual 'subtitles off' state from session")
        MPVLib.setPropertyString("sid", "no")
        return
      }

      if (lastManualSubIsExternal == true) {
        val externalSub = subTracks.find { it.external }
        if (externalSub != null) {
          Log.d(TAG, "Smart Sub: Prioritizing external subtitle as requested by last manual selection (id=${externalSub.id})")
          MPVLib.setPropertyInt("sid", externalSub.id)
          return
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
          return
        }
      }

      val manualSubId = lastManualSubId
      if (manualSubId != null && manualSubId > 0) {
        val match = subTracks.find { it.id == manualSubId }
        if (match != null) {
          Log.d(TAG, "Smart Sub: Inheriting session choice by track ID (id=${match.id})")
          MPVLib.setPropertyInt("sid", match.id)
          return
        }
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
