package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

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
   * Delete a file on the server
   */
  suspend fun deleteFile(path: String): Result<Unit>

  /**
   * Lightweight liveness ping. Implementations that hold a persistent connection (e.g. SMB)
   * should perform a cheap round-trip to keep the session warm and transparently reconnect
   * if it has been dropped. Default is a no-op for stateless clients.
   */
  suspend fun keepAlive() {}
}
