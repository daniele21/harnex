package io.github.daniele21.localllm.download

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

data class DownloadTransportRequest @JvmOverloads constructor(
    val uri: URI,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val rangeStartBytes: Long = 0L,
)

interface DownloadTransportResponse : Closeable {
    val statusCode: Int
    val contentLengthBytes: Long?
    val contentEncoding: String?
    val redirectLocation: String?
    val contentRange: String? get() = null

    fun openBody(): InputStream
}

fun interface DownloadTransport {
    @Throws(IOException::class)
    fun execute(request: DownloadTransportRequest): DownloadTransportResponse
}

class HttpUrlConnectionDownloadTransport : DownloadTransport {
    override fun execute(request: DownloadTransportRequest): DownloadTransportResponse {
        val connection = request.uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = METHOD_GET
        connection.connectTimeout = request.connectTimeoutMs
        connection.readTimeout = request.readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty(HEADER_ACCEPT_ENCODING, ENCODING_IDENTITY)
        if (request.rangeStartBytes > 0) {
            connection.setRequestProperty(HEADER_RANGE, "bytes=${request.rangeStartBytes}-")
        }
        connection.connect()
        return HttpUrlConnectionResponse(connection)
    }

    private class HttpUrlConnectionResponse(private val connection: HttpURLConnection) : DownloadTransportResponse {
        private var body: InputStream? = null

        override val statusCode: Int
            get() = connection.responseCode

        override val contentLengthBytes: Long?
            get() = connection.contentLengthLong.takeIf { it >= 0 }

        override val contentEncoding: String?
            get() = connection.contentEncoding

        override val redirectLocation: String?
            get() = connection.getHeaderField(HEADER_LOCATION)
        override val contentRange: String?
            get() = connection.getHeaderField(HEADER_CONTENT_RANGE)

        override fun openBody(): InputStream {
            check(statusCode in 200..299) { "HTTP response does not contain a success body" }
            return body ?: connection.inputStream.also { body = it }
        }

        override fun close() {
            runCatching { body?.close() }
            connection.disconnect()
        }
    }

    private companion object {
        const val METHOD_GET = "GET"
        const val HEADER_RANGE = "Range"
        const val HEADER_CONTENT_RANGE = "Content-Range"
        const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
        const val HEADER_LOCATION = "Location"
        const val ENCODING_IDENTITY = "identity"
    }
}
