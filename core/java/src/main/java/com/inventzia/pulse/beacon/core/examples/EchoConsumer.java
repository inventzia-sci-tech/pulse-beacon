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

import com.inventzia.pulse.beacon.core.AbstractActor;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.TextMessage;

/**
 * Example: a publishing actor. For every event it receives, it emits a
 * {@link TextMessage} describing that event on its own output topic.
 *
 * <p>Demonstrates the outbound side of an actor: {@link #publish} (inherited
 * from {@link AbstractActor}) forwards to the engine the actor was bound to at
 * registration. The actor must be registered as a publisher of {@code echoTopic}
 * for the echo to be routed; a subscriber on that topic (e.g. a
 * {@link PrintGateway}) will then see the echoes.
 */
public final class EchoConsumer extends AbstractActor {

    private final Topic<TextMessage> echoTopic;
    private final String             echoKey;

    /**
     * @param name      this actor's name
     * @param echoTopic the topic to publish echoes on
     * @param echoKey   the routing key carried by each echo message
     */
    public EchoConsumer(String name, Topic<TextMessage> echoTopic, String echoKey) {
        super(name);
        this.echoTopic = echoTopic;
        this.echoKey   = echoKey;
    }

    @Override
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        // Re-stamp the echo at the source event's time, carrying a text summary.
        String summary = topic.name() + " :: " + payload;
        TextMessage echo = new TextMessage(echoKey, payload.getDatumTime(), summary);
        log.largeInfo(() -> "echo " + topic.name() + " → " + echoTopic.name());
        publish(echoTopic, echo);
    }
}
