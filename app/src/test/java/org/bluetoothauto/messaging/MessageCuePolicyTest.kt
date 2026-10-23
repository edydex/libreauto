// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.messaging

import org.junit.Assert.*
import org.junit.Test

class MessageCuePolicyTest {
    private val now = 1_000_000L
    private fun message() = MessageCandidate("com.google.android.apps.messaging", "sms:1", "Alex", "Alex", "Meet at seven?", now)

    @Test fun freshMessageBecomesNaturalSpeech() {
        val cue = requireNotNull(MessageCuePolicy().offer(message(), now))
        assertEquals("Message from Alex on Messages. Meet at seven?", cue.spokenText)
        assertEquals("Alex", cue.sender)
    }

    @Test fun knownPackagesOnly() {
        listOf("com.android.messaging", "com.android.mms", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thoughtcrime.securesms").forEach {
            assertNotNull(MessageCuePolicy().offer(message().copy(packageName = it), now))
        }
        listOf("com.spotify.music", "org.telegram.fake", "com.google.android.gm", "com.android.systemui").forEach {
            assertNull(MessageCuePolicy().offer(message().copy(packageName = it), now))
        }
    }

    @Test fun groupSummariesOngoingSecretAndOutgoingAreNotRead() {
        listOf(message().copy(groupSummary = true), message().copy(ongoing = true), message().copy(secret = true),
            message().copy(senderIsSelf = true)).forEach { assertNull(MessageCuePolicy().offer(it, now)) }
    }

    @Test fun unknownFallbackSchemaIsRejectedButMessagingStyleIsRecognized() {
        assertNull(MessageCuePolicy().offer(message().copy(categoryMessage = false), now))
        assertNotNull(MessageCuePolicy().offer(message().copy(categoryMessage = false, messagingStyle = true), now))
    }

    @Test fun repostingAnOldMessageDoesNotMakeItFresh() {
        assertNull(MessageCuePolicy().offer(message().copy(postTime = now - 30_001), now))
        assertNull(MessageCuePolicy().offer(message().copy(messageTime = now - 30_001), now))
        assertNotNull(MessageCuePolicy().offer(message().copy(messageTime = now - 30_000), now))
    }

    @Test fun brokenOrFarFutureTimestampsAreRejected() {
        listOf(0L, -1L, Long.MIN_VALUE, now + 5_001, Long.MAX_VALUE).forEach {
            assertNull(MessageCuePolicy().offer(message().copy(postTime = it), now))
        }
        assertNotNull(MessageCuePolicy().offer(message().copy(postTime = now + 5_000), now))
    }

    @Test fun sameNotificationProgressRepostsAreDeduplicated() {
        val policy = MessageCuePolicy()
        assertNotNull(policy.offer(message(), now))
        assertNull(policy.offer(message().copy(postTime = now + 1_000), now + 1_000))
        assertNotNull(policy.offer(message().copy(text = "Actually, make it eight."), now + 1_000))
        assertNull(policy.offer(message().copy(postTime = now + 2_000), now + 2_000))
    }

    @Test fun identicalTextInAnotherConversationIsIndependent() {
        val policy = MessageCuePolicy()
        assertNotNull(policy.offer(message(), now))
        assertNotNull(policy.offer(message().copy(key = "sms:2"), now))
    }

    @Test fun dedupMemoryExpiresAndCanBeCleared() {
        val policy = MessageCuePolicy()
        assertNotNull(policy.offer(message(), now))
        val later = now + 120_000
        assertNotNull(policy.offer(message().copy(postTime = later), later))
        policy.clear()
        assertNotNull(policy.offer(message().copy(postTime = later), later))
    }

    @Test fun dedupRetainsAtMost64Fingerprints() {
        val policy = MessageCuePolicy()
        assertNotNull(policy.offer(message(), now))
        repeat(64) { assertNotNull(policy.offer(message().copy(key = "other:$it"), now)) }
        assertNotNull(policy.offer(message(), now))
    }

    @Test fun speechIsBoundedAndControlCharactersAreRemoved() {
        val cue = requireNotNull(MessageCuePolicy().offer(message().copy(sender = "A".repeat(500), text = "hello\u0000\n" + "x".repeat(10_000)), now))
        assertTrue(cue.spokenText.length <= 400)
        assertEquals(64, cue.sender.length)
        assertFalse(cue.spokenText.any { it.isISOControl() })
    }

    @Test fun emptyAndPlaceholderNotificationsAreNotMessages() {
        listOf("", "  ", "New message", "3 new messages", "You have 2 unread messages", "Message content hidden",
            "Checking for new messages…").forEach { assertNull(MessageCuePolicy().offer(message().copy(text = it), now)) }
        assertNull(MessageCuePolicy().offer(message().copy(sender = "", title = ""), now))
    }

    @Test fun unicodeNamesAndMessagesArePreserved() {
        val cue = requireNotNull(MessageCuePolicy().offer(message().copy(sender = "Мама", text = "Буду через пять минут 👋"), now))
        assertTrue(cue.spokenText.contains("Мама"))
        assertTrue(cue.message.contains("👋"))
    }
}
