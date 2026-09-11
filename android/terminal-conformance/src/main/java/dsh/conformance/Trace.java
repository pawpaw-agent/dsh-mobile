package dsh.conformance;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The observable history of one corpus case, not just its ending.
 *
 * <p>Comparing only the final screen is both too weak and unrealistic:</p>
 * <ul>
 *   <li><b>Too weak</b> — a case that clears the screen at the end (any program
 *       that exits the alternate screen, or an explicit erase) leaves a blank final
 *       screen, so two implementations that disagree wildly mid-stream still
 *       "agree".</li>
 *   <li><b>Unrealistic</b> — pty data does not arrive in one piece. A terminal must
 *       survive an escape sequence or a UTF-8 character split across two reads,
 *       which a single-shot feed never exercises.</li>
 * </ul>
 *
 * <p>So every case is fed in slices and the screen is hashed after each slice. The
 * trace is the comparison unit; the final {@link Screen} is kept only for producing
 * a readable diff when a trace mismatches.</p>
 */
public final class Trace {

    /** How many slices a case is split into, regardless of its size. */
    private static final int TARGET_SLICES = 128;

    /** Per-slice digests, in feed order. */
    public final List<String> steps = new ArrayList<>();
    /** The screen after the final slice; used for human-readable diffs. */
    public Screen finalScreen;
    /** The slice size used, reported so a failure can be reproduced. */
    public int sliceSize;

    private Trace() {
    }

    /**
     * Feed a case to one implementation, recording the screen digest per slice.
     *
     * @param terminal the implementation under test.
     * @param input    the exact bytes of the case.
     * @return the recorded trace.
     */
    public static Trace of(TerminalUnderTest terminal, byte[] input) {
        Trace trace = new Trace();
        // Small cases get byte-at-a-time feeding (maximum boundary stress); large
        // cases get a slice size that keeps the step count bounded, so the trace
        // stays cheap while still splitting plenty of sequences.
        int slice = Math.max(1, (input.length + TARGET_SLICES - 1) / TARGET_SLICES);
        trace.sliceSize = slice;
        terminal.reset();
        for (int offset = 0; offset < input.length; offset += slice) {
            int length = Math.min(slice, input.length - offset);
            terminal.feed(input, offset, length);
            trace.steps.add(digest(terminal.snapshot().dump()));
        }
        if (input.length == 0) {
            trace.steps.add(digest(terminal.snapshot().dump()));
        }
        trace.finalScreen = terminal.snapshot();
        return trace;
    }

    /** Index of the first slice whose screen differs, or -1 when the traces match. */
    public int firstDifference(Trace other) {
        if (other == null) {
            return 0;
        }
        int shared = Math.min(steps.size(), other.steps.size());
        for (int index = 0; index < shared; index++) {
            if (!steps.get(index).equals(other.steps.get(index))) {
                return index;
            }
        }
        return steps.size() == other.steps.size() ? -1 : shared;
    }

    /** Whether two traces agree at every slice. */
    public boolean sameAs(Trace other) {
        return firstDifference(other) < 0;
    }

    private static String digest(String value) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] bytes = sha.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; failing loudly beats silently comparing nothing.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
