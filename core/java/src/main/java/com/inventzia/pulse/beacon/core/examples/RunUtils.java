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
package com.inventzia.pulse.beacon.core.examples;

import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/**
 * Small helpers shared by the runnable examples and the integration test for
 * assembling and launching a run. Scaffolding, not platform API.
 */
public final class RunUtils {

    private RunUtils() {
    }

    /**
     * Resolves a classpath resource (e.g. a bundled JSONL fixture) to a
     * filesystem {@link Path}.
     *
     * @param classpathPath an absolute classpath path, e.g.
     *                      {@code "/examples/data/messages_one.jsonl"}
     * @return the resolved path
     * @throws IllegalStateException if the resource is not on the classpath
     */
    public static Path resource(String classpathPath) {
        URL url = RunUtils.class.getResource(classpathPath);
        if (url == null) {
            throw new IllegalStateException("resource not found on classpath: " + classpathPath);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("invalid resource URI: " + classpathPath, e);
        }
    }

    /**
     * Blocks until {@code gateway} reaches at least {@code target} (by lifecycle
     * ordinal), or the timeout elapses. Used to start the engine and wait for it
     * to be ready before releasing the producers.
     *
     * @param gateway       the gateway to watch
     * @param target        the minimum status to wait for
     * @param timeoutMillis maximum time to wait
     * @throws InterruptedException if interrupted while waiting
     */
    public static void awaitStatus(Gateway gateway, GatewayStatus target, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (gateway.status().ordinal() < target.ordinal()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }
}
