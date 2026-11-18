package com.boundaryx.cloudrasp.java4shell;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collections;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class DecoderHook {
    private static final String HELPER = DecoderHook.class.getPackage().getName() + ".Rejection";
    /**
     * Shape-valid java4shell trigger with an invalid byte in the first 64-byte
     * block. If the hook is ineffective, the intrinsic detects that error and
     * exits before {@code L_finalBit}, so the self-test itself cannot overflow
     * the heap on AVX-512 hardware.
     */
    private static final byte[] SELF_TEST = new byte[65];

    static {
        for (int index = 0; index < SELF_TEST.length; index++) {
            SELF_TEST[index] = (byte) 'A';
        }
        SELF_TEST[0] = '!';
        SELF_TEST[SELF_TEST.length - 1] = '=';
    }

    private static boolean installed;
    private static int hookedMethods;

    private DecoderHook() {}

    public static synchronized int install(Instrumentation instrumentation) throws Exception {
        if (installed) {
            return hookedMethods;
        }
        Class<?> decoder = Base64.Decoder.class;
        if (!instrumentation.isRetransformClassesSupported() || !instrumentation.isModifiableClass(decoder)) {
            throw new IllegalStateException("decoder retransformation unavailable");
        }
        injectHelper(instrumentation);
        ElementMatcher.Junction<MethodDescription> decode = named("decode0").and(isPrivate()).and(not(isStatic())).and(not(isNative()))
                .and(returns(int.class)).and(takesArguments(byte[].class, int.class, int.class, byte[].class));
        InstallStatus status = new InstallStatus();
        ClassFileTransformer transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .ignore(none())
                .type(is(decoder), isBootstrapClassLoader())
                .transform((builder, type, loader, module, domain) -> {
                    type.getDeclaredMethods().filter(decode).getOnly();
                    status.hooked = 1;
                    return builder.visit(Advice.to(PaddingAdvice.class).on(decode));
                })
                .with(status)
                .makeRaw();
        instrumentation.addTransformer(transformer, true);
        try {
            instrumentation.retransformClasses(decoder);
            if (!status.transformed || status.hooked != 1) {
                throw new IllegalStateException("decoder hook not installed", status.failure);
            }
            verifyHook();
            hookedMethods = status.hooked;
            installed = true;
            return hookedMethods;
        } finally {
            if (!installed) {
                instrumentation.removeTransformer(transformer);
            }
        }
    }

    /**
     * Proves the hook is effective before reporting readiness. The probe
     * suppresses the audit log, so a successful install emits no rejection
     * event; the decode must fail with the guard's exception instead.
     */
    private static void verifyHook() throws Exception {
        Class<?> helper = Class.forName(HELPER, true, null);
        if (helper.getClassLoader() != null) {
            throw new IllegalStateException("decoder hook self-test: helper is not in the bootstrap loader");
        }
        Method begin = helper.getMethod("beginProbe");
        Method end = helper.getMethod("endProbe");
        boolean intercepted = false;
        begin.invoke(null);
        try {
            Base64.getDecoder().decode(SELF_TEST);
        } catch (IllegalArgumentException failure) {
            intercepted = "base64-java4shell".equals(failure.getMessage());
        } finally {
            end.invoke(null);
        }
        if (!intercepted) {
            throw new IllegalStateException("decoder hook self-test: malformed input was not intercepted");
        }
    }

    private static void injectHelper(Instrumentation instrumentation) throws Exception {
        File directory = Files.createTempDirectory("java4shell-bootstrap-").toFile();
        directory.deleteOnExit();
        try (ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(DecoderHook.class.getClassLoader())) {
            ClassInjector.UsingInstrumentation.of(directory, ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, instrumentation)
                    .injectRaw(Collections.singleton(HELPER), locator);
        }
    }

    private static final class InstallStatus extends AgentBuilder.Listener.Adapter {
        private volatile boolean transformed;
        private volatile int hooked;
        private volatile Throwable failure;

        @Override
        public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded, DynamicType result) {
            transformed = true;
        }

        @Override
        public void onError(String name, ClassLoader loader, JavaModule module, boolean loaded, Throwable error) {
            if (loader == null && name.equals(Base64.Decoder.class.getName())) {
                failure = error;
            }
        }
    }
}
