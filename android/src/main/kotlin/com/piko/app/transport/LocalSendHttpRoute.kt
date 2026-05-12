package com.piko.app.transport

import java.net.URLDecoder

data class LocalSendHttpRoute(
    val method: String,
    val path: String,
    val query: Map<String, String>,
) {
    companion object {
        fun parse(requestLine: String): LocalSendHttpRoute {
            val parts = requestLine.split(" ", limit = 3)
            require(parts.size >= 2) { "HTTP request line is incomplete" }
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', missingDelimiterValue = "")
                .split('&')
                .filter { it.isNotBlank() }
                .associate { item ->
                    val key = item.substringBefore('=').urlDecode()
                    val value = item.substringAfter('=', missingDelimiterValue = "").urlDecode()
                    key to value
                }
            return LocalSendHttpRoute(
                method = method,
                path = path,
                query = query,
            )
        }
    }
}

private fun String.urlDecode(): String {
    return URLDecoder.decode(this, Charsets.UTF_8.name())
}
