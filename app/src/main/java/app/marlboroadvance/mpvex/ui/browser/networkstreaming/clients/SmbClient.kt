package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import android.net.Uri
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
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
  private var shareName: String = ""

  override suspend fun connect(): Result<Unit> =
    connectMutex.withLock {
      withContext(Dispatchers.IO) {
        try {
          if (isConnected()) return@withContext Result.success(Unit)
          val client = getOrCreateClient()
          val resolvedAddress = try {
            withTimeout(5000) { java.net.InetAddress.getByName(connection.host) }
          } catch (e: Exception) {
            return@withContext Result.failure(Exception("Host not found: ${connection.host}"))
          }
          val hostForUrl = resolvedAddress.hostAddress ?: connection.host
          resolvedHostIp = hostForUrl
          shareName = connection.path.trim('/')
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

  override fun isConnected(): Boolean = session != null && smbConnection != null && smbConnection!!.isConnected

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

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        if (!isConnected()) connect().getOrThrow()
        val ds = diskShare ?: return@withContext Result.failure(Exception("Not connected"))
        val relativePath = getRelativePath(path)
        val rawFiles = withTimeout(15000) { ds.list(relativePath) }
        val files = rawFiles.mapNotNull { fileInfo ->
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
        Result.success(files)
      } catch (e: Exception) { Result.failure(e) }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        if (!isConnected()) connect().getOrThrow()
        val ds = diskShare ?: return@withContext Result.failure(Exception("Not connected"))
        val file = ds.openFile(getRelativePath(path), EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, null)
        val size = file.fileInformation.standardInformation.endOfFile
        file.close()
        Result.success(size)
      } catch (e: Exception) { Result.failure(e) }
    }

  override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> =
    withContext(Dispatchers.IO) {
      try {
        if (!isConnected()) connect().getOrThrow()
        val ds = diskShare ?: return@withContext Result.failure(Exception("Not connected"))
        val file = ds.openFile(getRelativePath(path), EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, null)

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
        Result.success(BufferedInputStream(rawSeekable, 1024 * 1024))
      } catch (e: Exception) { Result.failure(e) }
    }

  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val fullPath = if (path.startsWith("smb://")) path else "$baseUrl${if (path.startsWith("/")) path else "/$path"}"
        val uriString = if (connection.isAnonymous) fullPath 
                        else {
                          val hostPart = "${connection.host}${if (connection.port != 445) ":${connection.port}" else ""}"
                          val pathPart = if (path.startsWith("/")) path else "/$path"
                          "smb://${connection.username}:${connection.password}@$hostPart${connection.path}$pathPart"
                        }
        Result.success(Uri.parse(uriString))
      } catch (e: Exception) { Result.failure(e) }
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
