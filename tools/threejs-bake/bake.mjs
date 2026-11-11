import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import * as THREE from 'three';
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js';

/**
 * Bakes an img2threejs procedural factory into a .glb.
 *
 * WHY: img2threejs emits a Three.js TypeScript factory — great for animation
 * and editing, but it is CODE, so it can't be shown in the harness's GLB
 * viewer and can't be used by engines that aren't Three.js (Neural Navigators
 * runs Babylon). Baking gives us a real mesh: the factory stays the editable
 * master, the .glb is the portable artifact.
 *
 * Usage:
 *   npx tsx bake.mjs --factory <createFooModel.ts> --spec <spec.json> --out <foo.glb>
 *                    [--export <exportName>]
 *
 * The factory is loaded through tsx, so TypeScript is transpiled on the fly.
 */

function arg(name, required = true) {
  const i = process.argv.indexOf(`--${name}`);
  const v = i === -1 ? undefined : process.argv[i + 1];
  if (required && !v) {
    console.error(JSON.stringify({ ok: false, error: `missing --${name}` }));
    process.exit(2);
  }
  return v;
}

const factoryPath = resolve(arg('factory'));
const specPath = resolve(arg('spec'));
const outPath = resolve(arg('out'));
const exportName = arg('export', false);

// GLTFExporter reaches for browser globals when writing binary output. Node 18+
// already has Blob/TextEncoder; FileReader is the one that's genuinely absent,
// and only the binary path touches it.
// GLTFExporter's binary path does `reader.readAsArrayBuffer(blob)` and waits on
// `reader.onloadend` — miss that callback and parseAsync never settles, which
// looks exactly like a hang. Fire onloadend (and onload, for other callers).
if (typeof globalThis.FileReader === 'undefined') {
  globalThis.FileReader = class {
    readAsArrayBuffer(blob) {
      blob
        .arrayBuffer()
        .then((buf) => {
          this.result = buf;
          this.onload?.({ target: this });
          this.onloadend?.({ target: this });
        })
        .catch((err) => this.onerror?.(err));
    }
  };
}

// strip a UTF-8 BOM: specs written by Windows tooling carry one and JSON.parse
// rejects it outright
const spec = JSON.parse(readFileSync(specPath, 'utf8').replace(/^﻿/, ''));

// Factories live in the workspace next to their spec, so Node would resolve
// their `import 'three'` by walking up from THERE and find nothing. The
// generated file only imports from 'three' (no relative siblings), so stage a
// copy inside this tool where three/ is installed and import that instead.
const staging = resolve('.bake-staging');
mkdirSync(staging, { recursive: true });
const staged = resolve(staging, `factory-${Date.now()}.ts`);
writeFileSync(staged, readFileSync(factoryPath));
let mod;
try {
  mod = await import(pathToFileURL(staged).href);
} finally {
  rmSync(staged, { force: true });
}

// the generated factory is `createObjectNameModel`; accept an explicit name,
// then a default export, then the first create*Model function we can find
const factory =
  (exportName && mod[exportName]) ||
  (typeof mod.default === 'function' ? mod.default : undefined) ||
  Object.entries(mod).find(([k, v]) => typeof v === 'function' && /^create.*Model$/.test(k))?.[1];

if (typeof factory !== 'function') {
  console.error(
    JSON.stringify({
      ok: false,
      error: `no factory function found in ${factoryPath}`,
      exports: Object.keys(mod),
    }),
  );
  process.exit(1);
}

// Real generated factories take (options) only — the README's (spec, options)
// shape doesn't match what stage3 emits. Try the real signature first and fall
// back, so both survive.
let group;
for (const attempt of [() => factory({}), () => factory(spec, {})]) {
  try {
    const candidate = attempt();
    if (candidate && typeof candidate.traverse === 'function') {
      group = candidate;
      break;
    }
  } catch {
    /* try the next calling convention */
  }
}
if (!group) {
  console.error(JSON.stringify({ ok: false, error: 'factory did not return a THREE.Object3D' }));
  process.exit(1);
}

// a scene the exporter is happy with, plus a quick census for the caller
const scene = new THREE.Scene();
scene.add(group);
let meshes = 0;
let triangles = 0;
scene.traverse((o) => {
  if (!o.isMesh || !o.geometry) return;
  meshes++;
  const g = o.geometry;
  triangles += g.index ? g.index.count / 3 : (g.attributes.position?.count ?? 0) / 3;
});

// parseAsync, not the callback form: with recent three the callback variant can
// leave the promise unsettled under Node (no browser event loop to pump it)
const glb = await new GLTFExporter().parseAsync(scene, { binary: true, onlyVisible: false });

mkdirSync(dirname(outPath), { recursive: true });
const bytes = Buffer.from(glb instanceof ArrayBuffer ? glb : new Uint8Array(glb));
writeFileSync(outPath, bytes);

console.log(
  JSON.stringify({
    ok: true,
    out: outPath,
    bytes: bytes.length,
    meshes,
    triangles: Math.round(triangles),
  }),
);
