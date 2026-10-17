import { LIMITS, ID, ensure, isRecord } from './limits.mjs';

export const STEP_COLUMNS = [
  'cenario',
  'ordem',
  'id',
  'servico',
  'metodo',
  'caminho',
  'headers',
  'corpo',
  'capturas',
  'intervaloMs',
  'prazoMs',
];
export const RESULT_COLUMNS = ['cenario', 'passo', 'status', 'headers', 'corpo'];
const RESERVED = new Set(['__proto__', 'constructor', 'prototype']);
const REF = /\$\{([A-Za-z][A-Za-z0-9_-]{0,63})\}/g;

export function validateJson(value, depth = 0) {
  ensure(depth <= 20, 'JSON_PROFUNDO');
  if (typeof value === 'number') ensure(Number.isSafeInteger(value), 'NUMERO_INSEGURO');
  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      ensure(!RESERVED.has(key), 'CHAVE_PROIBIDA');
      validateJson(child, depth + 1);
    }
  }
}
export function parseExactJson(text) {
  let value;
  try {
    value = JSON.parse(text, (_key, item, context) => {
      if (typeof item === 'number')
        ensure(
          /^-?(?:0|[1-9][0-9]*)$/.test(context.source) && Number.isSafeInteger(item),
          'NUMERO_INSEGURO',
        );
      return item;
    });
  } catch (error) {
    if (error.message === 'NUMERO_INSEGURO') throw error;
    ensure(false, 'JSON_INVALIDO');
  }
  validateJson(value);
  return value;
}
function json(text, fallback) {
  if (text === '' || text === undefined) return fallback;
  ensure(typeof text === 'string', 'JSON_DEVE_SER_TEXTO');
  return parseExactJson(text);
}
function object(text) {
  const value = json(text, {});
  ensure(isRecord(value), 'OBJETO_ESPERADO');
  return value;
}
function references(value, names) {
  if (typeof value === 'string') {
    for (const match of value.matchAll(REF)) ensure(names.has(match[1]), 'REFERENCIA_INVALIDA');
    ensure(!value.replace(REF, '').includes('${'), 'REFERENCIA_INVALIDA');
  } else if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      ensure(!key.includes('${'), 'REFERENCIA_EM_CHAVE');
      references(child, names);
    }
  }
}
function headers(value, external = false) {
  for (const [key, item] of Object.entries(value)) {
    ensure(
      typeof item === 'string' && item.length <= 4096 && !/[\r\n\0]/.test(item),
      'HEADER_INVALIDO',
    );
    ensure(/^[a-z0-9-]{1,64}$/i.test(key), 'HEADER_INVALIDO');
    const name = key.toLowerCase();
    ensure(
      external === 'response' ||
        ['accept', 'content-type'].includes(name) ||
        /^x-[a-z0-9-]+$/.test(name) ||
        (external && name === 'authorization'),
      'HEADER_PROIBIDO',
    );
    ensure(
      external || !['x-api-key', 'x-auth-token', 'x-authorization'].includes(name),
      'CREDENCIAL_EXTERNA',
    );
  }
  ensure(
    new Set(Object.keys(value).map((x) => x.toLowerCase())).size === Object.keys(value).length,
    'HEADER_DUPLICADO',
  );
}
export function pointerValid(pointer) {
  ensure(
    typeof pointer === 'string' &&
      pointer.length <= 256 &&
      (pointer === '' || pointer.startsWith('/')),
    'POINTER_INVALIDO',
  );
  ensure(!/~(?![01])/.test(pointer), 'POINTER_INVALIDO');
  for (const key of pointer.split('/').slice(1))
    ensure(!RESERVED.has(key.replace(/~1/g, '/').replace(/~0/g, '~')), 'POINTER_INVALIDO');
}
export function targetPath(path) {
  ensure(
    typeof path === 'string' &&
      path.length <= 2048 &&
      /^\/api\/v1\/[A-Za-z0-9_/?=&.~-]*$/.test(path) &&
      !path.includes('//') &&
      !path.split(/[/?&=]/).some((x) => x === '.' || x === '..'),
    'CAMINHO_INVALIDO',
  );
  return path;
}
export function configValid(config) {
  ensure(
    isRecord(config) &&
      Object.keys(config).every((k) => k === 'servicos') &&
      isRecord(config.servicos),
    'CONFIG_INVALIDA',
  );
  ensure(
    Object.keys(config.servicos).length > 0 && Object.keys(config.servicos).length <= 20,
    'CONFIG_INVALIDA',
  );
  for (const [name, service] of Object.entries(config.servicos)) {
    ensure(
      ID.test(name) &&
        isRecord(service) &&
        Object.keys(service).every((k) => ['origem', 'headers'].includes(k)),
      'CONFIG_INVALIDA',
    );
    let url;
    try {
      url = new URL(service.origem);
    } catch {
      ensure(false, 'ORIGEM_INVALIDA');
    }
    ensure(
      ['http:', 'https:'].includes(url.protocol) &&
        !url.username &&
        !url.password &&
        url.pathname === '/' &&
        !url.search &&
        !url.hash,
      'ORIGEM_INVALIDA',
    );
    ensure(
      typeof service.origem === 'string' && /^https?:\/\/[^/]+\/?$/.test(service.origem),
      'ORIGEM_INVALIDA',
    );
    ensure(service.headers === undefined || isRecord(service.headers), 'CONFIG_INVALIDA');
    headers(service.headers ?? {}, true);
  }
  return config;
}
function table(rows, columns, sheet) {
  try {
    ensure(
      Array.isArray(rows) &&
        rows.length &&
        rows[0].length === columns.length &&
        columns.every((column, index) => rows[0][index] === column),
      'COLUNAS_INVALIDAS',
    );
  } catch (error) {
    error.aba = sheet;
    error.linha = 1;
    throw error;
  }
  return rows
    .slice(1)
    .map((row, index) => ({ row, line: index + 2 }))
    .filter(({ row }) => row.some((v) => v !== '' && v !== null))
    .map(({ row, line }) => {
      if (row.length > columns.length) {
        const error = new Error('COLUNAS_INVALIDAS');
        error.aba = sheet;
        error.linha = line;
        throw error;
      }
      return {
        ...Object.fromEntries(columns.map((column, index) => [column, row[index] ?? ''])),
        source: { aba: sheet, linha: line },
      };
    });
}
export function compile(sheets, config) {
  let source;
  try {
    return compilePlan(sheets, config, (row) => {
      source = row.source;
    });
  } catch (error) {
    if (source && !error.aba) {
      error.aba = source.aba;
      error.linha = source.linha;
    }
    throw error;
  }
}
function compilePlan(sheets, config, locate) {
  configValid(config);
  const results = new Map();
  for (const row of table(sheets.Resultados, RESULT_COLUMNS, 'Resultados')) {
    locate(row);
    ensure(ID.test(row.cenario) && ID.test(row.passo), 'IDENTIFICADOR_INVALIDO');
    const key = `${row.cenario}/${row.passo}`;
    ensure(!results.has(key), 'RESULTADO_DUPLICADO');
    ensure(
      Number.isInteger(row.status) && row.status >= 100 && row.status <= 599,
      'STATUS_INVALIDO',
    );
    const expectedHeaders = object(row.headers);
    headers(expectedHeaders, 'response');
    // Response headers may include standard fields, not only request headers.
    const body = object(row.corpo);
    for (const pointer of Object.keys(body)) pointerValid(pointer);
    results.set(key, { status: row.status, headers: expectedHeaders, body });
  }
  const scenarios = new Map();
  let count = 0;
  for (const sheet of ['Carga', 'Passos']) {
    const rows = table(sheets[sheet], STEP_COLUMNS, sheet);
    rows.sort((a, b) => a.ordem - b.ordem);
    const orders = new Set();
    for (const row of rows) {
      locate(row);
      ensure(++count <= LIMITS.steps, 'LIMITE_PASSOS');
      ensure(
        ID.test(row.cenario) && ID.test(row.id) && ID.test(row.servico),
        'IDENTIFICADOR_INVALIDO',
      );
      ensure(Object.hasOwn(config.servicos, row.servico), 'SERVICO_DESCONHECIDO');
      ensure(Number.isSafeInteger(row.ordem) && row.ordem > 0, 'ORDEM_INVALIDA');
      const order = `${row.cenario}/${row.ordem}`;
      ensure(!orders.has(order), 'ORDEM_DUPLICADA');
      orders.add(order);
      if (!scenarios.has(row.cenario))
        scenarios.set(row.cenario, {
          name: row.cenario,
          steps: [],
          names: new Set(['execucao', 'cenario']),
          ids: new Set(),
        });
      ensure(scenarios.size <= LIMITS.scenarios, 'LIMITE_CENARIOS');
      const scenario = scenarios.get(row.cenario);
      ensure(!scenario.ids.has(row.id), 'PASSO_DUPLICADO');
      scenario.ids.add(row.id);
      ensure(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(row.metodo), 'METODO_INVALIDO');
      const names = scenario.names;
      references(row.caminho, names);
      targetPath(row.caminho.replace(REF, 'referencia'));
      const requestHeaders = object(row.headers);
      headers(requestHeaders);
      const externalNames = new Set(
        Object.keys(config.servicos[row.servico].headers ?? {}).map((k) => k.toLowerCase()),
      );
      ensure(
        Object.keys(requestHeaders).every((k) => !externalNames.has(k.toLowerCase())),
        'HEADER_EXTERNO_PROTEGIDO',
      );
      const body = json(row.corpo, undefined);
      ensure(row.metodo !== 'GET' || body === undefined, 'GET_COM_CORPO');
      const captures = object(row.capturas);
      const expected = results.get(`${row.cenario}/${row.id}`);
      ensure(expected, 'RESULTADO_AUSENTE');
      results.delete(`${row.cenario}/${row.id}`);
      for (const value of [requestHeaders, body, expected.headers, expected.body])
        references(value, names);
      let interval = 0;
      let deadline = 0;
      if (row.intervaloMs !== '' || row.prazoMs !== '') {
        interval = row.intervaloMs;
        deadline = row.prazoMs;
        ensure(
          row.metodo === 'GET' &&
            Number.isInteger(interval) &&
            interval >= 50 &&
            interval <= 5000 &&
            Number.isInteger(deadline) &&
            deadline >= interval &&
            deadline <= 30000,
          'POLLING_INVALIDO',
        );
      }
      for (const [name, source] of Object.entries(captures)) {
        ensure(ID.test(name) && !names.has(name), 'CAPTURA_INVALIDA');
        ensure(typeof source === 'string', 'CAPTURA_INVALIDA');
        if (source.startsWith('@header:'))
          ensure(/^@header:[a-z0-9-]{1,64}$/i.test(source), 'CAPTURA_INVALIDA');
        else pointerValid(source);
        names.add(name);
      }
      scenario.steps.push({
        id: row.id,
        service: row.servico,
        method: row.metodo,
        path: row.caminho,
        headers: requestHeaders,
        body,
        captures,
        expected,
        interval,
        deadline,
      });
    }
  }
  ensure(scenarios.size > 0 && results.size === 0, 'RESULTADO_ORFAO_OU_SEM_PASSOS');
  return [...scenarios.values()].map(({ name, steps }) => ({ name, steps }));
}

export function resolveValue(value, context, path = false) {
  // Conservative budget before allocating expanded strings/objects: JSON escaping
  // takes at most six bytes per UTF-16 unit; encoded paths at most eighteen.
  let bytes = 0;
  const charge = (size) => {
    bytes += size;
    ensure(bytes <= LIMITS.response, 'LIMITE_INTERPOLACAO');
  };
  const clone = (item) => {
    if (typeof item === 'string') {
      charge(item.length * 6 + 2);
      return item;
    }
    if (Array.isArray(item)) {
      charge(item.length + 2);
      return item.map(clone);
    }
    if (isRecord(item)) {
      charge(2);
      return Object.fromEntries(
        Object.entries(item).map(([key, child]) => {
          charge(key.length * 6 + 4);
          return [key, clone(child)];
        }),
      );
    }
    charge(32);
    return item;
  };
  const visit = (item) => {
    if (typeof item === 'string') {
      const full = /^\$\{([A-Za-z][A-Za-z0-9_-]{0,63})\}$/.exec(item);
      if (full && !path) return clone(context[full[1]]);
      const parts = [];
      let offset = 0;
      for (const match of item.matchAll(REF)) {
        const literal = item.slice(offset, match.index);
        charge(literal.length * 6);
        parts.push(literal);
        const captured = context[match[1]];
        ensure(
          captured !== undefined && captured !== null && typeof captured !== 'object',
          'REFERENCIA_NAO_ESCALAR',
        );
        charge(String(captured).length * (path ? 18 : 6));
        parts.push(path ? encodeURIComponent(String(captured)) : String(captured));
        offset = match.index + match[0].length;
      }
      charge((item.length - offset) * 6 + 2);
      parts.push(item.slice(offset));
      return parts.join('');
    }
    if (Array.isArray(item)) {
      charge(item.length + 2);
      return item.map(visit);
    }
    if (isRecord(item)) {
      charge(2);
      return Object.fromEntries(
        Object.entries(item).map(([key, child]) => {
          charge(key.length * 6 + 4);
          return [key, visit(child)];
        }),
      );
    }
    charge(32);
    return item;
  };
  return visit(value);
}
export function readPointer(value, pointer) {
  for (const key of pointer === ''
    ? []
    : pointer
        .slice(1)
        .split('/')
        .map((k) => k.replace(/~1/g, '/').replace(/~0/g, '~'))) {
    if (value === null || typeof value !== 'object' || !Object.hasOwn(value, key)) return undefined;
    value = value[key];
  }
  return value;
}
