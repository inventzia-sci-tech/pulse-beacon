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
package com.inventzia.pulse.beacon.core.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventzia.pulse.beacon.core.OperatingMode;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Pulse output layout, in one place (Java / engine side).
 *
 * <p>Path convention (see {@code New/pulse-output.md}):
 * <pre>
 *   $PULSE_OUTPUT / {historical|live} / &lt;app&gt; / &lt;runId&gt; / {run.json, events.jsonl, console.log}
 * </pre>
 *
 * <p>This is the exact counterpart of {@code pulse-viewer/reference/run_layout.py}: both agree
 * byte-for-byte on the directory shape and manifest, so a run written by the engine here is listed and
 * opened by the Python viewer there without translation. It is intentionally free of any recorder,
 * appender, or engine coupling: it only resolves the root, builds a collision- and path-safe run
 * directory, and reads and writes the {@code run.json} manifest atomically. The orchestration that
 * ties a recorder and a per-run log to a directory lives in {@link RunRecording}.
 */
public final class RunLayout {

    /** Manifest file name. */
    public static final String MANIFEST = "run.json";
    /** Event recording file name. */
    public static final String EVENTS = "events.jsonl";
    /** Per-run console log file name. */
    public static final String CONSOLE = "console.log";

    /** MDC key carrying the active run id, so per-run log routing can scope records by run identity. */
    public static final String MDC_RUN_ID = "pulseRunId";

    private static final int MAX_COMPONENT = 64;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final DateTimeFormatter RUN_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final ObjectMapper JSON = new ObjectMapper();

    private RunLayout() {}

    // ------------------------------------------------------------------
    // Root, tier, id, directory
    // ------------------------------------------------------------------

    /** Resolve the output root: {@code explicit} if given, else {@code $PULSE_OUTPUT}, else {@code ~/.pulse/runs}. */
    public static Path outputRoot(Path explicit) {
        if (explicit != null) return explicit;
        String env = System.getenv("PULSE_OUTPUT");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".pulse", "runs");
    }

    /** Map an {@link OperatingMode} to its directory tier: historical (compressed) / live (real-time, mixed). */
    public static String tierForMode(OperatingMode mode) {
        return switch (mode) {
            case COMPRESSED_TIME -> "historical";
            case REAL_TIME, MIXED -> "live";
            default -> "other";
        };
    }

    /**
     * One safe path component: keep letters/digits/{@code ._-}; collapse the rest to {@code -}; strip
     * leading and trailing dots/dashes; bound the length; never a separator, {@code .}, {@code ..}, or
     * empty (those become {@code unnamed}). Mirrors {@code run_layout._safe}.
     */
    static String safe(String name) {
        String s = UNSAFE.matcher(name == null ? "" : name.strip()).replaceAll("-");
        s = strip(s);
        if (s.length() > MAX_COMPONENT) s = s.substring(0, MAX_COMPONENT);
        s = strip(s);
        return (s.isEmpty() || s.equals(".") || s.equals("..")) ? "unnamed" : s;
    }

    private static String strip(String s) {
        int i = 0, j = s.length();
        while (i < j && (s.charAt(i) == '-' || s.charAt(i) == '.')) i++;
        while (j > i && (s.charAt(j - 1) == '-' || s.charAt(j - 1) == '.')) j--;
        return s.substring(i, j);
    }

    /** True if {@code target} resolves inside {@code root} (defence against traversal via a crafted app name). */
    static boolean isWithin(Path root, Path target) {
        Path r = root.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        return t.startsWith(r);
    }

    /** A sortable, unique run id: {@code <UTC timestamp>Z-<short uuid>}. */
    public static String newRunId() {
        return RUN_TS.format(Instant.now()) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /** The directory for one run: {@code root / tier(mode) / safe(app) / runId}. */
    public static Path runDir(Path root, OperatingMode mode, String app, String runId) {
        return root.resolve(tierForMode(mode)).resolve(safe(app)).resolve(runId);
    }

    // ------------------------------------------------------------------
    // Create / finalize
    // ------------------------------------------------------------------

    /** The four paths of one run directory. */
    public record RunPaths(Path dir, Path manifest, Path events, Path console) {}

    /** Recording counters, as they appear under the manifest's {@code counts}. */
    public record RecordingCounts(long observed, long events, long overflow,
                                  long serializationErrors, long abandoned) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("observed", observed);
            m.put("events", events);
            m.put("overflow", overflow);
            m.put("serializationErrors", serializationErrors);
            m.put("abandoned", abandoned);
            return m;
        }
    }

    /**
     * Create a run directory and write the initial manifest ({@code runStatus: running},
     * {@code recordingStatus: recording}). Collision-safe (the run directory itself must be freshly
     * created; a generated-id collision retries, a supplied-id collision errors) and path-safe (the
     * resolved directory must be under the output root). Mirrors {@code run_layout.create_run}.
     *
     * @param root            output root, or {@code null} to resolve from {@code $PULSE_OUTPUT}/default
     * @param mode            the run's operating mode (selects the tier)
     * @param app             the application name (sanitised to one safe component)
     * @param runId           a caller-supplied run id, or {@code null} to generate one
     * @param source          free-form source identity (e.g. {@code "engine:Engine"})
     * @param window          {@code [start, end]} epoch millis, or {@code null}
     * @param typeFingerprint the datum-type universe fingerprint, or {@code null}
     * @param providerIds     the contributing provider ids
     * @param recording       recording descriptor ({@code capture}/{@code sequence}/{@code routes}), or {@code null}
     * @return the run's paths
     */
    public static RunPaths createRun(Path root, OperatingMode mode, String app, String runId,
                                     String source, long[] window, String typeFingerprint,
                                     List<String> providerIds, Map<String, Object> recording) {
        Path base = outputRoot(root);
        Path dir = null;
        String rid = null;
        for (int attempt = 0; attempt < 8 && dir == null; attempt++) {
            rid = runId != null ? runId : newRunId();
            Path candidate = runDir(base, mode, app, rid);
            if (!isWithin(base, candidate)) {
                throw new IllegalArgumentException("unsafe run directory outside the output root: " + candidate);
            }
            try {
                Files.createDirectories(candidate.getParent());   // tiers may pre-exist
                Files.createDirectory(candidate);                 // the run dir itself must be fresh
                dir = candidate;
            } catch (FileAlreadyExistsException e) {
                if (runId != null) {                              // supplied id: never overwrite an existing run
                    throw new UncheckedIOException(
                            new IOException("run directory already exists: " + candidate));
                }
                // generated-id collision (vanishingly rare given the uuid suffix): try a new id
            } catch (IOException e) {
                throw new UncheckedIOException("could not create run directory: " + candidate, e);
            }
        }
        if (dir == null) {
            throw new IllegalStateException("could not allocate a unique run directory under " + base);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", rid);
        manifest.put("app", app);
        manifest.put("mode", mode.name());
        manifest.put("tier", tierForMode(mode));
        manifest.put("source", source == null ? "" : source);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("endedAt", null);
        manifest.put("window", window == null ? null : orderedWindow(window));
        manifest.put("typeFingerprint", typeFingerprint);
        manifest.put("providerIds", providerIds == null ? List.of() : new ArrayList<>(providerIds));
        manifest.put("runStatus", "running");
        manifest.put("recordingStatus", "recording");
        manifest.put("counts", null);
        if (recording != null) manifest.put("recording", recording);
        manifest.put("artifacts", artifacts());

        RunPaths paths = new RunPaths(dir, dir.resolve(MANIFEST), dir.resolve(EVENTS), dir.resolve(CONSOLE));
        writeManifest(dir, manifest);
        return paths;
    }

    /**
     * Finalize a run's manifest. {@code runStatus} is the engine outcome; {@code recordingStatus} and
     * {@code counts} come from the recorder after it has drained and closed, so publish them here, not
     * when the engine returns. Engine and recording outcomes are kept separate on purpose: either can
     * fail while the other succeeds. Mirrors {@code run_layout.finalize_run}.
     *
     * @param dir             the run directory
     * @param runStatus       the engine outcome (e.g. {@code "completed"}, {@code "failed"}, {@code "aborted"})
     * @param recordingStatus the recorder outcome, or {@code null} to leave it unchanged
     * @param counts          the recording counts, or {@code null} to leave them unchanged
     * @return the finalized manifest
     */
    public static Map<String, Object> finalizeRun(Path dir, String runStatus,
                                                  String recordingStatus, RecordingCounts counts) {
        Map<String, Object> m = readManifest(dir);
        if (m == null) m = new LinkedHashMap<>();
        m.put("runStatus", runStatus);
        if (recordingStatus != null) m.put("recordingStatus", recordingStatus);
        if (counts != null) m.put("counts", counts.toMap());
        m.put("endedAt", Instant.now().toString());
        writeManifest(dir, m);
        return m;
    }

    // ------------------------------------------------------------------
    // Atomic manifest IO
    // ------------------------------------------------------------------

    /**
     * Atomically write {@code run.json}: one writer, temp file, fsync, then atomic replace, so a reader
     * or a crash mid-write never sees a half-written manifest. Mirrors {@code run_layout.write_manifest}
     * ({@code os.replace} there; {@link Files#move} with {@link StandardCopyOption#ATOMIC_MOVE} here,
     * falling back to a replace where the filesystem does not support atomic move).
     */
    public static void writeManifest(Path dir, Map<String, Object> manifest) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(MANIFEST + ".tmp");
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                fos.write(bytes);
                fos.write('\n');
                fos.flush();
                fos.getFD().sync();   // durability before the swap
            }
            Path target = dir.resolve(MANIFEST);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write manifest in " + dir, e);
        }
    }

    /** Read {@code run.json} as an ordered map, or {@code null} if absent or unreadable. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readManifest(Path dir) {
        Path p = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(p)) return null;
        try {
            return JSON.readValue(Files.readAllBytes(p), LinkedHashMap.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * All runs under the root, newest first (timestamp-first id). Each entry is the manifest plus a
     * {@code dir} key; a run directory with a missing/unreadable manifest still lists, minimally.
     * Mirrors {@code run_layout.list_runs}.
     */
    public static List<Map<String, Object>> listRuns(Path root) {
        Path base = outputRoot(root);
        List<Map<String, Object>> runs = new ArrayList<>();
        if (!Files.isDirectory(base)) return runs;
        try (Stream<Path> tiers = Files.list(base)) {
            for (Path tier : (Iterable<Path>) tiers.filter(Files::isDirectory)::iterator) {
                try (Stream<Path> apps = Files.list(tier)) {
                    for (Path app : (Iterable<Path>) apps.filter(Files::isDirectory)::iterator) {
                        try (Stream<Path> rundirs = Files.list(app)) {
                            for (Path rd : (Iterable<Path>) rundirs.filter(Files::isDirectory)::iterator) {
                                Map<String, Object> m = readManifest(rd);
                                if (m == null) {
                                    m = new LinkedHashMap<>();
                                    m.put("runId", rd.getFileName().toString());
                                    m.put("app", app.getFileName().toString());
                                    m.put("runStatus", "unknown");
                                }
                                m.putIfAbsent("runId", rd.getFileName().toString());
                                m.put("dir", rd.toString());
                                runs.add(m);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list runs under " + base, e);
        }
        runs.sort((a, b) -> String.valueOf(b.get("runId")).compareTo(String.valueOf(a.get("runId"))));
        return runs;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> orderedWindow(long[] window) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("start", window[0]);
        w.put("end", window[1]);
        return w;
    }

    private static Map<String, Object> artifacts() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("manifest", MANIFEST);
        a.put("events", EVENTS);
        a.put("console", CONSOLE);
        return a;
    }
}
