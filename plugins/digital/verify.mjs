import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const source = dirname(fileURLToPath(import.meta.url));
const plugin = resolve(source, '../../target/codex/digital');
const temporaryHome = await mkdtemp(join(tmpdir(), 'digital-mcp-verification-'));
const token = 'verification-token';
let mutationsReceived = 0;
const native = createServer(async (request, response) => {
    assert.equal(request.headers.authorization, `Bearer ${token}`);
    if (request.url === '/health') {
        response.setHeader('Content-Type', 'application/json');
        response.end(JSON.stringify({ protocol: 1, pid: process.pid, application: 'Digital' }));
        return;
    }
    let body = '';
    for await (const chunk of request)
        body += chunk;
    const payload = JSON.parse(body);
    if (payload.command === 'edit') {
        mutationsReceived++;
        request.socket.destroy();
        return;
    }
    assert.equal(payload.command, 'windows');
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ windows: [] }));
});
await new Promise(resolve => native.listen(0, '127.0.0.1', resolve));
await mkdir(join(temporaryHome, '.digital'));
await writeFile(join(temporaryHome, '.digital', 'codex-bridge.json'), JSON.stringify({
    protocol: 1, pid: process.pid, port: native.address().port, token
}), { mode: 0o600 });

const client = new Client({ name: 'digital-plugin-verification', version: '0.1.0' });
const transport = new StdioClientTransport({
    command: join(plugin, 'runtime', 'node'),
    args: [join(plugin, 'runtime', 'server.mjs')],
    cwd: plugin,
    env: { HOME: temporaryHome, PATH: '/usr/bin:/bin' },
    stderr: 'pipe'
});
let stderr = '';
try {
    await client.connect(transport);
    transport.stderr?.on('data', data => { stderr += data; });
    const listed = await client.listTools();
    assert.equal(listed.tools.length, 13);
    const edit = listed.tools.find(tool => tool.name === 'edit_circuit');
    assert.ok(edit.inputSchema.required.includes('expectedRevision'));
    assert.equal(edit.annotations.openWorldHint, false);
    const windows = await client.callTool({ name: 'list_windows', arguments: {} });
    assert.deepEqual(windows.structuredContent, { windows: [] });
    assert.deepEqual(JSON.parse(windows.content[0].text), windows.structuredContent);
    const disconnected = await client.callTool({ name: 'edit_circuit', arguments: {
        expectedRevision: 'a'.repeat(64),
        operations: [{ op: 'add_element', type: 'And', x: 0, y: 0 }]
    } });
    assert.equal(disconnected.isError, true);
    assert.equal(disconnected.structuredContent.error.code, 'result_uncertain');
    assert.equal(mutationsReceived, 1);
    assert.equal(stderr, '');
    console.log('MCP initialize/tools/list: 13 tools; clean PATH bundled runtime; structured read; uncertain mutation dispatched once.');
} finally {
    await client.close();
    await new Promise(resolve => native.close(resolve));
    await rm(temporaryHome, { recursive: true, force: true });
}
