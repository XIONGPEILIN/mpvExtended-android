package app.marlboroadvance.mpvex.ui.player

/** Chapter lengths are inferred from the next start, or duration for the final chapter. */
internal object ChapterSkipPolicy {
  fun target(position: Double, start: Double, end: Double, duration: Double): Double? {
    if (listOf(position, start, end, duration).any { !it.isFinite() }) return null
    if (duration <= 0 || start < 0 || end > duration || end <= start) return null
    if (position < start || position >= end) return null
    return end.takeIf { end - start in 80.0..100.0 }
  }
}

/** One instance per file load. Toggling the feature never clears its history. */
internal class ChapterSkipSession {
  private val skipped = mutableSetOf<Int>()

  fun claim(chapter: Int, position: Double, start: Double, end: Double, duration: Double): Double? {
    if (chapter < 0 || chapter in skipped) return null
    val target = ChapterSkipPolicy.target(position, start, end, duration) ?: return null
    skipped.add(chapter)
    return target
  }
}
