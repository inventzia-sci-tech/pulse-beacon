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
package com.inventzia.beacon.core;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing identity for a stream of events on the bus.
 *
 * <p>A Topic pairs a human-readable {@code name} with the concrete
 * {@link Event} subtype carried on it. Each Topic is bound to exactly one
 * event type and therefore exactly one {@link #typeId()}.
 *
 * <p>The type parameter {@code E} lets the bus expose typed publish/subscribe
 * signatures, eliminating casts at call sites.
 *
 * <p><b>Codegen contract.</b> Every concrete Event class must declare
 * {@code public static final String TYPE_ID} matching what its
 * {@link Event#typeId()} returns. Topic enforces this at construction time.
 */
public record Topic<E extends Event>(String name, Class<E> eventType) {

    public Topic {
        Objects.requireNonNull(name, "Topic name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Topic name must not be empty");
        }
        Objects.requireNonNull(eventType, "Topic eventType must not be null");
        // Eagerly validate the codegen contract: TYPE_ID must exist.
        typeIdOf(eventType);
    }

    /** The single typeId carried by this topic. */
    public String typeId() {
        return typeIdOf(eventType);
    }

    // --- TYPE_ID lookup, cached ---------------------------------------------

    private static final Map<Class<? extends Event>, String> TYPE_ID_CACHE =
            new ConcurrentHashMap<>();

    private static String typeIdOf(Class<? extends Event> cls) {
        return TYPE_ID_CACHE.computeIfAbsent(cls, Topic::readTypeIdField);
    }

    private static String readTypeIdField(Class<? extends Event> cls) {
        try {
            Field f = cls.getDeclaredField("TYPE_ID");
            Object value = f.get(null);
            if (!(value instanceof String s) || s.isEmpty()) {
                throw new IllegalStateException(
                        "Event class " + cls.getName()
                        + " has TYPE_ID but it is not a non-empty String");
            }
            return s;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    "Event class " + cls.getName()
                    + " must declare 'public static final String TYPE_ID' "
                    + "(codegen contract for Topic)", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot read TYPE_ID on " + cls.getName(), e);
        }
    }
}
