package com.boundaryx.cloudrasp.java4shell;

import net.bytebuddy.asm.Advice;

public final class PaddingAdvice {
    /**
     * The AVX-512 decode stub consumes 64-byte blocks and passes the remaining
     * {@code (end - start) & 63} bytes to {@code L_finalBit}. That handler
     * underflows only when 1 to 3 bytes remain and the last byte is padding.
     * MIME input is routed to the safe scalar path and is never affected. This
     * mirrors the upstream fix, which sends tails shorter than 4 bytes back to
     * the Java layer.
     */
    @Advice.OnMethodEnter
    public static void check(@Advice.Argument(0) byte[] input, @Advice.Argument(1) int start,
                             @Advice.Argument(2) int end, @Advice.FieldValue("isMIME") boolean mime,
                             @Advice.FieldValue("isURL") boolean url) {
        int len = end - start;
        int rem = len & 63;
        if (mime || len < 65 || rem == 0 || rem > 3 || input[end - 1] != '=') {
            return;
        }
        Rejection.reject(url);
        throw new IllegalArgumentException("base64-java4shell");
    }
}
