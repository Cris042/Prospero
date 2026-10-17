#!/usr/bin/env node
import { open } from 'node:fs/promises';
import { runWorkbook } from './index.mjs';

const controller = new AbortController();
let cancelled = 0;
const onInt = () => {
  cancelled = 130;
  controller.abort();
};
const onTerm = () => {
  cancelled = 143;
  controller.abort();
};
process.once('SIGINT', onInt);
process.once('SIGTERM', onTerm);
try {
  const args = process.argv.slice(2);
  const file = args.shift();
  let configPath;
  let scenario;
  while (args.length) {
    const option = args.shift();
    const value = args.shift();
    if (!value || !['--config', '--cenario'].includes(option))
      throw new Error('ARGUMENTOS_INVALIDOS');
    if (option === '--config' && configPath === undefined) configPath = value;
    else if (option === '--cenario' && scenario === undefined) scenario = value;
    else throw new Error('ARGUMENTOS_INVALIDOS');
  }
  if (!file || !configPath) throw new Error('USO_RUNNER_ARQUIVO_XLSX_CONFIG');
  const handle = await open(configPath, 'r');
  let config;
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > 65536) throw new Error('CONFIG_INVALIDA');
    const buffer = Buffer.alloc(65537);
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    if (bytesRead > 65536) throw new Error('CONFIG_INVALIDA');
    config = JSON.parse(buffer.subarray(0, bytesRead).toString('utf8'));
  } finally {
    await handle.close();
  }
  const report = await runWorkbook(file, config, { scenario, signal: controller.signal });
  console.log(JSON.stringify(report));
  process.exitCode = cancelled || (report.sucesso ? 0 : 1);
} catch (error) {
  const code = /^[A-Z][A-Z0-9_]{0,100}$/.test(error.message) ? error.message : 'ENTRADA_INVALIDA';
  console.error(
    JSON.stringify({
      sucesso: false,
      erro: { codigo: code, ...(error.aba ? { aba: error.aba, linha: error.linha } : {}) },
    }),
  );
  process.exitCode = cancelled || 2;
} finally {
  process.removeListener('SIGINT', onInt);
  process.removeListener('SIGTERM', onTerm);
}
