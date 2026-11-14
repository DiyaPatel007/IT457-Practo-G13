package edu.dau.practolite.common.observability;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Marks the first request served by an execution environment.
 *
 * <p>Without SnapStart the flag is true for the first request after INIT. With SnapStart the
 * snapshot is taken before any real request, so every environment restored from it also starts
 * with the flag true. Priming (which sends a fake request before the snapshot) calls
 * {@link #resetAfterPriming()} so priming does not use up the flag.
 */
public final class ColdStart {

    private static final AtomicBoolean FIRST_REQUEST = new AtomicBoolean(true);
    private static final AtomicBoolean PRIMING = new AtomicBoolean(false);

    private ColdStart() {
    }

    /** Returns true exactly once per execution environment. */
    public static boolean consume() {
        return FIRST_REQUEST.getAndSet(false);
    }

    public static void beginPriming() {
        PRIMING.set(true);
    }

    public static void resetAfterPriming() {
        PRIMING.set(false);
        FIRST_REQUEST.set(true);
    }

    public static boolean isPriming() {
        return PRIMING.get();
    }
}
