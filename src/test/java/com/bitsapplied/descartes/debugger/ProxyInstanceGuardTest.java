package com.bitsapplied.descartes.debugger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxyInstanceGuardTest {

  // Unlikely to collide with a real listener during tests.
  private static final int TEST_PORT = 64999;

  @TempDir
  Path tempDir;

  private String previousPidDir;

  @BeforeEach
  void redirectPidDirectory() {
    previousPidDir = System.getProperty("descartes.pid.dir");
    System.setProperty("descartes.pid.dir", tempDir.toString());
  }

  @AfterEach
  void restorePidDirectory() {
    if (previousPidDir == null) {
      System.clearProperty("descartes.pid.dir");
    } else {
      System.setProperty("descartes.pid.dir", previousPidDir);
    }
  }

  @Test
  void writeAndDeleteRoundTrip() {
    ProxyInstanceGuard.writePidFile(TEST_PORT, "test-build");

    Path pidFile = ProxyInstanceGuard.pidFile(TEST_PORT);
    assertThat(pidFile).exists();
    assertThat(pidFile.getParent()).isEqualTo(tempDir);

    ProxyInstanceGuard.deletePidFile(TEST_PORT);
    assertThat(pidFile).doesNotExist();
  }

  @Test
  void findExistingIgnoresOwnProcess() {
    // The file records this JVM's PID, which must never be reported as a
    // conflicting instance.
    ProxyInstanceGuard.writePidFile(TEST_PORT, "test-build");

    assertThat(ProxyInstanceGuard.findExisting(TEST_PORT)).isEmpty();
  }

  @Test
  void findExistingCleansUpStalePidFile() throws Exception {
    Path pidFile = ProxyInstanceGuard.pidFile(TEST_PORT);
    Files.createDirectories(pidFile.getParent());
    // PIDs on macOS/Linux stay far below this value, so it can never be alive.
    Files.writeString(pidFile, "pid=99999999\nbuild=stale\nstarted=2026-01-01T00:00:00Z\n");

    Optional<ProxyInstanceGuard.ExistingInstance> existing = ProxyInstanceGuard.findExisting(TEST_PORT);

    assertThat(existing).isEmpty();
    assertThat(pidFile).doesNotExist();
  }

  @Test
  void terminateReturnsTrueForAlreadyDeadProcess() {
    assertThat(ProxyInstanceGuard.terminate(99999999L, java.time.Duration.ofSeconds(1))).isTrue();
  }
}
