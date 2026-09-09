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
package org.firej.codegen;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.CALOAD;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_M1;
import static org.objectweb.asm.Opcodes.IFGE;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IF_ICMPGE;
import static org.objectweb.asm.Opcodes.IF_ICMPGT;
import static org.objectweb.asm.Opcodes.IF_ICMPLT;
import static org.objectweb.asm.Opcodes.IF_ICMPNE;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V21;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.firej.Regex;
import org.firej.RegexCompilationException;
import org.firej.cache.RegexTemplate;
import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.FlattenedDfa.Range;
import org.firej.runtime.GeneratedClassLoader;
import org.firej.runtime.InterpreterRegex;
import org.objectweb.asm.ClassTooLargeException;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Compiles a flattened DFA to a {@code CharArrayRegex} subclass. Replaces the
 * 2007 Jasmin + {@code jsr}/{@code ret} pipeline, which modern JVM verifiers reject.
 *
 * <p>Two emitters. The default is <em>threaded</em>: every state is a block of
 * code that reads the next character, decides where it leads and jumps
 * straight to the destination state's block — the paper's Algorithm 2, with
 * plain {@code goto} so the verifier is happy. There is no state variable and
 * no dispatch. The <em>switch</em> emitter is the paper's Algorithm 1: a loop
 * around a {@code tableswitch} on a state variable, kept so the cost of that
 * indirection can be measured ({@link GeneratorKind#BYTECODE_SWITCH}).
 *
 * <p>How a threaded state decides is the second choice. With the
 * <em>class map</em> (the default) the generator partitions the character
 * space at the boundaries of every range in the automaton; the generated class
 * holds a static {@code char[]} from character to class (built once by
 * {@link ClassMap}), and a state with more than two ranges resolves its
 * character with one array load and one {@code tableswitch} over the classes,
 * which the JIT compiles to a jump table. Without it
 * ({@link GeneratorKind#BYTECODE_RANGES}) every state tests its ranges in
 * sequence, a compare-and-branch pair each — fast for a digit, slow for
 * {@code [a-zA-Z0-9_]}. States with one or two ranges keep the compare chain
 * either way: it is cheaper than a load.
 *
 * <p><b>The JIT's size limit.</b> HotSpot never compiles a method whose
 * bytecode is longer than {@link #JIT_LIMIT} (its {@code HugeMethodLimit},
 * 8,000 bytes, while {@code DontCompileHugeMethods} is on, as it is by
 * default); such a method runs in the JVM's bytecode interpreter, 20 to 40
 * times slower than compiled code and slower than FIRE/J's own interpreter,
 * a cliff a walk reaches at a few hundred states. So a
 * threaded {@code walk} that would exceed the limit is <em>split</em>: the
 * states, numbered along the paths a walk takes, are cut into consecutive
 * segments whose blocks fit under the limit, each segment is a method of its
 * own ({@code seg0}, {@code seg1}, …) threaded internally, and {@code walk}
 * becomes a small loop that dispatches on the state a segment returns. A
 * transition to a state in another segment ("a hop") stores the position and
 * the accept position in fields and returns the destination; the next segment
 * reloads them. Block sizes are measured by emitting the single method first
 * and reading the offsets of its labels, so the split is exact, and the
 * result is checked against the limit and re-cut with a smaller budget if a
 * segment overshoots. The switch emitter is not split; it, and any class ASM
 * cannot write, fall back to the interpreter.
 */
public final class AsmCodeGenerator implements CodeGenerator {
    private static final String SUPER = "org/firej/runtime/CharArrayRegex";
    private static final String CLASS_MAP = "org/firej/runtime/ClassMap";
    private static final String PACKAGE = "org.firej.generated";
    private static final AtomicLong NEXT_ID = new AtomicLong();
    /** A state with more ranges than this dispatches through the class map. */
    private static final int CHAIN_RANGES = 2;
    /**
     * HotSpot's {@code HugeMethodLimit}: a method with more bytecode than this
     * is never JIT-compiled while {@code DontCompileHugeMethods} is on, which
     * it is by default.
     */
    public static final int JIT_LIMIT = 8000;
    /**
     * What one segment's state blocks may add up to. The rest of the limit is
     * the segment's prologue, its entry switch (four bytes per state) and the
     * hop sites, three bytes over an in-segment jump each.
     */
    private static final int SEGMENT_BUDGET = 7200;
    /** A DFA with more states than a {@code char} can index is left to the interpreter. */
    private static final int MAX_STATES = Character.MAX_VALUE;

    private final GeneratedClassLoader loader = new GeneratedClassLoader();
    private final boolean threaded;
    private final boolean classMap;

    public AsmCodeGenerator() {
        this(true, true);
    }

    public AsmCodeGenerator(boolean threaded) {
        this(threaded, threaded);
    }

    public AsmCodeGenerator(boolean threaded, boolean classMap) {
        this.threaded = threaded;
        this.classMap = threaded && classMap;
    }

    @Override
    public synchronized RegexTemplate compile(FlattenedDfa dfa) {
        if (dfa.stateCount() > MAX_STATES) {
            return () -> new InterpreterRegex(dfa);
        }
        String simple = "R" + NEXT_ID.incrementAndGet();
        String binary = PACKAGE + "." + simple;
        String internal = PACKAGE.replace('.', '/') + "/" + simple;
        byte[] bytecode;
        try {
            bytecode = emit(internal, dfa, threaded, classMap);
        } catch (MethodTooLargeException | ClassTooLargeException e) {
            // The switch emitter is one method under the JVM's 64 KiB cap; the threaded
            // one is split into segments, but a class can still overflow its constant pool.
            return () -> new InterpreterRegex(dfa);
        }
        Class<?> cls = loader.define(binary, bytecode);
        return () -> newInstance(cls);
    }

    /**
     * The class file this generator would define for {@code dfa}, for
     * inspection rather than loading: the tests read the sizes of
     * {@code walk} and its segments off it.
     *
     * @throws MethodTooLargeException if a method would exceed the JVM's 64 KiB cap
     */
    public byte[] emit(FlattenedDfa dfa) {
        return emit(PACKAGE.replace('.', '/') + "/Probe", dfa, threaded, classMap);
    }

    private static Regex newInstance(Class<?> cls) {
        try {
            return (Regex) cls.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException e) {
            throw new RegexCompilationException("Generated matcher constructor failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RegexCompilationException("Failed to instantiate generated matcher", e);
        }
    }

    static byte[] emit(String internalName, FlattenedDfa dfa, boolean threaded, boolean classMap) {
        Partition partition = threaded && classMap ? Partition.of(dfa) : null;
        if (!threaded) {
            ClassWriter cw = beginClass(internalName, dfa, partition, null);
            emitSwitchExec(cw, dfa);
            cw.visitEnd();
            return cw.toByteArray();
        }
        Threaded emitter = new Threaded(internalName, dfa, partition);
        ClassWriter cw = beginClass(internalName, dfa, partition, null);
        Threaded.Layout single = emitter.emitSingle(cw);
        if (single.length() <= JIT_LIMIT) {
            cw.visitEnd();
            return cw.toByteArray();
        }
        int budget = SEGMENT_BUDGET;
        while (true) {
            int[] starts = emitter.segments(single.blockSizes(), budget);
            cw = beginClass(internalName, dfa, partition, starts);
            int largest = emitter.emitSplit(cw, starts);
            if (largest <= JIT_LIMIT || budget <= JIT_LIMIT / 8) {
                // A single state whose block alone is over the limit stays over it; that
                // segment runs interpreted, the rest of the class does not.
                cw.visitEnd();
                return cw.toByteArray();
            }
            budget = budget * 4 / 5;
        }
    }

    /**
     * The class with its fields, static initialiser and constructor; the walk
     * method(s) are added by the caller. {@code segmentStarts} is non-null
     * for a split walk: the first state of every segment.
     */
    private static ClassWriter beginClass(String internalName, FlattenedDfa dfa, Partition partition,
            int[] segmentStarts) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, SUPER, null);
        cw.visitSource(internalName.substring(internalName.lastIndexOf('/') + 1) + ".java", null);

        boolean classMapUsed = partition != null && partition.used();
        boolean segmentMap = segmentStarts != null && segmentStarts.length > 1;
        if (classMapUsed) {
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "CLASSMAP", "[C", null, null).visitEnd();
        }
        if (segmentStarts != null) {
            cw.visitField(ACC_PRIVATE, "acc", "I", null, null).visitEnd();
        }
        if (segmentMap) {
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "SEGMAP", "[C", null, null).visitEnd();
        }
        if (classMapUsed || segmentMap) {
            MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            if (classMapUsed) {
                clinit.visitLdcInsn(partition.boundaries());
                clinit.visitMethodInsn(INVOKESTATIC, CLASS_MAP, "of", "(Ljava/lang/String;)[C", false);
                clinit.visitFieldInsn(PUTSTATIC, internalName, "CLASSMAP", "[C");
            }
            if (segmentMap) {
                // The segment of a state is the number of segment starts at or below it:
                // the same shape as the class map, so the same builder serves.
                StringBuilder b = new StringBuilder(segmentStarts.length - 1);
                for (int k = 1; k < segmentStarts.length; k++) {
                    b.append((char) segmentStarts[k]);
                }
                clinit.visitLdcInsn(b.toString());
                clinit.visitMethodInsn(INVOKESTATIC, CLASS_MAP, "of", "(Ljava/lang/String;)[C", false);
                clinit.visitFieldInsn(PUTSTATIC, internalName, "SEGMAP", "[C");
            }
            clinit.visitInsn(RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
        }

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitLdcInsn(dfa.pattern());
        init.visitMethodInsn(INVOKESPECIAL, SUPER, "<init>", "(Ljava/lang/String;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return cw;
    }

    /**
     * The character space cut at every range boundary of the automaton.
     * {@code boundaries} holds the cut points ascending (never 0); the class
     * of a character is the number of cut points at or below it, so there are
     * {@code boundaries.length() + 1} classes and a character at or above the
     * last cut point is in the last one, which needs no table.
     */
    record Partition(String boundaries, boolean used) {
        static Partition of(FlattenedDfa dfa) {
            TreeSet<Integer> cuts = new TreeSet<>();
            boolean used = false;
            for (Range[] edges : dfa.transitions()) {
                if (edges.length > CHAIN_RANGES) {
                    used = true;
                }
                for (Range edge : edges) {
                    if (edge.min() > 0) {
                        cuts.add(edge.min());
                    }
                    if (edge.max() < Character.MAX_VALUE) {
                        cuts.add(edge.max() + 1);
                    }
                }
            }
            StringBuilder b = new StringBuilder(cuts.size());
            for (int cut : cuts) {
                b.append((char) cut);
            }
            return new Partition(b.toString(), used);
        }

        int classCount() {
            return boundaries.length() + 1;
        }

        /** The class of {@code c}: cut points at or below it. */
        int classOf(int c) {
            int lo = 0;
            int hi = boundaries.length();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (boundaries.charAt(mid) <= c) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /** Length of the table: characters at or above the last cut point are the last class. */
        int mapLength() {
            return boundaries.isEmpty() ? 0 : boundaries.charAt(boundaries.length() - 1);
        }
    }

    /**
     * Shared prologue of {@code walk(int start)}. Locals: 0 this, 1 start,
     * 2 arr, 3 len, 4 state (switch emitter only), 5 returnValue, 6 i, 7 c.
     * Returns the {@code fail} label, which the epilogue emits only if
     * something jumped to it.
     */
    private static Label emitPrologue(MethodVisitor mv, FlattenedDfa dfa) {
        Label fail = new Label();
        if (dfa.anchoredStart()) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitJumpInsn(IFNE, fail);
        }

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, SUPER, "arrayBuffer", "[C");
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, SUPER, "length", "I");
        mv.visitVarInsn(ISTORE, 3);

        if (dfa.accept()[dfa.startState()]) {
            mv.visitVarInsn(ILOAD, 1);
        } else {
            mv.visitInsn(ICONST_M1);
        }
        mv.visitVarInsn(ISTORE, 5);

        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ISTORE, 6);
        return fail;
    }

    /**
     * Shared epilogue: {@code exit} records where the walk stopped in
     * {@code pos} (so {@code CharArrayRegex.exec} can tell a walk cut off by
     * the copy window from one that died), applies the {@code $} check and
     * returns {@code returnValue}; {@code fail} returns {@code -1}.
     */
    private static void emitEpilogue(MethodVisitor mv, FlattenedDfa dfa, Label exit, Label fail) {
        mv.visitLabel(exit);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitFieldInsn(PUTFIELD, SUPER, "pos", "I");
        if (dfa.anchoredEnd()) {
            mv.visitVarInsn(ILOAD, 5);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitJumpInsn(IF_ICMPNE, fail);
        }
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(IRETURN);

        if (dfa.anchoredStart() || dfa.anchoredEnd()) {
            mv.visitLabel(fail);
            mv.visitInsn(ICONST_M1);
            mv.visitInsn(IRETURN);
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Bytes the epilogue adds after the {@code exit} label. */
    private static int epilogueBytes(FlattenedDfa dfa) {
        return 6 + (dfa.anchoredEnd() ? 6 : 0) + 3 + (dfa.anchoredStart() || dfa.anchoredEnd() ? 2 : 0);
    }

    /**
     * Algorithm 2: one block per state, {@code goto} to the next state's block.
     * A block reads {@code arr[i]}, decides where the character leads — by a
     * compare chain over its ranges, or through the class map — and on a hit
     * records the accept position, advances {@code i} and jumps; with no hit
     * it exits. Emits either one {@code walk} or, past {@link #JIT_LIMIT}, a
     * dispatching {@code walk} over segment methods.
     */
    private static final class Threaded {
        private final String internalName;
        private final FlattenedDfa dfa;
        private final Partition partition;
        private final boolean[] accept;
        private final Range[][] transitions;
        private final int n;

        Threaded(String internalName, FlattenedDfa dfa, Partition partition) {
            this.internalName = internalName;
            this.dfa = dfa;
            this.partition = partition;
            this.accept = dfa.accept();
            this.transitions = dfa.transitions();
            this.n = dfa.stateCount();
        }

        /** Where a block's jumps leave the segment: destinations outside {@code [lo, hi)} go through {@code label}. */
        private record Hop(int lo, int hi, Label label) {
            boolean outside(int dest) {
                return dest < lo || dest >= hi;
            }
        }

        /** The single-method layout: every state's block size and the method's bytecode length. */
        record Layout(int[] blockSizes, int length) {
        }

        Layout emitSingle(ClassWriter cw) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "walk", "(I)I", null, null);
            mv.visitCode();
            Label exit = new Label();
            Label fail = emitPrologue(mv, dfa);
            Label[] states = new Label[n];
            for (int s = 0; s < n; s++) {
                states[s] = new Label();
            }
            mv.visitJumpInsn(GOTO, states[dfa.startState()]);
            for (int s = 0; s < n; s++) {
                mv.visitLabel(states[s]);
                emitBlock(mv, s, states, exit, null);
            }
            emitEpilogue(mv, dfa, exit, fail);
            // Labels visited through a ClassWriter know their offsets; blocks are laid out in state order.
            int[] sizes = new int[n];
            for (int s = 0; s < n; s++) {
                sizes[s] = (s + 1 < n ? states[s + 1] : exit).getOffset() - states[s].getOffset();
            }
            return new Layout(sizes, exit.getOffset() + epilogueBytes(dfa));
        }

        /**
         * Cuts the states into consecutive segments whose blocks fit the
         * budget, charging each state its measured block, its entry-switch
         * slot and a hop site per distinct destination. Returns the first
         * state of every segment.
         */
        int[] segments(int[] blockSizes, int budget) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            int sum = 0;
            for (int s = 0; s < n; s++) {
                int cost = blockSizes[s] + 4 + 3 * destinations(s);
                if (s > starts.getLast() && sum + cost > budget) {
                    starts.add(s);
                    sum = 0;
                }
                sum += cost;
            }
            return starts.stream().mapToInt(Integer::intValue).toArray();
        }

        private int destinations(int s) {
            Set<Integer> dests = new HashSet<>();
            for (Range edge : transitions[s]) {
                dests.add(edge.dest());
            }
            return dests.size();
        }

        /** Emits the dispatching {@code walk} and one method per segment; returns the largest method's code size. */
        int emitSplit(ClassWriter cw, int[] starts) {
            emitDispatcher(cw, starts);
            int largest = 0;
            for (int k = 0; k < starts.length; k++) {
                int lo = starts[k];
                int hi = k + 1 < starts.length ? starts[k + 1] : n;
                largest = Math.max(largest, emitSegment(cw, k, lo, hi));
            }
            return largest;
        }

        /**
         * {@code walk} for a split automaton: seeds {@code pos} and {@code acc},
         * then calls the segment holding the current state until a segment
         * returns {@code -1}. Locals: 0 this, 1 start, 2 state, 5 returnValue.
         */
        private void emitDispatcher(ClassWriter cw, int[] starts) {
            int k = starts.length;
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "walk", "(I)I", null, null);
            mv.visitCode();
            Label fail = new Label();
            if (dfa.anchoredStart()) {
                mv.visitVarInsn(ILOAD, 1);
                mv.visitJumpInsn(IFNE, fail);
            }
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 1);
            mv.visitFieldInsn(PUTFIELD, SUPER, "pos", "I");
            mv.visitVarInsn(ALOAD, 0);
            if (accept[dfa.startState()]) {
                mv.visitVarInsn(ILOAD, 1);
            } else {
                mv.visitInsn(ICONST_M1);
            }
            mv.visitFieldInsn(PUTFIELD, internalName, "acc", "I");
            pushInt(mv, dfa.startState());
            mv.visitVarInsn(ISTORE, 2);

            Label loop = new Label();
            Label check = new Label();
            Label done = new Label();
            mv.visitLabel(loop);
            if (k == 1) {
                emitSegmentCall(mv, 0);
            } else {
                // idx = state < mapLength ? SEGMAP[state] : k - 1
                Label useLast = new Label();
                Label haveIndex = new Label();
                mv.visitVarInsn(ILOAD, 2);
                pushInt(mv, starts[k - 1]);
                mv.visitJumpInsn(IF_ICMPGE, useLast);
                mv.visitFieldInsn(GETSTATIC, internalName, "SEGMAP", "[C");
                mv.visitVarInsn(ILOAD, 2);
                mv.visitInsn(CALOAD);
                mv.visitJumpInsn(GOTO, haveIndex);
                mv.visitLabel(useLast);
                pushInt(mv, k - 1);
                mv.visitLabel(haveIndex);
                Label[] cases = new Label[k];
                for (int i = 0; i < k; i++) {
                    cases[i] = new Label();
                }
                mv.visitTableSwitchInsn(0, k - 1, done, cases);
                for (int i = 0; i < k; i++) {
                    mv.visitLabel(cases[i]);
                    emitSegmentCall(mv, i);
                    mv.visitJumpInsn(GOTO, check);
                }
            }
            mv.visitLabel(check);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitJumpInsn(IFGE, loop);

            mv.visitLabel(done);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, internalName, "acc", "I");
            mv.visitVarInsn(ISTORE, 5);
            if (dfa.anchoredEnd()) {
                mv.visitVarInsn(ILOAD, 5);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, SUPER, "length", "I");
                mv.visitJumpInsn(IF_ICMPNE, fail);
            }
            mv.visitVarInsn(ILOAD, 5);
            mv.visitInsn(IRETURN);
            if (dfa.anchoredStart() || dfa.anchoredEnd()) {
                mv.visitLabel(fail);
                mv.visitInsn(ICONST_M1);
                mv.visitInsn(IRETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        /** {@code state = segK(state)}. */
        private void emitSegmentCall(MethodVisitor mv, int k) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKESPECIAL, internalName, "seg" + k, "(I)I", false);
            mv.visitVarInsn(ISTORE, 2);
        }

        /**
         * One segment: {@code int segK(int state)} reloads the walk's locals
         * from the fields, enters the block of {@code state}, threads within
         * {@code [lo, hi)}, and on leaving stores {@code pos} and {@code acc}
         * and returns the next state, or {@code -1} when the walk is over.
         * Locals as in {@code walk}, with 1 the entry state and 4 the result.
         * Returns the method's code size.
         */
        private int emitSegment(ClassWriter cw, int k, int lo, int hi) {
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_FINAL, "seg" + k, "(I)I", null, null);
            mv.visitCode();
            Label begin = new Label();
            mv.visitLabel(begin);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, SUPER, "arrayBuffer", "[C");
            mv.visitVarInsn(ASTORE, 2);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, SUPER, "length", "I");
            mv.visitVarInsn(ISTORE, 3);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, internalName, "acc", "I");
            mv.visitVarInsn(ISTORE, 5);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, SUPER, "pos", "I");
            mv.visitVarInsn(ISTORE, 6);

            Label exit = new Label();
            Label hop = new Label();
            Label[] states = new Label[n];
            Label[] entries = new Label[hi - lo];
            for (int s = lo; s < hi; s++) {
                states[s] = new Label();
                entries[s - lo] = states[s];
            }
            mv.visitVarInsn(ILOAD, 1);
            mv.visitTableSwitchInsn(lo, hi - 1, exit, entries);
            Hop away = new Hop(lo, hi, hop);
            for (int s = lo; s < hi; s++) {
                mv.visitLabel(states[s]);
                emitBlock(mv, s, states, exit, away);
            }
            mv.visitLabel(exit);
            mv.visitInsn(ICONST_M1);
            mv.visitLabel(hop); // the next state is on the stack
            mv.visitVarInsn(ISTORE, 4);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 6);
            mv.visitFieldInsn(PUTFIELD, SUPER, "pos", "I");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 5);
            mv.visitFieldInsn(PUTFIELD, internalName, "acc", "I");
            mv.visitVarInsn(ILOAD, 4);
            mv.visitInsn(IRETURN);
            Label end = new Label();
            mv.visitLabel(end);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return end.getOffset() - begin.getOffset();
        }

        /** A state's block: bounds check, character load, decision. */
        private void emitBlock(MethodVisitor mv, int s, Label[] states, Label exit, Hop hop) {
            Range[] edges = transitions[s];
            if (edges.length == 0) {
                mv.visitJumpInsn(GOTO, exit);
                return;
            }
            mv.visitVarInsn(ILOAD, 6);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitJumpInsn(IF_ICMPGE, exit);

            emitLoadChar(mv);

            if (partition != null && edges.length > CHAIN_RANGES) {
                emitClassDispatch(mv, edges, states, exit, hop);
            } else {
                emitRangeChain(mv, edges, states, hop);
            }
            mv.visitJumpInsn(GOTO, exit);
        }

        /** Test each range in turn; on a hit take the transition. Falls through when nothing matched. */
        private void emitRangeChain(MethodVisitor mv, Range[] edges, Label[] states, Hop hop) {
            for (Range edge : edges) {
                Label nextEdge = new Label();
                mv.visitVarInsn(ILOAD, 7);
                pushInt(mv, edge.min());
                mv.visitJumpInsn(IF_ICMPLT, nextEdge);
                if (edge.max() != Character.MAX_VALUE) {
                    mv.visitVarInsn(ILOAD, 7);
                    pushInt(mv, edge.max());
                    mv.visitJumpInsn(IF_ICMPGT, nextEdge);
                }
                emitTake(mv, edge.dest(), states, hop);
                mv.visitLabel(nextEdge);
            }
        }

        /**
         * Look the character's class up and {@code tableswitch} on it. Classes
         * that lead to the same state share one transition block; classes with no
         * transition go to {@code exit} through the switch's default.
         */
        private void emitClassDispatch(MethodVisitor mv, Range[] edges, Label[] states, Label exit, Hop hop) {
            int classes = partition.classCount();
            int[] dest = new int[classes];
            Arrays.fill(dest, -1);
            for (Range edge : edges) {
                int from = partition.classOf(edge.min());
                int to = partition.classOf(edge.max());
                for (int c = from; c <= to; c++) {
                    dest[c] = edge.dest();
                }
            }

            // cls = c < mapLength ? CLASSMAP[c] : lastClass
            Label useLast = new Label();
            Label haveClass = new Label();
            mv.visitVarInsn(ILOAD, 7);
            pushInt(mv, partition.mapLength());
            mv.visitJumpInsn(IF_ICMPGE, useLast);
            mv.visitFieldInsn(GETSTATIC, internalName, "CLASSMAP", "[C");
            mv.visitVarInsn(ILOAD, 7);
            mv.visitInsn(CALOAD);
            mv.visitJumpInsn(GOTO, haveClass);
            mv.visitLabel(useLast);
            pushInt(mv, classes - 1);
            mv.visitLabel(haveClass);

            Label[] byClass = new Label[classes];
            Label[] byDest = new Label[n];
            for (int c = 0; c < classes; c++) {
                if (dest[c] < 0) {
                    byClass[c] = exit;
                } else {
                    if (byDest[dest[c]] == null) {
                        byDest[dest[c]] = new Label();
                    }
                    byClass[c] = byDest[dest[c]];
                }
            }
            mv.visitTableSwitchInsn(0, classes - 1, exit, byClass);
            for (int d = 0; d < byDest.length; d++) {
                if (byDest[d] != null) {
                    mv.visitLabel(byDest[d]);
                    emitTake(mv, d, states, hop);
                }
            }
        }

        /**
         * Consume the character, note an accepting destination, and jump to
         * it — or, from a segment whose destination lies elsewhere, hand the
         * destination to the dispatcher.
         */
        private void emitTake(MethodVisitor mv, int dest, Label[] states, Hop hop) {
            mv.visitIincInsn(6, 1);
            if (accept[dest]) {
                mv.visitVarInsn(ILOAD, 6);
                mv.visitVarInsn(ISTORE, 5);
            }
            if (hop != null && hop.outside(dest)) {
                pushInt(mv, dest);
                mv.visitJumpInsn(GOTO, hop.label());
            } else {
                mv.visitJumpInsn(GOTO, states[dest]);
            }
        }
    }

    /** {@code c = arr[i]} into local 7. */
    private static void emitLoadChar(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(CALOAD);
        mv.visitVarInsn(ISTORE, 7);
    }

    /**
     * Algorithm 1: a loop around a {@code tableswitch} on the state variable.
     */
    private static void emitSwitchExec(ClassWriter cw, FlattenedDfa dfa) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "walk", "(I)I", null, null);
        mv.visitCode();

        Label exit = new Label();
        Label fail = emitPrologue(mv, dfa);

        pushInt(mv, dfa.startState());
        mv.visitVarInsn(ISTORE, 4);

        Label loopStart = new Label();
        Label afterSwitch = new Label();

        mv.visitLabel(loopStart);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitJumpInsn(IF_ICMPGE, exit);

        emitLoadChar(mv);

        int n = dfa.stateCount();
        Label[] cases = new Label[n];
        for (int s = 0; s < n; s++) {
            cases[s] = new Label();
        }

        mv.visitVarInsn(ILOAD, 4);
        mv.visitTableSwitchInsn(0, n - 1, exit, cases);

        boolean[] accept = dfa.accept();
        Range[][] transitions = dfa.transitions();
        for (int s = 0; s < n; s++) {
            mv.visitLabel(cases[s]);
            Range[] edges = transitions[s];
            for (Range edge : edges) {
                Label nextEdge = new Label();
                mv.visitVarInsn(ILOAD, 7);
                pushInt(mv, edge.min());
                mv.visitJumpInsn(IF_ICMPLT, nextEdge);
                mv.visitVarInsn(ILOAD, 7);
                pushInt(mv, edge.max());
                mv.visitJumpInsn(IF_ICMPGT, nextEdge);

                pushInt(mv, edge.dest());
                mv.visitVarInsn(ISTORE, 4);
                if (accept[edge.dest()]) {
                    mv.visitVarInsn(ILOAD, 6);
                    mv.visitInsn(ICONST_1);
                    mv.visitInsn(IADD);
                    mv.visitVarInsn(ISTORE, 5);
                }
                mv.visitJumpInsn(GOTO, afterSwitch);
                mv.visitLabel(nextEdge);
            }
            mv.visitJumpInsn(GOTO, exit);
        }

        mv.visitLabel(afterSwitch);
        mv.visitIincInsn(6, 1);
        mv.visitJumpInsn(GOTO, loopStart);

        emitEpilogue(mv, dfa, exit, fail);
    }

    static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    @Override
    public String getName() {
        return !threaded ? "BYTECODE_SWITCH" : classMap ? "BYTECODE" : "BYTECODE_RANGES";
    }
}
