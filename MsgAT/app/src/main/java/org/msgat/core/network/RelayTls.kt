// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import io.netty.buffer.ByteBufAllocator
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.msgat.core.crypto.securestorage.SecurePrefs
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Immutable endpoint policy shared by control and independently authenticated bulk channels. */
internal data class RelayEndpoint(val host: String, val port: Int, val certificatePin: String, val allowDevelopmentCertificate: Boolean) {
    val verifiesServer: Boolean get() = certificatePin.isNotEmpty() || !allowDevelopmentCertificate

    fun handler(allocator: ByteBufAllocator): SslHandler {
        val tls = SslContextBuilder.forClient().sslProvider(SslProvider.JDK).protocols("TLSv1.3", "TLSv1.2")
        if (certificatePin.isNotEmpty()) {
            require(certificatePin.matches(Regex("[a-f0-9]{64}"))) { "Invalid TLS SHA-256 pin" }
            tls.trustManager(object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client certificate not supported") }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val certificate = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
                    val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    val expected = ByteArray(32) { certificatePin.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    if (!MessageDigest.isEqual(actual, expected)) throw CertificateException("Server certificate pin mismatch")
                }
            })
        } else if (allowDevelopmentCertificate) tls.trustManager(InsecureTrustManagerFactory.INSTANCE)
        val handler = tls.build().newHandler(allocator, host, port)
        if (certificatePin.isEmpty() && !allowDevelopmentCertificate) {
            val parameters = handler.engine().sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            handler.engine().sslParameters = parameters
        }
        return handler
    }

    companion object {
        fun from(prefs: SecurePrefs): RelayEndpoint {
            val host = requireNotNull(prefs.serverIp).also { require(it.isNotBlank()) }
            val port = prefs.serverPort.also { require(it in 1..65535) }
            return RelayEndpoint(host, port, prefs.serverCertificateSha256.orEmpty().replace(":", "").replace(" ", "").lowercase(), prefs.allowDevelopmentCertificate)
        }
    }
}
