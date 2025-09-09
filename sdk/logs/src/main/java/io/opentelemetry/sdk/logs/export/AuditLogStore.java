/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs.export;

import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.io.IOException;
import java.util.Collection;

public interface AuditLogStore {

  void save(LogRecordData logRecord) throws IOException;

  void removeAll(Collection<LogRecordData> logs);

  Collection<LogRecordData> getAll();
}
