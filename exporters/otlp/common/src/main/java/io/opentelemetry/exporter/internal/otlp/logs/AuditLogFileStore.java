/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.internal.otlp.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.AuditLogStore;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 *
 * <p>A file-based implementation of {@link AuditLogStore} that provides thread-safe concurrent
 * reading and writing of audit log records to/from the file system.
 */
public final class AuditLogFileStore implements AuditLogStore {

  private static final String DEFAULT_LOG_FILE_EXTENSION = ".log";

  public static final String DEFAULT_LOG_FILE_NAME = "audit" + DEFAULT_LOG_FILE_EXTENSION;

  private static final Logger logger = Logger.getLogger(AuditLogFileStore.class.getName());

  /** Generates a unique ID for a log record based on its content. */
  static String generateRecordId(@Nullable LogRecordData logRecord) {
    if (logRecord == null) {
      return "";
    }
    return String.valueOf(
        (logRecord.getTimestampEpochNanos()
                + String.valueOf(logRecord.getBodyValue())
                + logRecord.getSeverity().toString())
            .hashCode());
  }

  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  private final Path logFilePath;

  private final Set<String> loggedRecords = ConcurrentHashMap.newKeySet();

  @Nullable private ObjectMapper objectMapper;

  /**
   * Creates a new AuditLogFileStore that stores logs in the specified path. If the path is a
   * directory, logs will be stored in a default file within that directory. If the path is a file,
   * logs will be stored directly in that file.
   *
   * @param path the path to the log file or directory
   * @throws IOException if the file or directory cannot be created or accessed
   */
  public AuditLogFileStore(Path path) throws IOException {
    if (Files.isDirectory(path)) {
      this.logFilePath = path.resolve(DEFAULT_LOG_FILE_NAME);
    } else {
      this.logFilePath = path;
    }

    // Ensure parent directories exist
    if (logFilePath.getParent() != null) {
      Files.createDirectories(logFilePath.getParent());
    }

    // Create the file if it doesn't exist
    if (!Files.exists(logFilePath)) {
      Files.createFile(logFilePath);
    }

    // verify that we can read and write to the file
    if (!Files.isReadable(logFilePath)) {
      throw new IOException("Can't read: " + logFilePath);
    }
    if (!Files.isWritable(logFilePath)) {
      throw new IOException("Can't write: " + logFilePath);
    }

    // Load existing log record IDs to avoid duplicates
    loadExistingRecordIds();
  }

  /**
   * Creates a new AuditLogFileStore that stores logs in the specified file.
   *
   * @param filePath the path to the log file
   * @throws IOException if the file cannot be created or accessed
   */
  public AuditLogFileStore(String filePath) throws IOException {
    this(Paths.get(filePath));
  }

  @Override
  public Collection<LogRecordData> getAll() {
    lock.readLock().lock();
    try {
      Collection<LogRecordData> records = new ArrayList<>();
      try (Stream<String> lines = Files.lines(logFilePath)) {
        lines.forEach(
            line -> {
              LogRecordData record = parseLogRecord(line);
              if (record != null) {
                records.add(record);
              }
            });
      } catch (IOException e) {
        logger.throwing(AuditLogFileStore.class.getName(), "getAll", e);
      }
      return records;
    } finally {
      lock.readLock().unlock();
    }
  }

  ObjectMapper json() {
    if (objectMapper == null) {
      objectMapper = new ObjectMapper();
    }
    return objectMapper;
  }

  /** Loads existing record IDs from the log file to prevent duplicates. */
  private void loadExistingRecordIds() throws IOException {
    if (!Files.exists(logFilePath) || Files.size(logFilePath) == 0) {
      return;
    }

    lock.readLock().lock();
    try (Stream<String> lines = Files.lines(logFilePath)) {
      lines.forEach(
          line -> {
            String recordId = generateRecordId(parseLogRecord(line));
            if (recordId != null) {
              loggedRecords.add(recordId);
            }
          });
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Parses a log record from a stored line (simplified implementation). In a production system, you
   * might want to use JSON or another structured format.
   */
  @Nullable
  LogRecordData parseLogRecord(String line) {
    try {
      return json().readValue(line, JsonLogRecordData.class);
    } catch (JsonProcessingException e) {
      logger.throwing(AuditLogFileStore.class.getName(), "parseLogRecord", e);
    }
    return null;
  }

  @Override
  public void removeAll(Collection<LogRecordData> logs) {
    lock.writeLock().lock();
    try {
      Set<String> recordIdsToRemove = new HashSet<>();
      for (LogRecordData log : logs) {
        recordIdsToRemove.add(generateRecordId(log));
      }

      // Read all lines, filter out the ones to remove, then write back
      Collection<String> remainingLines = new ArrayList<>();
      try (BufferedReader reader = Files.newBufferedReader(logFilePath)) {
        String line;
        while ((line = reader.readLine()) != null) {
          String recordId = generateRecordId(parseLogRecord(line));
          if (recordId == null || !recordIdsToRemove.contains(recordId)) {
            remainingLines.add(line);
          } else {
            loggedRecords.remove(recordId);
          }
        }
      } catch (IOException e) {
        logger.throwing(AuditLogFileStore.class.getName(), "removeAll", e);
      }

      // Write the remaining lines back to the file
      try (BufferedWriter writer =
          Files.newBufferedWriter(
              logFilePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
        for (String line : remainingLines) {
          writer.write(line);
          writer.newLine();
        }
      } catch (IOException e) {
        logger.throwing(AuditLogFileStore.class.getName(), "removeAll", e);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void save(LogRecordData logRecord) throws IOException {
    String recordId = generateRecordId(logRecord);

    // Check if we've already logged this record
    if (loggedRecords.contains(recordId)) {
      return;
    }

    lock.writeLock().lock();
    try (OutputStream out =
        Files.newOutputStream(logFilePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      LogMarshaler.create(logRecord).writeJsonTo(baos); // closes the stream!
      out.write(baos.toByteArray());
      out.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
      loggedRecords.add(recordId);
    } finally {
      lock.writeLock().unlock();
    }
  }
}
