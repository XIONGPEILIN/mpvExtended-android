package app.marlboroadvance.mpvex.ui.player

import java.net.URI

/**
 * FFmpeg protocol options used only for MPVEX's loopback HTTP proxy.
 *
 * Persistent HTTP requests and short-seek read-through avoid needless loopback reconnects.
 * MOV-family files additionally disable demuxer-level interleaving: otherwise a valid file
 * whose audio and video samples occupy distant regions can alternate huge Range seeks for
 * every packet and discard nearly all bytes fetched from the remote server.
 */
internal object LocalProxyStreamOptions {
  const val LAVF_OPTIONS = "multiple_requests=1,short_seek_size=4194304"
  const val MOV_DEMUXER_OPTIONS = "interleaved_read=0"

  fun forUri(uri: String): String =
    if (isLoopbackHttpUri(uri)) LAVF_OPTIONS else ""

  fun demuxerOptionsFor(
    uri: String,
    mediaName: String?,
  ): String {
    if (!isLoopbackHttpUri(uri)) return ""
    val extension = mediaName?.substringAfterLast('.', "")?.lowercase()
    return if (extension in setOf("mp4", "m4v", "mov")) MOV_DEMUXER_OPTIONS else ""
  }

  private fun isLoopbackHttpUri(value: String): Boolean =
    runCatching {
      val uri = URI(value)
      (uri.scheme.equals("http", ignoreCase = true) ||
        uri.scheme.equals("https", ignoreCase = true)) &&
        (uri.host == "127.0.0.1" || uri.host.equals("localhost", ignoreCase = true))
    }.getOrDefault(false)
}
