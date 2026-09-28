import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const outDir = join(process.cwd(), 'public', 'models');
await mkdir(outDir, { recursive: true });

const files = [
  ['AK47_URL', 'ak47.glb'],
  ['PISTOL_URL', 'pistol.glb'],
  ['SHOTGUN_URL', 'shotgun.glb']
];

let downloaded = 0;
for (const [envName, filename] of files) {
  const url = process.env[envName];
  if (!url) {
    console.log(`[build] ${envName} not set; skipping ${filename}`);
    continue;
  }
  console.log(`[build] downloading ${filename}`);
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`${filename}: HTTP ${res.status}`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  if (bytes.length < 1000) throw new Error(`${filename}: downloaded file is unexpectedly small (${bytes.length})`);
  await writeFile(join(outDir, filename), bytes);
  console.log(`[build] wrote ${filename}: ${bytes.length} bytes`);
  downloaded++;
}

console.log(`[build] completed, ${downloaded} weapon assets downloaded`);
