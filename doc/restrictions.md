# Restrictions and Workarounds

This document tracks known operational restrictions in Descartes MCP and how to work around them.

## Proxy Mode: One Target JVM Per Proxy

In remote proxy mode, one proxy process can debug one target JVM at a time.

What this means in practice:
- A single proxy instance is configured with one JDWP host/port.
- `debugger_session start` starts/stops one active debugger session in that proxy.
- If your workflow needs to debug multiple JVMs concurrently, one proxy is not enough.

Why this matters:
- If an agent launches or interacts with two separate JVM debug targets at once, only the JVM connected to the current proxy instance is available through that MCP server.
- The other JVM requires a separate proxy instance (with its own MCP port).

## Workaround: Run Multiple Proxies (One Per Target JVM)

Use one proxy per debug target, each with a unique MCP port and JDWP port.

Example with two target JVMs:

```bash
# Proxy A -> JVM A (JDWP 5005), MCP on 9090
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090

# Proxy B -> JVM B (JDWP 5006), MCP on 9091
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5006 --mcp-port 9091
```

Then register two MCP server entries (one per proxy), for example:
- `descartes-proxy-a` with `MCP_PORT=9090`
- `descartes-proxy-b` with `MCP_PORT=9091`

## Additional Notes

- This is about proxy/session architecture, not thread-level debugging inside one JVM.
- JDWP itself allows a single debugger client connection per target JVM, so do not attach another debugger to the same target while the proxy is attached.
- If you only need to switch targets sequentially (not concurrently), you can stop the current session/proxy and restart against a different JDWP target.
