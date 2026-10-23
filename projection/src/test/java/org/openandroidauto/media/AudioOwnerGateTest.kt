package org.openandroidauto.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class AudioOwnerGateTest {
    @Test fun `only selected producer can enqueue PCM`() {
        val accepted=mutableListOf<Int>();val gate=AudioOwnerGate({accepted.add(it[0].toInt());true},{})
        assertFalse(gate.write(AudioOwnerGate.Source.MEDIA,byteArrayOf(1)))
        gate.select(AudioOwnerGate.Source.MEDIA)
        assertTrue(gate.write(AudioOwnerGate.Source.MEDIA,byteArrayOf(1)))
        assertFalse(gate.write(AudioOwnerGate.Source.NAVIGATION,byteArrayOf(2)))
        gate.select(AudioOwnerGate.Source.NAVIGATION)
        assertFalse(gate.write(AudioOwnerGate.Source.MEDIA,byteArrayOf(1)))
        assertTrue(gate.write(AudioOwnerGate.Source.NAVIGATION,byteArrayOf(2)))
        gate.select(AudioOwnerGate.Source.ASSISTANT)
        assertFalse(gate.write(AudioOwnerGate.Source.NAVIGATION,byteArrayOf(2)))
        assertTrue(gate.write(AudioOwnerGate.Source.ASSISTANT,byteArrayOf(3)))
        assertEquals(listOf(1,2,3),accepted)
    }
    @Test fun `ownership switches flush old audio before new focus`() {
        val calls=mutableListOf<Boolean>();val gate=AudioOwnerGate({true},{calls.add(it)})
        gate.select(AudioOwnerGate.Source.MEDIA);gate.select(AudioOwnerGate.Source.NAVIGATION);gate.select(AudioOwnerGate.Source.NONE)
        assertEquals(listOf(false,true,false,true,false),calls)
        gate.select(AudioOwnerGate.Source.NONE);assertEquals(5,calls.size)
    }
    @Test fun `transport backpressure remains explicit`() {val gate=AudioOwnerGate({false},{});gate.select(AudioOwnerGate.Source.ASSISTANT);assertFalse(gate.write(AudioOwnerGate.Source.ASSISTANT,byteArrayOf(1)))}
    @Test fun `transition waits for in-flight old write then rejects subsequent old writes`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val events=CopyOnWriteArrayList<String>()
        val gate=AudioOwnerGate({entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));events.add("old-write");true},{events.add("focus-$it")})
        gate.select(AudioOwnerGate.Source.MEDIA);events.clear()
        val old=Thread{gate.write(AudioOwnerGate.Source.MEDIA,byteArrayOf(1))};old.start();assertTrue(entered.await(2,TimeUnit.SECONDS))
        val next=Thread{gate.select(AudioOwnerGate.Source.NAVIGATION)};next.start();Thread.sleep(30);assertTrue(events.isEmpty())
        release.countDown();old.join(2000);next.join(2000)
        assertEquals(listOf("old-write","focus-false","focus-true"),events)
        assertFalse(gate.write(AudioOwnerGate.Source.MEDIA,byteArrayOf(1)))
    }
}
