package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NanoHTTPD 2.3.1 closes response data only after a successful send. If the HTTP client
 * abandons a range request, its socket write throws and the remote input stream is leaked.
 *
 * Closing in [finally] ties every HTTP response lifecycle to its corresponding remote file
 * handle, including the normal MPV seek/cancel path.
 */
internal class CloseOnDisconnectResponse private constructor(
  status: NanoHTTPD.Response.IStatus,
  mimeType: String,
  private val closeOnceData: CloseOnceInputStream,
  totalBytes: Long,
  private val onClosed: () -> Unit,
) : NanoHTTPD.Response(status, mimeType, closeOnceData, totalBytes) {

  constructor(
    status: NanoHTTPD.Response.IStatus,
    mimeType: String,
    data: InputStream,
    totalBytes: Long,
    onClosed: () -> Unit = {},
  ) : this(status, mimeType, CloseOnceInputStream(data), totalBytes, onClosed)

  protected override fun send(outputStream: OutputStream) {
    try {
      super.send(outputStream)
    } finally {
      runCatching { closeOnceData.close() }
      onClosed()
    }
  }

  internal fun sendForTest(outputStream: OutputStream) {
    send(outputStream)
  }

  private class CloseOnceInputStream(
    input: InputStream,
  ) : FilterInputStream(input) {
    private val closed = AtomicBoolean()

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        super.close()
      }
    }
  }
}
