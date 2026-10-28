package com.aeronex.app.session

import com.aeronex.app.link.Transport

/**
 * Runs [setup] once on the link's RX thread (before its first read) and once on its TX thread (before its first
 * write), e.g. to raise their priority on Android; [LinkSession][com.aeronex.app.link.LinkSession]
 * creates those threads itself. By the [Transport] contract each method is called from its own thread only. A
 * failing setup is ignored: the link must run at default priority rather than not at all.
 */
internal class ThreadSetupTransport(private val inner: Transport, private val setup: () -> Unit) : Transport by inner {
    private var readerReady = false // RX thread only
    private var writerReady = false // TX thread only

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (!readerReady) {
            readerReady = true
            runSetup()
        }
        return inner.read(buffer, timeoutMs)
    }

    override fun write(bytes: ByteArray): Boolean {
        if (!writerReady) {
            writerReady = true
            runSetup()
        }
        return inner.write(bytes)
    }

    private fun runSetup() {
        try {
            setup()
        } catch (_: Exception) {
            // Default priority.
        }
    }
}
