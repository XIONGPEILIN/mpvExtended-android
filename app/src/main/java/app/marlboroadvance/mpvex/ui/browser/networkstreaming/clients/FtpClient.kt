package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.BufferedInputStream
import java.io.InputStream

class FtpClient(private val connection: NetworkConnection) : NetworkClient {
  private var ftpClient: FTPClient? = null

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val client = FTPClient()
        client.controlEncoding = "UTF-8"
        client.setConnectTimeout(15000)
        client.setDataTimeout(60000)
        client.setDefaultTimeout(60000)
        client.controlKeepAliveTimeout = 300
        client.setControlKeepAliveReplyTimeout(10000)

        client.connect(connection.host, connection.port)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
          client.disconnect()
          return@withContext Result.failure(Exception("FTP server refused connection"))
        }

        val success = if (connection.isAnonymous) client.login("anonymous", "") else client.login(connection.username, connection.password)
        if (!success) {
          client.disconnect()
          return@withContext Result.failure(Exception("Login failed"))
        }

        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.enterLocalPassiveMode()
        try { client.sendCommand("OPTS UTF8 ON") } catch (_: Exception) {}
        client.bufferSize = 1024 * 1024 // 1MB buffer

        if (connection.path != "/" && connection.path.isNotEmpty()) {
          client.changeWorkingDirectory(connection.path)
        }

        ftpClient = client
        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      ftpClient?.let { client ->
        try { if (client.isConnected) { client.logout(); client.disconnect() } } catch (_: Exception) {}
      }
      ftpClient = null
    }
  }

  override fun isConnected(): Boolean = ftpClient?.isConnected == true

  /**
   * Runs an operation on the control connection, transparently reconnecting and retrying
   * once if the FTP session has been dropped (idle timeout / server reset). The socket can
   * still report "connected" after a server-side drop, so we always retry once on failure.
   */
  private suspend fun <T> withFtp(block: (FTPClient) -> T): Result<T> {
    var lastError: Exception? = null
    for (attempt in 0..1) {
      try {
        if (attempt == 1 || !isConnected()) {
          disconnect()
          connect().getOrThrow()
        }
        return Result.success(block(ftpClient!!))
      } catch (e: Exception) {
        lastError = e
        disconnect()
      }
    }
    return Result.failure(lastError ?: Exception("Not connected"))
  }

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      withFtp { client ->
        client.listFiles(path).mapNotNull { file ->
          if (file.name == "." || file.name == "..") return@mapNotNull null
          NetworkFile(
            name = file.name,
            path = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}",
            isDirectory = file.isDirectory,
            size = file.size,
            lastModified = file.timestamp?.timeInMillis ?: 0,
            mimeType = if (!file.isDirectory) getMimeType(file.name) else null,
          )
        }
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      withFtp { client ->
        val files = client.listFiles(path)
        if (files.isNotEmpty() && !files[0].isDirectory) {
          files[0].size
        } else {
          throw Exception("File not found")
        }
      }
    }

  override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> =
    withContext(Dispatchers.IO) {
      try {
        // For FTP, we need a dedicated connection for each stream to avoid control channel conflicts
        val client = FTPClient()
        client.controlEncoding = "UTF-8"
        client.connect(connection.host, connection.port)
        if (connection.isAnonymous) client.login("anonymous", "") else client.login(connection.username, connection.password)
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.enterLocalPassiveMode()
        client.bufferSize = 1024 * 1024
        
        if (offset > 0) client.setRestartOffset(offset)
        
        val rawStream = client.retrieveFileStream(path) ?: return@withContext Result.failure(Exception("Failed to open FTP stream"))
        val bufferedStream = BufferedInputStream(rawStream, 1024 * 1024)

        val wrappedStream = object : InputStream() {
          override fun read(): Int = bufferedStream.read()
          override fun read(b: ByteArray, off: Int, len: Int): Int = bufferedStream.read(b, off, len)
          override fun available(): Int = bufferedStream.available()
          override fun close() {
            try { bufferedStream.close() } catch (_: Exception) {}
            try { if (client.isConnected) { client.completePendingCommand(); client.logout(); client.disconnect() } } catch (_: Exception) {}
          }
        }
        Result.success(wrappedStream)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun deleteFile(path: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      withFtp { client ->
        if (!client.deleteFile(path)) throw Exception("Failed to delete file on FTP server")
      }
    }

  private fun getMimeType(fileName: String): String? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
      "mp4", "m4v" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "wmv" -> "video/x-ms-wmv"
      "flv" -> "video/x-flv"
      "webm" -> "video/webm"
      "mpeg", "mpg" -> "video/mpeg"
      "3gp" -> "video/3gpp"
      "ts" -> "video/mp2t"
      else -> null
    }
  }
}
