/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core.crosslanguage;

import com.inventzia.pulse.beacon.core.ComponentReporter;
import com.inventzia.pulse.beacon.core.Slf4jReporter;

import jep.Interpreter;
import jep.SubInterpreter;

import java.util.List;

/**
 * The Java-side half of the in-process cross-language bridge in the <b>Java-host</b>
 * (JEP) direction: it runs Python {@code beacon.core} components under the JVM.
 *
 * <p>A Java host declares each Python component to run as a {@link Component}
 * (its Java {@code CrossLanguage} endpoint, which streamer {@link Loop} it drives,
 * the fully-qualified Python type to build, and its constructor arguments), and
 * {@link #launch(Component)} returns a thread that — when started — opens a JEP
 * CPython interpreter bound to that thread, hands the endpoint and args to the
 * generic Python factory ({@code crosslanguage/jep_host.py}), and drives the
 * component's streamer loop until it ends. One interpreter per component, because
 * a JEP interpreter is bound to its creating thread.
 *
 * <p>This is the mirror of the Python-host launcher's {@code jpype_host.start_jvm}:
 * there Python boots the JVM; here the JVM boots CPython. It knows nothing about
 * any specific component — the choice of <em>which</em> types with <em>which</em>
 * args is the caller's, declared as data. Everything below the boundary (the
 * components, {@code dispatch}, the streamer) is shared unchanged with the JPype
 * path; only this bootstrap is direction-specific.
 *
 * <p><b>Runtime requirements</b> (the JVM must be launched to satisfy these; see
 * {@code docs/cross-language-python-inprocess.md} §8b and {@code run-jep-example.sh}):
 * JEP's jar on the classpath, its native {@code libjep.so} on {@code java.library.path},
 * and the shared {@code libpython} reachable via {@code LD_LIBRARY_PATH}.
 */
public final class JepLauncher {

    /** Which streamer loop a component runs: engine → Python, or Python → engine. */
    public enum Loop {
        /** Engine → Python: an Actor (consumes, may publish back) or a sink Gateway. */
        CONSUME,
        /** Python → engine: a source Gateway (yields from {@code produce}). */
        PRODUCE
    }

    /**
     * A Python component to run, declared as data.
     *
     * @param thread    the streamer thread's name (also the interpreter's owner)
     * @param endpoint  the Java {@code CrossLanguageActor} / {@code CrossLanguageGateway} proxy
     * @param loop      which streamer loop this component drives
     * @param typeFqn   fully-qualified name of the Python component class to instantiate
     * @param args      constructor arguments (Java objects, e.g. a parity sink, pass through)
     * @param publishes for a {@code CONSUME} Actor, whether to bind a channel so it can publish back
     */
    public record Component(String thread, Object endpoint, Loop loop,
                            String typeFqn, List<Object> args, boolean publishes) {

        /** Convenience: a non-publishing component. */
        public Component(String thread, Object endpoint, Loop loop, String typeFqn, List<Object> args) {
            this(thread, endpoint, loop, typeFqn, args, false);
        }
    }

    /** The Python module holding the generic factory functions ({@code run_consume} / {@code run_produce}). */
    private static final String FACTORY_MODULE =
            "inventzia.pulse.beacon.core.crosslanguage.jep_host";

    /** The Python logging facade module (its default sink is redirected to the Java logger). */
    private static final String REPORTER_MODULE =
            "inventzia.pulse.beacon.core.reporter";

    private final ComponentReporter log = new ComponentReporter("jep-launcher", Slf4jReporter.shared());
    private final List<String> pythonPath;

    /**
     * @param pythonPath directories to prepend to each interpreter's {@code sys.path} so the
     *                   public {@code inventzia.pulse.*} packages resolve from a source checkout
     *                   (the packages' {@code src/} roots); may be empty when they are
     *                   pip-installed into the embedded interpreter's environment
     */
    public JepLauncher(List<String> pythonPath) {
        this.pythonPath = List.copyOf(pythonPath);
    }

    /**
     * Returns an <em>unstarted</em> thread that runs {@code component} in its own JEP
     * interpreter. The caller starts it when appropriate (e.g. consume components before
     * the engine, produce components after). The thread blocks in the streamer loop
     * until it ends (END for consume, source exhaustion for produce).
     */
    public Thread launch(Component component) {
        return new Thread(() -> {
            try (Interpreter interp = new SubInterpreter()) {
                interp.set("python_path", pythonPath);
                interp.exec("import sys");
                interp.exec("sys.path[0:0] = list(python_path)"); // prepend, preserving order
                // Route this interpreter's component logging through the Java logger,
                // so Python and Java share one ordered, identically-formatted stream.
                interp.set("java_log", Slf4jReporter.shared());
                interp.exec("from " + REPORTER_MODULE + " import set_default_reporter, CallbackReporter");
                interp.exec("set_default_reporter(CallbackReporter(java_log))");
                interp.set("endpoint", component.endpoint());
                interp.set("args", component.args());
                String fn = component.loop() == Loop.CONSUME ? "run_consume" : "run_produce";
                interp.exec("from " + FACTORY_MODULE + " import " + fn);
                interp.exec(call(component, fn));
            } catch (Exception e) {
                log.severe("JEP component '" + component.thread() + "' failed", e);
            }
        }, component.thread());
    }

    /** Builds the Python call, e.g. {@code run_consume(endpoint, 'pkg.Type', args, publishes=True)}. */
    private static String call(Component c, String fn) {
        if (c.loop() == Loop.CONSUME) {
            return fn + "(endpoint, '" + c.typeFqn() + "', args, publishes="
                    + (c.publishes() ? "True" : "False") + ")";
        }
        return fn + "(endpoint, '" + c.typeFqn() + "', args)";
    }
}
