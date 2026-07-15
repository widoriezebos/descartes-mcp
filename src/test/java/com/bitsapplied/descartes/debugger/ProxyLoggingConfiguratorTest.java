package com.bitsapplied.descartes.debugger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

class ProxyLoggingConfiguratorTest {

  @Test
  void infoModeKeepsErrorSummaryWithoutThrowableDetails() throws Exception {
    String output = runLoggingProbe(ProxyLogLevel.INFO);

    assertThat(output).contains("probe-summary", "probe-detail").doesNotContain("IllegalStateException", "\tat ");
  }

  @Test
  void debugModeIncludesThrowableDetails() throws Exception {
    String output = runLoggingProbe(ProxyLogLevel.DEBUG);

    assertThat(output).contains("probe-summary", "IllegalStateException: probe-detail", "LoggingProbe.main");
  }

  private String runLoggingProbe(ProxyLogLevel level) throws Exception {
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
        LoggingProbe.class.getName(), level.name()).redirectErrorStream(true).start();

    boolean exited = process.waitFor(10, TimeUnit.SECONDS);
    if (!exited) {
      process.destroyForcibly();
      throw new AssertionError("Logging probe did not exit within 10 seconds");
    }

    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(output).isZero();
    return output;
  }

  public static final class LoggingProbe {
    private static final Logger logger = LogManager.getLogger(LoggingProbe.class);

    private LoggingProbe() {
    }

    public static void main(String[] args) {
      ProxyLoggingConfigurator.configure(ProxyLogLevel.parse(args[0]));
      logger.error("probe-summary", new IllegalStateException("probe-detail"));
    }
  }
}
