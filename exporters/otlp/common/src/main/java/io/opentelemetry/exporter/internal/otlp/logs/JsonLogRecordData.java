/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.internal.otlp.logs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 *
 * <p>A JSON-based implementation of {@link LogRecordData} that maps JSON log record fields to the
 * corresponding OpenTelemetry SDK log record data fields.
 *
 * @see LogMarshaler#writeJsonTo(java.io.OutputStream)
 */
public class JsonLogRecordData implements LogRecordData {

  static class ArrayWrapper {
    @Nullable
    @JsonProperty("values")
    @JsonDeserialize(using = AttrValueArrayDeserializer.class)
    List<Object> values;
  }

  static class AttributesWrapper {
    @Nullable
    @JsonProperty("key")
    String key;

    @Nullable
    @JsonProperty("value")
    ValueWrapper value;
  }

  /** Custom deserializer for array values that handles different value types. */
  static class AttrValueArrayDeserializer extends JsonDeserializer<List<Object>> {
    @Override
    public List<Object> deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode arrayNode = parser.getCodec().readTree(parser);
      List<Object> result = new ArrayList<>();

      if (arrayNode.isArray()) {
        for (JsonNode valueNode : arrayNode) {
          Object value = extractValue(valueNode);
          if (value != null) {
            result.add(value);
          }
        }
      }

      return result;
    }

    static Object extractValue(JsonNode valueNode) {
      if (valueNode.has("boolValue")) {
        return valueNode.get("boolValue").asBoolean();
      }
      if (valueNode.has("doubleValue")) {
        return valueNode.get("doubleValue").asDouble();
      }
      if (valueNode.has("intValue")) {
        String intStr = valueNode.get("intValue").asText();
        try {
          return Long.parseLong(intStr);
        } catch (NumberFormatException e) {
          return intStr;
        }
      }
      if (valueNode.has("stringValue")) {
        return valueNode.get("stringValue").asText();
      }
      if (valueNode.has("bytesValue")) {
        return valueNode.get("bytesValue").asText();
      }
      return valueNode.asText();
    }
  }

  static class BodyWrapper {
    @Nullable
    @JsonProperty("stringValue")
    String stringValue;
  }

  static class ValueWrapper {
    @Nullable
    @JsonProperty("arrayValue")
    ArrayWrapper arrayValue;

    @Nullable
    @JsonProperty("boolValue")
    Boolean boolValue;

    @Nullable
    @JsonProperty("doubleValue")
    Double doubleValue;

    @Nullable
    @JsonProperty("intValue")
    String intValue;

    @Nullable
    @JsonProperty("stringValue")
    String stringValue;
  }

  @Nullable
  @JsonProperty("attributes")
  private Collection<AttributesWrapper> attributes;

  @Nullable
  @JsonProperty("body")
  private BodyWrapper body;

  @JsonProperty("droppedAttributesCount")
  private int droppedAttributesCount;

  @JsonProperty("observedTimeUnixNano")
  private long observedTimestampEpochNanos;

  @Nullable @JsonIgnore private Severity severity;

  @JsonProperty("severityNumber")
  private int severityNumber;

  @Nullable
  @JsonProperty("severityText")
  private String severityText;

  @Nullable
  @JsonProperty("spanId")
  private String spanId;

  @JsonProperty("timeUnixNano")
  private long timestampEpochNanos;

  @Nullable
  @JsonProperty("traceId")
  private String traceId;

  @Nullable @JsonIgnore private transient Attributes attrs;

  @Override
  public Attributes getAttributes() {
    if (attrs != null) {
      return attrs;
    }

    AttributesBuilder builder = Attributes.builder();
    if (attributes != null) {
      for (AttributesWrapper attr : attributes) {
        if (attr.key == null || attr.value == null) {
          continue;
        }
        if (attr.value.stringValue != null) {
          builder.put(attr.key, attr.value.stringValue);
        } else if (attr.value.intValue != null) {
          builder.put(attr.key, Long.valueOf(attr.value.intValue));
        } else if (attr.value.boolValue != null) {
          builder.put(attr.key, Boolean.valueOf(attr.value.boolValue));
        } else if (attr.value.doubleValue != null) {
          builder.put(attr.key, Double.valueOf(attr.value.doubleValue));
        } else if (attr.value.arrayValue != null && attr.value.arrayValue.values != null) {
          // Handle array values - convert to appropriate array types
          List<Object> values = attr.value.arrayValue.values;
          if (!values.isEmpty()) {
            Object firstValue = values.get(0);
            if (firstValue instanceof String) {
              builder.put(attr.key, values.toArray(new String[values.size()]));
            } else if (firstValue instanceof Long) {
              builder.put(attr.key, values.stream().mapToLong(v -> (Long) v).toArray());
            } else if (firstValue instanceof Double) {
              builder.put(attr.key, values.stream().mapToDouble(v -> (Double) v).toArray());
            } else if (firstValue instanceof Boolean) {
              // Convert Boolean list to string representation since Attributes doesn't support
              // Boolean arrays
              boolean[] boolArray = new boolean[values.size()];
              for (int i = 0; i < values.size(); i++) {
                boolArray[i] = (Boolean) values.get(i);
              }
              builder.put(attr.key, boolArray);
            }
          }
        }
      }
    }

    attrs = builder.build();
    return attrs;
  }

  @Override
  @Deprecated
  public io.opentelemetry.sdk.logs.data.Body getBody() {
    return new io.opentelemetry.sdk.logs.data.Body() {
      @Override
      public String asString() {
        if (body == null || body.stringValue == null) {
          return "";
        }
        return body.stringValue;
      }

      @Override
      public Type getType() {
        return io.opentelemetry.sdk.logs.data.Body.Type.STRING;
      }
    };
  }

  /**
   * Returns the empty instrumentation scope.
   *
   * @return {@link InstrumentationScopeInfo#empty()}.
   */
  @Override
  public InstrumentationScopeInfo getInstrumentationScopeInfo() {
    return InstrumentationScopeInfo.empty();
  }

  @Override
  public long getObservedTimestampEpochNanos() {
    return observedTimestampEpochNanos;
  }

  @Override
  public Resource getResource() {
    return Resource.empty();
  }

  @Override
  public Severity getSeverity() {
    if (severity != null) {
      return severity;
    }
    for (Severity s : Severity.values()) {
      if (s.getSeverityNumber() == severityNumber) {
        severity = s;
        return severity;
      }
    }

    return Severity.UNDEFINED_SEVERITY_NUMBER;
  }

  @Override
  public String getSeverityText() {
    if (severityText == null || severityText.isEmpty()) {
      return getSeverity().name();
    }
    return severityText;
  }

  @Override
  public SpanContext getSpanContext() {
    if (traceId == null || traceId.isEmpty() || spanId == null || spanId.isEmpty()) {
      return SpanContext.getInvalid();
    }
    return SpanContext.create(traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault());
  }

  @Override
  public long getTimestampEpochNanos() {
    return timestampEpochNanos;
  }

  @Override
  public int getTotalAttributeCount() {
    if (droppedAttributesCount < 0) {
      return getAttributes().size() + droppedAttributesCount;
    }
    return getAttributes().size();
  }
}
