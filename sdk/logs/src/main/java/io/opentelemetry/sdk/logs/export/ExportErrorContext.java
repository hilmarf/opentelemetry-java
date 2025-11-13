/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs.export;

import io.opentelemetry.context.ContextKey;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.util.Collection;
import java.util.function.Consumer;
import javax.annotation.concurrent.Immutable;

@Immutable
public class ExportErrorContext {
  public static final ContextKey<Consumer<Collection<LogRecordData>>> KEY =
      ContextKey.named("export-error-consumer");

  private ExportErrorContext() {}
}
