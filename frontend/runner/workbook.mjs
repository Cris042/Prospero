import { open } from 'node:fs/promises';
import { Worker } from 'node:worker_threads';
import { LIMITS, ensure } from './limits.mjs';

export async function readWorkbook(path, signal) {
  ensure(typeof path === 'string' && path.endsWith('.xlsx'), 'EXTENSAO_INVALIDA');
  const handle = await open(path, 'r');
  let buffer;
  try {
    const stat = await handle.stat();
    ensure(stat.isFile() && stat.size > 0 && stat.size <= LIMITS.file, 'LIMITE_ARQUIVO');
    // One bounded snapshot, shared by ZIP inspection and Excel parsing; no path reopen.
    buffer = Buffer.alloc(stat.size + 1);
    let offset = 0;
    while (offset < buffer.length) {
      const { bytesRead } = await handle.read(buffer, offset, buffer.length - offset, offset);
      if (!bytesRead) break;
      offset += bytesRead;
    }
    ensure(offset <= stat.size, 'ARQUIVO_ALTERADO');
    buffer = buffer.subarray(0, offset);
  } finally {
    await handle.close();
  }
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./parser-worker.mjs', import.meta.url), {
      workerData: buffer,
      resourceLimits: { maxOldGenerationSizeMb: 128 },
    });
    let done = false;
    const finish = (error, value, location) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', aborted);
      void worker.terminate();
      if (error) reject(Object.assign(new Error(error), location));
      else resolve(value);
    };
    const timer = setTimeout(() => finish('PARSER_TIMEOUT'), LIMITS.parseMs);
    const aborted = () => finish('CANCELADO');
    signal?.addEventListener('abort', aborted, { once: true });
    if (signal?.aborted) return aborted();
    worker.on('message', (message) => finish(message.error, message.sheets, message.location));
    worker.on('error', () => finish('PARSER_FALHOU'));
    worker.on('exit', () => {
      if (!done) finish('PARSER_FALHOU');
    });
  });
}
