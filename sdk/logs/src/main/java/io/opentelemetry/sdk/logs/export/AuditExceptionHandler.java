/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs.export;

public interface AuditExceptionHandler {

  void handle(AuditException exception);
}
