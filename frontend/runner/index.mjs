import { readWorkbook } from './workbook.mjs';
import { compile, configValid } from './contract.mjs';
import { execute } from './executor.mjs';
import { ensure } from './limits.mjs';

export async function runWorkbook(filePath, config, options = {}) {
  let snapshot;
  try {
    snapshot = structuredClone(config);
  } catch {
    ensure(false, 'CONFIG_INVALIDA');
  }
  configValid(snapshot);
  options.signal?.throwIfAborted();
  let sheets;
  try {
    sheets = await readWorkbook(filePath, options.signal);
  } catch (error) {
    if (/^[A-Z][A-Z0-9_]{0,100}$/.test(error.message)) throw error;
    throw new Error('ARQUIVO_INACESSIVEL');
  }
  const plan = compile(sheets, snapshot);
  ensure(
    options.scenario === undefined || plan.some((c) => c.name === options.scenario),
    'CENARIO_DESCONHECIDO',
  );
  return execute(
    plan.filter((c) => options.scenario === undefined || c.name === options.scenario),
    snapshot,
    options,
  );
}
