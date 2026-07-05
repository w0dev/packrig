package net.ft8vc.rig.fakes

import net.ft8vc.rig.SerialTransport

/**
 * Scripted [SerialTransport] for JVM tests: records writes and RTS/DTR edges,
 * plays back enqueued reply bytes (optionally in small chunks to exercise
 * partial-read reassembly), and simulates write failures and timeouts.
 */
class FakeSerialTransport : SerialTransport {

    var openResult = true
    var opened = false
        private set
    var failWrites = false
    var failReads = false

    /** Max bytes returned per read() — lower to force partial reads. */
    var readChunkLimit = Int.MAX_VALUE

    val writes = mutableListOf<ByteArray>()
    val rtsEdges = mutableListOf<Boolean>()
    val dtrEdges = mutableListOf<Boolean>()

    private val pending = ArrayDeque<Byte>()

    fun enqueueReply(ascii: String) {
        ascii.toByteArray(Charsets.US_ASCII).forEach { pending.addLast(it) }
    }

    fun writtenAscii(): List<String> = writes.map { it.toString(Charsets.US_ASCII) }

    override fun open(): Boolean {
        opened = openResult
        return openResult
    }

    override fun close() {
        opened = false
    }

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (failWrites) return false
        writes += bytes.copyOf()
        return true
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (failReads) return -1
        if (pending.isEmpty()) return 0
        val n = minOf(buffer.size, readChunkLimit, pending.size)
        repeat(n) { buffer[it] = pending.removeFirst() }
        return n
    }

    override fun setRts(asserted: Boolean): Boolean {
        rtsEdges += asserted
        return true
    }

    override fun setDtr(asserted: Boolean): Boolean {
        dtrEdges += asserted
        return true
    }
}
