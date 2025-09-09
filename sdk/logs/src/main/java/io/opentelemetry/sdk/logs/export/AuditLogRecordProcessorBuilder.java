/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs.export;

import static io.opentelemetry.api.internal.Utils.checkArgument;
import static java.util.Objects.requireNonNull;

import io.opentelemetry.sdk.common.export.RetryPolicy;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builder class for {@link AuditLogRecordProcessor}.
 *
 * @since 1.27.0
 */
public final class AuditLogRecordProcessorBuilder {

  // Visible for testing
  public static final int DEFAULT_EXPORT_TIMEOUT_MILLIS = 30_000;
  // Visible for testing
  public static final int DEFAULT_MAX_EXPORT_BATCH_SIZE = 512;
  // Visible for testing
  public static final long DEFAULT_SCHEDULE_DELAY_MILLIS = 1000;

  @Nullable private AuditExceptionHandler exceptionHandler;

  private long exporterTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(DEFAULT_EXPORT_TIMEOUT_MILLIS);

  @Nonnull private final LogRecordExporter logRecordExporter;

  @Nonnull private final AuditLogStore logStore;

  private int maxExportBatchSize = DEFAULT_MAX_EXPORT_BATCH_SIZE;

  private RetryPolicy retryPolicy = RetryPolicy.getDefault();

  private long scheduleDelayNanos = TimeUnit.MILLISECONDS.toNanos(DEFAULT_SCHEDULE_DELAY_MILLIS);

  private boolean waitOnExport = false;

  AuditLogRecordProcessorBuilder(
      @Nonnull LogRecordExporter logRecordExporter, @Nonnull AuditLogStore logStore) {
    this.logRecordExporter = requireNonNull(logRecordExporter, "logRecordExporter");
    this.logStore = requireNonNull(logStore, "logStore");
  }

  /**
   * Returns a new {@link AuditLogRecordProcessor} that batches, then forwards them to the given
   * {@code logRecordExporter}.
   *
   * @return a new {@link AuditLogRecordProcessor}.
   */
  public AuditLogRecordProcessor build() {
    return new AuditLogRecordProcessor(
        logRecordExporter,
        exceptionHandler,
        logStore,
        scheduleDelayNanos,
        maxExportBatchSize,
        exporterTimeoutNanos,
        retryPolicy,
        waitOnExport);
  }

  @Nullable
  AuditExceptionHandler getExceptionHandler() {
    return exceptionHandler;
  }

  // Visible for testing
  long getExporterTimeoutNanos() {
    return exporterTimeoutNanos;
  }

  AuditLogStore getLogStore() {
    return logStore;
  }

  // Visible for testing
  int getMaxExportBatchSize() {
    return maxExportBatchSize;
  }

  // Visible for testing
  RetryPolicy getRetryPolicy() {
    return retryPolicy;
  }

  // Visible for testing
  long getScheduleDelayNanos() {
    return scheduleDelayNanos;
  }

  boolean isWaitOnExport() {
    return waitOnExport;
  }

  public AuditLogRecordProcessorBuilder setExceptionHandler(
      @Nonnull AuditExceptionHandler exceptionHandler) {
    requireNonNull(exceptionHandler, "exceptionHandler");
    this.exceptionHandler = exceptionHandler;
    return this;
  }

  /**
   * Sets the maximum time an export will be allowed to run before being cancelled. If unset,
   * defaults to {@value DEFAULT_EXPORT_TIMEOUT_MILLIS}ms.
   */
  public AuditLogRecordProcessorBuilder setExporterTimeout(long timeout, @Nonnull TimeUnit unit) {
    requireNonNull(unit, "unit");
    checkArgument(timeout >= 0, "timeout must be non-negative");
    exporterTimeoutNanos = timeout == 0 ? Long.MAX_VALUE : unit.toNanos(timeout);
    return this;
  }

  /**
   * Sets the maximum batch size for every export. This must be smaller or equal to {@code
   * maxQueueSize}.
   *
   * <p>Default value is {@code 512}.
   *
   * @param maxExportBatchSize the maximum batch size for every export.
   * @return this.
   * @see AuditLogRecordProcessorBuilder#DEFAULT_MAX_EXPORT_BATCH_SIZE
   */
  public AuditLogRecordProcessorBuilder setMaxExportBatchSize(int maxExportBatchSize) {
    checkArgument(maxExportBatchSize > 0, "maxExportBatchSize must be positive.");
    this.maxExportBatchSize = maxExportBatchSize;
    return this;
  }

  /**
   * Sets the retry policy for failed exports. If unset, defaults to {@link
   * RetryPolicy#getDefault()}.
   *
   * @param retryPolicy the retry policy to use for failed exports
   * @return this
   */
  public AuditLogRecordProcessorBuilder setRetryPolicy(@Nonnull RetryPolicy retryPolicy) {
    requireNonNull(retryPolicy, "retryPolicy");
    this.retryPolicy = retryPolicy;
    return this;
  }

  /**
   * Sets the delay interval between two consecutive exports. If unset, defaults to {@value
   * DEFAULT_SCHEDULE_DELAY_MILLIS}ms.
   */
  public AuditLogRecordProcessorBuilder setScheduleDelay(long delay, TimeUnit unit) {
    requireNonNull(unit, "unit");
    checkArgument(delay >= 0, "delay must be non-negative");
    scheduleDelayNanos = unit.toNanos(delay);
    return this;
  }

  /**
   * Sets whether to wait for the export to complete before processing new logs. If unset, defaults
   * to {@code false}.
   */
  public AuditLogRecordProcessorBuilder setWaitOnExport(boolean waitOnExport) {
    this.waitOnExport = waitOnExport;
    return this;
  }
}
