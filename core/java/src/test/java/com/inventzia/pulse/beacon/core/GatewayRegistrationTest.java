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
package com.inventzia.pulse.beacon.core;

import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registration-API honesty for {@link AbstractGateway}'s one-to-one routing tables:
 * a conflicting overwrite is refused (not silently dropped), adding a superset of keys
 * registers the new ones, and an unregister only affects entries the gateway owns.
 */
class GatewayRegistrationTest {

    private final Topic<HeartBeat> topic = new Topic<>("t", HeartBeat.class);

    /** M1: a second subscriber for an already-routed (topic, key) is refused, not silently lost. */
    @Test
    void conflictingSubscriberRegistrationThrowsAndKeepsTheFirst() {
        TestGateway hub = new TestGateway("hub");
        TestGateway a = new TestGateway("a");
        TestGateway b = new TestGateway("b");

        hub.registerSubscriber(a, topic, List.of("K"));
        assertThatThrownBy(() -> hub.registerSubscriber(b, topic, List.of("K")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one-to-one");
        assertThat(hub.sub(topic, "K")).isSameAs(a); // first registration preserved
    }

    /** M1 (publisher side): symmetric refusal for a conflicting publisher. */
    @Test
    void conflictingPublisherRegistrationThrows() {
        TestGateway hub = new TestGateway("hub");
        TestGateway a = new TestGateway("a");
        TestGateway b = new TestGateway("b");

        hub.registerPublisher(a, topic, List.of("K"));
        assertThatThrownBy(() -> hub.registerPublisher(b, topic, List.of("K")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(hub.pub(topic, "K")).isSameAs(a);
    }

    /** M2: re-registering with a superset of keys adds the new keys (was silently dropped before). */
    @Test
    void registeringASupersetOfKeysAddsTheNewOnes() {
        TestGateway hub = new TestGateway("hub");
        TestGateway a = new TestGateway("a");

        hub.registerSubscriber(a, topic, List.of("A"));
        hub.registerSubscriber(a, topic, List.of("A", "B")); // A already present, B is new

        assertThat(hub.sub(topic, "A")).isSameAs(a);
        assertThat(hub.sub(topic, "B")).as("new key B registered, not dropped by the circular guard").isSameAs(a);
    }

    /** M3: an unregister from a gateway that does not own the entry leaves it intact. */
    @Test
    void unregisterOnlyRemovesEntriesTheGatewayOwns() {
        TestGateway hub = new TestGateway("hub");
        TestGateway a = new TestGateway("a");
        TestGateway other = new TestGateway("other");

        hub.registerSubscriber(a, topic, List.of("K"));

        hub.unregisterSubscriber(other, topic, List.of("K")); // 'other' does not own K
        assertThat(hub.sub(topic, "K")).as("another gateway's unregister must not remove a's entry").isSameAs(a);

        hub.unregisterSubscriber(a, topic, List.of("K")); // a owns K
        assertThat(hub.sub(topic, "K")).isNull();
    }

    /** A minimal concrete gateway exposing the protected routing lookups for assertions. */
    private static final class TestGateway extends AbstractGateway {
        TestGateway(String name) { super(name, 0L, 1_000L); }

        @Override public void run() { }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) { }

        Gateway sub(Topic<?> topic, String key) { return subscriberForKey(topic, key); }

        Gateway pub(Topic<?> topic, String key) { return publisherForKey(topic, key); }
    }
}
