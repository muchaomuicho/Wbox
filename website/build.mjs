import { cp, mkdir, copyFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url));
const output = join(root, 'dist');
await mkdir(output, { recursive: true });
for (const file of ['index.html', 'styles.css', 'app.js']) {
  await access(join(root, file));
  await copyFile(join(root, file), join(output, file));
}
await cp(join(root, 'public'), output, { recursive: true });
console.log('Built Wbox website: website/dist');
