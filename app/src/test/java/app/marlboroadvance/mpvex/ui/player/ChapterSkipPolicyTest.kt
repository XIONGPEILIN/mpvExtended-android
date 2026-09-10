package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterSkipPolicyTest {
  @Test fun skipsEachChapterOnlyOnceEvenAfterSeekingBack() {
    val session = ChapterSkipSession()
    assertEquals(90.0, session.claim(0, 0.0, 0.0, 90.0, 1440.0)!!, 0.0)
    assertEquals(300.0, session.claim(2, 220.0, 210.0, 300.0, 1440.0)!!, 0.0)
    assertNull(session.claim(0, 30.0, 0.0, 90.0, 1440.0))
    assertNull(session.claim(2, 215.0, 210.0, 300.0, 1440.0))
    assertEquals(90.0, ChapterSkipSession().claim(0, 0.0, 0.0, 90.0, 1440.0)!!, 0.0)
  }

  @Test fun invalidTimingDoesNotConsumeTheOneTimeSkip() {
    val session = ChapterSkipSession()
    assertNull(session.claim(0, 0.0, 0.0, 90.0, 0.0))
    assertEquals(90.0, session.claim(0, 0.0, 0.0, 90.0, 1440.0)!!, 0.0)
  }

  @Test fun skipsToChapterEndNotNinetySecondsFromCurrentPosition() {
    assertEquals(120.0, ChapterSkipPolicy.target(65.0, 30.0, 120.0, 1440.0)!!, 0.0)
  }

  @Test fun acceptsEightyToOneHundredSecondsInclusive() {
    for (length in listOf(80.0, 85.0, 89.95, 90.0, 90.08, 95.0, 100.0)) {
      assertEquals(length, ChapterSkipPolicy.target(0.0, 0.0, length, 1440.0)!!, 0.0)
    }
    for (length in listOf(0.0, 30.0, 79.99, 100.01, 120.0)) {
      assertNull(ChapterSkipPolicy.target(0.0, 0.0, length, 1440.0))
    }
  }

  @Test fun supportsLastChapterUsingVideoDuration() {
    assertEquals(1440.0, ChapterSkipPolicy.target(1360.0, 1350.0, 1440.0, 1440.0)!!, 0.0)
  }

  @Test fun ignoresPositionsOutsideChapterIncludingExactEnd() {
    assertNull(ChapterSkipPolicy.target(29.0, 30.0, 120.0, 1440.0))
    assertNull(ChapterSkipPolicy.target(120.0, 30.0, 120.0, 1440.0))
    assertEquals(120.0, ChapterSkipPolicy.target(30.0, 30.0, 120.0, 1440.0)!!, 0.0)
  }

  @Test fun rejectsInvalidOrUnknownTiming() {
    assertNull(ChapterSkipPolicy.target(0.0, 0.0, 90.0, 0.0))
    assertNull(ChapterSkipPolicy.target(0.0, -1.0, 89.0, 1440.0))
    assertNull(ChapterSkipPolicy.target(0.0, 0.0, 90.0, 80.0))
    assertNull(ChapterSkipPolicy.target(Double.NaN, 0.0, 90.0, 1440.0))
    assertNull(ChapterSkipPolicy.target(0.0, 0.0, Double.POSITIVE_INFINITY, 1440.0))
  }
}
