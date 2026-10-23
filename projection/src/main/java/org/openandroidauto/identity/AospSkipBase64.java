/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openandroidauto.identity;

import java.util.Arrays;

/** Small bounded port of AOSP android.util.Base64.Decoder's standard-alphabet SKIP
 * state machine. Invalid padding is rejected; non-alphabet bytes are skipped.
 * It never logs its input/output. */
final class AospSkipBase64 {
    private AospSkipBase64() {}
    static byte[] decode(byte[] input) {
        if (input == null || input.length > 4096) throw new IllegalArgumentException();
        byte[] output = new byte[input.length * 3 / 4 + 10];
        int op = 0, state = 0, value = 0;
        try {
            for (byte b : input) {
                int d = value(b & 255);
                switch (state) {
                    case 0:
                        if (d >= 0) { value = d; state = 1; }
                        else if (d != -1) throw new IllegalArgumentException();
                        break;
                    case 1:
                        if (d >= 0) { value = (value << 6) | d; state = 2; }
                        else if (d != -1) throw new IllegalArgumentException();
                        break;
                    case 2:
                        if (d >= 0) { value = (value << 6) | d; state = 3; }
                        else if (d == -2) { output[op++] = (byte) (value >> 4); state = 4; }
                        else if (d != -1) throw new IllegalArgumentException();
                        break;
                    case 3:
                        if (d >= 0) {
                            value = (value << 6) | d;
                            output[op++] = (byte) (value >> 16);
                            output[op++] = (byte) (value >> 8);
                            output[op++] = (byte) value;
                            state = 0;
                        } else if (d == -2) {
                            output[op++] = (byte) (value >> 10);
                            output[op++] = (byte) (value >> 2);
                            state = 5;
                        } else if (d != -1) throw new IllegalArgumentException();
                        break;
                    case 4:
                        if (d == -2) state = 5;
                        else if (d != -1) throw new IllegalArgumentException();
                        break;
                    case 5:
                        if (d != -1) throw new IllegalArgumentException();
                        break;
                    default: throw new IllegalArgumentException();
                }
            }
            if (state == 1 || state == 4) throw new IllegalArgumentException();
            if (state == 2) output[op++] = (byte) (value >> 4);
            if (state == 3) {
                output[op++] = (byte) (value >> 10);
                output[op++] = (byte) (value >> 2);
            }
            return Arrays.copyOf(output, op);
        } finally { Arrays.fill(output, (byte) 0); }
    }
    private static int value(int b) {
        if (b >= 'A' && b <= 'Z') return b - 'A';
        if (b >= 'a' && b <= 'z') return b - 'a' + 26;
        if (b >= '0' && b <= '9') return b - '0' + 52;
        if (b == '+') return 62;
        if (b == '/') return 63;
        return b == '=' ? -2 : -1;
    }
}
