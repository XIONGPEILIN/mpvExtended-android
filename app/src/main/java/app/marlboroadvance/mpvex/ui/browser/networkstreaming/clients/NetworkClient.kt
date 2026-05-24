package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import android.net.Uri
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import java.io.InputStream

/**
 * Common interface for all network protocol clients
 */
interface NetworkClient {
  /**
   * Connect to the server
   */
  suspend fun connect(): Result<Unit>

  /**
   * Disconnect from the server
   */
  suspend fun disconnect()

  /**
   * Check if currently connected
   */
  fun isConnected(): Boolean

  /**
   * List files and directories at the given path
   */
  suspend fun listFiles(path: String): Result<List<NetworkFile>>

  /**
   * Get input stream for a file, optionally starting at a specific offset
   */
  suspend fun getFileStream(path: String, offset: Long = 0L): Result<InputStream>

  /**
   * Get file size for a specific file path
   */
  suspend fun getFileSize(path: String): Result<Long>

  /**
   * Get file URI for playback
   */
  suspend fun getFileUri(path: String): Result<Uri>
}
