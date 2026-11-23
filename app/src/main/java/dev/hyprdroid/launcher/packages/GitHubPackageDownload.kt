package dev.hyprdroid.launcher.packages

import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal fun interface PackageDownload {
    fun read(url: String, limit: Int): ByteArray
}

/** GitHub Release assets redirect to GitHub's dedicated asset CDN. No redirects back from the CDN. */
internal object GitHubPackageDownload : PackageDownload {
    private const val TIMEOUT_MS = 15_000
    private val cdnHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

    override fun read(url: String, limit: Int): ByteArray {
        var uri = URI(url)
        if (!uri.allowed(setOf("github.com"))) throw PackageException("Недопустимый адрес репозитория")
        val started = System.nanoTime()
        repeat(2) { hop ->
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                when (connection.responseCode) {
                    200 -> {
                        if (connection.contentLengthLong > limit ||
                            connection.contentEncoding?.equals("identity", ignoreCase = true) == false
                        ) throw PackageException("Недопустимый размер или кодировка ответа репозитория")
                        return connection.inputStream.use { input ->
                            val bounded = object : java.io.FilterInputStream(input) {
                                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                    if ((System.nanoTime() - started) / 1_000_000 >= TIMEOUT_MS) {
                                        throw PackageException("Истекло время загрузки из репозитория")
                                    }
                                    return super.read(bytes, offset, length)
                                }
                            }
                            readPackageBytes(bounded, limit)
                        }
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                        if (hop != 0 || location.isNullOrBlank() || location.length > 4096) {
                            throw PackageException("Недопустимое перенаправление репозитория")
                        }
                        uri = try { URI(location) } catch (_: java.net.URISyntaxException) {
                            throw PackageException("Недопустимое перенаправление репозитория")
                        }
                        if (!uri.allowed(cdnHosts)) throw PackageException("Недоверенный адрес перенаправления")
                    }
                    else -> throw PackageException("GitHub не вернул ресурс репозитория (HTTP ${connection.responseCode})")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw PackageException("Ресурс репозитория недоступен")
    }

    private fun URI.allowed(hosts: Set<String>): Boolean =
        scheme == "https" && host in hosts && rawUserInfo == null && port == -1 && rawFragment == null
}
