#!/usr/bin/env node

const net = require('net');

// Simple test to verify MCP handshake
const TCP_HOST = 'localhost';
const TCP_PORT = 9080;

console.log('Testing MCP handshake...');

const client = new net.Socket();

client.on('connect', () => {
    console.log('Connected to server');
    
    // Send initialize request
    const initRequest = {
        jsonrpc: "2.0",
        method: "initialize",
        params: {
            protocolVersion: "2024-11-05",
            capabilities: {
                tools: {},
                resources: {}
            },
            clientInfo: {
                name: "test-client",
                version: "1.0.0"
            }
        },
        id: 1
    };
    
    console.log('Sending initialize request:', JSON.stringify(initRequest));
    client.write(JSON.stringify(initRequest) + '\n');
});

client.on('data', (data) => {
    const messages = data.toString().split('\n').filter(msg => msg.trim());
    messages.forEach(msg => {
        console.log('Received:', msg);
        try {
            const response = JSON.parse(msg);
            
            if (response.id === 1) {
                console.log('Got initialize response');
                
                // Now request tools list
                const toolsRequest = {
                    jsonrpc: "2.0",
                    method: "tools/list",
                    params: {},
                    id: 2
                };
                
                console.log('Sending tools/list request:', JSON.stringify(toolsRequest));
                client.write(JSON.stringify(toolsRequest) + '\n');
            } else if (response.id === 2) {
                console.log('Got tools/list response:');
                console.log('Tools count:', response.result?.tools?.length || 0);
                if (response.result?.tools) {
                    response.result.tools.forEach(tool => {
                        console.log('  -', tool.name);
                    });
                }
                
                // Now request resources list
                const resourcesRequest = {
                    jsonrpc: "2.0",
                    method: "resources/list",
                    params: {},
                    id: 3
                };
                
                console.log('Sending resources/list request:', JSON.stringify(resourcesRequest));
                client.write(JSON.stringify(resourcesRequest) + '\n');
            } else if (response.id === 3) {
                console.log('Got resources/list response:');
                console.log('Resources count:', response.result?.resources?.length || 0);
                if (response.result?.resources) {
                    response.result.resources.forEach(resource => {
                        console.log('  -', resource.uri, ':', resource.name);
                    });
                }
                
                console.log('\nTest completed successfully!');
                client.end();
            }
        } catch (e) {
            console.error('Error parsing response:', e.message);
        }
    });
});

client.on('error', (err) => {
    console.error('Connection error:', err.message);
});

client.on('close', () => {
    console.log('Connection closed');
    process.exit(0);
});

// Connect to server
client.connect(TCP_PORT, TCP_HOST);

// Timeout after 5 seconds
setTimeout(() => {
    console.error('Test timed out');
    process.exit(1);
}, 5000);