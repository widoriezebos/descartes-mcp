#!/usr/bin/env node

const net = require('net');
const readline = require('readline');

// Configuration from environment variables with defaults
const TCP_HOST = process.env.MCP_HOST || 'localhost';
const TCP_PORT = parseInt(process.env.MCP_PORT || '9080', 10);
const DEBUG = process.env.MCP_DEBUG === 'true';
const RECONNECT_MIN_DELAY = parseInt(process.env.MCP_RECONNECT_MIN_DELAY || '500', 10);
const RECONNECT_MAX_DELAY = parseInt(process.env.MCP_RECONNECT_MAX_DELAY || '5000', 10);
const MESSAGE_QUEUE_SIZE = parseInt(process.env.MCP_MESSAGE_QUEUE_SIZE || '100', 10);
const REQUEST_TIMEOUT = parseInt(process.env.MCP_REQUEST_TIMEOUT || '30000', 10);
const TCP_KEEP_ALIVE_DELAY = parseInt(process.env.MCP_TCP_KEEP_ALIVE || '10000', 10);
const LOG_RATE_LIMIT_WINDOW = parseInt(process.env.MCP_LOG_RATE_LIMIT_WINDOW || '60000', 10);
const LOG_RATE_LIMIT_MAX = parseInt(process.env.MCP_LOG_RATE_LIMIT_MAX || '10', 10);
const MAX_MESSAGE_SIZE = parseInt(process.env.MCP_MAX_MESSAGE_SIZE || '10485760', 10); // 10MB default
const DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS =
    parseInt(process.env.MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS || '5000', 10);

const METHOD_TOOLS_CALL = 'tools/call';
const DEBUGGER_EVENTS_TOOL_NAME = 'debugger_events';
const DEBUGGER_EVENTS_OPERATION_WAIT = 'wait';
const DEBUGGER_EVENTS_OPERATION_WAIT_FOR = 'wait_for';
const DEBUGGER_EVENTS_OPERATION_WAIT_FOR_EVENT = 'wait_for_event';

// Connection states
const ConnectionState = {
    DISCONNECTED: 'DISCONNECTED',
    CONNECTING: 'CONNECTING',
    CONNECTED: 'CONNECTED',
    CLOSING: 'CLOSING'
};

// JSON-RPC error codes
const ErrorCodes = {
    PARSE_ERROR: -32700,
    INVALID_REQUEST: -32600,
    METHOD_NOT_FOUND: -32601,
    INVALID_PARAMS: -32602,
    INTERNAL_ERROR: -32603,
    SERVER_ERROR: -32000,
    CONNECTION_ERROR: -32001,
    TIMEOUT_ERROR: -32002,
    QUEUE_FULL_ERROR: -32003
};

// Global state
let client = null;
let connectionState = ConnectionState.DISCONNECTED;
let reconnectDelay = RECONNECT_MIN_DELAY;
let reconnectTimer = null;
let reconnectAttemptCount = 0;
let hasConnectedBefore = false;
let receiveBuffer = '';
let initializeRequestId = null;

// Message queue for handling messages while disconnected
const messageQueue = [];

// Request tracking for timeouts and correlation
const pendingRequests = new Map();

// Log rate limiting with automatic cleanup
const logCounts = new Map();
const logSuppressed = new Map();
let logCleanupTimer = null;

// Initialize log cleanup timer
function initializeLogCleanup() {
    logCleanupTimer = setInterval(() => {
        const now = Date.now();
        for (const [key, value] of logCounts.entries()) {
            if (now - value.firstTime > LOG_RATE_LIMIT_WINDOW) {
                logCounts.delete(key);
                logSuppressed.delete(key);
            }
        }
        
        // Report suppressed logs if any
        const summary = getSuppressedMessage();
        if (summary) {
            console.error(`[MCP-TCP-Adapter] ${new Date().toISOString()} - ${summary}`);
        }
    }, LOG_RATE_LIMIT_WINDOW);
}

// Function to check if a log should be rate limited
function shouldRateLimit(category, message) {
    const now = Date.now();
    const key = `${category}:${message}`;
    
    const entry = logCounts.get(key);
    if (!entry) {
        logCounts.set(key, { count: 1, firstTime: now });
        return false;
    }
    
    entry.count++;
    
    if (entry.count > LOG_RATE_LIMIT_MAX) {
        const suppressed = logSuppressed.get(key) || 0;
        logSuppressed.set(key, suppressed + 1);
        return true;
    }
    
    return false;
}

// Function to get suppressed count message
function getSuppressedMessage() {
    if (logSuppressed.size === 0) return null;
    
    const messages = [];
    for (const [key, count] of logSuppressed.entries()) {
        const [category, msg] = key.split(':', 2);
        messages.push(`${count} ${category} messages: "${msg}"`);
    }
    
    logSuppressed.clear();
    return `Log Summary - Suppressed: ${messages.join(', ')}`;
}

// Logging functions
function debug(message) {
    if (DEBUG) {
        if (!shouldRateLimit('debug', message)) {
            console.error(`[MCP-TCP-Adapter DEBUG] ${new Date().toISOString()} - ${message}`);
        }
    }
}

function info(message) {
    if (!shouldRateLimit('info', message)) {
        console.error(`[MCP-TCP-Adapter] ${new Date().toISOString()} - ${message}`);
    }
}

function error(message) {
    console.error(`[MCP-TCP-Adapter ERROR] ${new Date().toISOString()} - ${message}`);
}

// Validate JSON-RPC message structure
function validateJsonRpcMessage(message) {
    try {
        const parsed = JSON.parse(message);
        
        // Check for required jsonrpc field
        if (parsed.jsonrpc !== '2.0') {
            return { valid: false, error: 'Invalid JSON-RPC version' };
        }
        
        // Check message size
        if (message.length > MAX_MESSAGE_SIZE) {
            return { valid: false, error: 'Message exceeds maximum size limit' };
        }
        
        // Request must have method
        if ('method' in parsed && typeof parsed.method !== 'string') {
            return { valid: false, error: 'Invalid method field' };
        }
        
        // Response must have result or error
        if ('result' in parsed && 'error' in parsed) {
            return { valid: false, error: 'Response cannot have both result and error' };
        }
        
        return { valid: true, parsed };
    } catch (e) {
        return { valid: false, error: 'Invalid JSON' };
    }
}

// Send JSON-RPC error response
function sendErrorResponse(id, code, message, data = null) {
    const errorResponse = {
        jsonrpc: '2.0',
        error: {
            code: code,
            message: message
        }
    };
    
    if (data !== null) {
        errorResponse.error.data = data;
    }
    
    if (id !== undefined && id !== null) {
        errorResponse.id = id;
    }
    
    const responseStr = JSON.stringify(errorResponse);
    console.log(responseStr);
    debug(`Sent error response: ${responseStr}`);
}

// Send message to stdout (to MCP client)
function sendToClient(message) {
    console.log(message);
    debug(`Sent to client: ${message}`);
}

// Parse request ID from message
function getRequestId(message) {
    try {
        const parsed = JSON.parse(message);
        return parsed.id;
    } catch (e) {
        return null;
    }
}

function hasRequestId(requestId) {
    return requestId !== undefined && requestId !== null;
}

function normalizeToolName(rawName) {
    if (typeof rawName !== 'string') {
        return null;
    }
    const normalized = rawName.trim();
    const separator = Math.max(normalized.lastIndexOf('.'), normalized.lastIndexOf('/'));
    return separator >= 0 && separator + 1 < normalized.length
        ? normalized.substring(separator + 1)
        : normalized;
}

function normalizeDebuggerEventsOperation(operation) {
    if (typeof operation !== 'string') {
        return null;
    }
    const normalized = operation.trim();
    if (normalized === DEBUGGER_EVENTS_OPERATION_WAIT_FOR ||
        normalized === DEBUGGER_EVENTS_OPERATION_WAIT_FOR_EVENT) {
        return DEBUGGER_EVENTS_OPERATION_WAIT;
    }
    return normalized;
}

function longValue(value) {
    if (typeof value === 'number' && Number.isFinite(value)) {
        return Math.trunc(value);
    }
    if (typeof value === 'string') {
        const parsed = Number.parseInt(value.trim(), 10);
        if (!Number.isNaN(parsed)) {
            return parsed;
        }
    }
    return null;
}

function safeAddTimeout(base, increment) {
    if (!Number.isFinite(base) || !Number.isFinite(increment)) {
        return Number.MAX_SAFE_INTEGER;
    }
    if (base > Number.MAX_SAFE_INTEGER - increment) {
        return Number.MAX_SAFE_INTEGER;
    }
    return base + increment;
}

function resolveRequestTimeoutMs(message) {
    const baseTimeoutMs = REQUEST_TIMEOUT;
    const fallback = { effectiveTimeoutMs: baseTimeoutMs, debuggerEventsWaitTimeoutMs: null };
    try {
        const parsed = JSON.parse(message);
        if (!parsed || parsed.method !== METHOD_TOOLS_CALL || typeof parsed.params !== 'object' || parsed.params === null) {
            return fallback;
        }

        const toolName = normalizeToolName(parsed.params.name);
        if (toolName !== DEBUGGER_EVENTS_TOOL_NAME) {
            return fallback;
        }

        const args = parsed.params.arguments;
        if (!args || typeof args !== 'object') {
            return fallback;
        }

        const operation = normalizeDebuggerEventsOperation(args.operation);
        if (operation !== DEBUGGER_EVENTS_OPERATION_WAIT) {
            return fallback;
        }

        const waitTimeoutMs = longValue(args.timeout_ms);
        if (waitTimeoutMs === null || waitTimeoutMs <= 0) {
            return fallback;
        }

        const paddedTimeoutMs = safeAddTimeout(waitTimeoutMs, DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS);
        const effectiveTimeoutMs = Math.max(baseTimeoutMs, paddedTimeoutMs);
        return { effectiveTimeoutMs, debuggerEventsWaitTimeoutMs: waitTimeoutMs };
    } catch (e) {
        debug(`Failed to resolve request-specific timeout: ${e.message}`);
        return fallback;
    }
}

// Track a request for timeout handling
function trackRequest(requestId, message) {
    if (!hasRequestId(requestId)) return;
    const timeoutConfig = resolveRequestTimeoutMs(message);
    const timeoutMs = timeoutConfig.effectiveTimeoutMs;
    
    const timeoutHandle = setTimeout(() => {
        if (pendingRequests.has(requestId)) {
            pendingRequests.delete(requestId);
            if (timeoutConfig.debuggerEventsWaitTimeoutMs !== null) {
                sendErrorResponse(
                    requestId,
                    ErrorCodes.TIMEOUT_ERROR,
                    `Adapter timeout after ${timeoutMs}ms while waiting for debugger_events.wait ` +
                    `(requested timeout_ms=${timeoutConfig.debuggerEventsWaitTimeoutMs}). ` +
                    `No matching debugger event may have arrived yet; retry debugger_events.wait ` +
                    `using since_sequence from latest_sequence, or increase MCP_REQUEST_TIMEOUT ` +
                    `and the MCP client tool-call deadline ` +
                    `(Codex CLI: tool_timeout_sec in ~/.codex/config.toml; ` +
                    `Claude Code: MCP_TOOL_TIMEOUT in ~/.claude/settings.json).`
                );
            } else {
                sendErrorResponse(requestId, ErrorCodes.TIMEOUT_ERROR, `Request timeout after ${timeoutMs}ms`);
            }
            debug(`Request ${requestId} timed out after ${timeoutMs}ms`);
        }
    }, timeoutMs);
    
    pendingRequests.set(requestId, {
        message,
        timestamp: Date.now(),
        timeoutHandle,
        timeoutMs,
        debuggerEventsWaitTimeoutMs: timeoutConfig.debuggerEventsWaitTimeoutMs
    });
}

// Clear a tracked request
function clearRequest(requestId) {
    if (!hasRequestId(requestId)) return;
    
    const request = pendingRequests.get(requestId);
    if (request) {
        clearTimeout(request.timeoutHandle);
        pendingRequests.delete(requestId);
    }
}

// Process incoming data from TCP server with proper framing
function processServerData(data) {
    receiveBuffer += data.toString();
    
    let newlineIndex;
    while ((newlineIndex = receiveBuffer.indexOf('\n')) !== -1) {
        const message = receiveBuffer.slice(0, newlineIndex).trim();
        receiveBuffer = receiveBuffer.slice(newlineIndex + 1);
        
        if (message) {
            debug(`Received from server: ${message}`);
            
            // Validate the message
            const validation = validateJsonRpcMessage(message);
            if (!validation.valid) {
                error(`Invalid message from server: ${validation.error}`);
                continue;
            }
            
            // Clear request tracking if this is a response
            if (validation.parsed && validation.parsed.id !== undefined) {
                clearRequest(validation.parsed.id);
            }
            
            // Forward to client
            sendToClient(message);
        }
    }
    
    // Check for buffer overflow
    if (receiveBuffer.length > MAX_MESSAGE_SIZE) {
        error(`Receive buffer overflow, clearing buffer`);
        receiveBuffer = '';
    }
}

// Queue a message for sending when connected
function queueMessage(message) {
    const requestId = getRequestId(message);
    
    // Check if queue is full
    if (messageQueue.length >= MESSAGE_QUEUE_SIZE) {
        const removed = messageQueue.shift();
        const removedId = getRequestId(removed);

        // Send error response for dropped message
        if (hasRequestId(removedId)) {
            sendErrorResponse(removedId, ErrorCodes.QUEUE_FULL_ERROR, 
                'Message queue full - request dropped');
            clearRequest(removedId);
        }

        info(`Message queue full, dropped oldest message`);
    }

    messageQueue.push(message);
    
    if (hasRequestId(requestId)) {
        trackRequest(requestId, message);
    }

    debug(`Queued message (queue size: ${messageQueue.length})`);
}

// Process queued messages after connection
function processQueuedMessages() {
    if (messageQueue.length === 0) return;
    
    info(`Processing ${messageQueue.length} queued messages`);
    
    while (messageQueue.length > 0 && connectionState === ConnectionState.CONNECTED) {
        const message = messageQueue.shift();
        
        try {
            client.write(message + '\n');
            
            // Track request for timeout
            const requestId = getRequestId(message);
            if (hasRequestId(requestId) && !pendingRequests.has(requestId)) {
                trackRequest(requestId, message);
            }
            
            debug(`Sent queued message: ${message}`);
        } catch (err) {
            error(`Failed to send queued message: ${err.message}`);
            
            const requestId = getRequestId(message);
            if (hasRequestId(requestId)) {
                sendErrorResponse(requestId, ErrorCodes.CONNECTION_ERROR, 
                    'Failed to send message to MCP server');
            }
        }
    }
}

// Add jitter to reconnect delay
function getReconnectDelayWithJitter(baseDelay) {
    const jitter = baseDelay * 0.2 * (Math.random() * 2 - 1);
    return Math.floor(baseDelay + jitter);
}

// Schedule reconnection with exponential backoff
function scheduleReconnect() {
    if (reconnectTimer || connectionState === ConnectionState.CLOSING) {
        return;
    }
    
    reconnectAttemptCount++;
    
    // Check if we have an initialize request waiting
    const hasInitializeWaiting = initializeRequestId !== null || 
        messageQueue.some(msg => {
            try {
                const parsed = JSON.parse(msg);
                return parsed.method === 'initialize';
            } catch (e) {
                return false;
            }
        });
    
    // Use minimal delay if initialize is waiting
    let effectiveDelay = reconnectDelay;
    if (hasInitializeWaiting && reconnectAttemptCount <= 10) {
        effectiveDelay = RECONNECT_MIN_DELAY;
    }
    
    const delayWithJitter = getReconnectDelayWithJitter(effectiveDelay);
    
    // Smart logging
    if (reconnectAttemptCount <= 3 || reconnectAttemptCount % 10 === 0 || hasInitializeWaiting) {
        info(`Scheduling reconnection attempt #${reconnectAttemptCount} in ${delayWithJitter}ms${
            hasInitializeWaiting ? ' (initialize waiting)' : ''}`);
    } else {
        debug(`Scheduling reconnection attempt #${reconnectAttemptCount} in ${delayWithJitter}ms`);
    }
    
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        
        // Exponential backoff (unless initialize is waiting)
        if (!hasInitializeWaiting) {
            reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_DELAY);
        }
        
        connectToServer();
    }, delayWithJitter);
}

// Establish TCP connection
function connectToServer() {
    if (connectionState === ConnectionState.CONNECTING || 
        connectionState === ConnectionState.CLOSING) {
        debug('Connection already in progress or closing');
        return;
    }
    
    connectionState = ConnectionState.CONNECTING;
    
    // Smart logging
    if (reconnectAttemptCount === 0 || reconnectAttemptCount <= 3 || 
        reconnectAttemptCount % 10 === 0) {
        info(`Attempting to connect to ${TCP_HOST}:${TCP_PORT}...`);
    } else {
        debug(`Attempting to connect to ${TCP_HOST}:${TCP_PORT}...`);
    }
    
    // Create new socket
    client = new net.Socket();
    
    // Set socket options
    client.setKeepAlive(true, TCP_KEEP_ALIVE_DELAY);
    client.setNoDelay(true); // Disable Nagle's algorithm for lower latency
    
    // Handle successful connection
    client.on('connect', () => {
        const isReconnection = hasConnectedBefore;
        connectionState = ConnectionState.CONNECTED;
        receiveBuffer = ''; // Clear any incomplete messages
        
        info(`${isReconnection ? 'Reconnected' : 'Connected'} to MCP server at ${TCP_HOST}:${TCP_PORT}`);
        hasConnectedBefore = true;
        
        // Reset reconnection state
        reconnectDelay = RECONNECT_MIN_DELAY;
        reconnectAttemptCount = 0;
        
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        
        // Send capability change notifications on reconnection
        if (isReconnection) {
            debug('Sending capability change notifications after reconnection');
            
            // These notifications inform the MCP client that it should re-query capabilities
            const notifications = [
                { method: 'notifications/tools/list_changed' },
                { method: 'notifications/resources/list_changed' },
                { method: 'notifications/prompts/list_changed' }
            ];
            
            notifications.forEach(notification => {
                const msg = JSON.stringify({
                    jsonrpc: '2.0',
                    method: notification.method,
                    params: {}
                });
                sendToClient(msg);
            });
        }
        
        // Process queued messages
        processQueuedMessages();
    });
    
    // Handle data from TCP server
    client.on('data', (data) => {
        processServerData(data);
    });
    
    // Handle connection errors
    client.on('error', (err) => {
        if (err.code === 'ECONNREFUSED') {
            debug('Connection refused - server may not be running');
        } else if (err.code === 'ETIMEDOUT') {
            debug('Connection timeout - server may be unreachable');
        } else if (err.code === 'EHOSTUNREACH') {
            debug('Host unreachable');
        } else if (err.code === 'ENETUNREACH') {
            debug('Network unreachable');
        } else {
            debug(`TCP connection error: ${err.code || err.message}`);
        }
    });
    
    // Handle connection close
    client.on('close', () => {
        const wasConnected = connectionState === ConnectionState.CONNECTED;
        
        if (connectionState !== ConnectionState.CLOSING) {
            connectionState = ConnectionState.DISCONNECTED;
            
            if (wasConnected) {
                info('Connection to MCP server closed unexpectedly');
                
                // Notify pending requests of disconnection
                for (const [requestId, request] of pendingRequests.entries()) {
                    sendErrorResponse(requestId, ErrorCodes.CONNECTION_ERROR, 
                        'Connection to MCP server lost');
                }
                pendingRequests.clear();
            } else {
                debug('Connection attempt failed');
            }
            
            // Schedule reconnection
            scheduleReconnect();
        }
        
        client = null;
    });
    
    // Attempt connection
    client.connect(TCP_PORT, TCP_HOST);
}

// Set up stdin input handling
const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
});

// Handle input from MCP client
rl.on('line', (line) => {
    const trimmedLine = line.trim();
    if (!trimmedLine) return;
    
    debug(`Received from stdin: ${trimmedLine}`);
    
    // Validate the message
    const validation = validateJsonRpcMessage(trimmedLine);
    if (!validation.valid) {
        const requestId = getRequestId(trimmedLine);
        sendErrorResponse(hasRequestId(requestId) ? requestId : null, ErrorCodes.PARSE_ERROR, validation.error);
        return;
    }
    
    const parsed = validation.parsed;
    const requestId = parsed.id;
    const isInitialize = parsed.method === 'initialize';
    
    // Special handling for initialize request
    if (isInitialize) {
        initializeRequestId = requestId;
        
        // Set timeout for initialize
        setTimeout(() => {
            if (initializeRequestId === requestId) {
                initializeRequestId = null;
                sendErrorResponse(requestId, ErrorCodes.TIMEOUT_ERROR, 
                    'Initialize request timeout - MCP server not available');
                
                // Remove from queue if still there
                const index = messageQueue.findIndex(msg => {
                    const id = getRequestId(msg);
                    return id === requestId;
                });
                
                if (index > -1) {
                    messageQueue.splice(index, 1);
                }
            }
        }, REQUEST_TIMEOUT);
    }
    
    // Send or queue the message
    if (connectionState === ConnectionState.CONNECTED && client) {
        try {
            client.write(trimmedLine + '\n');
            if (hasRequestId(requestId) && !pendingRequests.has(requestId)) {
                trackRequest(requestId, trimmedLine);
            }
            
            // Clear initialize tracking once sent
            if (isInitialize && initializeRequestId === requestId) {
                initializeRequestId = null;
            }
        } catch (err) {
            error(`Failed to write to server: ${err.message}`);
            sendErrorResponse(requestId, ErrorCodes.CONNECTION_ERROR, 
                'Failed to send message to MCP server');
        }
    } else {
        if (isInitialize) {
            info('Received initialize request while disconnected - queueing and attempting to connect');
            // Remove any existing initialize requests to avoid duplicates
            for (let i = messageQueue.length - 1; i >= 0; i--) {
                try {
                    const existing = JSON.parse(messageQueue[i]);
                    if (existing.method === 'initialize') {
                        const existingId = getRequestId(messageQueue[i]);
                        messageQueue.splice(i, 1);
                        if (hasRequestId(existingId)) {
                            clearRequest(existingId);
                        }
                    }
                } catch (err) {
                    debug(`Failed to parse queued message for initialize dedupe: ${err.message}`);
                }
            }

            messageQueue.unshift(trimmedLine);
            if (hasRequestId(requestId)) {
                trackRequest(requestId, trimmedLine);
            }
        } else {
            queueMessage(trimmedLine);

            if (hasRequestId(requestId)) {
                info(`Connection not available, request queued (queue size: ${messageQueue.length})`);
            }
        }
        
        // Ensure we're trying to connect
        if (connectionState === ConnectionState.DISCONNECTED && !reconnectTimer) {
            scheduleReconnect();
        }
    }
});

// Handle stdin close
rl.on('close', () => {
    info('Stdin closed, shutting down');
    shutdown(0);
});

// Handle process termination
function shutdown(exitCode = 0) {
    connectionState = ConnectionState.CLOSING;
    
    // Clear all timers
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
    }
    
    if (logCleanupTimer) {
        clearInterval(logCleanupTimer);
    }
    
    // Clear pending requests
    for (const request of pendingRequests.values()) {
        clearTimeout(request.timeoutHandle);
    }
    pendingRequests.clear();
    
    // Close TCP connection
    if (client) {
        client.destroy();
    }
    
    process.exit(exitCode);
}

// Signal handlers
process.on('SIGINT', () => {
    info('Received SIGINT, shutting down gracefully');
    shutdown(0);
});

process.on('SIGTERM', () => {
    info('Received SIGTERM, shutting down gracefully');
    shutdown(0);
});

// Error handlers
process.on('uncaughtException', (err) => {
    error(`Uncaught exception: ${err.message}`);
    error(err.stack);
    // Try to continue running
});

process.on('unhandledRejection', (reason, promise) => {
    error(`Unhandled rejection at: ${promise}, reason: ${reason}`);
    // Try to continue running
});

// Initialize and start
info(`Starting MCP TCP adapter - Host: ${TCP_HOST}, Port: ${TCP_PORT}`);
info(`Configuration: Queue size: ${MESSAGE_QUEUE_SIZE}, Request timeout: ${REQUEST_TIMEOUT}ms`);

initializeLogCleanup();
connectToServer();
process.stdin.resume();
