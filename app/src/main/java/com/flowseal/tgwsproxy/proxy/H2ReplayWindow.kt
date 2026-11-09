package com.flowseal.tgwsproxy.proxy

/**
 * Bounded recovery history for Cloudflare /api requests.
 *
 * Only encrypted MTProto packets with a non-zero auth_key_id and a small
 * payload are retained. A request is replayable only after its body was sent
 * and no response body has started. Reply IDs prevent duplicate delivery when
 * both the original request and its replay eventually complete.
 */
internal class H2ReplayWindow(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxAgeMs: Long = 30_000L,
    private val requestTimeoutMs: Long = 3_000L,
    private val retryDelayMs: Long = 2_000L,
    private val maxPackets: Int = 16,
    private val maxBytes: Int = 64 * 1024,
    private val maxAttempts: Int = 3,
    private val maxConcurrentReplays: Int = 2
) {
    internal class Entry internal constructor(
        internal val body: ByteArray,
        internal val sequence: Long,
        internal val createdAt: Long
    ) {
        internal class Pending(
            val replay: Boolean,
            var sentAt: Long? = null,
            var receiving: Boolean = false
        )

        internal val pending = LinkedHashMap<Long, Pending>()
        internal var firstSentAt: Long? = null
        internal var attempts = 0
        internal var retired = false
        internal var lastAttemptAt: Long? = null
        internal var replyId: ByteArray? = null
    }

    internal class Ticket internal constructor(
        internal val id: Long,
        internal val entry: Entry,
        internal val replay: Boolean,
        internal val reservedAt: Long = 0L
    )

    private val entries = ArrayList<Entry>()
    private var authKeyId: ByteArray? = null
    private var retained = 0
    private var nextSequence = 0L
    private var nextTicket = 0L
    private var pendingReplays = 0
    private var lastProgressAt = clock()

    @Synchronized
    fun remember(body: ByteArray): Entry? {
        if (body.size < 8 || body.size > maxBytes) return null
        val key = body.copyOfRange(0, 8)
        if (key.all { it == 0.toByte() }) return null

        if (authKeyId == null || !authKeyId!!.contentEquals(key)) {
            // Old requests may still complete after an auth_key_id rotation.
            // Keep their replay reservations counted until their callbacks finish.
            clearHistoryLocked()
            authKeyId = key
        }
        entries.firstOrNull { it.body.contentEquals(body) }?.let { return it }

        val now = clock()
        val entry = Entry(body.copyOf(), ++nextSequence, now)
        entries.add(entry)
        retained += entry.body.size
        trimLocked(now)
        return if (entries.contains(entry)) entry else null
    }

    @Synchronized
    fun registerOriginal(entry: Entry): Ticket = registerLocked(entry, replay = false)

    @Synchronized
    fun markSent(ticket: Ticket) {
        val pending = ticket.entry.pending[ticket.id] ?: return
        val now = clock()
        pending.sentAt = now
        if (!ticket.replay && ticket.entry.firstSentAt == null) {
            ticket.entry.firstSentAt = now
        }
    }

    @Synchronized
    fun markReceiving(ticket: Ticket) {
        ticket.entry.pending[ticket.id]?.receiving = true
        lastProgressAt = clock()
    }

    @Synchronized
    fun markProgress(ticket: Ticket? = null) {
        if (ticket == null || ticket.entry.pending.containsKey(ticket.id)) {
            lastProgressAt = clock()
        }
    }

    @Synchronized
    fun complete(ticket: Ticket) {
        val removed = ticket.entry.pending.remove(ticket.id) ?: return
        if (removed.replay) pendingReplays = (pendingReplays - 1).coerceAtLeast(0)
        trimLocked(clock())
    }

    @Synchronized
    fun nextReplayCandidate(allowLatestIdlePoll: Boolean = true): Entry? {
        val now = clock()
        trimLocked(now)
        return entries.firstOrNull { entry ->
            val age = now - entry.createdAt
            val waiting = entry.retired || entry.pending.values.any { request ->
                request.sentAt?.let { !request.receiving && now - it >= requestTimeoutMs } == true
            }
            val anotherRequestPending = entries.any { other ->
                other !== entry && other.pending.isNotEmpty()
            }
            val latestIdlePoll = allowLatestIdlePoll && entry === entries.lastOrNull() &&
                entry.pending.isEmpty() && !anotherRequestPending &&
                now - lastProgressAt >= IDLE_POLL_MS
            age in 0..maxAgeMs &&
                entry.firstSentAt?.let { now - it <= maxAgeMs } == true &&
                entry.attempts < maxAttempts &&
                (entry.lastAttemptAt == null || now - entry.lastAttemptAt!! >= retryDelayMs) &&
                entry.pending.values.none { it.replay } &&
                entry.pending.values.none { it.receiving } &&
                (waiting || latestIdlePoll)
        }
    }

    @Synchronized
    fun reserveReplay(entry: Entry, allowLatestIdlePoll: Boolean = true): Ticket? {
        if (pendingReplays >= maxConcurrentReplays ||
            nextReplayCandidate(allowLatestIdlePoll) !== entry
        ) return null
        val now = clock()
        entry.attempts += 1
        entry.lastAttemptAt = now
        pendingReplays += 1
        val ticket = registerLocked(entry, replay = true)
        return Ticket(ticket.id, ticket.entry, replay = true, reservedAt = now)
    }

    /** Retires an overdue request to free a channel/lane slot for newer uploads. */
    @Synchronized
    fun retire(ticket: Ticket): Boolean {
        val pending = ticket.entry.pending[ticket.id] ?: return false
        if (pending.receiving || pending.sentAt == null) return false
        ticket.entry.pending.remove(ticket.id)
        if (pending.replay) pendingReplays = (pendingReplays - 1).coerceAtLeast(0)
        ticket.entry.retired = true
        return true
    }

    /** Releases a replay reservation that never reached the network. */
    @Synchronized
    fun cancelReplay(ticket: Ticket) {
        if (!ticket.replay) return
        val removed = ticket.entry.pending.remove(ticket.id) ?: return
        if (removed.replay) pendingReplays = (pendingReplays - 1).coerceAtLeast(0)
        if (removed.sentAt == null && ticket.entry.lastAttemptAt == ticket.reservedAt) {
            ticket.entry.attempts = (ticket.entry.attempts - 1).coerceAtLeast(0)
            ticket.entry.lastAttemptAt = 0L
        }
    }

    /** Returns false when this is a duplicate response to the same native packet. */
    @Synchronized
    fun acceptReply(ticket: Ticket?, reply: ByteArray): Boolean {
        lastProgressAt = clock()
        if (ticket == null) return true
        val entry = ticket.entry
        if (!entry.pending.containsKey(ticket.id)) return false
        val replyId = reply.copyOfRange(0, minOf(24, reply.size))
        val previous = entry.replyId
        if (previous != null && previous.contentEquals(replyId)) return false
        entry.replyId = replyId
        entry.attempts = 0
        return true
    }

    @Synchronized
    fun body(entry: Entry): ByteArray = entry.body.copyOf()

    @Synchronized
    fun clear() = clearLocked()

    internal val retainedPackets: Int
        get() = synchronized(this) {
            trimLocked(clock())
            entries.size
        }

    internal val retainedBytes: Int
        get() = synchronized(this) {
            trimLocked(clock())
            retained
        }

    @Synchronized
    internal fun findForTest(entry: Entry): Entry? = entries.firstOrNull { it === entry }

    @Synchronized
    internal fun attemptsForTest(entry: Entry): Int = entry.attempts

    private fun registerLocked(entry: Entry, replay: Boolean): Ticket {
        val ticket = Ticket(++nextTicket, entry, replay)
        entry.pending[ticket.id] = Entry.Pending(replay)
        return ticket
    }

    private fun trimLocked(now: Long) {
        val expired = entries.filter { now - it.createdAt > maxAgeMs }
        for (entry in expired) {
            entries.remove(entry)
            retained -= entry.body.size
        }
        while (entries.size > maxPackets || retained > maxBytes) {
            val victim = entries.firstOrNull { entry ->
                entry.pending.isEmpty() &&
                    !(entry.retired && entry.attempts < maxAttempts && now - entry.createdAt <= maxAgeMs)
            } ?: entries.firstOrNull() ?: break
            entries.remove(victim)
            retained -= victim.body.size
        }
        retained = retained.coerceAtLeast(0)
    }

    private companion object {
        const val IDLE_POLL_MS = 1_000L
    }

    private fun clearHistoryLocked() {
        entries.clear()
        retained = 0
        authKeyId = null
    }

    private fun clearLocked() {
        clearHistoryLocked()
        pendingReplays = 0
    }
}
