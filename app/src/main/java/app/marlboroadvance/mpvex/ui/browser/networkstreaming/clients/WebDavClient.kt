package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

class WebDavClient(private val connection: NetworkConnection) : NetworkClient {
  private var sardine: Sardine? = null

  companion object {
    // Shared client so connection pooling/threads are reused across range requests
    // instead of leaking a new pool+dispatcher on every getFileStream call.
    private val sharedHttpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    }
  }

  private fun buildUrl(relativePath: String): String {
    val protocol = if (connection.useHttps) "https" else "http"
    val basePath = connection.path.trim('/')
    val cleanPath = relativePath.trim('/')
    return when {
      cleanPath.isEmpty() || cleanPath == "/" -> {
        if (basePath.isEmpty()) "$protocol://${connection.host}:${connection.port}/" 
        else "$protocol://${connection.host}:${connection.port}/$basePath"
      }
      basePath.isEmpty() -> "$protocol://${connection.host}:${connection.port}/$cleanPath"
      else -> "$protocol://${connection.host}:${connection.port}/$basePath/$cleanPath"
    }
  }

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val client = OkHttpSardine()
        if (!connection.isAnonymous) client.setCredentials(connection.username, connection.password)
        client.exists(buildUrl(""))
        sardine = client
        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) { sardine = null }
  }

  override fun isConnected(): Boolean = sardine != null

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        if (!isConnected()) connect().getOrThrow()
        val resources = sardine!!.list(buildUrl(path))
        val files = resources.drop(1).map { resource: DavResource ->
          val resourceName = resource.name ?: ""
          NetworkFile(
            name = resourceName,
            path = if (path.isEmpty() || path == "/") resourceName else "${path.trimEnd('/')}/$resourceName",
            isDirectory = resource.isDirectory,
            size = resource.contentLength ?: 0,
            lastModified = resource.modified?.time ?: 0,
            mimeType = if (!resource.isDirectory) getMimeType(resourceName) else null,
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
        val resources = sardine!!.list(buildUrl(path), 0)
        if (resources.isNotEmpty() && !resources[0].isDirectory) {
          Result.success(resources[0].contentLength ?: -1L)
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
        val url = buildUrl(path)
        val okHttpClient = sharedHttpClient

        val requestBuilder = Request.Builder().url(url).get()
        if (offset > 0) requestBuilder.addHeader("Range", "bytes=$offset-")
        if (!connection.isAnonymous) requestBuilder.addHeader("Authorization", Credentials.basic(connection.username, connection.password))

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
          response.close()
          return@withContext Result.failure(Exception("HTTP ${response.code}"))
        }

        val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
        val rawStream = body.byteStream()
        
        val wrappedStream = object : InputStream() {
          override fun read(): Int = rawStream.read()
          override fun read(b: ByteArray, off: Int, len: Int): Int = rawStream.read(b, off, len)
          override fun available(): Int = rawStream.available()
          override fun close() {
            try { rawStream.close() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
          }
        }
        Result.success(wrappedStream)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun deleteFile(path: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        if (!isConnected()) connect().getOrThrow()
        sardine!!.delete(buildUrl(path))
        Result.success(Unit)
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
