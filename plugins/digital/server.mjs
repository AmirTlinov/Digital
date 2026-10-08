import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { BridgeError, command } from './bridge.mjs';

const server = new McpServer({ name: 'digital', version: '0.1.0' }, {
    instructions: 'Работайте с живым окном Digital. Перед изменением прочитайте inspect_circuit и передайте его revision как expectedRevision. Если окон несколько, указывайте windowId из list_windows. После неопределённого результата изменяющей команды проверьте состояние перед повтором.'
});
const windowId = z.string().min(1).optional().describe('ID окна из list_windows; можно опустить, если окно одно.');
const expectedRevision = z.string().regex(/^[a-f0-9]{64}$/).describe('Актуальная revision из inspect_circuit или последней успешной команды.');
const coordinate = z.number().int().min(-1_000_000).max(1_000_000);
const rotation = z.number().int().min(0).max(3).describe('Число четвертей оборота: 0–3.');
const elementId = z.string().regex(/^e:[0-9]+$/).describe('ID элемента из исходного inspect_circuit.');
const wireId = z.string().regex(/^w:[0-9]+$/).describe('ID провода из исходного inspect_circuit.');
const numericValue = z.union([z.string(), z.number().int().safe()])
    .describe('Целое число; значения за пределами безопасного диапазона JavaScript передавайте строкой.');
const scalar = z.union([numericValue, z.boolean()]);
const attributeKey = z.enum(['Label', 'Bits', 'Inputs', 'Value', 'InDefault', 'Default',
    'rotation', 'mirror', 'wideShape', 'Description', 'small', 'isHighZ', 'intFormat']);
const operations = z.array(z.discriminatedUnion('op', [
    z.object({
        op: z.literal('add_element'), type: z.string().min(1), x: coordinate, y: coordinate,
        rotation: rotation.optional(), label: z.string().optional(),
        bits: z.number().int().min(1).max(64).optional(),
        inputCount: z.number().int().min(2).max(64).optional(), value: numericValue.optional()
    }).strict(),
    z.object({ op: z.literal('remove_element'), id: elementId }).strict(),
    z.object({ op: z.literal('move_element'), id: elementId, x: coordinate, y: coordinate, rotation: rotation.optional() }).strict(),
    z.object({ op: z.literal('set_attribute'), id: elementId, key: attributeKey, value: scalar }).strict(),
    z.object({ op: z.literal('add_wire'), x1: coordinate, y1: coordinate, x2: coordinate, y2: coordinate }).strict(),
    z.object({ op: z.literal('remove_wire'), id: wireId }).strict()
])).min(1).max(200).describe('Единый пакет правок. IDs относятся к исходному снимку на протяжении всего пакета.');
const absolutePath = z.string().startsWith('/').describe('Абсолютный путь к файлу .dig.');

function tool(name, title, description, nativeCommand, inputSchema, { readOnly = false, destructive = false } = {}) {
    server.registerTool(name, {
        title, description, inputSchema,
        annotations: {
            readOnlyHint: readOnly,
            destructiveHint: destructive,
            idempotentHint: readOnly,
            openWorldHint: false
        }
    }, async args => {
        try {
            const result = await command(nativeCommand, args, !readOnly);
            return { structuredContent: result, content: [{ type: 'text', text: JSON.stringify(result) }] };
        } catch (error) {
            const result = { error: {
                code: error instanceof BridgeError ? error.code : 'plugin_error',
                message: error instanceof Error ? error.message : String(error)
            } };
            return { isError: true, structuredContent: result, content: [{ type: 'text', text: JSON.stringify(result) }] };
        }
    });
}

tool('list_windows', 'Окна Digital', 'Показывает открытые окна Digital и их IDs.', 'windows', {}, { readOnly: true });
tool('inspect_circuit', 'Состояние схемы', 'Читает текущую схему: revision, элементы, провода, состояние симулятора и сигналы.', 'inspect', { windowId }, { readOnly: true });
tool('component_library', 'Библиотека компонентов', 'Находит доступные типы компонентов, их атрибуты и выводы в библиотеке текущего Digital.', 'library', {
    windowId, query: z.string().optional(), limit: z.number().int().min(1).max(100).optional()
}, { readOnly: true });
tool('open_circuit', 'Открыть схему', 'Открывает существующий .dig в Digital и возвращает состояние окна.', 'open', { path: absolutePath });
tool('new_circuit', 'Новая схема', 'Создаёт пустую схему в новом окне Digital.', 'new', {});
tool('edit_circuit', 'Изменить схему', 'Применяет пакет операций к остановленной схеме при совпадении expectedRevision. После изменений возвращает актуальное состояние.', 'edit', {
    windowId, expectedRevision, operations
}, { destructive: true });
tool('save_circuit', 'Сохранить схему', 'Сохраняет схему в .dig при совпадении expectedRevision. Для первого сохранения нужен абсолютный путь. Перезапись другого существующего файла требует явно указанного overwrite:true.', 'save', {
    windowId, expectedRevision, path: absolutePath.optional(), overwrite: z.boolean().optional().default(false)
}, { destructive: true });
tool('start_simulation', 'Запустить симуляцию', 'Включает симуляцию текущей схемы; тактовый генератор остаётся под ручным управлением.', 'start', { windowId, expectedRevision });
tool('stop_simulation', 'Остановить симуляцию', 'Останавливает симуляцию и возвращает редактор схемы.', 'stop', { windowId, expectedRevision });
tool('clock_cycle', 'Ручной такт', 'Выполняет один полный такт генератора в запущенной симуляции.', 'clock', { windowId, expectedRevision });
tool('read_signals', 'Прочитать сигналы', 'Читает входы, выходы и наблюдаемые сигналы текущей симуляции с разрядностью, десятичным значением, HEX и highZ.', 'signals', { windowId }, { readOnly: true });
tool('set_inputs', 'Изменить входы', 'Задаёт значения именованных входов запущенной симуляции. Значение: десятичная строка, 0xHEX, 0bBIN или Z.', 'inputs', {
    windowId, expectedRevision,
    values: z.array(z.object({ name: z.string().min(1), value: z.string().min(1) }).strict()).min(1).max(200)
});
tool('export_svg', 'Изображение схемы', 'Возвращает SVG текущей схемы вместе с windowId и revision.', 'svg', { windowId }, { readOnly: true });

await server.connect(new StdioServerTransport());
