import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';

const executeFile = promisify(execFile);
const descriptorPath = join(homedir(), '.digital', 'codex-bridge.json');
const discoveryTimeout = 15_000;
const commandTimeout = 20_000;
const maxPayloadBytes = 1_048_576;
let discovery;

export class BridgeError extends Error {
    constructor(code, message) {
        super(message);
        this.code = code;
    }
}

async function healthyBridge(timeout = 800) {
    try {
        const descriptor = JSON.parse(await readFile(descriptorPath, 'utf8'));
        if (descriptor.protocol !== 1 || !Number.isInteger(descriptor.port) ||
            descriptor.port < 1 || descriptor.port > 65_535 ||
            !Number.isInteger(descriptor.pid) || descriptor.pid < 1 ||
            typeof descriptor.token !== 'string' || !/^[\x21-\x7e]+$/.test(descriptor.token))
            return null;
        process.kill(descriptor.pid, 0);
        const response = await fetch(`http://127.0.0.1:${descriptor.port}/health`, {
            headers: { Authorization: `Bearer ${descriptor.token}` },
            signal: AbortSignal.timeout(timeout),
            redirect: 'error'
        });
        if (!response.ok)
            return null;
        const health = await response.json();
        if (health.protocol !== 1 || health.pid !== descriptor.pid || health.application !== 'Digital')
            return null;
        return descriptor;
    } catch {
        return null;
    }
}

async function discoverBridge() {
    const deadline = Date.now() + discoveryTimeout;
    const ready = await healthyBridge();
    if (ready)
        return ready;
    try {
        await executeFile('/usr/bin/open', ['/Applications/Digital.app'], {
            timeout: Math.min(5_000, Math.max(1, deadline - Date.now())),
            maxBuffer: 16_384
        });
    } catch {
        throw new BridgeError('application_unavailable', 'Не удалось запустить /Applications/Digital.app. Установите Digital и попробуйте снова.');
    }
    while (Date.now() < deadline) {
        const bridge = await healthyBridge(Math.min(800, Math.max(1, deadline - Date.now())));
        if (bridge)
            return bridge;
        if (Date.now() < deadline)
            await delay(Math.min(200, deadline - Date.now()));
    }
    throw new BridgeError('bridge_unavailable', 'Digital не открыл нативное соединение за 15 секунд. Проверьте запущенное приложение.');
}

function ensureBridge() {
    if (!discovery)
        discovery = discoverBridge().finally(() => { discovery = undefined; });
    return discovery;
}

/** A command is dispatched once. Discovery probes cannot mutate the circuit. */
export async function command(name, args, mutation) {
    const body = JSON.stringify({ command: name, args });
    if (Buffer.byteLength(body, 'utf8') > maxPayloadBytes)
        throw new BridgeError('request_too_large', 'Размер команды превышает 1 МБ.');
    const bridge = await ensureBridge();
    let response;
    let result;
    try {
        response = await fetch(`http://127.0.0.1:${bridge.port}/command`, {
            method: 'POST',
            headers: {
                Authorization: `Bearer ${bridge.token}`,
                'Content-Type': 'application/json'
            },
            body,
            signal: AbortSignal.timeout(commandTimeout),
            redirect: 'error'
        });
        result = await response.json();
    } catch {
        throw new BridgeError(mutation ? 'result_uncertain' : 'connection_lost', mutation
            ? 'Ответ Digital не получен. Команда могла примениться; прочитайте inspect_circuit перед повторным изменением.'
            : 'Не удалось получить ответ Digital.');
    }
    if (!response.ok) {
        throw new BridgeError(result?.error?.code ?? 'native_error',
            result?.error?.message ?? `Digital вернул HTTP ${response.status}.`);
    }
    if (!result || typeof result !== 'object' || Array.isArray(result))
        throw new BridgeError('invalid_response', 'Digital вернул результат неизвестного формата.');
    return result;
}
