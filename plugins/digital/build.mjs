import { build } from 'esbuild';
import { copyFile, cp, mkdir, mkdtemp, readFile, readdir, realpath, rename, rm, stat, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const source = dirname(fileURLToPath(import.meta.url));
const project = resolve(source, '../..');
const output = join(project, 'target', 'codex', 'digital');
if (process.platform !== 'darwin' || process.arch !== 'arm64')
    throw new Error('The Digital plugin runtime must be built on macOS arm64.');
const nodePath = await realpath(process.execPath);
const nodeLicense = resolve(dirname(nodePath), '..', 'LICENSE');
await stat(nodeLicense);
const dependencies = execFileSync('/usr/bin/otool', ['-L', nodePath], { encoding: 'utf8' })
    .split('\n').slice(1).map(line => line.trim().split(' (')[0]).filter(Boolean);
if (dependencies.some(path => !path.startsWith('/usr/lib/') && !path.startsWith('/System/Library/')))
    throw new Error('Node links to external libraries. Use a self-contained Node distribution.');

await mkdir(join(project, 'target', 'codex'), { recursive: true });
const staging = await mkdtemp(join(project, 'target', 'codex-build-'));
try {
    await mkdir(join(staging, 'runtime'));
    for (const name of ['plugin.json', 'mcp.json'])
        await copyFile(join(source, name), join(staging, name));
    await cp(join(source, 'skills'), join(staging, 'skills'), { recursive: true });
    await copyFile(join(project, 'LICENSE'), join(staging, 'LICENSE'));
    await copyFile(nodePath, join(staging, 'runtime', 'node'));
    await copyFile(nodeLicense, join(staging, 'runtime', 'NODE-LICENSE'));
    const bundle = await build({
        absWorkingDir: source,
        entryPoints: ['server.mjs'],
        outfile: join(staging, 'runtime', 'server.mjs'),
        bundle: true,
        platform: 'node',
        target: 'node22',
        format: 'esm',
        legalComments: 'eof',
        metafile: true,
        banner: { js: "import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);" }
    });
    const packageRoots = new Set();
    for (const input of Object.keys(bundle.metafile.inputs)) {
        const match = input.match(/^(node_modules\/(?:@[^/]+\/)?[^/]+)\//);
        if (match)
            packageRoots.add(match[1]);
    }
    const notices = [];
    for (const packageRoot of [...packageRoots].sort()) {
        const folder = join(source, packageRoot);
        const metadata = JSON.parse(await readFile(join(folder, 'package.json'), 'utf8'));
        notices.push(`${metadata.name} ${metadata.version}\nLicense: ${metadata.license ?? 'See license below'}`);
        const licenseFiles = (await readdir(folder)).filter(name => /^(LICENSE|LICENCE|COPYING|NOTICE)([.-]|$)/i.test(name));
        for (const name of licenseFiles.sort())
            if ((await stat(join(folder, name))).isFile())
                notices.push(await readFile(join(folder, name), 'utf8'));
    }
    await writeFile(join(staging, 'runtime', 'THIRD_PARTY_NOTICES'), notices.join('\n\n----------\n\n') + '\n');
    await rm(output, { recursive: true, force: true });
    await rename(staging, output);
    console.log(`Built ${output}`);
    console.log(`Bundled Node ${process.version}, ${process.arch}; ${packageRoots.size} licensed packages.`);
} finally {
    await rm(staging, { recursive: true, force: true });
}
