// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice;

import android.os.BadParcelableException;
import android.os.Parcel;

/** Small, versioned same-signer PCM contract. Contains no engine, model, account, target app or Send API.
 * START: version, nonzero token, callback Binder. PCM: token, rate, byte[]. STOP: token.
 * One-way callbacks: token then ready(0/1), fixed status integer, or one bounded transcript.
 * CLOSED means the add-on has observed its native worker death, unbind and internal IPC release.
 */
public final class DictationProtocol {
    private DictationProtocol() {}
    public static final int VERSION=1, START=1, PCM=2, STOP=3;
    public static final int READY=1, STATUS=2, TRANSCRIPT=3;
    public static final String PACKAGE="org.openandroidauto.dictation";
    public static final String SERVICE="org.openandroidauto.dictation.DictationSessionService";
    public static final String PERMISSION="org.openandroidauto.dictation.permission.DICTATE";
    public static final String DESCRIPTOR="org.openandroidauto.dictation.session.v1";
    public static final String CALLBACK="org.openandroidauto.dictation.callback.v1";
    public static final int SAMPLE_RATE=16000, MAX_PCM=6400, MAX_TEXT=2000;
    public static final int STARTING=1, LISTENING=2, INPUT_FINISHED=3, FINAL_READY=4, CLOSED=5,
        CANCELLED=6, MODEL_REQUIRED=7, BUSY=8, INVALID_AUDIO=9, INPUT_OVERFLOW=10,
        NO_SPEECH=11, TIMEOUT=12, FAILED=13;
    public static boolean validStatus(int value) { return value>=STARTING && value<=FAILED; }
    public static boolean validPcm(byte[] pcm,int rate) {
        return rate==SAMPLE_RATE && pcm!=null && pcm.length>=2 && pcm.length<=MAX_PCM && (pcm.length&1)==0;
    }
    public static boolean validText(String text) {
        if(text==null || text.length()<1 || text.length()>MAX_TEXT || text.trim().isEmpty()) return false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isISOControl(c) && c!='\n' && c!='\t') return false;
            if(Character.isHighSurrogate(c)) { if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) return false; }
            else if(Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    public static void end(Parcel p) { if(p.dataAvail()!=0) throw new BadParcelableException("Unexpected dictation payload"); }
    public static byte[] readPcm(Parcel p) {
        if(p.dataAvail()<4 || p.dataAvail()>MAX_PCM+16) throw new BadParcelableException("Invalid dictation audio");
        int at=p.dataPosition(), size=p.readInt();
        if(size<2 || size>MAX_PCM || (size&1)!=0 || p.dataAvail()<size) throw new BadParcelableException("Invalid dictation audio");
        p.setDataPosition(at); byte[] result=p.createByteArray();
        if(!validPcm(result,SAMPLE_RATE)) throw new BadParcelableException("Invalid dictation audio");
        return result;
    }
    public static String readText(Parcel p) {
        if(p.dataAvail()<4 || p.dataAvail()>MAX_TEXT*2+16) throw new BadParcelableException("Invalid dictation text");
        int at=p.dataPosition(),size=p.readInt();
        if(size<1 || size>MAX_TEXT) throw new BadParcelableException("Invalid dictation text");
        p.setDataPosition(at); String result=p.readString();
        if(result==null || result.length()!=size || !validText(result)) throw new BadParcelableException("Invalid dictation text");
        return result;
    }
}
