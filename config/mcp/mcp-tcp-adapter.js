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
const HEALTH_CHECK_INTERVAL = parseInt(process.env.MCP_HEALTH_CHECK_INTERVAL || '5000', 10);
const LOG_RATE_LIMIT_WINDOW = parseInt(process.env.MCP_LOG_RATE_LIMIT_WINDOW || '60000', 10); // 1 minute
const LOG_RATE_LIMIT_MAX = parseInt(process.env.MCP_LOG_RATE_LIMIT_MAX || '10', 10); // max 10 similar logs per window

// Connection states
const ConnectionState = {
    DISCONNECTED: 'DISCONNECTED',
    CONNECTING: 'CONNECTING',
    CONNECTED: 'CONNECTED'
};

// Log rate limiting
const logCounts = new Map();
const logSuppressed = new Map();

// Function to check if a log should be rate limited
function shouldRateLimit(category, message) {
    const now = Date.now();
    const key = `${category}:${message}`;
    
    // Clean up old entries
    for (const [k, v] of logCounts.entries()) {
        if (now - v.firstTime > LOG_RATE_LIMIT_WINDOW) {
            logCounts.delete(k);
            logSuppressed.delete(k);
        }
    }
    
    // Check current count
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
        messages.push(`${count} "${msg}" messages`);
    }
    
    // Clear suppressed counts after reporting
    logSuppressed.clear();
    
    return `[Log Summary] Suppressed: ${messages.join(', ')}`;
}

// Debug logging function with rate limiting
function debug(message) {
    if (DEBUG) {
        if (!shouldRateLimit('debug', message)) {
            console.error(`[MCP-TCP-Adapter] ${new Date().toISOString()} - ${message}`);
        }
    }
}

// Info logging function with rate limiting (always visible)
function info(message) {
    if (!shouldRateLimit('info', message)) {
        console.error(`[MCP-TCP-Adapter] ${new Date().toISOString()} - ${message}`);
    }
}

// Periodically report suppressed logs
const logSummaryTimer = setInterval(() => {
    const summary = getSuppressedMessage();
    if (summary) {
        console.error(`[MCP-TCP-Adapter] ${new Date().toISOString()} - ${summary}`);
    }
}, LOG_RATE_LIMIT_WINDOW);

info(`Starting MCP TCP adapter - Host: ${TCP_HOST}, Port: ${TCP_PORT}`);

// Connection management
let client = null;
let connectionState = ConnectionState.DISCONNECTED;
let reconnectDelay = RECONNECT_MIN_DELAY;
let reconnectTimer = null;
let healthCheckTimer = null;
let lastActivityTime = Date.now();
let hasConnectedBefore = false;

// Message queue for handling messages while disconnected
const messageQueue = [];

// Function to add jitter to reconnect delay
function getReconnectDelayWithJitter(baseDelay) {
    // Add random jitter between -20% to +20% of base delay
    const jitter = baseDelay * 0.2 * (Math.random() * 2 - 1);
    return Math.floor(baseDelay + jitter);
}

// Function to send JSON-RPC error response
function sendErrorResponse(id, code, message) {
    const errorResponse = {
        jsonrpc: "2.0",
        error: {
            code: code,
            message: message
        }
    };
    if (id !== undefined) {
        errorResponse.id = id;
    }
    console.log(JSON.stringify(errorResponse));
}

// Function to parse JSON-RPC request ID from a message
function getRequestId(message) {
    try {
        const parsed = JSON.parse(message);
        return parsed.id;
    } catch (e) {
        return undefined;
    }
}

// Function to queue a message
function queueMessage(message) {
    if (messageQueue.length >= MESSAGE_QUEUE_SIZE) {
        // Remove oldest message if queue is full
        const removed = messageQueue.shift();
        debug(`Message queue full, dropping oldest message: ${removed}`);
    }
    messageQueue.push(message);
    debug(`Queued message (queue size: ${messageQueue.length})`);
}

// Function to process queued messages
function processQueuedMessages() {
    if (messageQueue.length === 0) return;
    
    info(`Processing ${messageQueue.length} queued messages`);
    while (messageQueue.length > 0 && connectionState === ConnectionState.CONNECTED) {
        const message = messageQueue.shift();
        debug(`Sending queued message: ${message}`);
        client.write(message + '\n');
    }
}

// Function to reset health check timer
function resetHealthCheck() {
    lastActivityTime = Date.now();
    
    // Clear existing timer
    if (healthCheckTimer) {
        clearTimeout(healthCheckTimer);
    }
    
    // Set new timer
    healthCheckTimer = setTimeout(() => {
        if (connectionState === ConnectionState.CONNECTED) {
            const timeSinceLastActivity = Date.now() - lastActivityTime;
            if (timeSinceLastActivity >= HEALTH_CHECK_INTERVAL) {
                debug('No activity detected, sending health check ping');
                
                // Send a simple JSON-RPC ping
                const ping = JSON.stringify({
                    jsonrpc: "2.0",
                    method: "ping",
                    id: `health-check-${Date.now()}`
                });
                
                try {
                    client.write(ping + '\n');
                    
                    // If no response within 5 seconds, assume connection is dead
                    setTimeout(() => {
                        const currentTimeSinceActivity = Date.now() - lastActivityTime;
                        if (currentTimeSinceActivity >= HEALTH_CHECK_INTERVAL + 5000) {
                            info('Health check failed, connection appears to be dead');
                            client.destroy();
                        }
                    }, 5000);
                } catch (err) {
                    debug(`Health check write failed: ${err.message}`);
                    client.destroy();
                }
            }
        }
    }, HEALTH_CHECK_INTERVAL);
}

// Function to establish TCP connection
function connectToServer() {
    if (connectionState === ConnectionState.CONNECTING) {
        debug('Already attempting to connect, skipping duplicate connection attempt');
        return;
    }
    
    connectionState = ConnectionState.CONNECTING;
    
    // Smart logging for connection attempts
    if (reconnectAttemptCount === 0 || reconnectAttemptCount <= 3 || reconnectAttemptCount % 10 === 0) {
        info(`Attempting to connect to ${TCP_HOST}:${TCP_PORT}...`);
    } else {
        debug(`Attempting to connect to ${TCP_HOST}:${TCP_PORT}...`);
    }
    
    // Create new socket
    client = new net.Socket();
    
    // Set TCP keep-alive
    client.setKeepAlive(true, 10000); // Send keep-alive every 10 seconds
    
    // Handle successful connection
    client.on('connect', () => {
        const isReconnection = hasConnectedBefore;
        connectionState = ConnectionState.CONNECTED;
        
        if (isReconnection) {
            info(`Reconnected to MCP server at ${TCP_HOST}:${TCP_PORT}`);
        } else {
            info(`Connected to MCP server at ${TCP_HOST}:${TCP_PORT}`);
            hasConnectedBefore = true;
        }
        
        // Reset reconnect delay and attempt counter on successful connection
        reconnectDelay = RECONNECT_MIN_DELAY;
        reconnectAttemptCount = 0;
        
        // Clear any pending reconnect timer
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        
        // If this is a reconnection, send notifications that capabilities may have changed
        // This prompts Claude to re-check tools and resources
        if (isReconnection) {
            debug('Sending capability change notifications after reconnection');
            
            const notification = {
                jsonrpc: "2.0",
                method: "notifications/tools/list_changed",
                params: {}
            };
            console.log(JSON.stringify(notification));
            
            const resourcesNotification = {
                jsonrpc: "2.0",
                method: "notifications/resources/list_changed",
                params: {}
            };
            console.log(JSON.stringify(resourcesNotification));
        }
        
        // Process any queued messages
        processQueuedMessages();
        
        // Start health check
        resetHealthCheck();
    });
    
    // Handle data from TCP server
    client.on('data', (data) => {
        resetHealthCheck();
        
        // Split by newlines in case multiple messages come together
        const messages = data.toString().split('\n').filter(msg => msg.trim());
        messages.forEach(msg => {
            debug(`Received from server: ${msg}`);
            console.log(msg);
        });
    });
    
    // Handle connection errors
    client.on('error', (err) => {
        if (err.code === 'ECONNREFUSED') {
            debug(`Connection refused - server may not be running`);
        } else if (err.code === 'ETIMEDOUT') {
            debug(`Connection timeout - server may be unreachable`);
        } else {
            debug(`TCP connection error: ${err.message}`);
        }
        
        // Error will trigger close event, which handles reconnection
    });
    
    // Handle connection close
    client.on('close', () => {
        const wasConnected = connectionState === ConnectionState.CONNECTED;
        connectionState = ConnectionState.DISCONNECTED;
        
        if (wasConnected) {
            info('Connection to MCP server closed');
        } else {
            debug('Connection attempt failed');
        }
        
        // Clear health check timer
        if (healthCheckTimer) {
            clearTimeout(healthCheckTimer);
            healthCheckTimer = null;
        }
        
        // Schedule reconnection with exponential backoff
        scheduleReconnect();
    });
    
    // Attempt connection
    client.connect(TCP_PORT, TCP_HOST);
}

// Track reconnection attempts for smart logging
let reconnectAttemptCount = 0;

// Function to schedule reconnection with exponential backoff
function scheduleReconnect() {
    // Don't schedule if we're already waiting to reconnect
    if (reconnectTimer) {
        debug('Reconnect already scheduled');
        return;
    }
    
    reconnectAttemptCount++;
    
    // Check if we have an initialize request waiting - if so, use minimal delay
    let hasInitializeWaiting = false;
    for (const msg of messageQueue) {
        try {
            const parsed = JSON.parse(msg);
            if (parsed.method === 'initialize') {
                hasInitializeWaiting = true;
                break;
            }
        } catch (e) {
            // Ignore parse errors
        }
    }
    
    // If we have an initialize waiting, use minimal delay to reconnect quickly
    let effectiveDelay = reconnectDelay;
    if (hasInitializeWaiting && reconnectAttemptCount <= 10) {
        // For first 10 attempts with initialize waiting, use very short delay
        effectiveDelay = RECONNECT_MIN_DELAY;
        debug('Initialize request waiting - using minimal reconnect delay');
    }
    
    // Calculate delay with jitter
    const delayWithJitter = getReconnectDelayWithJitter(effectiveDelay);
    
    // Smart logging - only log first few attempts and then periodically
    if (reconnectAttemptCount <= 3 || reconnectAttemptCount % 10 === 0 || hasInitializeWaiting) {
        info(`Scheduling reconnection attempt #${reconnectAttemptCount} in ${delayWithJitter}ms${hasInitializeWaiting ? ' (initialize waiting)' : ''}`);
    } else {
        debug(`Scheduling reconnection attempt #${reconnectAttemptCount} in ${delayWithJitter}ms`);
    }
    
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        
        // Increase delay for next attempt (exponential backoff)
        // But only if we don't have an initialize waiting
        if (!hasInitializeWaiting) {
            reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_DELAY);
        }
        
        // Attempt to reconnect
        connectToServer();
    }, delayWithJitter);
}

// Set up stdin input handling
const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
});

rl.on('line', (line) => {
    debug(`Received from stdin: ${line}`);
    
    if (connectionState === ConnectionState.CONNECTED) {
        try {
            client.write(line + '\n');
            resetHealthCheck();
        } catch (err) {
            debug(`Failed to write to server: ${err.message}`);
            const requestId = getRequestId(line);
            sendErrorResponse(requestId, -32003, 'Failed to send message to MCP server');
        }
    } else {
        // Parse the message to check if it needs special handling
        let isInitialize = false;
        let requestId = undefined;
        
        try {
            const parsed = JSON.parse(line);
            requestId = parsed.id;
            isInitialize = parsed.method === 'initialize';
        } catch (e) {
            debug('Could not parse message to check method');
        }
        
        if (isInitialize) {
            // Special handling for initialize - queue it but don't return error immediately
            // The initialize will be sent as soon as we connect
            info('Received initialize request while disconnected - will send when connected');
            
            // Put initialize at the front of the queue so it's processed first
            messageQueue.unshift(line);
            debug(`Queued initialize request at front (queue size: ${messageQueue.length})`);
            
            // Set a timeout for initialize - if we can't connect within 30 seconds, return error
            setTimeout(() => {
                // Check if this initialize is still in the queue (not sent)
                const stillQueued = messageQueue.some(msg => msg === line);
                if (stillQueued && connectionState !== ConnectionState.CONNECTED) {
                    // Remove from queue
                    const index = messageQueue.indexOf(line);
                    if (index > -1) {
                        messageQueue.splice(index, 1);
                    }
                    
                    // Send timeout error
                    info('Initialize request timed out after 30 seconds - server not available');
                    sendErrorResponse(requestId, -32003, 'MCP server connection timeout - server not responding after 30 seconds');
                }
            }, 30000); // 30 second timeout for initialize
        } else {
            // Queue other messages normally
            queueMessage(line);
            
            if (requestId) {
                info(`Connection not available, message queued (queue size: ${messageQueue.length})`);
            }
        }
        
        // Ensure we're trying to reconnect
        if (connectionState === ConnectionState.DISCONNECTED && !reconnectTimer) {
            scheduleReconnect();
        }
    }
});

// Handle stdin close (parent process exited)
rl.on('close', () => {
    info('Stdin closed, parent process has exited - shutting down');
    
    // Clean up and exit
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
    }
    if (healthCheckTimer) {
        clearTimeout(healthCheckTimer);
    }
    if (logSummaryTimer) {
        clearInterval(logSummaryTimer);
    }
    if (client) {
        client.end();
    }
    
    process.exit(0);
});

// Handle stdin errors
process.stdin.on('error', (err) => {
    info(`Stdin error: ${err.message} - shutting down`);
    
    // Clean up and exit
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
    }
    if (healthCheckTimer) {
        clearTimeout(healthCheckTimer);
    }
    if (logSummaryTimer) {
        clearInterval(logSummaryTimer);
    }
    if (client) {
        client.end();
    }
    
    process.exit(0);
});

// Handle process termination gracefully
process.on('SIGINT', () => {
    info('Received SIGINT, shutting down gracefully');
    
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
    }
    if (healthCheckTimer) {
        clearTimeout(healthCheckTimer);
    }
    if (logSummaryTimer) {
        clearInterval(logSummaryTimer);
    }
    if (client) {
        client.end();
    }
    
    process.exit(0);
});

process.on('SIGTERM', () => {
    info('Received SIGTERM, shutting down gracefully');
    
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
    }
    if (healthCheckTimer) {
        clearTimeout(healthCheckTimer);
    }
    if (logSummaryTimer) {
        clearInterval(logSummaryTimer);
    }
    if (client) {
        client.end();
    }
    
    process.exit(0);
});

// Handle uncaught exceptions to prevent process crash
process.on('uncaughtException', (err) => {
    console.error(`[MCP-TCP-Adapter] Uncaught exception: ${err.message}`);
    console.error(err.stack);
    // Don't exit - try to recover
});

// Handle unhandled promise rejections
process.on('unhandledRejection', (reason, promise) => {
    console.error('[MCP-TCP-Adapter] Unhandled Rejection at:', promise, 'reason:', reason);
    // Don't exit - try to recover
});

// Start initial connection
connectToServer();

// Keep the process alive but also monitor stdin health
process.stdin.resume();

// Periodically check if stdin is still open
const stdinCheckInterval = setInterval(() => {
    if (process.stdin.destroyed || !process.stdin.readable) {
        info('Stdin is no longer readable - parent process likely exited');
        
        // Clean up
        clearInterval(stdinCheckInterval);
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
        }
        if (healthCheckTimer) {
            clearTimeout(healthCheckTimer);
        }
        if (logSummaryTimer) {
            clearInterval(logSummaryTimer);
        }
        if (client) {
            client.end();
        }
        
        process.exit(0);
    }
}, 1000); // Check every second