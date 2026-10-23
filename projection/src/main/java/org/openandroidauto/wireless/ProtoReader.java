// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.wireless;

/** Small strict wire reader; protocol facts only, independently implemented. */
final class ProtoReader {
    private final byte[] data;
    private int offset;
    int field, wire;
    ProtoReader(byte[] data) { this.data = data; }
    boolean next() {
        if (offset == data.length) return false;
        long key = varint();
        if (key <= 0 || key > 0xffffffffL) throw bad();
        field = (int) (key >>> 3); wire = (int) (key & 7);
        if (field < 1 || field > 0x1fffffff || wire != 0 && wire != 1 && wire != 2 && wire != 5) throw bad();
        return true;
    }
    long number() { if (wire != 0) throw bad(); return varint(); }
    byte[] bytes(int bound) {
        if (wire != 2) throw bad();
        long size = varint();
        if (size < 0 || size > bound || size > data.length - offset) throw bad();
        byte[] result = new byte[(int) size];
        System.arraycopy(data, offset, result, 0, result.length); offset += result.length;
        return result;
    }
    void skip() {
        if (wire == 0) { varint(); return; }
        long size = wire == 1 ? 8 : wire == 5 ? 4 : varint();
        if (size < 0 || size > data.length - offset) throw bad();
        offset += (int) size;
    }
    private long varint() {
        long value = 0;
        for (int index = 0; index < 10; index++) {
            if (offset >= data.length) throw bad();
            int part = data[offset++] & 255;
            if (index == 9 && part > 1) throw bad();
            value |= (long) (part & 127) << (index * 7);
            if ((part & 128) == 0) return value;
        }
        throw bad();
    }
    static IllegalArgumentException bad() { return new IllegalArgumentException("Malformed protocol payload"); }
}
