package com.meuplayer.app

import android.content.Context
import android.util.Log
import androidx.webkit.WebViewFeature
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Per-app WebView proxy. DNS lookups are sent as DNS wire messages to Cloudflare DoH
 * over TLS with SNI and certificate hostname verification. HTTPS content remains
 * end-to-end encrypted: CONNECT is tunneled without TLS interception.
 */
internal class InAppCloudflareDnsProxy private constructor() {
    companion object {
        private const val TAG = "DORAMAX_DOH"
        private const val DOH_IP = "1.1.1.1"
        private const val DOH_HOST = "cloudflare-dns.com"
        private const val DOH_PATH = "/dns-query"
        private const val MAX_HEADER = 16 * 1024
        private val random = SecureRandom()

        @Volatile private var instance: InAppCloudflareDnsProxy? = null

        fun get(): InAppCloudflareDnsProxy =
            instance ?: synchronized(this) {
                instance ?: InAppCloudflareDnsProxy().also { instance = it }
            }
    }

    private val clients = Executors.newCachedThreadPool()
    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val control = Executors.newSingleThreadExecutor()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(context: Context, onReady: (Boolean, String?) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onReady(false, "Este Android System WebView não oferece proxy interno.")
            return
        }
        control.execute {
            try {
                if (running && server?.isBound == true) {
                    onReady(true, null)
                    return@execute
                }
                val local = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
                server = local
                running = true
                clients.execute { acceptLoop(local) }
                val config = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:${local.localPort}")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, control) {
                    onReady(true, null)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Não foi possível iniciar DNS privado no app", error)
                stopInternal()
                onReady(false, error.message ?: "Falha ao iniciar DNS Cloudflare interno.")
            }
        }
    }

    fun stop() {
        control.execute { stopInternal() }
    }

    private fun stopInternal() {
        running = false
        runCatching { server?.close() }
        server = null
        runCatching {
            ProxyController.getInstance().clearProxyOverride(control) { }
        }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running && !listener.isClosed) {
            try {
                val client = listener.accept()
                clients.execute { handleClient(client) }
            } catch (error: Exception) {
                if (running) Log.w(TAG, "Falha aceitando conexão WebView", error)
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { incoming ->
            try {
                incoming.soTimeout = 15000
                val header = readHeader(incoming.getInputStream())
                val firstLine = header.substringBefore("\r\n")
                val output = incoming.getOutputStream()

                if (firstLine.startsWith("CONNECT ", ignoreCase = true)) {
                    val authority = firstLine.substringAfter("CONNECT ").substringBefore(' ').trim()
                    val host = authority.substringBeforeLast(':', authority).trim('[', ']')
                    val port = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
                    require(host.isNotBlank() && port in 1..65535) { "Destino CONNECT inválido" }
                    connectAndTunnel(incoming, host, port, null)
                    return
                }

                // WebView pode enviar HTTP em formato absolute-form. Não rejeitar esses pedidos:
                // reescreve para origin-form e encaminha pelo mesmo resolvedor DoH.
                val parts = firstLine.split(' ', limit = 3)
                require(parts.size == 3) { "Linha HTTP inválida" }
                val uri = java.net.URI(parts[1])
                require(uri.scheme.equals("http", true) && !uri.host.isNullOrBlank()) {
                    "Somente HTTP absoluto ou HTTPS CONNECT é aceito"
                }
                val port = if (uri.port > 0) uri.port else 80
                val path = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val rewritten = header.replaceFirst(
                    firstLine,
                    "${parts[0]} $path ${parts[2]}"
                )
                connectAndTunnel(incoming, uri.host, port, rewritten.toByteArray(Charsets.ISO_8859_1))
            } catch (error: Exception) {
                Log.w(TAG, "Conexão proxy encerrada: ${error.message}")
                runCatching {
                    if (!client.isClosed) client.getOutputStream().write(
                        "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    )
                }
            }
        }
    }

    private fun connectAndTunnel(
        client: Socket,
        host: String,
        port: Int,
        initialRequest: ByteArray?
    ) {
        val addresses = resolveAddresses(host)
        var lastError: Exception? = null
        for (address in addresses) {
            try {
                Socket().use { upstream ->
                    upstream.connect(InetSocketAddress(address, port), 12000)
                    upstream.soTimeout = 0
                    client.soTimeout = 0
                    val output = client.getOutputStream()
                    if (initialRequest == null) {
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    } else {
                        upstream.getOutputStream().write(initialRequest)
                    }
                    output.flush()
                    if (initialRequest != null) upstream.getOutputStream().flush()
                    tunnel(client, upstream)
                }
                return
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "Falha conectando a $host via ${address.hostAddress}: ${error.message}")
            }
        }
        throw lastError ?: IllegalStateException("Nenhum endereço DNS válido para $host")
    }

    private fun readHeader(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEADER) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            matched = when {
                matched == 0 && value == 13 -> 1
                matched == 1 && value == 10 -> 2
                matched == 2 && value == 13 -> 3
                matched == 3 && value == 10 -> 4
                value == 13 -> 1
                else -> 0
            }
            if (matched == 4) return out.toString(Charsets.ISO_8859_1.name())
        }
        throw IllegalStateException("Cabeçalho HTTP inválido ou muito grande")
    }

    private fun tunnel(client: Socket, upstream: Socket) {
        val finished = CountDownLatch(2)
        fun copy(from: Socket, to: Socket) {
            try {
                from.getInputStream().copyTo(to.getOutputStream())
            } catch (_: Exception) {
            } finally {
                runCatching { to.shutdownOutput() }
                finished.countDown()
            }
        }
        clients.execute { copy(client, upstream) }
        clients.execute { copy(upstream, client) }
        finished.await(30, TimeUnit.MINUTES)
    }

    private fun resolveAddresses(host: String): List<InetAddress> {
        // IP literals não precisam de resolução. Para nomes, somente Cloudflare DoH.
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            return listOf(InetAddress.getByName(host))
        }
        val key = host.trimEnd('.').lowercase()
        val now = System.currentTimeMillis()
        dnsCache[key]?.takeIf { it.first > now }?.let { return it.second }
        val results = ArrayList<InetAddress>()
        for (type in listOf(1, 28)) { // A e AAAA: compatibilidade com CDNs e players IPv6.
            runCatching {
                val response = dohExchange(buildDnsQuery(host, type))
                results.addAll(parseAddressRecords(response, type))
            }.onFailure { Log.w(TAG, "Consulta DoH tipo $type falhou para $host", it) }
        }
        val resolved = results.distinctBy { it.hostAddress }
            .ifEmpty { throw IllegalStateException("Cloudflare DoH não retornou A/AAAA para $host") }
        dnsCache[key] = (now + 60_000L) to resolved
        return resolved
    }

    private fun buildDnsQuery(host: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val id = random.nextInt(65536)
        out.write((id shr 8) and 0xff)
        out.write(id and 0xff)
        out.write(1); out.write(0) // recursion desired
        out.write(0); out.write(1) // one question
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0))
        host.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= 63)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        out.write((type shr 8) and 0xff); out.write(type and 0xff) // A ou AAAA
        out.write(0); out.write(1) // IN
        return out.toByteArray()
    }

    private fun dohExchange(message: ByteArray): ByteArray {
        val raw = Socket()
        raw.connect(InetSocketAddress(DOH_IP, 443), 10000)
        raw.soTimeout = 12000
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, DOH_HOST, 443, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply {
            serverNames = listOf(SNIHostName(DOH_HOST))
            endpointIdentificationAlgorithm = "HTTPS"
        }
        tls.startHandshake()
        tls.use { socket ->
            val request = buildString {
                append("POST $DOH_PATH HTTP/1.1\r\n")
                append("Host: $DOH_HOST\r\n")
                append("Accept: application/dns-message\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Content-Length: ${message.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().apply { write(request); write(message); flush() }
            val input = socket.getInputStream()
            val headers = readHttpHeader(input)
            val status = headers.lineSequence().firstOrNull().orEmpty()
            check(status.contains(" 200 ")) { "Cloudflare DoH respondeu: $status" }
            val length = headers.lineSequence()
                .firstOrNull { it.startsWith("content-length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
                ?: throw IllegalStateException("Resposta DoH sem Content-Length")
            require(length in 12..65535) { "Resposta DoH fora do limite" }
            return ByteArray(length).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) throw IllegalStateException("Resposta DoH incompleta")
                    offset += count
                }
            }
        }
    }

    private fun readHttpHeader(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (state == 4) return out.toString(Charsets.ISO_8859_1.name())
        }
        throw IllegalStateException("Cabeçalho DoH inválido")
    }

    private fun parseAddressRecords(packet: ByteArray, requestedType: Int): List<InetAddress> {
        require(packet.size >= 12) { "Pacote DNS incompleto" }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val flags = buffer.getShort(2).toInt() and 0xffff
        check(flags and 0x8000 != 0 && flags and 0x000f == 0) { "Falha na resposta DNS Cloudflare" }
        val questions = buffer.getShort(4).toInt() and 0xffff
        val answers = buffer.getShort(6).toInt() and 0xffff
        var offset = 12
        repeat(questions) {
            offset = skipDnsName(packet, offset) + 4
            require(offset <= packet.size)
        }
        val result = ArrayList<InetAddress>()
        repeat(answers) {
            offset = skipDnsName(packet, offset)
            require(offset + 10 <= packet.size)
            val type = readU16(packet, offset)
            val clazz = readU16(packet, offset + 2)
            val dataLength = readU16(packet, offset + 8)
            offset += 10
            require(offset + dataLength <= packet.size)
            if (clazz == 1 && type == requestedType &&
                ((type == 1 && dataLength == 4) || (type == 28 && dataLength == 16))) {
                result.add(InetAddress.getByAddress(packet.copyOfRange(offset, offset + dataLength)))
            }
            offset += dataLength
        }
        return result
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun skipDnsName(packet: ByteArray, start: Int): Int {
        var offset = start
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xff
            if (length == 0) return offset + 1
            if (length and 0xc0 == 0xc0) return offset + 2
            require(length <= 63 && offset + 1 + length <= packet.size)
            offset += length + 1
        }
        throw IllegalStateException("Nome DNS inválido")
    }
}
