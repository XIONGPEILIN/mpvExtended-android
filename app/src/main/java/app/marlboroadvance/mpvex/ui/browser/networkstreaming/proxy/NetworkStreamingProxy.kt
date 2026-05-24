package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Local HTTP proxy server that enables seeking for network streaming protocols
 */
class NetworkStreamingProxy private constructor() : NanoHTTPD("127.0.0.1", 0) {

  companion object {
    private const val TAG = "NetworkStreamingProxy"

    @Volatile
    private var instance: NetworkStreamingProxy? = null

    fun getInstance(): NetworkStreamingProxy {
      return instance ?: synchronized(this) {
        instance ?: NetworkStreamingProxy().also {
          it.start()
          instance = it
        }
      }
    }

    fun stopInstance() {
      synchronized(this) {
        instance?.let { proxy ->
          proxy.stop()
          proxy.cleanup()
          instance = null
        }
      }
    }
  }

  // Store active connections and their clients
  private val activeStreams = ConcurrentHashMap<String, StreamInfo>()
  // Global cache of network clients to reuse connections (Connection Pooling)
  private val clientCache = ConcurrentHashMap<Long, NetworkClient>()

  data class StreamInfo(
    val connection: NetworkConnection,
    val filePath: String,
    val client: NetworkClient,
    var fileSize: Long = -1L,
    var mimeType: String = "video/mp4",
    var title: String? = null,
  )

  /**
   * Register a stream for proxying
   */
  fun registerStream(
    streamId: String,
    connection: NetworkConnection,
    filePath: String,
    fileSize: Long = -1L,
    mimeType: String = "video/mp4",
    title: String? = null,
  ): String {
    // Reuse existing client for this connection ID to prevent connection exhaustion
    val client = clientCache.getOrPut(connection.id) {
      NetworkClientFactory.createClient(connection)
    }

    val streamInfo = StreamInfo(
      connection = connection,
      filePath = filePath,
      client = client,
      fileSize = fileSize,
      mimeType = mimeType,
      title = title,
    )

    activeStreams[streamId] = streamInfo

    return "http://127.0.0.1:$listeningPort/$streamId"
  }

  fun resolveDisplayName(url: String): String? {
    try {
      val uri = android.net.Uri.parse(url)
      val streamId = uri.path?.removePrefix("/")?.split("/")?.firstOrNull()
      if (!streamId.isNullOrEmpty()) {
        return activeStreams[streamId]?.title
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error resolving display name for $url", e)
    }
    return null
  }

  fun unregisterStream(streamId: String) {
    // We don't disconnect the client here because it's shared in the cache
    activeStreams.remove(streamId)
  }

  private fun cleanup() {
    activeStreams.clear()
    clientCache.values.forEach { client ->
      runBlocking {
        try {
          client.disconnect()
        } catch (_: Exception) {}
      }
    }
    clientCache.clear()
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri
    val streamId = uri.removePrefix("/").split("/").firstOrNull()
    if (streamId.isNullOrEmpty()) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
    }

    val streamInfo = activeStreams[streamId] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")

    val rangeHeader = session.headers["range"]

    return try {
      if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
        handleRangeRequest(session, streamInfo, rangeHeader)
      } else {
        handleFullRequest(session, streamInfo)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error serving request for stream $streamId: ${streamInfo.filePath}", e)
      newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
    }
  }

  private fun handleRangeRequest(session: IHTTPSession, streamInfo: StreamInfo, rangeHeader: String): Response {
    val rangeValue = rangeHeader.removePrefix("bytes=")
    val parts = rangeValue.split("-")
    val start = parts[0].toLongOrNull() ?: 0L
    
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = runBlocking {
        if (!streamInfo.client.isConnected()) streamInfo.client.connect()
        streamInfo.client.getFileSize(streamInfo.filePath).getOrDefault(-1L)
      }
    }

    val fileSize = streamInfo.fileSize
    val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() else null
    val rangeEnd = end ?: (fileSize - 1)
    val contentLength = if (fileSize > 0) rangeEnd - start + 1 else -1L

    val inputStream = runBlocking {
      if (!streamInfo.client.isConnected()) streamInfo.client.connect()
      streamInfo.client.getFileStream(streamInfo.filePath, start).getOrNull()
    } ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to open stream")

    val response = if (contentLength > 0) {
        newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, streamInfo.mimeType, inputStream, contentLength)
    } else {
        // Fallback for unknown size
        newChunkedResponse(Response.Status.PARTIAL_CONTENT, streamInfo.mimeType, inputStream)
    }

    response.addHeader("Accept-Ranges", "bytes")
    if (fileSize > 0) {
      response.addHeader("Content-Range", "bytes $start-$rangeEnd/$fileSize")
    }
    return response
  }

  private fun handleFullRequest(session: IHTTPSession, streamInfo: StreamInfo): Response {
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = runBlocking {
        if (!streamInfo.client.isConnected()) streamInfo.client.connect()
        streamInfo.client.getFileSize(streamInfo.filePath).getOrDefault(-1L)
      }
    }

    val inputStream = runBlocking {
      if (!streamInfo.client.isConnected()) streamInfo.client.connect()
      streamInfo.client.getFileStream(streamInfo.filePath, 0).getOrNull()
    } ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to open stream")

    val response = if (streamInfo.fileSize > 0) {
        newFixedLengthResponse(Response.Status.OK, streamInfo.mimeType, inputStream, streamInfo.fileSize)
    } else {
        newChunkedResponse(Response.Status.OK, streamInfo.mimeType, inputStream)
    }

    response.addHeader("Accept-Ranges", "bytes")
    return response
  }
}
