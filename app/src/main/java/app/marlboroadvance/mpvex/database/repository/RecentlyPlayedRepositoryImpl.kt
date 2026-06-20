package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.RecentlyPlayedDao
import app.marlboroadvance.mpvex.database.entities.RecentlyPlayedEntity
import app.marlboroadvance.mpvex.domain.recentlyplayed.repository.RecentlyPlayedRepository
import kotlinx.coroutines.flow.Flow

class RecentlyPlayedRepositoryImpl(
  private val recentlyPlayedDao: RecentlyPlayedDao,
) : RecentlyPlayedRepository {
  private companion object {
    // Cap the recently-played table so it cannot grow without bound.
    const val MAX_RECENTLY_PLAYED_ROWS = 500
  }
  override suspend fun addRecentlyPlayed(
    filePath: String,
    fileName: String,
    videoTitle: String?,
    duration: Long,
    fileSize: Long,
    width: Int,
    height: Int,
    launchSource: String?,
    networkConnectionId: Long?,
    playlistId: Int?,
  ) {
    // Check if there's an existing entry for this file
    val existingEntry = recentlyPlayedDao.getByFilePath(filePath)
    
    if (existingEntry != null) {
      // Update existing entry, but preserve the original launchSource
      val entity = RecentlyPlayedEntity(
        id = existingEntry.id,
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle ?: existingEntry.videoTitle,
        duration = if (duration > 0) duration else existingEntry.duration,
        fileSize = if (fileSize > 0) fileSize else existingEntry.fileSize,
        width = if (width > 0) width else existingEntry.width,
        height = if (height > 0) height else existingEntry.height,
        timestamp = System.currentTimeMillis(),
        // Preserve the original launch source when reopening the same file
        launchSource = existingEntry.launchSource,
        networkConnectionId = networkConnectionId ?: existingEntry.networkConnectionId,
        playlistId = playlistId ?: existingEntry.playlistId,
      )
      recentlyPlayedDao.insert(entity)
    } else {
      // Create a new entry
      val entity = RecentlyPlayedEntity(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        timestamp = System.currentTimeMillis(),
        launchSource = launchSource,
        networkConnectionId = networkConnectionId,
        playlistId = playlistId,
      )
      recentlyPlayedDao.insert(entity)
    }

    // Bound table growth (only meaningful work when inserting a brand-new path).
    recentlyPlayedDao.pruneToLimit(MAX_RECENTLY_PLAYED_ROWS)
  }

  override suspend fun getLastPlayed(): RecentlyPlayedEntity? = recentlyPlayedDao.getLastPlayed()

  override fun observeLastPlayed(): Flow<RecentlyPlayedEntity?> = recentlyPlayedDao.observeLastPlayed()

  override suspend fun getLastPlayedForHighlight(): RecentlyPlayedEntity? =
    recentlyPlayedDao.getLastPlayedForHighlight()

  override fun observeLastPlayedForHighlight(): Flow<RecentlyPlayedEntity?> =
    recentlyPlayedDao.observeLastPlayedForHighlight()

  override suspend fun getRecentlyPlayed(limit: Int): List<RecentlyPlayedEntity> =
    recentlyPlayedDao.getRecentlyPlayed(limit)

  override fun observeRecentlyPlayed(limit: Int): Flow<List<RecentlyPlayedEntity>> =
    recentlyPlayedDao.observeRecentlyPlayed(limit)

  override suspend fun getRecentlyPlayedBySource(
    launchSource: String,
    limit: Int,
  ): List<RecentlyPlayedEntity> = recentlyPlayedDao.getRecentlyPlayedBySource(launchSource, limit)

  override suspend fun clearAll() {
    recentlyPlayedDao.clearAll()
  }

  override suspend fun deleteByFilePath(filePath: String) {
    recentlyPlayedDao.deleteByFilePath(filePath)
  }

  override suspend fun deleteByPlaylistId(playlistId: Int) {
    recentlyPlayedDao.deleteByPlaylistId(playlistId)
  }

  override suspend fun updateFilePath(
    oldPath: String,
    newPath: String,
    newFileName: String,
  ) {
    recentlyPlayedDao.updateFilePath(oldPath, newPath, newFileName)
  }

  override suspend fun updateVideoTitle(
    filePath: String,
    videoTitle: String,
  ) {
    recentlyPlayedDao.updateVideoTitle(filePath, videoTitle)
  }

  override suspend fun updateVideoMetadata(
    filePath: String,
    videoTitle: String?,
    duration: Long,
    fileSize: Long,
    width: Int,
    height: Int,
  ) {
    recentlyPlayedDao.updateVideoMetadata(filePath, videoTitle, duration, fileSize, width, height)
  }
}
