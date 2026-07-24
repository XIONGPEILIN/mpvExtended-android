package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class CloseOnDisconnectResponseTest {

  @Test
  fun closesRemoteStreamAfterSuccessfulResponse() {
    val input = CloseTrackingInputStream(ByteArray(64 * 1024))
    val response =
      CloseOnDisconnectResponse(
        NanoHTTPD.Response.Status.PARTIAL_CONTENT,
        "video/mp4",
        input,
        64L * 1024L,
      )

    response.sendForTest(ByteArrayOutputStream())

    assertEquals(1, input.closeCount)
  }

  @Test
  fun closesRemoteStreamWhenClientResetsConnection() {
    val input = CloseTrackingInputStream(ByteArray(64 * 1024))
    var closeCallbacks = 0
    val response =
      CloseOnDisconnectResponse(
        NanoHTTPD.Response.Status.PARTIAL_CONTENT,
        "video/mp4",
        input,
        64L * 1024L,
        onClosed = { closeCallbacks++ },
      )

    response.sendForTest(DisconnectingOutputStream(bytesBeforeDisconnect = 512))

    assertEquals(1, input.closeCount)
    assertEquals(1, closeCallbacks)
  }

  private class CloseTrackingInputStream(
    data: ByteArray,
  ) : InputStream() {
    private val delegate = ByteArrayInputStream(data)
    var closeCount = 0
      private set

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
      delegate.read(buffer, offset, length)

    override fun close() {
      closeCount++
      delegate.close()
    }
  }

  private class DisconnectingOutputStream(
    private val bytesBeforeDisconnect: Int,
  ) : OutputStream() {
    private var written = 0

    override fun write(value: Int) {
      if (written >= bytesBeforeDisconnect) throw IOException("Connection reset")
      written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      if (written + length > bytesBeforeDisconnect) throw IOException("Connection reset")
      written += length
    }
  }
}
