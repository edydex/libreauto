// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardBackRouterTest {
    static final class Host implements DashboardBackRouter.Host {
        boolean voice,context,map=true;int voiceCalls,contextCalls,mapCalls,rootCalls;
        public boolean cancelVoiceInput(){voiceCalls++;if(!voice)return false;voice=false;return true;}
        public boolean dismissContext(){contextCalls++;if(!context)return false;context=false;return true;}
        public boolean returnToMap(){mapCalls++;if(map)return false;map=true;return true;}
        public void atMapRoot(){rootCalls++;}
    }
    @Test public void backReleaseClosesContextWithoutExitingDashboard(){Host h=new Host();h.context=true;DashboardBackRouter r=new DashboardBackRouter(h);assertTrue(r.dispatch(4,0,false));assertTrue(h.context);assertTrue(r.dispatch(4,1,false));assertFalse(h.context);assertEquals(0,h.rootCalls);assertEquals(0,h.mapCalls);}
    @Test public void repeatedDownAndDuplicateUpOnlyNavigateOnce(){Host h=new Host();DashboardBackRouter r=new DashboardBackRouter(h);r.dispatch(4,0,false);r.dispatch(4,0,false);r.dispatch(4,0,false);r.dispatch(4,1,false);r.dispatch(4,1,false);assertEquals(1,h.rootCalls);}
    @Test public void cancelledBackAndOrphanReleaseDoNotCancelCurrentFlow(){Host h=new Host();h.context=true;DashboardBackRouter r=new DashboardBackRouter(h);r.dispatch(4,1,false);r.dispatch(4,0,false);r.dispatch(4,1,true);assertTrue(h.context);assertEquals(0,h.contextCalls);}
    @Test public void backwardAndForwardDeleteStayWithTextEditor(){Host h=new Host();h.context=true;DashboardBackRouter r=new DashboardBackRouter(h);for(int key:new int[]{67,112})for(int action:new int[]{0,1})assertFalse(r.dispatch(key,action,false));assertTrue(h.context);assertEquals(0,h.contextCalls);assertEquals(0,h.rootCalls);}
    @Test public void voiceCancellationPrecedesDraftOrCandidateDismissal(){Host h=new Host();h.voice=true;h.context=true;h.map=false;DashboardBackRouter r=new DashboardBackRouter(h);r.back();assertFalse(h.voice);assertTrue(h.context);assertFalse(h.map);assertEquals(0,h.contextCalls);}
    @Test public void contextualBackStaysOnItsCurrentTab(){Host h=new Host();h.context=true;h.map=false;new DashboardBackRouter(h).back();assertFalse(h.context);assertFalse(h.map);assertEquals(0,h.mapCalls);}
    @Test public void secondBackReturnsNonMapTabToMap(){Host h=new Host();h.context=true;h.map=false;DashboardBackRouter r=new DashboardBackRouter(h);r.back();r.back();assertTrue(h.map);assertEquals(0,h.rootCalls);}
    @Test public void idleMapBackIsHandledAsRootWithoutAnExitAction(){Host h=new Host();new DashboardBackRouter(h).back();assertTrue(h.map);assertEquals(1,h.rootCalls);}
    @Test public void stopResetsHeldBackBeforeNewSession(){Host h=new Host();DashboardBackRouter r=new DashboardBackRouter(h);r.dispatch(4,0,false);r.reset();r.dispatch(4,1,false);assertEquals(0,h.rootCalls);}
    @Test public void gestureBackCannotReplayAnEarlierHeldKeyRelease(){Host h=new Host();DashboardBackRouter r=new DashboardBackRouter(h);r.dispatch(4,0,false);r.back();r.dispatch(4,1,false);assertEquals(1,h.rootCalls);}
    @Test public void escapeUsesContextNavigationInsteadOfDialogCancel(){Host h=new Host();h.context=true;DashboardBackRouter r=new DashboardBackRouter(h);assertTrue(r.dispatch(111,0,false));assertTrue(h.context);assertTrue(r.dispatch(111,1,false));assertFalse(h.context);assertEquals(0,h.rootCalls);}
    @Test public void otherHardwareKeysAreNotSwallowed(){Host h=new Host();DashboardBackRouter r=new DashboardBackRouter(h);for(int key:new int[]{3,5,6,23,66,85})assertFalse(r.dispatch(key,0,false));assertEquals(0,h.rootCalls);}
}
