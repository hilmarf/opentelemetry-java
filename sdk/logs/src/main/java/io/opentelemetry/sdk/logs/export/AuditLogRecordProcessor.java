/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs.export;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * An implementation of {@link LogRecordProcessor} that processes logs for auditing purposes. It has
 * an automatic retry mechanism implemented, in case the exporter takes longer or throws exceptions.
 * Internally it uses a priority (-> LogLevel) queue for the log messages and the given {@link
 * AuditLogStore} for persistence, which holds the logs until they are successfully exported. At
 * startup, it loads all previously persisted logs from the {@link AuditLogStore} and adds them to
 * the queue for processing. Logs with higher severity are processed first. The processor
 * periodically checks the queue and exports logs in batches, regardless of the queue size. If the
 * queue reaches a certain size, it triggers an immediate export. If an export fails, it retries
 * exporting the logs with exponential backoff. When the maximum number of retry attempts is
 * reached, it either throws an {@link AuditException} or calls the provided {@link
 * AuditExceptionHandler}.
 */
public final class AuditLogRecordProcessor implements LogRecordProcessor {

  /**
   * Returns a new Builder for {@link AuditLogRecordProcessor}.
   *
   * @param logRecordExporter the {@link LogRecordExporter} to which the Logs are pushed.
   *     OtlpGrpcLogRecordExporter is a good choice.
   * @return a new {@link AuditLogRecordProcessor}.
   * @throws NullPointerException if the {@code logRecordExporter} is {@code null}.
   */
  public static AuditLogRecordProcessorBuilder builder(
      LogRecordExporter logRecordExporter, AuditLogStore logStore) {
    return new AuditLogRecordProcessorBuilder(logRecordExporter, logStore);
  }

  /** The exporter to export logs. OtlpGrpcLogRecordExporter is recommended. */
  private final LogRecordExporter exporter;

  /** The exception handler to handle exceptions during log export. */
  @Nullable private final AuditExceptionHandler handler;

  @Nullable private CompletableResultCode lastResultCode;

  /** The persistent storage for logs. */
  private final AuditLogStore persistency;

  /** The PriorityBlockingQueue to hold the logs before exporting. */
  private final Queue<LogRecordData> queue;

  /** A flag to indicate whether the processor is shutdown. */
  private final AtomicBoolean shutdown = new AtomicBoolean(false);

  /** The maximum number of logs to export in a single batch. */
  private final int size;

  /** The timeout for exporting logs in nanoseconds. */
  private final long timeout;

  /** The retry policy for handling failed exports. */
  private final RetryPolicy retryPolicy;

  /** Current retry attempt counter. */
  private final AtomicInteger currentRetryAttempt = new AtomicInteger(0);

  /** Last retry timestamp to implement backpressure. */
  private final AtomicLong lastRetryTimestamp = new AtomicLong(0);

  /** Whether to wait for each export try to complete. */
  private final boolean waitOnExport;

  /**
   * Creates a new AuditLogRecordProcessor with the given parameters.
   *
   * @param logRecordExporter the {@link LogRecordExporter} to which the Logs are pushed.
   * @param exceptionHandler the {@link AuditExceptionHandler} to handle exceptions during log
   *     export.
   * @param scheduleDelayNanos the delay in nanoseconds between periodic exports.
   * @param maxExportBatchSize the maximum number of logs to export in a single batch.
   * @param exporterTimeoutNanos the timeout for exporting logs in nanoseconds.
   * @param retryPolicy the retry policy for handling failed exports.
   * @param waitOnExport whether to wait for the export to complete.
   */
  AuditLogRecordProcessor(
      LogRecordExporter logRecordExporter,
      @Nullable AuditExceptionHandler exceptionHandler,
      AuditLogStore logStore,
      long scheduleDelayNanos,
      int maxExportBatchSize,
      long exporterTimeoutNanos,
      RetryPolicy retryPolicy,
      boolean waitOnExport) {
    exporter = logRecordExporter;
    size = maxExportBatchSize;
    timeout = exporterTimeoutNanos;
    handler = exceptionHandler;
    this.retryPolicy = retryPolicy;
    this.waitOnExport = waitOnExport;
    queue =
        new PriorityBlockingQueue<>(
            maxExportBatchSize,
            (record1, record2) -> {
              // compare by severity, higher severity first
              return Integer.compare(
                  record2.getSeverity().getSeverityNumber(),
                  record1.getSeverity().getSeverityNumber());
            });
    persistency = logStore;

    // Get all logs from persistent storage and add them to the queue
    queue.addAll(persistency.getAll());
    exportLogs(); // export logs immediately to ensure no logs are missed

    scheduler = Executors.newSingleThreadScheduledExecutor();
    future =
        scheduler.scheduleAtFixedRate(
            () -> {
              exportLogs(); // export logs periodically, regardless of the queue size
            },
            scheduleDelayNanos,
            scheduleDelayNanos,
            TimeUnit.NANOSECONDS);
  }

  private final ScheduledFuture<?> future;
  private final ScheduledExecutorService scheduler;

  /**
   * Exports logs from the queue to the exporter. If the queue is empty, it does nothing. If the
   * export fails, it retries exporting logs with exponential backoff and handles exceptions using
   * the provided handler.
   */
  void exportLogs() {
    if (queue.isEmpty()) {
      return;
    }

    // Check if we're in a backpressure period
    long currentTime = System.currentTimeMillis();
    if (currentRetryAttempt.get() > 0) {
      long timeSinceLastRetry = currentTime - lastRetryTimestamp.get();
      long requiredDelay = calculateRetryDelay(currentRetryAttempt.get());

      if (timeSinceLastRetry < requiredDelay) {
        // Still in backpressure period, skip this export attempt
        return;
      }
    }

    Collection<CompletableResultCode> results = new ArrayList<>();
    Collection<LogRecordData> allFailedLogs = new ArrayList<>();

    while (!queue.isEmpty()) {
      // Create a collection of LogRecordData from the queue with a maximum size
      Object[] arr = queue.stream().limit(size).toArray();
      Collection<LogRecordData> logs = new ArrayList<>(arr.length);
      for (Object o : arr) {
        logs.add((LogRecordData) o);
      }

      CompletableResultCode export = tryExport(logs);
      // lastResultCode = export;
      results.add(export);

      export.whenComplete(
          () -> {
            if (export.isSuccess()) {
              // Reset retry counter on successful export
              currentRetryAttempt.set(0);
              lastRetryTimestamp.set(0);
              persistency.removeAll(logs);
            } else {
              allFailedLogs.addAll(logs);
              // if (handler != null) {
              // handler.handle(new AuditException("Export failed", export.getFailureThrowable(),
              // logs));
              // }
            }
          });

      // Remove logs from queue regardless of success/failure to prevent infinite loops
      queue.removeAll(logs);
    }

    CompletableResultCode all = CompletableResultCode.ofAll(results);
    if (waitOnExport) {
      all.join(timeout * results.size(), TimeUnit.NANOSECONDS);
    }

    if (all.isDone() && !all.isSuccess()) {
      if (waitOnExport) {
        // Export failed, prepare for retry if attempts remain
        if (currentRetryAttempt.getAndAdd(1) < retryPolicy.getMaxAttempts()) {
          lastRetryTimestamp.set(System.currentTimeMillis());
          queue.addAll(allFailedLogs);
          try {
            Thread.sleep(calculateRetryDelay(currentRetryAttempt.get()));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exportLogs(); // retry exporting failed logs
          return;
        }
      }
      // only, when we wait on export, we can really retry
      handleExportFailure(allFailedLogs, all.getFailureThrowable());
    }

    lastResultCode = all;
  }

  /**
   * Exports logs with retry logic and exponential backoff.
   *
   * @param logs the collection of logs to export
   * @return CompletableResultCode representing the final result after all retry attempts
   */
  private CompletableResultCode tryExport(Collection<LogRecordData> logs) {
    CompletableResultCode lastResult = CompletableResultCode.ofFailure();
    try {
      lastResult = exporter.export(logs);
      // Wait for the export to complete with timeout
      if (waitOnExport) {
        lastResult.join(timeout, TimeUnit.NANOSECONDS);
      }
    } catch (RuntimeException e) {
      return CompletableResultCode.ofExceptionalFailure(e);
    }
    return lastResult;
  }

  /**
   * Calculates the retry delay using exponential backoff with jitter.
   *
   * @param attemptNumber the current attempt number (1-based)
   * @return delay in milliseconds
   */
  private long calculateRetryDelay(int attemptNumber) {
    long delay =
        (long)
            (retryPolicy.getInitialBackoff().toMillis()
                * Math.pow(retryPolicy.getBackoffMultiplier(), attemptNumber - 1));

    // Cap the delay to maximum
    delay = Math.min(delay, retryPolicy.getMaxBackoff().toMillis());

    // Add jitter to prevent thundering herd (±25% random variation)
    double jitter = 0.25 * delay * (Math.random() - 0.5);
    delay += (long) jitter;

    return Math.max(delay, 0); // Ensure non-negative delay
  }

  /**
   * Handles export failure by updating retry state and potentially throwing exceptions.
   *
   * @param failedLogs the logs that failed to export
   * @param cause the cause of the failure
   */
  private void handleExportFailure(
      Collection<LogRecordData> failedLogs, @Nullable Throwable cause) {
    if (!waitOnExport && handler != null) {
      handler.handle(new AuditException("Export failed", cause, failedLogs));
      return;
    }
    if (currentRetryAttempt.get() < retryPolicy.getMaxAttempts()) {
      // If retries haven't been exhausted, the retry logic will handle the next attempt
      return;
    }

    // Max retries exceeded, reset counter and handle as final failure
    currentRetryAttempt.set(0);
    lastRetryTimestamp.set(0);

    String message =
        String.format(
            Locale.ENGLISH,
            "Export failed after %d retry attempts. Last error: %s",
            retryPolicy.getMaxAttempts(),
            cause != null ? cause.getMessage() : "Unknown error");

    if (handler != null) {
      handler.handle(new AuditException(message, cause, failedLogs));
    } else {
      throw new AuditException(message, cause, failedLogs);
    }
  }

  /**
   * Exports logs immediately, regardless of the queue size. This is useful for flushing logs when
   * the processor is shutdown.
   *
   * @return {@link CompletableResultCode#ofSuccess()}.
   */
  @Override
  public CompletableResultCode forceFlush() {
    exportLogs();
    return lastResultCode != null ? lastResultCode : CompletableResultCode.ofSuccess();
  }

  /**
   * Returns the last export operation ({@link #exportLogs()}) result. If the processor was never
   * triggered, it returns <code>null</code>. Useful to wait for the last export to finish via
   * {@link CompletableResultCode#join(long, TimeUnit)}.
   *
   * @return CompletableResultCode from {@link LogRecordExporter#export(Collection)} of {@link
   *     #exporter}.
   */
  @Nullable
  public CompletableResultCode getLastResultCode() {
    return lastResultCode;
  }

  /**
   * Accepts a log record and adds it to the queue. If the processor is shutdown, it throws an
   * exception or calls the handler.
   *
   * @param context the context of the log record.
   * @param logRecord the log record to be processed.
   */
  @Override
  public void onEmit(Context context, ReadWriteLogRecord logRecord) {
    if (logRecord == null) {
      return;
    }

    if (shutdown.get()) {
      AuditException exception =
          new AuditException(
              new IllegalStateException(
                  "AuditLogRecordProcessor is shutdown, cannot accept new logs."),
              context,
              Collections.singletonList(logRecord.toLogRecordData()));
      if (handler != null) {
        handler.handle(exception);
      } else {
        throw exception;
      }
    }

    try {
      LogRecordData data = logRecord.toLogRecordData();
      persistency.save(data);
      queue.add(data);
    } catch (IOException e) {
      AuditException exception =
          new AuditException(e, context, Collections.singletonList(logRecord.toLogRecordData()));
      if (handler != null) {
        handler.handle(exception);
      } else {
        throw exception;
      }
    }

    if (queue.size() >= size) {
      // when we have reached certain size, we export logs immediately
      exportLogs();
    }
  }

  /**
   * Shuts down the processor. This method will export all remaining logs in the queue before
   * shutting down. If this method is called multiple times, it will only export logs once.
   *
   * @return {@link CompletableResultCode#ofSuccess()}.
   */
  @Override
  public CompletableResultCode shutdown() {
    if (!shutdown.getAndSet(true)) {
      // First time shutdown is called, we export all remaining logs
      future.cancel(false);
      scheduler.shutdown();
      return forceFlush();
    }
    return lastResultCode != null ? lastResultCode : CompletableResultCode.ofSuccess();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("AuditLogRecordProcessor [exporter=");
    builder.append(exporter);
    builder.append(", handler=");
    builder.append(handler);
    builder.append(", queue=");
    builder.append(queue);
    builder.append(", shutdown=");
    builder.append(shutdown);
    builder.append(", size=");
    builder.append(size);
    builder.append(", timeout=");
    builder.append(timeout);
    builder.append(", retryPolicy=");
    builder.append(retryPolicy);
    builder.append(", persistency=");
    builder.append(persistency);
    builder.append("]");
    return builder.toString();
  }
}
