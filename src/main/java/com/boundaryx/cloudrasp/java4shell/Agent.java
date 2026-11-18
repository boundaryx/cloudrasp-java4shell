package com.boundaryx.cloudrasp.java4shell;

import java.lang.instrument.Instrumentation;

public final class Agent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        try {
            install(instrumentation);
        } catch (Throwable failure) {
            System.err.println("[java4shell-rasp] state=refused " + failure);
            Runtime.getRuntime().halt(78);
        }
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Throwable {
        try {
            install(instrumentation);
        } catch (Throwable failure) {
            System.err.println("[java4shell-rasp] state=refused " + failure);
            throw failure;
        }
    }

    private static void install(Instrumentation instrumentation) throws Exception {
        int sites = DecoderHook.install(instrumentation);
        System.err.println("[java4shell-rasp] state=ready mode=predecode hooked_methods=" + sites);
    }
}
