package com.bitsapplied.descartes.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LogFileInfoTest {

  @Test
  void testForFileAppender() {
    LogFileInfo info = LogFileInfo.forFileAppender("ERROR_FILE", "/var/log/app/error.log", true);

    assertEquals("ERROR_FILE", info.appenderName());
    assertEquals("FileAppender", info.type());
    assertEquals("/var/log/app/error.log", info.filePath());
    assertTrue(info.readable());
    assertNull(info.filePattern());
    assertTrue(info.rolledFiles().isEmpty());
    assertFalse(info.isRolling());
    assertFalse(info.hasError());
  }

  @Test
  void testForRollingFileAppender() {
    LogFileInfo info = LogFileInfo.forRollingFileAppender("ALL_FILE", "/var/log/app/application.log",
        "/var/log/app/application-%d{yyyy-MM-dd}.log.gz", true);

    assertEquals("ALL_FILE", info.appenderName());
    assertEquals("RollingFileAppender", info.type());
    assertEquals("/var/log/app/application.log", info.filePath());
    assertEquals("/var/log/app/application-%d{yyyy-MM-dd}.log.gz", info.filePattern());
    assertTrue(info.readable());
    assertTrue(info.isRolling());
    assertFalse(info.hasError());
  }

  @Test
  void testWithError() {
    LogFileInfo info = LogFileInfo.withError("ERROR_FILE", "FileAppender", "/var/log/app/error.log",
        "Permission denied");

    assertEquals("ERROR_FILE", info.appenderName());
    assertEquals("/var/log/app/error.log", info.filePath());
    assertEquals("Permission denied", info.error());
    assertFalse(info.readable());
    assertTrue(info.hasError());
  }

  @Test
  void testWithMetadata() {
    LogFileInfo original = LogFileInfo.forFileAppender("ERROR_FILE", "/var/log/error.log", true);

    Instant now = Instant.now();
    LogFileInfo updated = original.withMetadata(123456L, now);

    assertEquals("ERROR_FILE", updated.appenderName());
    assertEquals("/var/log/error.log", updated.filePath());
    assertEquals(123456L, updated.sizeBytes());
    assertEquals(now, updated.lastModified());
    assertTrue(updated.readable());

    // Original should be unchanged
    assertNull(original.sizeBytes());
    assertNull(original.lastModified());
  }

  @Test
  void testWithRolledFiles() {
    LogFileInfo original = LogFileInfo.forRollingFileAppender("ALL_FILE", "/var/log/app.log", "/var/log/app-%d.log.gz",
        true);

    List<Path> rolledFiles = List.of(Path.of("/var/log/app-2024-01-01.log.gz"),
        Path.of("/var/log/app-2024-01-02.log.gz"));

    LogFileInfo updated = original.withRolledFiles(rolledFiles);

    assertEquals(2, updated.rolledFiles().size());
    assertTrue(updated.rolledFiles().contains(Path.of("/var/log/app-2024-01-01.log.gz")));

    // Original should be unchanged
    assertTrue(original.rolledFiles().isEmpty());
  }

  @Test
  void testDefensiveCopyOfRolledFiles() {
    List<Path> rolledFiles = new ArrayList<>();
    rolledFiles.add(Path.of("/var/log/app-1.log"));

    LogFileInfo info = LogFileInfo.forRollingFileAppender("ALL_FILE", "/var/log/app.log", "/var/log/app-%d.log", true)
        .withRolledFiles(rolledFiles);

    // Modify original list
    rolledFiles.add(Path.of("/var/log/app-2.log"));

    // Info should still have only one file
    assertEquals(1, info.rolledFiles().size());
  }

  @Test
  void testNullAppenderNameThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> new LogFileInfo(null, "FileAppender", "/test.log", null, null, true, null, null, null, null, null));
  }

  @Test
  void testBlankAppenderNameThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> new LogFileInfo("  ", "FileAppender", "/test.log", null, null, true, null, null, null, null, null));
  }

  @Test
  void testNullTypeThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> new LogFileInfo("ERROR_FILE", null, "/test.log", null, null, true, null, null, null, null, null));
  }

  @Test
  void testNullFilePathThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> new LogFileInfo("ERROR_FILE", "FileAppender", null, null, null, true, null, null, null, null, null));
  }

  @Test
  void testEqualsAndHashCode() {
    LogFileInfo info1 = LogFileInfo.forFileAppender("ERROR_FILE", "/var/log/error.log", true);

    LogFileInfo info2 = LogFileInfo.forFileAppender("ERROR_FILE", "/var/log/error.log", true);

    LogFileInfo info3 = LogFileInfo.forFileAppender("OTHER_FILE", "/var/log/error.log", true);

    // Reflexive
    assertEquals(info1, info1);
    assertEquals(info1.hashCode(), info1.hashCode());

    // Symmetric
    assertEquals(info1, info2);
    assertEquals(info2, info1);
    assertEquals(info1.hashCode(), info2.hashCode());

    // Different appender name
    assertNotEquals(info1, info3);

    // Null
    assertNotEquals(info1, null);
  }

  @Test
  void testEqualsWithAllFields() {
    Instant now = Instant.now();
    List<Path> rolledFiles = List.of(Path.of("/var/log/app-1.log"));

    LogFileInfo info1 = new LogFileInfo("ALL_FILE", "RollingFileAppender", "/var/log/app.log", 123456L, now, true,
        "/var/log/app-%d.log", rolledFiles, null, null, null);

    LogFileInfo info2 = new LogFileInfo("ALL_FILE", "RollingFileAppender", "/var/log/app.log", 123456L, now, true,
        "/var/log/app-%d.log", rolledFiles, null, null, null);

    assertEquals(info1, info2);
    assertEquals(info1.hashCode(), info2.hashCode());
  }

  @Test
  void testToStringContainsKeyInfo() {
    LogFileInfo info = LogFileInfo.forRollingFileAppender("ALL_FILE", "/var/log/app/application.log",
        "/var/log/app/application-%d.log.gz", true);

    String str = info.toString();
    assertTrue(str.contains("ALL_FILE"));
    assertTrue(str.contains("RollingFileAppender"));
    assertTrue(str.contains("/var/log/app/application.log"));
    assertTrue(str.contains("/var/log/app/application-%d.log.gz"));
    assertTrue(str.contains("readable=true"));
  }

  @Test
  void testToStringWithMetadata() {
    Instant now = Instant.now();
    LogFileInfo info = LogFileInfo.forFileAppender("ERROR_FILE", "/var/log/error.log", true).withMetadata(123456L, now);

    String str = info.toString();
    assertTrue(str.contains("sizeBytes=123456"));
    assertTrue(str.contains("lastModified="));
  }

  @Test
  void testToStringWithRolledFiles() {
    List<Path> rolledFiles = List.of(Path.of("/var/log/app-1.log"), Path.of("/var/log/app-2.log"),
        Path.of("/var/log/app-3.log"));

    LogFileInfo info = LogFileInfo.forRollingFileAppender("ALL_FILE", "/var/log/app.log", "/var/log/app-%d.log", true)
        .withRolledFiles(rolledFiles);

    String str = info.toString();
    assertTrue(str.contains("rolledFiles=3 files"));
  }

  @Test
  void testToStringWithError() {
    LogFileInfo info = LogFileInfo.withError("ERROR_FILE", "FileAppender", "/var/log/error.log", "Permission denied");

    String str = info.toString();
    assertTrue(str.contains("error='Permission denied'"));
  }

  @Test
  void testIsRolling() {
    LogFileInfo fileAppender = LogFileInfo.forFileAppender("ERROR_FILE", "/var/log/error.log", true);
    assertFalse(fileAppender.isRolling());

    LogFileInfo rollingAppender = LogFileInfo.forRollingFileAppender("ALL_FILE", "/var/log/app.log",
        "/var/log/app-%d.log", true);
    assertTrue(rollingAppender.isRolling());
  }

  @Test
  void testHasError() {
    LogFileInfo noError = LogFileInfo.forFileAppender("ERROR_FILE", "/var/log/error.log", true);
    assertFalse(noError.hasError());

    LogFileInfo withError = LogFileInfo.withError("ERROR_FILE", "FileAppender", "/var/log/error.log", "File not found");
    assertTrue(withError.hasError());

    LogFileInfo blankError = new LogFileInfo("ERROR_FILE", "FileAppender", "/var/log/error.log", null, null, false,
        null, null, null, null, "   ");
    assertFalse(blankError.hasError());
  }
}
