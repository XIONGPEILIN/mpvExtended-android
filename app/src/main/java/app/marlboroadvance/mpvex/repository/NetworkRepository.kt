package app.marlboroadvance.mpvex.repository

import app.marlboroadvance.mpvex.database.dao.NetworkConnectionDao
import app.marlboroadvance.mpvex.domain.network.ConnectionStatus
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for managing network connections and file browsing
 */
class NetworkRepository(
  private val dao: NetworkConnectionDao,
) {
  // Active network clients
  private val activeClients = mutableMapOf<Long, NetworkClient>()

  // Connection statuses
  private val _connectionStatuses = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())
  val connectionStatuses: StateFlow<Map<Long, ConnectionStatus>> = _connectionStatuses.asStateFlow()

  /**
   * Get all saved connections as a Flow
   */
  fun getAllConnections(): Flow<List<NetworkConnection>> = dao.getAllConnections()

  /**
   * Get connections that should auto-connect on launch
   */
  suspend fun getAutoConnectConnections(): List<NetworkConnection> = dao.getAutoConnectConnections()

  /**
   * Get all saved connections as a snapshot list.
   */
  suspend fun getAllConnectionsList(): List<NetworkConnection> = dao.getAllConnectionsList()

  /**
   * Get a connection by ID
   */
  suspend fun getConnectionById(id: Long): NetworkConnection? = dao.getConnectionById(id)

  /**
   * Add a new connection
   */
  suspend fun addConnection(connection: NetworkConnection): Long = dao.insert(connection)

  /**
   * Update an existing connection
   */
  suspend fun updateConnection(connection: NetworkConnection) {
    dao.update(connection)
    // Disconnect and remove cached client if it exists
    // This ensures the next connection uses the updated credentials
    activeClients[connection.id]?.let { client ->
      try {
        client.disconnect()
      } catch (e: Exception) {
        // Ignore errors during cleanup
      }
      activeClients.remove(connection.id)
      // Update status to disconnected
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
        ),
      )
    }
  }

  /**
   * Delete a connection
   */
  suspend fun deleteConnection(connection: NetworkConnection) {
    dao.delete(connection)
    // Clean up connection status
    _connectionStatuses.value -= connection.id
    // Disconnect if active
    activeClients[connection.id]?.let { client ->
      try {
        client.disconnect()
      } catch (e: Exception) {
        // Ignore errors during cleanup
      }
      activeClients.remove(connection.id)
    }
  }

  /**
   * Connect to a network share
   */
  suspend fun connect(connection: NetworkConnection): Result<Unit> {
    // Update status to connecting
    updateConnectionStatus(
      connection.id,
      ConnectionStatus(
        connectionId = connection.id,
        isConnecting = true,
      ),
    )

    return try {
      // Create client for this connection
      val client = NetworkClientFactory.createClient(connection)

      // Attempt to connect
      client.connect().onSuccess {
        // Store the active client
        activeClients[connection.id] = client

        // Update last connected time
        dao.updateLastConnected(connection.id, System.currentTimeMillis())

        // Update status to connected
        updateConnectionStatus(
          connection.id,
          ConnectionStatus(
            connectionId = connection.id,
            isConnected = true,
            isConnecting = false,
          ),
        )
      }.onFailure { e ->
        // Update status with error
        updateConnectionStatus(
          connection.id,
          ConnectionStatus(
            connectionId = connection.id,
            isConnected = false,
            isConnecting = false,
            error = e.message ?: "Connection failed",
          ),
        )
        throw e
      }
      Result.success(Unit)
    } catch (e: Exception) {
      // Update status with error
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
          error = e.message ?: "Connection failed",
        ),
      )
      Result.failure(e)
    }
  }

  /**
   * Disconnect from a network share
   */
  suspend fun disconnect(connection: NetworkConnection): Result<Unit> =
    try {
      // Get and disconnect the client
      activeClients[connection.id]?.let { client ->
        client.disconnect()
        activeClients.remove(connection.id)
      }

      // Update status to disconnected
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
        ),
      )
      Result.success(Unit)
    } catch (e: Exception) {
      // Even if disconnect fails, update status
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
          error = e.message,
        ),
      )
      Result.failure(e)
    }

  /**
   * List files in a directory on a network share
   */
  suspend fun listFiles(
    connection: NetworkConnection,
    path: String,
  ): Result<List<NetworkFile>> =
    try {
      // Always fetch the latest connection from database to ensure we have current credentials
      val latestConnection = dao.getConnectionById(connection.id) ?: connection

      // Reuse the cached client only if it is still actually connected; otherwise
      // drop it and reconnect (handles idle/server-dropped sessions).
      val client = obtainConnectedClient(connection.id, latestConnection)

      // List files
      client.listFiles(path)
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * Delete a file on the network share
   */
  suspend fun deleteFile(
    connection: NetworkConnection,
    path: String,
  ): Result<Unit> =
    try {
      // Always fetch the latest connection from database to ensure we have current credentials
      val latestConnection = dao.getConnectionById(connection.id) ?: connection

      // Reuse the cached client only if it is still actually connected; otherwise reconnect.
      val client = obtainConnectedClient(connection.id, latestConnection)

      // Delete file
      client.deleteFile(path)
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * Returns a connected client for [connectionId], reusing the cached one only when it is
   * still actually connected. A stale/dropped client is disconnected and replaced with a
   * freshly connected one. Throws if a new connection cannot be established.
   */
  private suspend fun obtainConnectedClient(
    connectionId: Long,
    connection: NetworkConnection,
  ): NetworkClient {
    val existing = activeClients[connectionId]
    if (existing != null && existing.isConnected()) return existing

    existing?.let { runCatching { it.disconnect() } }
    val newClient = NetworkClientFactory.createClient(connection)
    newClient.connect().getOrThrow()
    activeClients[connectionId] = newClient
    return newClient
  }

  /**
   * Get an active client for a connection
   */
  fun getActiveClient(connectionId: Long): NetworkClient? = activeClients[connectionId]

  /**
   * Check if a connection is active
   */
  fun isConnected(connectionId: Long): Boolean = activeClients.containsKey(connectionId)

  /**
   * Disconnect all active connections
   */
  suspend fun disconnectAll() {
    activeClients.values.forEach { client ->
      try {
        client.disconnect()
      } catch (e: Exception) {
        // Ignore errors during cleanup
      }
    }
    activeClients.clear()
    _connectionStatuses.value = emptyMap()
  }

  private fun updateConnectionStatus(
    connectionId: Long,
    status: ConnectionStatus,
  ) {
    _connectionStatuses.value += (connectionId to status)
  }
}
