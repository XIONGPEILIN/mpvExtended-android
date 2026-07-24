package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProxyStreamOptionsTest {
  @Test
  fun enablesReadThroughOnlyForLoopbackProxy() {
    assertEquals(
      LocalProxyStreamOptions.LAVF_OPTIONS,
      LocalProxyStreamOptions.forUri("http://127.0.0.1:43323/stream-id"),
    )
    assertEquals(
      LocalProxyStreamOptions.LAVF_OPTIONS,
      LocalProxyStreamOptions.forUri("http://localhost:8080/stream-id"),
    )
  }

  @Test
  fun leavesDirectAndLocalFilesUnchanged() {
    assertEquals("", LocalProxyStreamOptions.forUri("https://example.com/video.mp4"))
    assertEquals("", LocalProxyStreamOptions.forUri("file:///storage/emulated/0/video.mp4"))
    assertEquals("", LocalProxyStreamOptions.forUri("not a uri"))
  }

  @Test
  fun disablesMovInterleavingOnlyForProxiedMovFamilyFiles() {
    val proxyUri = "http://127.0.0.1:43323/stream-id"
    assertEquals(
      LocalProxyStreamOptions.MOV_DEMUXER_OPTIONS,
      LocalProxyStreamOptions.demuxerOptionsFor(proxyUri, "badly-interleaved.MP4"),
    )
    assertEquals(
      LocalProxyStreamOptions.MOV_DEMUXER_OPTIONS,
      LocalProxyStreamOptions.demuxerOptionsFor(proxyUri, "video.mov"),
    )
    assertEquals("", LocalProxyStreamOptions.demuxerOptionsFor(proxyUri, "video.mkv"))
    assertEquals(
      "",
      LocalProxyStreamOptions.demuxerOptionsFor(
        "https://example.com/video.mp4",
        "video.mp4",
      ),
    )
  }
}
