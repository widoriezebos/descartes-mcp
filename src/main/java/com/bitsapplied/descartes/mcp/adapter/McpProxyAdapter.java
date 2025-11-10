package com.bitsapplied.descartes.mcp.adapter;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.ConfigLoader;
import com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy;
import com.bitsapplied.descartes.debugger.RemoteDebugProxyConfig;

/**
 * Combined launcher that runs the Descartes remote debug proxy side-by-side
 * with the TCP adapter. Standard MCP clients can connect over stdin/stdout,
 * while the proxy bridges requests to a JDWP-enabled JVM.
 */
public final class McpProxyAdapter {
  private static final Logger logger = LoggerFactory.getLogger(McpProxyAdapter.class);

  private McpProxyAdapter() {
  }

  public static void main(String[] args) {
    printBanner();

    if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
      ConfigLoader.printUsage();
      return;
    }

    try {
      RemoteDebugProxyConfig proxyConfig = ConfigLoader.load(args);
      AdapterConfig baseConfig = AdapterConfig.fromEnvironment();
      AdapterConfig adapterConfig = AdapterConfig.builder().host("localhost").port(proxyConfig.getMcpPort())
          .debug(baseConfig.debug).reconnectMinDelayMs(baseConfig.reconnectMinDelayMs)
          .reconnectMaxDelayMs(baseConfig.reconnectMaxDelayMs).messageQueueSize(baseConfig.messageQueueSize)
          .requestTimeoutMs(baseConfig.requestTimeoutMs).tcpKeepAliveDelayMs(baseConfig.tcpKeepAliveDelayMs)
          .logRateLimitWindowMs(baseConfig.logRateLimitWindowMs).logRateLimitMax(baseConfig.logRateLimitMax)
          .maxMessageSizeBytes(baseConfig.maxMessageSizeBytes).build();

      MCPRemoteDebugProxy proxy = new MCPRemoteDebugProxy(proxyConfig);
      McpTcpAdapter adapter = McpTcpAdapter.create(adapterConfig);

      AtomicReference<Throwable> proxyFailure = new AtomicReference<>();
      Thread proxyThread = new Thread(() -> runProxy(proxy, adapter, proxyFailure), "descartes-remote-proxy");

      proxyThread.start();
      logger.info("Remote debug proxy starting on port {}", proxyConfig.getMcpPort());
      logger.info("Adapter connecting to localhost:{} for MCP transport", proxyConfig.getMcpPort());

      try {
        adapter.start(System.in, System.out, false);
      } finally {
        proxy.stop();
        try {
          proxyThread.join();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }

      Throwable failure = proxyFailure.get();
      if (failure != null) {
        logger.error("Remote debug proxy terminated with error: {}", failure.getMessage(), failure);
        System.exit(1);
      }

      int exitCode = adapter.getLastExitCode();
      logger.info("Adapter shut down (exit code {})", exitCode);
      System.exit(exitCode);
    } catch (Exception ex) {
      logger.error("Fatal error starting proxy adapter: {}", ex.getMessage(), ex);
      System.err.println(String.format(Locale.ROOT, "Fatal error: %s", ex.getMessage()));
      System.exit(1);
    }
  }

  private static void runProxy(MCPRemoteDebugProxy proxy, McpTcpAdapter adapter,
      AtomicReference<Throwable> proxyFailure) {
    try {
      proxy.start();
    } catch (Throwable t) {
      proxyFailure.set(t);
      logger.error("Proxy thread encountered fatal error: {}", t.getMessage(), t);
      adapter.stop();
    }
  }

  private static void printBanner() {
    System.out.println();
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println("  Descartes MCP - Remote Proxy + TCP Adapter");
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println("  Launches the JDWP proxy and bridges MCP via stdin/stdout");
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println();
  }
}
