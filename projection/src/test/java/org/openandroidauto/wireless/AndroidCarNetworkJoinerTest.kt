package org.openandroidauto.wireless

import org.junit.Assert.*
import org.junit.Test
import org.openandroidauto.transport.NetworkTcpTransport
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class AndroidCarNetworkJoinerTest {
    private class Clock:NetworkTcpTransport.Scheduler {
        var action:(()->Unit)?=null
        override fun schedule(milliseconds:Long,action:()->Unit):NetworkTcpTransport.Ticket {
            this.action=action;return NetworkTcpTransport.Ticket{this.action=null}
        }
    }
    private class Platform:AndroidCarNetworkJoiner.Platform {
        var ready=true;var closes=0;var requests=0
        lateinit var events:AndroidCarNetworkJoiner.Events
        lateinit var spec:AndroidCarNetworkJoiner.Spec
        var duringRequest:(()->Unit)?=null
        override fun prerequisites()=ready
        override fun request(spec:AndroidCarNetworkJoiner.Spec,events:AndroidCarNetworkJoiner.Events):AutoCloseable {
            requests++;this.spec=spec;this.events=events
            assertEquals("Synthetic Car",spec.ssid.toString(Charsets.UTF_8))
            assertEquals("test-password",spec.passphrase.toString(Charsets.US_ASCII))
            duringRequest?.invoke();return AutoCloseable{closes++}
        }
    }
    private class Callback:CarNetworkJoiner.Callback {
        var available=0;var unavailable=0;var lost=0;var lease:CarNetworkJoiner.Lease?=null
        override fun available(lease:CarNetworkJoiner.Lease){available++;this.lease=lease}
        override fun unavailable(){unavailable++}
        override fun lost(){lost++}
    }
    private class Bound(override val identity:Any=Any()):AndroidCarNetworkJoiner.BoundNetwork {
        var creates=0
        override fun createTransport(ipv4:ByteArray,port:Int):NetworkTcpTransport {
            creates++;return NetworkTcpTransport(object:SocketFactory(){
                override fun createSocket():Socket=error("Test must not connect")
                override fun createSocket(h:String?,p:Int):Socket=error("Forbidden")
                override fun createSocket(h:String?,p:Int,l:InetAddress?,lp:Int):Socket=error("Forbidden")
                override fun createSocket(h:InetAddress?,p:Int):Socket=error("Forbidden")
                override fun createSocket(h:InetAddress?,p:Int,l:InetAddress?,lp:Int):Socket=error("Forbidden")
            },ipv4,port)
        }
    }
    private fun credentials():LegacyWppSession.Credentials = LegacyWppSession.Credentials::class.java.declaredConstructors.single().let {
        it.isAccessible=true;it.newInstance("Synthetic Car".toByteArray(),"test-password".toByteArray(),byteArrayOf(2,1,2,3,4,5),0) as LegacyWppSession.Credentials
    }
    @Test fun exactCopiesErasedOriginalsRetainedAndLeaseReleasedOnce(){
        val platform=Platform();val callback=Callback();val credentials=credentials()
        val request=AndroidCarNetworkJoiner(platform,Clock()).request(9,credentials,callback)
        assertFalse(credentials.isErased);assertTrue(platform.spec.ssid.all{it==0.toByte()});assertTrue(platform.spec.passphrase.all{it==0.toByte()})
        val network=Bound();platform.events.available(network);platform.events.available(network)
        assertEquals(1,callback.available);assertEquals(9L,callback.lease!!.token);assertEquals(0,platform.closes)
        callback.lease!!.createTransport(byteArrayOf(192.toByte(),168.toByte(),1,1),5277)
        assertTrue(runCatching{callback.lease!!.createTransport(byteArrayOf(1,2,3,4),5277)}.exceptionOrNull() is IOException)
        request.close();callback.lease!!.close();platform.events.lost();platform.events.available(Bound())
        assertEquals(1,platform.closes);assertEquals(0,callback.lost);assertEquals(1,network.creates)
        assertTrue(runCatching{callback.lease!!.createTransport(byteArrayOf(1,2,3,4),5277)}.exceptionOrNull() is IOException)
        credentials.close()
    }
    @Test fun cancelBeforeLateAvailableNeverPublishesLease(){
        val platform=Platform();val callback=Callback();val request=AndroidCarNetworkJoiner(platform,Clock()).request(1,credentials(),callback)
        request.close();platform.events.available(Bound());platform.events.unavailable();assertEquals(0,callback.available);assertEquals(0,callback.unavailable);assertEquals(1,platform.closes)
    }
    @Test fun denialTimeoutAndLossAreTerminal(){
        val platform=Platform();val callback=Callback();val clock=Clock()
        AndroidCarNetworkJoiner(platform,clock).request(2,credentials(),callback)
        clock.action!!();platform.events.unavailable();assertEquals(1,callback.unavailable);assertEquals(1,platform.closes)
        val platform2=Platform();val callback2=Callback();AndroidCarNetworkJoiner(platform2,Clock()).request(3,credentials(),callback2)
        platform2.events.available(Bound());platform2.events.lost();platform2.events.lost()
        assertEquals(1,callback2.lost);assertEquals(1,platform2.closes)
    }
    @Test fun permissionFailureNeverRequestsNetwork(){
        val platform=Platform().apply{ready=false};val callback=Callback()
        AndroidCarNetworkJoiner(platform,Clock()).request(4,credentials(),callback).close()
        assertEquals(0,platform.requests);assertEquals(1,callback.unavailable)
    }
    @Test fun synchronousTerminalCallbackStillReleasesLateRegistration(){
        val platform=Platform();val callback=Callback();platform.duringRequest={platform.events.unavailable()}
        AndroidCarNetworkJoiner(platform,Clock()).request(5,credentials(),callback)
        assertEquals(1,platform.closes);assertEquals(1,callback.unavailable)
    }
    @Test fun replacementNetworkClosesRatherThanSwitchesExistingSession(){
        val platform=Platform();val callback=Callback();AndroidCarNetworkJoiner(platform,Clock()).request(6,credentials(),callback)
        platform.events.available(Bound());platform.events.available(Bound());assertEquals(1,callback.available);assertEquals(1,callback.lost);assertEquals(1,platform.closes)
    }
    @Test fun racedOldJoinDeadlineCannotEndAnAvailableLease(){
        val platform=Platform();val callback=Callback();val clock=Clock()
        val request=AndroidCarNetworkJoiner(platform,clock).request(7,credentials(),callback)
        val expired=clock.action!!;platform.events.available(Bound());expired()
        assertEquals(1,callback.available);assertEquals(0,callback.lost);assertEquals(0,platform.closes);request.close()
    }
}
