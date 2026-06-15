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
package com.inventzia.pulse.beacon.core.gateway.file;

/**
 * Unchecked exception thrown by {@link JsonlReaderGateway} and
 * {@link JsonlWriterGateway} when a JSONL read or write operation fails.
 */
public class JsonlGatewayException extends RuntimeException {

    public JsonlGatewayException(String message) {
        super(message);
    }

    public JsonlGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
