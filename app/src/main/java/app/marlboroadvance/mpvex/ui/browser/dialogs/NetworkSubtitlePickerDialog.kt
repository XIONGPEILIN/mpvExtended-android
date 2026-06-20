package app.marlboroadvance.mpvex.ui.browser.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.utils.media.NetworkMediaIdUtils
import java.util.Locale

private val SUBTITLE_EXTENSIONS = setOf(
  "srt", "vtt", "ass", "ssa", "sub", "idx", "sup",
  "smi", "sami", "ttml", "dfxp", "lrc", "txt",
)

private fun isSubtitleFile(name: String): Boolean =
  name.substringAfterLast('.', "").lowercase(Locale.getDefault()) in SUBTITLE_EXTENSIONS

/**
 * Lets the user browse the active network share and pick a subtitle file when streaming
 * from SMB/WebDAV/FTP. mpv cannot open smb:// directly, so the caller is responsible for
 * loading the chosen file through the local streaming proxy.
 */
@Composable
fun NetworkSubtitlePickerDialog(
  initialPath: String,
  listFiles: suspend (String) -> List<NetworkFile>,
  onFileSelected: (NetworkFile) -> Unit,
  onDismiss: () -> Unit,
) {
  var currentPath by remember { mutableStateOf(initialPath) }
  var entries by remember { mutableStateOf<List<NetworkFile>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(currentPath) {
    isLoading = true
    error = null
    val result = runCatching { listFiles(currentPath) }.getOrElse { emptyList() }
    // Folders first, then subtitle files; hide non-subtitle files entirely.
    val folders = result.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    val subs = result.filter { !it.isDirectory && isSubtitleFile(it.name) }
      .sortedBy { it.name.lowercase(Locale.getDefault()) }
    entries = folders + subs
    if (entries.isEmpty()) error = "No subtitle files in this folder"
    isLoading = false
  }

  val parentPath = NetworkMediaIdUtils.parentPath(currentPath)
  val canGoUp = parentPath != currentPath

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column {
        Text(
          text = "Add subtitle from network",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = currentPath.substringAfter("://").ifBlank { currentPath },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    },
    text = {
      Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
        when {
          isLoading -> {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          else -> {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
              if (canGoUp) {
                item {
                  PickerRow(
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                    label = "..",
                    onClick = { currentPath = parentPath },
                  )
                }
              }
              if (entries.isEmpty() && error != null) {
                item {
                  Text(
                    text = error ?: "",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              items(entries, key = { it.path }) { entry ->
                PickerRow(
                  icon = {
                    Icon(
                      imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Subtitles,
                      contentDescription = null,
                      tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  },
                  label = entry.name,
                  onClick = {
                    if (entry.isDirectory) currentPath = entry.path else onFileSelected(entry)
                  },
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
  )
}

@Composable
private fun PickerRow(
  icon: @Composable () -> Unit,
  label: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start,
  ) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
    Spacer(modifier = Modifier.width(16.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
