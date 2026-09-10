/*
 * Copyright 2008-2026 Vassilios Karakoidas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.firej.tools;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the bytecode size of the methods of a class file without loading
 * it: the {@code code_length} of each {@code Code} attribute. The size
 * benchmark and the tests use it on the generated matcher, because what
 * HotSpot does with a method depends on that number — one over
 * {@code HugeMethodLimit} (8,000 bytes) is never JIT-compiled while
 * {@code DontCompileHugeMethods} is on, and it is on by default — and the
 * threaded emitter splits its walk into {@code seg0}, {@code seg1}, … to
 * stay under it.
 */
public final class CodeSize {
    private CodeSize() {
    }

    /** The {@code code_length} of {@code method}, or {@code -1} if the class has no such method with code. */
    public static int of(byte[] classFile, String method) {
        return methods(classFile).getOrDefault(method, -1);
    }

    /** Every method that has code, by name, with its {@code code_length}, in class-file order. */
    public static Map<String, Integer> methods(byte[] classFile) {
        ByteBuffer b = ByteBuffer.wrap(classFile);
        if (b.getInt() != 0xCAFEBABE) {
            throw new IllegalArgumentException("not a class file");
        }
        b.getShort(); // minor
        b.getShort(); // major
        int poolCount = b.getShort() & 0xFFFF;
        String[] utf8 = new String[poolCount];
        for (int i = 1; i < poolCount; i++) {
            int tag = b.get() & 0xFF;
            switch (tag) {
                case 1 -> { // Utf8
                    int len = b.getShort() & 0xFFFF;
                    byte[] bytes = new byte[len];
                    b.get(bytes);
                    utf8[i] = new String(bytes, StandardCharsets.UTF_8);
                }
                case 3, 4 -> b.getInt(); // Integer, Float
                case 5, 6 -> { // Long, Double take two slots
                    b.getLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> b.getShort(); // Class, String, MethodType, Module, Package
                case 9, 10, 11, 12, 17, 18 -> b.getInt(); // refs, NameAndType, Dynamic, InvokeDynamic
                case 15 -> { // MethodHandle
                    b.get();
                    b.getShort();
                }
                default -> throw new IllegalArgumentException("unknown constant pool tag " + tag);
            }
        }
        b.getShort(); // access
        b.getShort(); // this
        b.getShort(); // super
        int interfaces = b.getShort() & 0xFFFF;
        b.position(b.position() + 2 * interfaces);
        Map<String, Integer> out = new LinkedHashMap<>();
        readMembers(b, utf8, null); // fields
        readMembers(b, utf8, out); // methods
        return out;
    }

    /** Reads a field or method table; with {@code out} set, records each member's {@code Code} length. */
    private static void readMembers(ByteBuffer b, String[] utf8, Map<String, Integer> out) {
        int count = b.getShort() & 0xFFFF;
        for (int m = 0; m < count; m++) {
            b.getShort(); // access
            int name = b.getShort() & 0xFFFF;
            b.getShort(); // descriptor
            int attributes = b.getShort() & 0xFFFF;
            for (int a = 0; a < attributes; a++) {
                int attributeName = b.getShort() & 0xFFFF;
                int length = b.getInt();
                if (out != null && "Code".equals(utf8[attributeName])) {
                    b.getShort(); // max_stack
                    b.getShort(); // max_locals
                    out.put(utf8[name], b.getInt());
                    b.position(b.position() + length - 8);
                } else {
                    b.position(b.position() + length);
                }
            }
        }
    }
}
