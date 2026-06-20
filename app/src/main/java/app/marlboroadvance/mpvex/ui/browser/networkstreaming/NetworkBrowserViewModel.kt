package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.utils.media.NetworkMediaIdUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ViewModel for browsing files on a network share
 * Follows MVVM pattern with proper separation of concerns
 */
class NetworkBrowserViewModel(
  private val application: Application,
  private val connectionId: Long,
  private val currentPath: String,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()

  private val _files = MutableStateFlow<List<NetworkFile>>(emptyList())
  val files: StateFlow<List<NetworkFile>> = _files.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /**
   * Load files in the current directory
   */
  fun loadFiles() {
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        repository.listFiles(connection, currentPath)
          .onSuccess { fileList ->
            // Use the same natural-order comparator the player uses when building the
            // playlist from a network folder, so the browser order matches the in-player
            // playlist order (e.g. ep2 before ep10).
            _files.value = fileList.sortedWith(
              compareBy<NetworkFile> { !it.isDirectory }
                .thenComparator { a, b ->
                  app.marlboroadvance.mpvex.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT
                    .compare(a.name, b.name)
                },
            )
          }
          .onFailure { e ->
            _error.value = e.message ?: "Unknown error"
          }
      } catch (e: Exception) {
        _error.value = e.message ?: "Unknown error"
      } finally {
        _isLoading.value = false
      }
    }
  }



  /**
   * Delete files from the network share
   */
  suspend fun deleteFiles(filesToDelete: List<NetworkFile>): Pair<Int, Int> {
    var deleted = 0
    var failed = 0
    
    try {
      val connection = repository.getConnectionById(connectionId)
        ?: return Pair(0, filesToDelete.size)
        
      for (file in filesToDelete) {
        repository.deleteFile(connection, file.path)
          .onSuccess {
            deleted++
          }
          .onFailure { e ->
            Log.e(TAG, "Failed to delete file: ${file.path}", e)
            failed++
          }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in deleteFiles", e)
      failed += filesToDelete.size - deleted - failed
    }
    
    return Pair(deleted, failed)
  }

  /**
   * Play a video file
   */
  fun playVideo(file: NetworkFile) {
    viewModelScope.launch {
      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        // Use proxy server for protocols that need seeking support
        val useProxy = connection.protocol in PROXY_PROTOCOLS

        val uri = if (useProxy) {
          val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()
          val streamId = "${connectionId}_${System.currentTimeMillis()}"
          val proxyUrl = proxy.registerStream(
            streamId = streamId,
            connection = connection,
            filePath = file.path,
            fileSize = file.size,
            mimeType = file.mimeType ?: "video/mp4",
            title = file.name,
          )
          android.net.Uri.parse(proxyUrl)
        } else {
          NetworkStreamingProvider.setConnection(connectionId, connection)
          NetworkStreamingProvider.getUri(application, connectionId, file.path)
        }

        // Launch the player
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setClass(application, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
        intent.putExtra("internal_launch", true)
        intent.putExtra("launch_source", "network_stream")
        intent.putExtra("title", file.name)
        intent.putExtra("filename", file.name)
        // Pass the original network file path for stable media identifier (position saving)
        intent.putExtra("network_file_path", NetworkMediaIdUtils.canonicalizeNetworkPath(file.path) ?: file.path)
        intent.putExtra("network_connection_id", connectionId)
        intent.setDataAndType(uri, file.mimeType ?: "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!useProxy) {
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        application.startActivity(intent)
      } catch (e: Exception) {
        Log.e(TAG, "Error playing video", e)
        _error.value = e.message ?: "Unknown error"
      }
    }
  }

  companion object {
    private const val TAG = "NetworkBrowserVM"

    // Protocols that require proxy server for seeking support
    private val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )

    fun factory(
      application: Application,
      connectionId: Long,
      currentPath: String,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkBrowserViewModel(application, connectionId, currentPath)
        }
      }
  }
}
