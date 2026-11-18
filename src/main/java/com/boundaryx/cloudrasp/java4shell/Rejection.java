package com.boundaryx.cloudrasp.java4shell;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bootstrap-injected helper for the decoder guard.
 *
 * <p>{@link PaddingAdvice} is inlined into {@code java.util.Base64.Decoder},
 * which is loaded by the bootstrap class loader. The inlined code therefore
 * cannot link against the agent class loader, so the rate-limited audit log and
 * the installer probe live here and {@link DecoderHook} injects this class into
 * the bootstrap search path.
 */
public final class Rejection {
    private static final AtomicLong rejects = new AtomicLong();
    private static final ThreadLocal<Boolean> probe = new ThreadLocal<>();

    private Rejection() {}

    /**
     * Marks the current thread as running the installer self-test. Rejections
     * raised by the self-test are still thrown but are not counted or logged,
     * so a successful installation never looks like an intercepted attack.
     */
    public static void beginProbe() {
        probe.set(Boolean.TRUE);
    }

    public static void endProbe() {
        probe.remove();
    }

    /** Records a rejected java4shell-shaped input, sampling the log. */
    public static void reject(boolean url) {
        if (probe.get() != null) {
            return;
        }
        long count = rejects.incrementAndGet();
        if (count <= 8 || Long.bitCount(count) == 1) {
            System.err.println("[java4shell-rasp] action=reject reason=malformed_base64 mode="
                    + (url ? "url" : "basic") + " count=" + count);
        }
    }
}
