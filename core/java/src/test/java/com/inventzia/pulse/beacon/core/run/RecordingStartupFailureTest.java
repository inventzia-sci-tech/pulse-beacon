/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core.run;

import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When the recording cannot be set up at all — an unwritable output root, a full disk.
 *
 * <p>The decision this pins: the run <b>continues</b>, but <b>not silently</b>. Continuing is right
 * because an observer must never take down what it observes, and killing a live run because a disk
 * is full is usually worse than losing its recording. Silence is wrong because the launcher asked to
 * record and is getting nothing — for a system whose point is auditability, "no artifact and no
 * complaint" is the worst of both.
 *
 * <p>Whether that should be fatal is the launcher's call, not the library's: a compliance replay
 * wants {@link RunRecording#requireRecording()}, a live trading run usually does not. So the library
 * makes the failure loud and available, and leaves the verdict to the caller.
 */
class RecordingStartupFailureTest {

    private static final long START = 1_283_630_000_000L;   // past window -> COMPRESSED_TIME
    private static final long END   = 1_283_630_005_000L;

    /** An output root that cannot hold a run directory: a regular file where a directory must be. */
    private static Path unusableRoot(Path dir) throws Exception {
        Path root = dir.resolve("not-a-directory");
        Files.writeString(root, "this is a file, so no run directory can be created beneath it");
        return root;
    }

    private static final class Fixture {
        final MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        final HeartBeatGateway beater;
        final RunRecording run;

        Fixture(Path root) {
            Topic<HeartBeat> beats = new Topic<>("hb", HeartBeat.class);
            beater = new HeartBeatGateway("beater", beats, "BEAT", 2_000L, START + 1_000L, START, END);
            engine.registerPublisher(beater, beats, List.of("BEAT"));
            run = RunRecording.start("UnrecordableApp", engine, START, END, "engine:test", 1024, root);
            run.recordRoute(engine, beats, List.of("BEAT"));
        }

        void execute() throws Exception {
            Thread engineThread = run.scoped(engine, "engine");
            engineThread.start();
            RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);
            Thread beaterThread = run.scoped(beater, "beater");
            beaterThread.start();
            engineThread.join(15_000);
            beaterThread.join(5_000);
            assertThat(engineThread.isAlive()).isFalse();
        }
    }

    @Test
    void theRunSurvivesAnUnusableOutputRoot(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(unusableRoot(dir));

        try (RunRecording run = f.run) {
            f.execute();

            assertThat(f.engine.status())
                    .as("the run completes; an observer must not take down what it observes")
                    .isEqualTo(GatewayStatus.COMPLETE);
            assertThat(run.isRecording()).as("but it is not being recorded").isFalse();
            assertThat(run.paths()).isNull();
            assertThat(run.startupFailure())
                    .as("and the reason is kept, not swallowed")
                    .isNotNull();
        }
    }

    @Test
    void requireRecordingRefusesAnUnrecordedRun(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(unusableRoot(dir));

        try (RunRecording run = f.run) {
            f.execute();

            // What a launcher calls when the artifact is the point and it would rather abort.
            assertThatThrownBy(run::requireRecording)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not being recorded")
                    .hasCauseInstanceOf(Throwable.class);
        }
    }

    @Test
    void requireRecordingPassesForAHealthyRun(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);

        try (RunRecording run = f.run) {
            f.execute();

            run.requireRecording();          // must not throw
            assertThat(run.isRecording()).isTrue();
            assertThat(run.startupFailure()).isNull();
            assertThat(Files.exists(run.paths().manifest())).isTrue();
        }
    }

    @Test
    void requireRecordingRefusesBeforeTheEngineHasInitialised(@TempDir Path dir) {
        Fixture f = new Fixture(dir);      // constructed, never run

        assertThatThrownBy(f.run::requireRecording)
                .as("nothing has been recorded yet, and saying otherwise would be a lie")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialised yet");
        f.run.close();
    }
}
