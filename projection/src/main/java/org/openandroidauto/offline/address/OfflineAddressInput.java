// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounds decompressed JSON bytes, nesting and individual tokens before JsonReader can accumulate them. */
public final class OfflineAddressInput extends FilterInputStream {
    public static final long MAX_BYTES=32L*1024*1024;
    private final long limit;
    private long bytes;
    private int depth,token;
    private boolean string,escape;
    public OfflineAddressInput(InputStream in) { this(in,MAX_BYTES); }
    public OfflineAddressInput(InputStream in,long limit) { super(in);this.limit=limit; }
    public long bytesRead() { return bytes; }
    @Override public int read() throws IOException { int value=super.read();if(value>=0)check(value);return value; }
    @Override public int read(byte[] buffer,int offset,int length) throws IOException {
        int size=in.read(buffer,offset,length);
        for(int index=0;index<size;index++)check(buffer[offset+index]&255);
        return size;
    }
    private void check(int value) throws IOException {
        if(++bytes>limit)throw new IOException("Address data is too large; choose a smaller area");
        if(string) {
            if(++token>8192)throw new IOException("Address data contains an oversized value");
            if(escape)escape=false;
            else if(value=='\\')escape=true;
            else if(value=='\"') { string=false;token=0; }
        } else if(value=='\"') { string=true;token=0; }
        else if(value=='{'||value=='[') { token=0;if(++depth>32)throw new IOException("Address data is nested too deeply"); }
        else if(value=='}'||value==']') { token=0;if(--depth<0)throw new IOException("Malformed address data"); }
        else if(value==','||value==':'||value<=32)token=0;
        else if(++token>128)throw new IOException("Address data contains an oversized token");
    }
}
