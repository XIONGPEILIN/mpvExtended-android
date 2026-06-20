package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbClient(private val connection: NetworkConnection) : NetworkClient {
  companion object {
    @Volatile
    private var sharedSmbClient: SMBClient? = null
    private val connectMutex = Mutex()

    private fun getOrCreateClient(): SMBClient {
      return sharedSmbClient ?: synchronized(this) {
        sharedSmbClient ?: run {
          val config = SmbConfig.builder()
            .withTransportLayerFactory(AsyncDirectTcpTransportFactory())
            .withTimeout(60000, TimeUnit.MILLISECONDS)
            .withSoTimeout(60000, TimeUnit.MILLISECONDS)
            .withReadBufferSize(1024 * 1024)
            .withWriteBufferSize(1024 * 1024)
            .withTransactBufferSize(1024 * 1024)
            .withDialects(
              com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2,
            )
            .withDfsEnabled(false)
            .withMultiProtocolNegotiate(true)
            .withSigningRequired(false)
            .withEncryptData(false)
            .build()
          SMBClient(config).also { sharedSmbClient = it }
        }
      }
    }
  }

  private var smbConnection: Connection? = null
  private var session: Session? = null
  private var diskShare: DiskShare? = null
  private var baseUrl: String = ""
  private var resolvedHostIp: String = ""
  // The actual SMB share name (first path segment only). SMB shares cannot contain slashes.
  private var shareName: String = ""
  // Optional sub-directory within the share that this connection is rooted at (no leading/trailing slash).
  private var basePath: String = ""

  override suspend fun connect(): Result<Unit> =
    connectMutex.withLock {
      withContext(Dispatchers.IO) {
        try {
          if (isConnected()) return@withContext Result.success(Unit)
          // A previous half-open connection may still be lingering; tear it down first.
          disconnect()
          val client = getOrCreateClient()
          val resolvedAddress = try {
            withTimeout(5000) { java.net.InetAddress.getByName(connection.host) }
          } catch (e: Exception) {
            return@withContext Result.failure(Exception("Host not found: ${connection.host}"))
          }
          val hostForUrl = resolvedAddress.hostAddress ?: connection.host
          resolvedHostIp = hostForUrl
          // connection.path may be "share", "share/sub/dir", "/share/sub/", or use backslashes.
          // The SMB share is ONLY the first segment; the remainder is the rooted sub-directory.
          val normalizedPath = connection.path.replace('\\', '/').trim('/')
          shareName = normalizedPath.substringBefore('/')
          basePath = normalizedPath.substringAfter('/', "").trim('/')
          baseUrl = "smb://${hostForUrl}${if (connection.port != 445) ":${connection.port}" else ""}/${shareName}"
          smbConnection = client.connect(hostForUrl, connection.port)
          val authContext = if (connection.isAnonymous) AuthenticationContext.anonymous()
                            else AuthenticationContext(connection.username, connection.password.toCharArray(), null)
          session = smbConnection!!.authenticate(authContext)
          diskShare = session!!.connectShare(shareName) as DiskShare
          Result.success(Unit)
        } catch (e: Exception) {
          disconnect(); Result.failure(e)
        }
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      try { diskShare?.close() } catch (_: Exception) {}
      try { session?.close() } catch (_: Exception) {}
      try { smbConnection?.close() } catch (_: Exception) {}
      diskShare = null; session = null; smbConnection = null
    }
  }

  override fun isConnected(): Boolean =
    session != null && smbConnection?.isConnected == true && diskShare?.isConnected == true

  /**
   * Ensures a live connection, forcing a full reconnect if the previous session/share
   * has gone stale (e.g. the server dropped an idle SMB session, which surfaces as
   * STATUS_ACCESS_DENIED on subsequent operations).
   */
  private suspend fun reconnect(): Result<Unit> {
    disconnect()
    return connect()
  }

  /**
   * Runs an operation against the disk share, transparently reconnecting and retrying
   * once if the share/session has been invalidated by the server.
   */
  private suspend fun <T> withShare(block: (DiskShare) -> T): Result<T> {
    var lastError: Exception? = null
    for (attempt in 0..1) {
      try {
        if (!isConnected()) {
          (if (attempt == 0) connect() else reconnect()).getOrThrow()
        }
        val ds = diskShare ?: throw IllegalStateException("Not connected")
        return Result.success(block(ds))
      } catch (e: Exception) {
        lastError = e
        // Drop the stale connection so the next attempt establishes a fresh session.
        disconnect()
      }
    }
    return Result.failure(lastError ?: Exception("Not connected"))
  }

  private fun getRelativePath(path: String): String {
    return when {
      path.startsWith("smb://") -> {
        try {
          val uri = java.net.URI(path)
          val pathParts = uri.path.trim('/').split('/', limit = 2)
          pathParts.getOrNull(1) ?: ""
        } catch (e: Exception) {
          val pathAfterProtocol = path.substringAfter("smb://")
          val pathPart = pathAfterProtocol.substringAfter("/")
          val pathParts = pathPart.trim('/').split('/', limit = 2)
          pathParts.getOrNull(1) ?: ""
        }
      }
      else -> path.trim('/')
    }
  }

  /**
   * Resolves a browse path to a path relative to the connected share, substituting the
   * connection's rooted sub-directory ([basePath]) when browsing the connection root.
   */
  private fun resolveSharePath(path: String): String {
    val rel = getRelativePath(path)
    return if (rel.isEmpty()) basePath else rel
  }

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      withShare { ds ->
        val relativePath = resolveSharePath(path)
        val rawFiles = ds.list(relativePath)
        rawFiles.mapNotNull { fileInfo ->
          val fileName = fileInfo.fileName
          if (fileName == "." || fileName == ".." || fileName.endsWith("$")) return@mapNotNull null
          val isDirectory = fileInfo.fileAttributes and 0x10 != 0L
          NetworkFile(
            name = fileName,
            path = if (relativePath.isEmpty()) "smb://${resolvedHostIp}/${shareName}/${fileName}"
                   else "smb://${resolvedHostIp}/${shareName}/${relativePath}/${fileName}",
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else fileInfo.endOfFile,
            lastModified = fileInfo.lastWriteTime.toEpochMillis(),
            mimeType = if (!isDirectory) getMimeType(fileName) else null,
          )
        }
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      withShare { ds ->
        val file = ds.openFile(resolveSharePath(path), EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, null)
        val size = file.fileInformation.standardInformation.endOfFile
        file.close()
        size
      }
    }

  override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> =
    withContext(Dispatchers.IO) {
      withShare { ds ->
        val file = ds.openFile(resolveSharePath(path), EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, null)

        // Optimized Seekable Raw Stream
        val rawSeekable = object : InputStream() {
          private var currentPosition = offset
          override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) == 1) b[0].toInt() and 0xFF else -1
          }
          override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = file.read(b, currentPosition, off, len)
            if (read > 0) currentPosition += read
            return read
          }
          override fun close() { try { file.close() } catch (_: Exception) {} }
        }
        
        // Wrap with 1MB buffer for high-speed sequential access (Restore the dozens of MB/s speed)
        BufferedInputStream(rawSeekable, 1024 * 1024)
      }
    }

  override suspend fun deleteFile(path: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      withShare { ds ->
        val relativePath = resolveSharePath(path)
        val isDirectory = ds.folderExists(relativePath)
        deleteRecursive(ds, relativePath, isDirectory)
      }
    }

  private fun deleteRecursive(ds: DiskShare, relativePath: String, isDirectory: Boolean) {
    // Clear attributes to normal first (helpful for Read-only items)
    try {
      // 0x80L is FILE_ATTRIBUTE_NORMAL
      ds.setFileInformation(relativePath, FileBasicInformation(null, null, null, null, 0x80L))
    } catch (_: Exception) {}

    if (isDirectory) {
      ds.list(relativePath).forEach { fileInfo ->
        val name = fileInfo.fileName
        if (name != "." && name != "..") {
          val subPath = if (relativePath.isEmpty()) name else "$relativePath\\$name"
          val isSubDir = (fileInfo.fileAttributes and 0x10L) != 0L
          deleteRecursive(ds, subPath, isSubDir)
        }
      }
      ds.rmdir(relativePath, false) // false because we already cleared children
    } else {
      ds.rm(relativePath)
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
