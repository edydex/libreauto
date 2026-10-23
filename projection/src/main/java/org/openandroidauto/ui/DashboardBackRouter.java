// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.ui;

/** Back belongs to dashboard navigation, never to the enclosing Dialog's dismiss action. */
public final class DashboardBackRouter {
    public interface Host {
        boolean cancelVoiceInput();
        boolean dismissContext();
        boolean returnToMap();
        void atMapRoot();
    }
    private final Host host;
    private int pressedKey=-1;
    public DashboardBackRouter(Host host){this.host=java.util.Objects.requireNonNull(host);}
    /** BACK=4 and Escape=111 navigate. Editing Delete=67/112 stays with normal Views. */
    public boolean dispatch(int keyCode,int action,boolean cancelled){
        if(keyCode!=4&&keyCode!=111)return false;
        if(action==0)pressedKey=keyCode;
        else if(action==1){boolean release=pressedKey==keyCode;pressedKey=-1;if(release&&!cancelled)back();}
        return true;
    }
    public void reset(){pressedKey=-1;}
    /** Also used by the normal phone/predictive-Back callback, which has no key sequence. */
    public void back(){
        pressedKey=-1;
        if(host.cancelVoiceInput()||host.dismissContext()||host.returnToMap())return;
        host.atMapRoot();
    }
}
