package io.github.abrahampaulj.pocketfuse.pair

import java.net.URI
import java.net.URLDecoder

/** Where the FaceFusion server is and how to open a session on it. */
data class ServerConfig(val host: String, val port: Int, val apiKey: String?, val secure: Boolean = false) {
    val baseUrl: String get() = (if (secure) "https://" else "http://") + bracketed(host) + ":" + port
    val label: String get() = "$host:$port"
    val isPublicAddress: Boolean get() = classify(host) == AddressKind.Public

    private fun bracketed(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host
}

enum class AddressKind { Loopback, Tailnet, Lan, Name, Public }

/**
 * Reads `pocketfuse://pair?host=..&port=..&key=..`, the link and QR code `pocketfuse.py serve` prints,
 * and plain `host:port` typed by hand. Returns null for anything else.
 */
object Pairing {
    fun parse(text: String): ServerConfig? {
        val trimmed = text.trim()
        return if (trimmed.startsWith("pocketfuse://")) parseLink(trimmed) else parseManual(trimmed, null)
    }

    fun parseManual(address: String, apiKey: String?): ServerConfig? {
        val cleaned = address.trim().removePrefix("http://").removeSuffix("/")
        val secure = address.trim().startsWith("https://")
        val withoutScheme = cleaned.removePrefix("https://")
        val host: String
        val port: Int
        val lastColon = withoutScheme.lastIndexOf(':')
        if (lastColon > 0 && !withoutScheme.endsWith("]")) {
            host = withoutScheme.substring(0, lastColon).removePrefix("[").removeSuffix("]")
            port = withoutScheme.substring(lastColon + 1).toIntOrNull() ?: return null
        } else {
            host = withoutScheme.removePrefix("[").removeSuffix("]")
            port = 8000
        }
        if (host.isBlank() || port !in 1..65535 || host.any { it.isWhitespace() || it == '/' }) return null
        return ServerConfig(host, port, apiKey?.takeIf { it.isNotBlank() }, secure)
    }

    private fun parseLink(link: String): ServerConfig? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.host != "pair" && uri.authority != "pair") return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else decode(pair.substring(0, index)) to decode(pair.substring(index + 1))
        }.toMap()
        val host = query["host"]?.takeIf { it.isNotBlank() } ?: return null
        val port = query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ServerConfig(host, port, query["key"]?.takeIf { it.isNotBlank() })
    }

    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
}

fun classify(host: String): AddressKind {
    val parts = host.split('.').map { it.toIntOrNull() }
    if (parts.size != 4 || parts.any { it == null || it !in 0..255 }) {
        return if (host == "localhost") AddressKind.Loopback else AddressKind.Name
    }
    val (a, b) = parts.map { it!! }
    return when {
        a == 127 -> AddressKind.Loopback
        a == 100 && b in 64..127 -> AddressKind.Tailnet
        a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) -> AddressKind.Lan
        else -> AddressKind.Public
    }
}
