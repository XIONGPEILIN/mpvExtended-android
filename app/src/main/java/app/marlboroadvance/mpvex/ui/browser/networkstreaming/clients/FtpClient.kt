package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import android.net.Uri
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

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        if (!isConnected()) connect().getOrThrow()
        val client = ftpClient!!
        
        val files = client.listFiles(path).mapNotNull { file ->
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
        Result.success(files)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        if (!isConnected()) connect().getOrThrow()
        val client = ftpClient!!
        val files = client.listFiles(path)
        if (files.isNotEmpty() && !files[0].isDirectory) {
          Result.success(files[0].size)
        } else {
          Result.failure(Exception("File not found"))
        }
      } catch (e: Exception) {
        Result.failure(e)
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

  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val uriString = if (connection.isAnonymous) "ftp://${connection.host}:${connection.port}$path" 
                        else "ftp://${connection.username}:${connection.password}@${connection.host}:${connection.port}$path"
        Result.success(Uri.parse(uriString))
      } catch (e: Exception) {
        Result.failure(e)
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
