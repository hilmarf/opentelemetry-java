/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.internal.otlp.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.AuditLogStore;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.logs.TestLogRecordData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogFileStoreTest {

  @TempDir Path tempDir;

  private AuditLogStore auditLogStore;
  private Path logFilePath;

  @BeforeEach
  void setUp() throws IOException {
    logFilePath = tempDir.resolve("test-audit.log");
    auditLogStore = new AuditLogFileStore(logFilePath);
  }

  @Test
  void constructor_withFilePath_createsFile() throws IOException {
    Path newLogFile = tempDir.resolve("new-audit.log");

    new AuditLogFileStore(newLogFile.toString());

    assertThat(Files.exists(newLogFile)).isTrue();
  }

  @Test
  void constructor_withDirectory_createsDefaultFile() throws IOException {
    Path dirPath = tempDir.resolve("audit-logs");
    Files.createDirectories(dirPath);

    new AuditLogFileStore(dirPath);

    assertThat(Files.exists(dirPath.resolve("audit.log"))).isTrue();
  }

  @Test
  void constructor_withNonExistentDirectory_createsDirectoriesAndFile() throws IOException {
    Path dirPath =
        tempDir
            .resolve("nested")
            .resolve("audit-logs")
            .resolve(AuditLogFileStore.DEFAULT_LOG_FILE_NAME);
    Path filePath = dirPath.resolve(AuditLogFileStore.DEFAULT_LOG_FILE_NAME);

    new AuditLogFileStore(filePath);

    assertThat(Files.exists(dirPath.resolve(AuditLogFileStore.DEFAULT_LOG_FILE_NAME))).isTrue();
  }

  @Test
  void save_singleLogRecord_storesSuccessfully() throws IOException {
    LogRecordData logRecord = createTestLogRecord("Test message", Severity.INFO, 1000L);

    auditLogStore.save(logRecord);

    Collection<LogRecordData> storedRecords = auditLogStore.getAll();
    assertThat(storedRecords).hasSize(1);
    // Note: The current implementation returns a basic LogRecordData with limited field mapping
    assertThat(storedRecords.iterator().next().getBodyValue().getValue().toString())
        .contains("Test message");
  }

  @Test
  void save_duplicateLogRecord_ignoresDuplicate() throws IOException {
    LogRecordData logRecord = createTestLogRecord("Test message", Severity.INFO, 1000L);

    auditLogStore.save(logRecord);
    auditLogStore.save(logRecord); // Same record

    Collection<LogRecordData> storedRecords = auditLogStore.getAll();
    assertThat(storedRecords).hasSize(1);
  }

  @Test
  void save_multipleUniqueLogRecords_storesAll() throws IOException {
    LogRecordData logRecord1 = createTestLogRecord("Message 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("Message 2", Severity.WARN, 2000L);
    LogRecordData logRecord3 = createTestLogRecord("Message 3", Severity.ERROR, 3000L);

    auditLogStore.save(logRecord1);
    auditLogStore.save(logRecord2);
    auditLogStore.save(logRecord3);

    Collection<LogRecordData> storedRecords = auditLogStore.getAll();
    assertThat(storedRecords).hasSize(3);
  }

  @Test
  void save_concurrentWrites_handlesCorrectly() {
    LogRecordData logRecord1 = createTestLogRecord("Concurrent 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("Concurrent 2", Severity.WARN, 2000L);

    assertDoesNotThrow(
        () -> {
          Thread thread1 =
              new Thread(
                  () -> {
                    try {
                      auditLogStore.save(logRecord1);
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  });

          Thread thread2 =
              new Thread(
                  () -> {
                    try {
                      auditLogStore.save(logRecord2);
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  });

          thread1.start();
          thread2.start();
          thread1.join();
          thread2.join();
        });

    Collection<LogRecordData> storedRecords = auditLogStore.getAll();
    assertThat(storedRecords).hasSize(2);
  }

  @Test
  void getAll_emptyStore_returnsEmptyCollection() {
    Collection<LogRecordData> records = auditLogStore.getAll();

    assertThat(records).isEmpty();
  }

  @Test
  void getAll_withStoredRecords_returnsAllRecords() throws IOException {
    LogRecordData logRecord1 = createTestLogRecord("Message 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("Message 2", Severity.WARN, 2000L);

    auditLogStore.save(logRecord1);
    auditLogStore.save(logRecord2);

    Collection<LogRecordData> records = auditLogStore.getAll();

    assertThat(records).hasSize(2);
  }

  @Test
  void removeAll_emptyCollection_doesNotThrow() {
    assertDoesNotThrow(() -> auditLogStore.removeAll(Collections.emptyList()));
  }

  @Test
  void removeAll_nonExistentRecords_doesNotThrow() {
    LogRecordData nonExistentRecord = createTestLogRecord("Non-existent", Severity.INFO, 9999L);

    assertDoesNotThrow(() -> auditLogStore.removeAll(Arrays.asList(nonExistentRecord)));
  }

  @Test
  void removeAll_existingRecords_removesSuccessfully() throws IOException {
    LogRecordData logRecord1 = createTestLogRecord("Message 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("Message 2", Severity.WARN, 2000L);
    LogRecordData logRecord3 = createTestLogRecord("Message 3", Severity.ERROR, 3000L);

    // Save all records
    auditLogStore.save(logRecord1);
    auditLogStore.save(logRecord2);
    auditLogStore.save(logRecord3);

    // Remove two records
    auditLogStore.removeAll(Arrays.asList(logRecord1, logRecord3));

    Collection<LogRecordData> remainingRecords = auditLogStore.getAll();
    assertThat(remainingRecords).hasSize(1);
    assertThat(remainingRecords.iterator().next().getSeverityText())
        .contains(String.valueOf(Severity.WARN));
  }

  @Test
  void removeAll_allRecords_leavesEmptyStore() throws IOException {
    LogRecordData logRecord1 = createTestLogRecord("Message 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("Message 2", Severity.WARN, 2000L);

    auditLogStore.save(logRecord1);
    auditLogStore.save(logRecord2);

    auditLogStore.removeAll(Arrays.asList(logRecord1, logRecord2));

    Collection<LogRecordData> remainingRecords = auditLogStore.getAll();
    assertThat(remainingRecords).isEmpty();
  }

  @Test
  void interfaceContract_saveRetrieveRemove_worksEndToEnd() throws IOException {
    // Test the complete AuditLogStore interface contract
    LogRecordData logRecord1 = createTestLogRecord("End-to-end 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("End-to-end 2", Severity.WARN, 2000L);
    LogRecordData logRecord3 = createTestLogRecord("End-to-end 3", Severity.ERROR, 3000L);

    // Initially empty
    assertThat(auditLogStore.getAll()).isEmpty();

    // Save records
    auditLogStore.save(logRecord1);
    auditLogStore.save(logRecord2);
    auditLogStore.save(logRecord3);

    // Verify all saved
    assertThat(auditLogStore.getAll()).hasSize(3);

    // Remove some records
    auditLogStore.removeAll(Arrays.asList(logRecord1, logRecord3));

    // Verify only one remains
    Collection<LogRecordData> remainingRecords = auditLogStore.getAll();
    assertThat(remainingRecords).hasSize(1);

    // Remove all remaining
    auditLogStore.removeAll(remainingRecords);

    // Verify empty
    assertThat(auditLogStore.getAll()).isEmpty();
  }

  @Test
  void generateRecordId_sameContent_generatesSameId() {
    LogRecordData logRecord1 = createTestLogRecord("Same message", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("Same message", Severity.INFO, 1000L);

    String id1 = AuditLogFileStore.generateRecordId(logRecord1);
    String id2 = AuditLogFileStore.generateRecordId(logRecord2);

    assertThat(id1).isEqualTo(id2);
  }

  @Test
  void generateRecordId_differentContent_generatesDifferentIds() {
    LogRecordData logRecord1 = createTestLogRecord("Message 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("Message 2", Severity.INFO, 1000L);

    String id1 = AuditLogFileStore.generateRecordId(logRecord1);
    String id2 = AuditLogFileStore.generateRecordId(logRecord2);

    assertThat(id1).isNotEqualTo(id2);
  }

  @Test
  void persistence_acrossInstances_maintainsData() throws IOException {
    LogRecordData logRecord = createTestLogRecord("Persistent message", Severity.INFO, 1000L);

    // Save with first instance
    auditLogStore.save(logRecord);

    // Create new instance pointing to same file
    AuditLogStore newInstance = new AuditLogFileStore(logFilePath);

    // Verify data is still there
    Collection<LogRecordData> records = newInstance.getAll();
    assertThat(records).hasSize(1);
  }

  static LogRecordData createTestLogRecord(String message, Severity severity, long timestampNanos) {

    AttributesBuilder attr = Attributes.builder();
    // iterate over all available AttributeTypes and create attributes for each type
    for (AttributeType type : AttributeType.values()) {
      switch (type) {
        case STRING:
          attr.put(AttributeKey.stringKey("stringAttr"), "stringValue");
          break;
        case LONG:
          attr.put(AttributeKey.longKey("longAttr"), 12345L);
          break;
        case DOUBLE:
          attr.put(AttributeKey.doubleKey("doubleAttr"), 123.45);
          break;
        case BOOLEAN:
          attr.put(AttributeKey.booleanKey("booleanAttr"), true);
          break;
        case STRING_ARRAY:
          attr.put(
              AttributeKey.stringArrayKey("stringArrayAttr"), Arrays.asList("one", "two", "three"));
          break;
        case LONG_ARRAY:
          attr.put(AttributeKey.longArrayKey("longArrayAttr"), Arrays.asList(1L, 2L, 3L));
          break;
        case DOUBLE_ARRAY:
          attr.put(AttributeKey.doubleArrayKey("doubleArrayAttr"), Arrays.asList(1.1, 2.2, 3.3));
          break;
        case BOOLEAN_ARRAY:
          attr.put(
              AttributeKey.booleanArrayKey("booleanArrayAttr"), Arrays.asList(true, false, true));
          break;
      }
    }

    return TestLogRecordData.builder()
        .setResource(Resource.getDefault())
        .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("test"))
        .setTimestamp(timestampNanos, TimeUnit.NANOSECONDS)
        .setObservedTimestamp(timestampNanos, TimeUnit.NANOSECONDS)
        .setSeverity(severity)
        .setSeverityText(severity.name())
        .setBody(message)
        .setAttributes(attr.build())
        .setTotalAttributeCount(attr.build().size())
        .setSpanContext(
            SpanContext.create(
                TraceId.fromLongs(0, 1),
                SpanId.fromLong(1),
                TraceFlags.getDefault(),
                TraceState.getDefault()))
        .build();
  }

  /*
   * Let's test our deserialization logic as well, by first serializing a LogRecordData to JSON using the SDK and then deserializing it back using our JsonLogRecordData class.
   */
  @Test
  void serializationDeserialization_cycle_worksCorrectly() throws IOException {
    LogRecordData logRecord1 = createTestLogRecord("End-to-end 1", Severity.INFO, 1000L);
    LogRecordData logRecord2 = createTestLogRecord("End-to-end 2", Severity.WARN, 2000L);
    LogRecordData logRecord3 = createTestLogRecord("End-to-end 3", Severity.ERROR, 3000L);

    // Initially empty
    assertThat(auditLogStore.getAll()).isEmpty();

    // Save records
    auditLogStore.save(logRecord1);
    auditLogStore.save(logRecord2);
    auditLogStore.save(logRecord3);

    // Verify all saved
    Collection<LogRecordData> storedRecords = auditLogStore.getAll();
    assertThat(storedRecords).hasSize(3);

    // create another AuditLogFileStore instance to write to the same file
    Path otherFilePath = tempDir.resolve("other-audit.log");
    AuditLogStore anotherInstance = new AuditLogFileStore(otherFilePath);
    for (LogRecordData record : storedRecords) {
      anotherInstance.save(record);
    }

    // Verify that both files have the same byte content
    byte[] originalFileBytes = Files.readAllBytes(logFilePath);
    byte[] otherFileBytes = Files.readAllBytes(otherFilePath);

    // Uncomment to debug content differences
    //    System.out.println("Org file content:\n" + new String(originalFileBytes));
    //    System.out.println("Oth file content:\n" + new String(otherFileBytes));
    assertThat(originalFileBytes).isEqualTo(otherFileBytes);
  }
}
