// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.messaging

import java.security.MessageDigest
import java.util.Locale

data class MessageCue(
    val key: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val message: String,
    val spokenText: String
)

internal data class MessageCandidate(
    val packageName: String,
    val key: String,
    val title: String,
    val sender: String,
    val text: String,
    val postTime: Long,
    val messageTime: Long = 0L,
    val messagingStyle: Boolean = false,
    val categoryMessage: Boolean = true,
    val groupSummary: Boolean = false,
    val ongoing: Boolean = false,
    val secret: Boolean = false,
    val senderIsSelf: Boolean = false
)

/** Fresh-message admission and RAM-only duplicate suppression; no Android or TTS dependencies. */
internal class MessageCuePolicy {
    // Hashes retain no message body or contact name. Fixed size and short expiry bound RAM.
    private val seen = LinkedHashMap<String, Long>()

    fun offer(candidate: MessageCandidate, nowMillis: Long): MessageCue? {
        val app = packages[candidate.packageName] ?: return null
        if (candidate.groupSummary || candidate.ongoing || candidate.secret || candidate.senderIsSelf) return null
        if (!candidate.messagingStyle && !candidate.categoryMessage) return null
        if (!fresh(candidate.postTime, nowMillis)) return null
        if (candidate.messageTime > 0 && !fresh(candidate.messageTime, nowMillis)) return null
        val sender = clean(candidate.sender.ifBlank { candidate.title }, 64)
        if (sender.isBlank()) return null
        val prefix = "Message from $sender on $app. "
        val body = clean(candidate.text, minOf(320, MAX_SPEECH - prefix.length))
        if (body.isBlank() || placeholder(body)) return null
        val key = clean(candidate.key, 256)
        if (key.isBlank()) return null
        val title = clean(candidate.title, 80)
        val fingerprint = fingerprint(candidate.packageName, key, title, sender, body)
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val timestamp = iterator.next().value
            if (nowMillis < timestamp || nowMillis - timestamp >= DEDUP_MS) iterator.remove()
        }
        if (seen.containsKey(fingerprint)) return null
        seen[fingerprint] = nowMillis
        while (seen.size > MAX_ENTRIES) seen.remove(seen.keys.first())
        return MessageCue(key, candidate.packageName, app, sender, body, prefix + body)
    }

    fun clear() { seen.clear() }

    private fun fingerprint(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun placeholder(body: String): Boolean {
        val value = body.lowercase(Locale.ROOT).trim().trimEnd('.', '…')
        return value in placeholders || messageCount.matches(value)
    }

    companion object {
        private const val MAX_ENTRIES = 64
        private const val MAX_SPEECH = 400
        private const val DEDUP_MS = 2L * 60 * 1000
        private const val MAX_AGE_MS = 30_000L
        private val spaces = Regex("\\s+")
        private val messageCount = Regex("(?:you have )?\\d+ (?:new |unread )?messages?")
        private val placeholders = setOf("new message", "new messages", "you have a new message", "you have new messages",
            "message content hidden", "content hidden", "unlock to view message", "unlock to see message",
            "checking for new messages", "connecting", "waiting for network")
        private val packages = mapOf(
            "com.google.android.apps.messaging" to "Messages",
            "com.android.messaging" to "Messages",
            "com.android.mms" to "Messages",
            "org.telegram.messenger" to "Telegram",
            // Official direct-download variant, retained in Telegram's source package configuration.
            "org.telegram.messenger.web" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal"
        )

        fun supported(packageName: String): Boolean = packageName in packages
        private fun fresh(timestamp: Long, now: Long): Boolean = timestamp > 0 && now > 0 && now - timestamp in -5_000L..MAX_AGE_MS
        private fun clean(value: String, max: Int): String = value.take(1024)
            .map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(spaces, " ").trim().take(max.coerceAtLeast(0))
    }
}
