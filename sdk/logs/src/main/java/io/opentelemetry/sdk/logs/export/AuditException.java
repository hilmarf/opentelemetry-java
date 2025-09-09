/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs.export;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nullable;

public class AuditException extends RuntimeException {

  private static final long serialVersionUID = 5791873097754062413L;

  @Nullable public Context context;

  public Collection<LogRecordData> logRecords;

  private AuditException(@Nullable String message, @Nullable Throwable cause) {
    super(message, cause);
    logRecords = Collections.emptyList();
  }

  AuditException(String message, @Nullable Throwable cause, Collection<LogRecordData> logs) {
    this(message, cause);
    this.logRecords = logs;
  }

  AuditException(Throwable cause, Context context, Collection<LogRecordData> logs) {
    super(cause);
    this.logRecords = logs;
    this.context = context;
  }
}
