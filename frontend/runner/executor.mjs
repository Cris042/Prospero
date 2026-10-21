import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import { LIMITS, ensure, isRecord } from './limits.mjs';
import {
  resolveValue,
  readPointer,
  targetPath,
  parseExactJson,
  riskPreparation,
} from './contract.mjs';

function summary(value) {
  if (value === undefined) return { tipo: 'ausente' };
  if (value === null) return { tipo: 'null' };
  if (typeof value === 'number' || typeof value === 'boolean')
    return { tipo: typeof value, valor: value };
  return { tipo: Array.isArray(value) ? 'array' : typeof value, valor: '[omitido]' };
}
function mismatch(expected, observed, field) {
  return { campo: field, esperado: summary(expected), observado: summary(observed) };
}
function assertions(response, expected) {
  if (response.status !== expected.status)
    return mismatch(expected.status, response.status, 'status');
  let index = 0;
  for (const [key, value] of Object.entries(expected.headers)) {
    if (response.headers.get(key) !== value)
      return mismatch(value, response.headers.get(key) ?? undefined, `header-${index}`);
    index++;
  }
  index = 0;
  for (const [pointer, value] of Object.entries(expected.body)) {
    const observed = readPointer(response.body, pointer);
    if (!isDeepStrictEqual(observed, value)) return mismatch(value, observed, `corpo-${index}`);
    index++;
  }
  return undefined;
}
async function request(step, config, context, signal) {
  const service = config.servicos[step.service];
  const path = targetPath(resolveValue(step.path, context, true));
  ensure(
    !step.deadline || step.method === 'GET' || riskPreparation({ ...step, path }),
    'POLLING_INVALIDO',
  );
  const url = new URL(path, service.origem);
  ensure(
    url.origin === new URL(service.origem).origin && url.pathname.startsWith('/api/v1/'),
    'CAMINHO_INVALIDO',
  );
  const headers = resolveValue(step.headers, context);
  for (const value of Object.values(headers))
    ensure(
      typeof value === 'string' && value.length <= 4096 && !/[\r\n\0]/.test(value),
      'HEADER_INVALIDO',
    );
  const body = resolveValue(step.body, context);
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), LIMITS.requestMs);
  try {
    const response = await fetch(url, {
      method: step.method,
      headers: { ...headers, ...service.headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'manual',
      signal: AbortSignal.any([controller.signal, signal]),
    });
    ensure(response.status < 300 || response.status >= 400, 'REDIRECIONAMENTO_PROIBIDO');
    const length = response.headers.get('content-length');
    ensure(
      !length || (Number.isSafeInteger(Number(length)) && Number(length) <= LIMITS.response),
      'RESPOSTA_EXCESSIVA',
    );
    const chunks = [];
    let size = 0;
    if (response.body)
      for await (const chunk of response.body) {
        size += chunk.byteLength;
        ensure(size <= LIMITS.response, 'RESPOSTA_EXCESSIVA');
        chunks.push(chunk);
      }
    let result;
    if (size) {
      try {
        result = parseExactJson(Buffer.concat(chunks).toString('utf8'));
      } catch (error) {
        if (error.message !== 'JSON_INVALIDO') throw error;
        result = undefined;
      }
    }
    return { status: response.status, headers: response.headers, body: result };
  } finally {
    clearTimeout(deadline);
    controller.abort();
  }
}
const KNOWN_ERRORS = new Set([
  'CAMINHO_INVALIDO',
  'HEADER_INVALIDO',
  'REFERENCIA_NAO_ESCALAR',
  'REDIRECIONAMENTO_PROIBIDO',
  'RESPOSTA_EXCESSIVA',
  'CAPTURA_AUSENTE',
  'JSON_PROFUNDO',
  'NUMERO_INSEGURO',
  'CHAVE_PROIBIDA',
  'LIMITE_REQUISICOES',
  'LIMITE_INTERPOLACAO',
]);

export async function execute(plan, config, options = {}) {
  const execution = randomUUID();
  const global = AbortSignal.timeout(LIMITS.executionMs);
  const executionSignal = options.signal ? AbortSignal.any([global, options.signal]) : global;
  const report = { sucesso: true, execucao: execution, cenarios: [] };
  let requests = 0;
  for (const scenario of plan) {
    const context = { execucao: execution, cenario: randomUUID() };
    const record = { cenario: scenario.name, sucesso: true, passos: [] };
    const signal = AbortSignal.any([executionSignal, AbortSignal.timeout(LIMITS.scenarioMs)]);
    report.cenarios.push(record);
    for (const step of scenario.steps) {
      const item = { id: step.id, status: 'nao-executado', tentativas: 0 };
      record.passos.push(item);
      if (!record.sucesso || executionSignal.aborted) {
        record.sucesso = false;
        report.sucesso = false;
        continue;
      }
      try {
        const expected = resolveValue(step.expected, context);
        const started = performance.now();
        const polling = step.deadline ? AbortSignal.timeout(step.deadline) : undefined;
        const stepSignal = polling ? AbortSignal.any([signal, polling]) : signal;
        let response;
        let difference;
        let retryable;
        do {
          stepSignal.throwIfAborted();
          ensure(++requests <= LIMITS.requests, 'LIMITE_REQUISICOES');
          item.tentativas++;
          response = await request(step, config, context, stepSignal);
          difference = assertions(response, expected);
          retryable =
            step.method === 'GET' ||
            (riskPreparation(step) &&
              response.status === 409 &&
              isRecord(response.body) &&
              response.body.codigo === 'RESERVA_VIVA');
          if (
            !difference ||
            !step.deadline ||
            !retryable ||
            performance.now() - started + step.interval >= step.deadline
          )
            break;
          await delay(step.interval, undefined, { signal: stepSignal });
        } while (true);
        if (difference) {
          item.erro = {
            codigo: step.deadline && retryable ? 'POLLING_ESGOTADO' : 'ASSERCAO_DIVERGENTE',
            ...difference,
          };
          throw new Error('ASSERTION');
        }
        for (const [name, source] of Object.entries(step.captures)) {
          const value = source.startsWith('@header:')
            ? (response.headers.get(source.slice(8)) ?? undefined)
            : readPointer(response.body, source);
          ensure(value !== undefined, 'CAPTURA_AUSENTE');
          context[name] = value;
        }
        const correlation = response.headers.get('x-request-id');
        if (
          /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
            correlation ?? '',
          )
        )
          item.correlacao = correlation;
        item.status = 'sucesso';
      } catch (error) {
        item.status = 'falha';
        record.sucesso = false;
        report.sucesso = false;
        if (!item.erro)
          item.erro = {
            codigo: options.signal?.aborted
              ? 'CANCELADO'
              : signal.aborted
                ? 'PRAZO_ESGOTADO'
                : KNOWN_ERRORS.has(error.message)
                  ? error.message
                  : error.name === 'TimeoutError' || error.name === 'AbortError'
                    ? 'TIMEOUT'
                    : 'FALHA_HTTP',
          };
      }
    }
  }
  return report;
}
