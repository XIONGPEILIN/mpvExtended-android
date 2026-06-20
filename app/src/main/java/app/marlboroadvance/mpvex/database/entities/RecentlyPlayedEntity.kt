package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  indices = [
    Index(value = ["filePath"], unique = true),
    Index(value = ["timestamp"]),
    Index(value = ["playlistId"]),
  ],
)
data class RecentlyPlayedEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,
  val filePath: String,
  val fileName: String,
  val videoTitle: String? = null,
  val duration: Long = 0,
  val fileSize: Long = 0,
  val width: Int = 0,
  val height: Int = 0,
  val timestamp: Long,
  val launchSource: String? = null,
  val networkConnectionId: Long? = null,
  val playlistId: Int? = null,
)
