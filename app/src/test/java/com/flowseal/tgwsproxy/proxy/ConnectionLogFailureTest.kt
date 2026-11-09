package com.flowseal.tgwsproxy.proxy

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionLogFailureTest {
    @Test
    fun mapsTransportExceptionsToUsefulSafeDiagnostics() {
        assertEquals("таймаут", ConnectionLog.failureReason(SocketTimeoutException("secret-worker.example.org")))
        assertEquals("TLS-проверка", ConnectionLog.failureReason(SSLHandshakeException("secret-worker.example.org")))
        assertEquals("соединение отклонено", ConnectionLog.failureReason(ConnectException("secret-worker.example.org")))
        assertEquals("поток закрыт", ConnectionLog.failureReason(EOFException("secret-worker.example.org")))
    }

    @Test
    fun doesNotLeakHostsOrSecretsFromExceptionMessages() {
        val reason = ConnectionLog.failureReason(IllegalArgumentException("token=fake-secret.example.org"))
        assertFalse(reason.contains("fake-secret"))
        assertFalse(reason.contains("token="))
    }
}
