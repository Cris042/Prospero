import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import ExcelJS from 'exceljs';
import { runWorkbook } from './index.mjs';
import { compile } from './contract.mjs';
import { execute } from './executor.mjs';
import { readWorkbook } from './workbook.mjs';

const STEP = [
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
const RESULT = ['cenario', 'passo', 'status', 'headers', 'corpo'];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function cli(args, onChild) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [
      new URL('./cli.mjs', import.meta.url).pathname,
      ...args,
    ]);
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => child.kill('SIGKILL'), 20000);
    child.stdout.on('data', (data) => {
      stdout += data;
    });
    child.stderr.on('data', (data) => {
      stderr += data;
    });
    child.once('error', reject);
    child.once('close', (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal, stdout, stderr });
    });
    onChild?.(child);
  });
}

function step(id, overrides = {}) {
  return {
    cenario: 'CenarioA',
    ordem: 1,
    id,
    servico: 'fo',
    metodo: 'GET',
    caminho: '/api/v1/recurso',
    ...overrides,
  };
}

function result(passo, overrides = {}) {
  return { cenario: 'CenarioA', passo, status: 200, ...overrides };
}

async function fixture(t, { carga = [], passos = [], resultados = [], alter } = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'prospero-runner-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const workbook = new ExcelJS.Workbook();
  for (const [name, headers, rows] of [
    ['Carga', STEP, carga],
    ['Passos', STEP, passos],
    ['Resultados', RESULT, resultados],
  ]) {
    const sheet = workbook.addWorksheet(name);
    sheet.addRow(headers);
    for (const row of rows) sheet.addRow(headers.map((key) => row[key] ?? null));
  }
  alter?.(workbook);
  const path = join(dir, 'cenario.xlsx');
  await workbook.xlsx.writeFile(path);
  return path;
}

async function server(t, handler) {
  const calls = [];
  const http = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const call = {
      method: req.method,
      url: req.url,
      headers: req.headers,
      body: Buffer.concat(chunks).toString(),
    };
    calls.push(call);
    try {
      await handler(call, res, calls);
    } catch {
      res.writeHead(500).end();
    }
  });
  await new Promise((resolve) => http.listen(0, '127.0.0.1', resolve));
  t.after(
    () =>
      new Promise((resolve) => {
        http.close(resolve);
        http.closeAllConnections();
      }),
  );
  const config = { servicos: { fo: { origem: `http://127.0.0.1:${http.address().port}` } } };
  return { config, calls };
}

function json(res, status, body, headers = {}) {
  res.writeHead(status, { 'content-type': 'application/json', ...headers });
  res.end(JSON.stringify(body));
}

async function invalid(path, config, options) {
  await assert.rejects(runWorkbook(path, config, options), (error) => {
    assert.match(error.message, /^[A-Z][A-Z0-9_]*(?::.*)?$/);
    assert.notEqual(error.message, 'P03_NAO_IMPLEMENTADO');
    assert.ok(
      !['PARSER_TIMEOUT', 'PARSER_FALHOU', 'ARQUIVO_INACESSIVEL'].includes(error.message),
      `Falha de infraestrutura não comprova validação: ${error.message}`,
    );
    return true;
  });
}

test('carga POST encadeia IDs e headers; JSON preserva tipos e UUIDs', async (t) => {
  const { config, calls } = await server(t, (call, res) => {
    if (call.url === '/api/v1/recurso')
      return json(
        res,
        201,
        { id: 'id-novo', quantidade: 7, ativo: true },
        { 'x-request-id': 'correlacao-local' },
      );
    return json(res, 200, { ok: true, vazio: null }, { 'x-estado': 'pronto' });
  });
  const path = await fixture(t, {
    carga: [
      step('Preparar', {
        metodo: 'POST',
        corpo: '{"execucao":"${execucao}","cenario":"${cenario}"}',
        capturas:
          '{"id":"/id","qtd":"/quantidade","ativo":"/ativo","correlacao":"@header:x-request-id"}',
      }),
    ],
    passos: [
      step('Consultar', {
        metodo: 'POST',
        caminho: '/api/v1/recurso/${id}',
        headers: '{"x-correlacao":"${correlacao}"}',
        corpo: '{"quantidade":"${qtd}","ativo":"${ativo}","texto":"id=${id}"}',
      }),
    ],
    resultados: [
      result('Preparar', { status: 201, corpo: '{"/quantidade":7,"/ativo":true}' }),
      result('Consultar', {
        headers: '{"X-Estado":"pronto"}',
        corpo: '{"/ok":true,"/vazio":null}',
      }),
    ],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, true);
  assert.match(report.execucao, UUID);
  assert.equal(calls.length, 2);
  const prepared = JSON.parse(calls[0].body);
  assert.match(prepared.execucao, UUID);
  assert.match(prepared.cenario, UUID);
  assert.equal(calls[1].url, '/api/v1/recurso/id-novo');
  assert.equal(calls[1].headers['x-correlacao'], 'correlacao-local');
  assert.deepEqual(JSON.parse(calls[1].body), { quantidade: 7, ativo: true, texto: 'id=id-novo' });
});

test('polling limitado observa conclusão e publica captura só ao concluir', async (t) => {
  const { config, calls } = await server(t, (call, res, all) => {
    if (call.url === '/api/v1/recurso')
      return json(res, 200, { estado: all.length < 3 ? 'pendente' : 'concluido', id: 'final' });
    return json(res, 200, { ok: true });
  });
  const path = await fixture(t, {
    passos: [
      step('Aguardar', { capturas: '{"id":"/id"}', intervaloMs: 50, prazoMs: 1000 }),
      step('Ler', { ordem: 2, caminho: '/api/v1/recurso/${id}' }),
    ],
    resultados: [result('Aguardar', { corpo: '{"/estado":"concluido"}' }), result('Ler')],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, true);
  assert.equal(report.cenarios[0].passos[0].tentativas, 3);
  assert.equal(calls[3].url, '/api/v1/recurso/final');
});

test('cenários repetem isolados, com seleção e UUIDs novos', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, { ok: true }));
  const path = await fixture(t, {
    passos: ['CenarioA', 'CenarioB'].map((cenario) =>
      step('Executar', {
        cenario,
        metodo: 'POST',
        corpo: '{"execucao":"${execucao}","cenario":"${cenario}"}',
      }),
    ),
    resultados: ['CenarioA', 'CenarioB'].map((cenario) => result('Executar', { cenario })),
  });
  const first = await runWorkbook(path, config, { scenario: 'CenarioB' });
  const second = await runWorkbook(path, config, { scenario: 'CenarioB' });
  assert.equal(first.sucesso, true);
  assert.equal(second.sucesso, true);
  assert.deepEqual(
    first.cenarios.map((c) => c.cenario),
    ['CenarioB'],
  );
  assert.equal(calls.length, 2);
  assert.notEqual(first.execucao, second.execucao);
  assert.notEqual(JSON.parse(calls[0].body).cenario, JSON.parse(calls[1].body).cenario);
});

test('erro em cenário não selecionado invalida tudo antes de HTTP', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
  const path = await fixture(t, {
    passos: [
      step('Valido'),
      step('Invalido', { cenario: 'CenarioB', caminho: 'https://alvo.invalid/api/v1/teste' }),
    ],
    resultados: [result('Valido'), result('Invalido', { cenario: 'CenarioB' })],
  });
  await invalid(path, config, { scenario: 'CenarioA' });
  assert.equal(calls.length, 0);
});

for (const caminho of [
  'https://alvo.invalid/api/v1/x',
  '//alvo.invalid/api/v1/x',
  '/api/v1/../segredo',
  '/api/v1/a/../../segredo',
  '/api/v1/%2e%2e/segredo',
  '/api/v1/a\\b',
]) {
  test(`caminho perigoso recusado antes de HTTP: ${caminho}`, async (t) => {
    const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
    const path = await fixture(t, {
      passos: [step('Ler', { caminho })],
      resultados: [result('Ler')],
    });
    await invalid(path, config);
    assert.equal(calls.length, 0);
  });
}

for (const [name, alter] of [
  [
    'fórmula com cache',
    (w) => {
      w.getWorksheet('Passos').getCell('F2').value = {
        formula: '"/api/v1/recurso"',
        result: '/api/v1/recurso',
      };
    },
  ],
  [
    'cabeçalho diferente',
    (w) => {
      w.getWorksheet('Resultados').getCell('A1').value = 'cenarioErrado';
    },
  ],
  [
    'aba adicional',
    (w) => {
      w.addWorksheet('Extra');
    },
  ],
  [
    'célula mesclada',
    (w) => {
      w.getWorksheet('Passos').mergeCells('F2:G2');
    },
  ],
]) {
  test(`${name} invalida workbook antes de HTTP`, async (t) => {
    const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
    const path = await fixture(t, { passos: [step('Ler')], resultados: [result('Ler')], alter });
    await invalid(path, config);
    assert.equal(calls.length, 0);
  });
}

test('referência futura é recusada sem efeitos de preparação', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, { id: 'x' }));
  const path = await fixture(t, {
    carga: [step('Preparar', { metodo: 'POST', corpo: '{"id":"${id}"}' })],
    passos: [step('Ler', { capturas: '{"id":"/id"}' })],
    resultados: [result('Preparar'), result('Ler')],
  });
  await invalid(path, config);
  assert.equal(calls.length, 0);
});

test('JSON com inteiro inseguro é recusado antes de HTTP', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
  const path = await fixture(t, {
    passos: [step('Criar', { metodo: 'POST', corpo: '{"id":9007199254740993}' })],
    resultados: [result('Criar')],
  });
  await invalid(path, config);
  assert.equal(calls.length, 0);
});

test('204 aceita resposta vazia', async (t) => {
  const { config } = await server(t, (_, res) => res.writeHead(204).end());
  const path = await fixture(t, {
    passos: [step('Remover', { metodo: 'DELETE' })],
    resultados: [result('Remover', { status: 204 })],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, true);
});

test('divergência tipada interrompe cenário e continua cenário seguinte', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, { valor: '7' }));
  const path = await fixture(t, {
    passos: [step('Falhar'), step('Pular', { ordem: 2 }), step('Seguir', { cenario: 'CenarioB' })],
    resultados: [
      result('Falhar', { corpo: '{"/valor":7}' }),
      result('Pular'),
      result('Seguir', { cenario: 'CenarioB' }),
    ],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, false);
  assert.deepEqual(
    report.cenarios[0].passos.map((p) => p.status),
    ['falha', 'nao-executado'],
  );
  assert.equal(report.cenarios[1].sucesso, true);
  assert.equal(calls.length, 2);
});

test('campo ausente difere de null em asserção JSON pointer', async (t) => {
  const { config } = await server(t, (_, res) => json(res, 200, {}));
  const path = await fixture(t, {
    passos: [step('Ler')],
    resultados: [result('Ler', { corpo: '{"/ausente":null}' })],
  });
  assert.equal((await runWorkbook(path, config)).sucesso, false);
});

test('redirecionamento não é seguido mesmo para origem permitida', async (t) => {
  const { config, calls } = await server(t, (_, res) =>
    res.writeHead(302, { location: '/api/v1/segredo' }).end(),
  );
  const path = await fixture(t, {
    passos: [step('Ler')],
    resultados: [result('Ler', { status: 302 })],
  });
  assert.equal((await runWorkbook(path, config)).sucesso, false);
  assert.equal(calls.length, 1);
});

test('resposta excessiva falha sem expor conteúdo', async (t) => {
  const secret = 'TOKEN_SENTINELA_NAO_PUBLICAR';
  const { config } = await server(t, (_, res) => res.writeHead(200).end(secret.repeat(50000)));
  const path = await fixture(t, { passos: [step('Ler')], resultados: [result('Ler')] });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, false);
  assert.equal(JSON.stringify(report).includes(secret), false);
});

test('deadline de chamada termina resposta que nunca chega', { timeout: 20000 }, async (t) => {
  const { config, calls } = await server(t, () => {});
  const path = await fixture(t, { passos: [step('Ler')], resultados: [result('Ler')] });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, false);
  assert.equal(calls.length, 1);
});

test('polling que nunca converge encerra no prazo sem executar próximo passo', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, { pronto: false }));
  const path = await fixture(t, {
    passos: [step('Esperar', { intervaloMs: 50, prazoMs: 150 }), step('Pular', { ordem: 2 })],
    resultados: [result('Esperar', { corpo: '{"/pronto":true}' }), result('Pular')],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, false);
  assert.equal(report.cenarios[0].passos[1].status, 'nao-executado');
  assert.ok(calls.length >= 1 && calls.length <= 4);
});

test('credencial externa vai ao destino e não aparece no relatório de falha', async (t) => {
  const secret = 'TOKEN_SENTINELA_NAO_PUBLICAR';
  const { config, calls } = await server(t, (_, res) =>
    json(res, 401, { segredo: secret }, { 'x-segredo': secret }),
  );
  config.servicos.fo.headers = { Authorization: `Bearer ${secret}` };
  const path = await fixture(t, {
    passos: [step('Ler')],
    resultados: [
      result('Ler', { headers: '{"x-segredo":"esperado"}', corpo: '{"/segredo":"esperado"}' }),
    ],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, false);
  assert.equal(calls[0].headers.authorization, `Bearer ${secret}`);
  assert.equal(JSON.stringify(report).includes(secret), false);
});

test('arquivo corrompido rejeita com código controlado', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'prospero-runner-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const path = join(dir, 'corrompido.xlsx');
  await writeFile(path, 'Nao e um ZIP');
  const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
  await invalid(path, config);
  assert.equal(calls.length, 0);
});

test('Carga precede Passos e cada aba respeita ordem declarada', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
  const path = await fixture(t, {
    carga: [
      step('Segundo', { ordem: 2, caminho: '/api/v1/segundo' }),
      step('Primeiro', { caminho: '/api/v1/primeiro' }),
    ],
    passos: [
      step('Quarto', { ordem: 2, caminho: '/api/v1/quarto' }),
      step('Terceiro', { caminho: '/api/v1/terceiro' }),
    ],
    resultados: ['Primeiro', 'Segundo', 'Terceiro', 'Quarto'].map((id) => result(id)),
  });
  assert.equal((await runWorkbook(path, config)).sucesso, true);
  assert.deepEqual(
    calls.map((call) => call.url),
    ['/api/v1/primeiro', '/api/v1/segundo', '/api/v1/terceiro', '/api/v1/quarto'],
  );
});

for (const [name, patch] of [
  ['GET com corpo', { corpo: '{"valor":1}' }],
  ['header Authorization na planilha', { headers: '{"Authorization":"Bearer segredo"}' }],
  ['serviço desconhecido', { servico: 'desconhecido' }],
  ['polling de mutação', { metodo: 'POST', intervaloMs: 50, prazoMs: 200 }],
  ['intervalo abaixo do mínimo', { intervaloMs: 49, prazoMs: 200 }],
  ['prazo acima do máximo', { intervaloMs: 50, prazoMs: 30001 }],
  ['célula maior que limite', { corpo: ' '.repeat(16385) }],
  ['JSON profundo', { metodo: 'POST', corpo: '['.repeat(22) + '0' + ']'.repeat(22) }],
]) {
  test(`${name} falha antes de HTTP`, async (t) => {
    const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
    const path = await fixture(t, { passos: [step('Ler', patch)], resultados: [result('Ler')] });
    await invalid(path, config);
    assert.equal(calls.length, 0);
  });
}

for (const origem of [
  'http://usuario:senha@127.0.0.1:8080',
  'http://127.0.0.1:8080/base',
  'http://127.0.0.1:8080?x=1',
  'http://127.0.0.1:8080#frag',
]) {
  test(`origem inválida recusada: ${origem}`, async (t) => {
    const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
    config.servicos.fo.origem = origem;
    const path = await fixture(t, { passos: [step('Ler')], resultados: [result('Ler')] });
    await invalid(path, config);
    assert.equal(calls.length, 0);
  });
}

test('cancelamento interrompe polling e impede novos passos', { timeout: 15000 }, async (t) => {
  const controller = new AbortController();
  const { config, calls } = await server(t, (_, res) => {
    json(res, 200, { pronto: false });
    controller.abort();
  });
  const path = await fixture(t, {
    passos: [step('Esperar', { intervaloMs: 100, prazoMs: 1000 }), step('Pular', { ordem: 2 })],
    resultados: [result('Esperar', { corpo: '{"/pronto":true}' }), result('Pular')],
  });
  // Cancelamento pode rejeitar a execução ou produzir relatório de falha; nunca iniciar novo HTTP.
  try {
    assert.equal((await runWorkbook(path, config, { signal: controller.signal })).sucesso, false);
  } catch (error) {
    assert.match(error.message, /ABORT|CANCEL/i);
  }
  assert.equal(calls.length, 1);
});

test('valor capturado perigoso no caminho falha antes da próxima chamada', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, { id: '../segredo' }));
  const path = await fixture(t, {
    carga: [step('Preparar', { metodo: 'POST', capturas: '{"id":"/id"}' })],
    passos: [step('Ler', { caminho: '/api/v1/recurso/${id}' })],
    resultados: [result('Preparar'), result('Ler')],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, false);
  assert.equal(calls.length, 1);
});

test('CLI publica JSON e códigos 0, 1 e 2 sem credenciais', { timeout: 45000 }, async (t) => {
  const secret = 'CREDENCIAL_CLI_SENTINELA';
  let status = 200;
  const { config } = await server(t, (_, res) => json(res, status, { segredo: secret }));
  config.servicos.fo.headers = { Authorization: `Bearer ${secret}` };
  const path = await fixture(t, { passos: [step('Ler')], resultados: [result('Ler')] });
  const configPath = `${path}.json`;
  await writeFile(configPath, JSON.stringify(config));
  const success = await cli([path, '--config', configPath]);
  assert.equal(success.code, 0);
  assert.equal(JSON.parse(success.stdout).sucesso, true);
  status = 500;
  const failure = await cli([path, '--config', configPath]);
  assert.equal(failure.code, 1);
  assert.equal(JSON.parse(failure.stdout).sucesso, false);
  const invalidArgs = await cli([path, '--config', configPath, '--desconhecido', secret]);
  assert.equal(invalidArgs.code, 2);
  assert.equal(JSON.parse(invalidArgs.stderr).sucesso, false);
  for (const output of [success, failure, invalidArgs]) {
    assert.equal(`${output.stdout}${output.stderr}`.includes(secret), false);
  }
});

for (const [signal, code] of [
  ['SIGINT', 130],
  ['SIGTERM', 143],
]) {
  test(`CLI ${signal} cancela chamada e retorna ${code}`, { timeout: 20000 }, async (t) => {
    let child;
    const { config, calls } = await server(t, () => {
      child.kill(signal);
    });
    const path = await fixture(t, {
      passos: [step('Esperar'), step('Pular', { ordem: 2 })],
      resultados: [result('Esperar'), result('Pular')],
    });
    const configPath = `${path}.json`;
    await writeFile(configPath, JSON.stringify(config));
    const output = await cli([path, '--config', configPath], (process) => {
      child = process;
    });
    assert.equal(output.code, code);
    assert.equal(output.signal, null);
    assert.equal(calls.length, 1);
    const report = JSON.parse(output.stdout || output.stderr);
    assert.equal(report.sucesso, false);
  });
}

for (const lexeme of ['9007199254740991.1', '1e-400', '1.0000000000000001']) {
  test(`lexema numérico impreciso ${lexeme} falha antes de HTTP`, async (t) => {
    const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
    const path = await fixture(t, {
      passos: [step('Criar', { metodo: 'POST', corpo: `{"valor":${lexeme}}` })],
      resultados: [result('Criar')],
    });
    await invalid(path, config);
    assert.equal(calls.length, 0);
  });
}

test('expansão de capturas respeita orçamento antes de serializar POST', async (t) => {
  const { config, calls } = await server(t, (_, res) =>
    json(res, 200, { valor: 'x'.repeat(100000) }),
  );
  const path = await fixture(t, {
    passos: [
      step('Capturar', { capturas: '{"large":"/valor"}' }),
      step('Expandir', {
        ordem: 2,
        metodo: 'POST',
        corpo: JSON.stringify(Array(100).fill('${large}')),
      }),
    ],
    resultados: [result('Capturar'), result('Expandir')],
  });
  const report = await runWorkbook(path, config);
  assert.equal(report.sucesso, false);
  assert.equal(calls.length, 1);
  assert.equal(report.cenarios[0].passos[1].erro.codigo, 'LIMITE_INTERPOLACAO');
});

test('ZIP recusa entrada expandida acima de 2 MiB antes de HTTP', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
  const path = await fixture(t, {
    passos: [step('Ler')],
    resultados: [result('Ler')],
    alter(workbook) {
      workbook.getWorksheet('Passos').getCell('G2').value = 'x'.repeat(2 * 1024 * 1024);
    },
  });
  await assert.rejects(runWorkbook(path, config), { message: 'ZIP_INVALIDO_OU_LIMITE' });
  assert.equal(calls.length, 0);
});

test('ZIP recusa mais de 128 entradas antes de HTTP', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
  const path = await fixture(t, {
    passos: [step('Ler')],
    resultados: [result('Ler')],
    alter(workbook) {
      for (let index = 0; index < 130; index++) workbook.addWorksheet(`Extra${index}`);
    },
  });
  await assert.rejects(runWorkbook(path, config), { message: 'ZIP_INVALIDO_OU_LIMITE' });
  assert.equal(calls.length, 0);
});

function preparacao(overrides = {}) {
  return step('Preparar', {
    servico: 'preparacao-risco',
    metodo: 'PUT',
    caminho: '/api/v1/preparacoes-risco/contas/CONTA-002',
    headers: '{"content-type":"application/json"}',
    corpo: '{"saldo":"100.00","custodia":{"ALFA3":10},"execucao":"${execucao}"}',
    intervaloMs: 50,
    prazoMs: 1000,
    ...overrides,
  });
}

function configurarPreparacao(config) {
  config.servicos['preparacao-risco'] = { ...config.servicos.fo };
  return config;
}

test('preparação opt-in repete somente reserva viva e mantém corpo até sucesso', async (t) => {
  const { config, calls } = await server(t, (_, res, all) =>
    all.length < 3
      ? json(res, 409, { codigo: 'RESERVA_VIVA' })
      : json(res, 200, { conta: 'CONTA-002', saldo: '100.00' }),
  );
  const path = await fixture(t, {
    passos: [preparacao()],
    resultados: [result('Preparar', { corpo: '{"/saldo":"100.00"}' })],
  });
  const report = await runWorkbook(path, configurarPreparacao(config));
  assert.equal(report.sucesso, true);
  assert.equal(report.cenarios[0].passos[0].tentativas, 3);
  assert.equal(calls.length, 3);
  assert.ok(calls.every((call) => call.method === 'PUT' && call.body === calls[0].body));
});

function planoPreparacao(config, overrides = {}, expectedOverrides = {}) {
  const request = preparacao(overrides);
  const expected = result('Preparar', { corpo: '{"/saldo":"100.00"}', ...expectedOverrides });
  return compile(
    {
      Carga: [STEP],
      Passos: [STEP, STEP.map((key) => request[key] ?? '')],
      Resultados: [RESULT, RESULT.map((key) => expected[key] ?? '')],
    },
    configurarPreparacao(config),
  );
}

for (const [name, patch, expected, error = /POLLING_INVALIDO/] of [
  ['serviço diferente', { servico: 'fo' }],
  ['POST', { metodo: 'POST' }],
  ['PATCH', { metodo: 'PATCH' }],
  ['DELETE', { metodo: 'DELETE' }],
  ['conta da tela', { caminho: '/api/v1/preparacoes-risco/contas/CONTA-001' }],
  ['outra conta', { caminho: '/api/v1/preparacoes-risco/contas/CONTA-004' }],
  ['sufixo de conta', { caminho: '/api/v1/preparacoes-risco/contas/CONTA-0023' }],
  ['barra final', { caminho: '/api/v1/preparacoes-risco/contas/CONTA-002/' }],
  [
    'newline final',
    { caminho: '/api/v1/preparacoes-risco/contas/CONTA-002\n' },
    undefined,
    /CAMINHO_INVALIDO/,
  ],
  ['query', { caminho: '/api/v1/preparacoes-risco/contas/CONTA-002?x=1' }],
  ['outro recurso', { caminho: '/api/v1/ordens' }],
  ['conta interpolada', { caminho: '/api/v1/preparacoes-risco/contas/${cenario}' }],
  ['status esperado 409', {}, { status: 409 }],
  ['intervalo abaixo do mínimo', { intervaloMs: 49 }],
  ['prazo acima do máximo', { prazoMs: 30001 }],
]) {
  test(`espera de preparação recusa ${name} antes de HTTP`, async (t) => {
    const { config, calls } = await server(t, (_, res) => json(res, 200, {}));
    assert.throws(() => planoPreparacao(config, patch, expected), error);
    assert.equal(calls.length, 0);
  });
}

for (const [name, status, body, raw = false] of [
  ['200 divergente', 200, { saldo: '999.00' }],
  ['409 código diferente', 409, { codigo: 'OUTRO_CONFLITO' }],
  ['409 código ausente', 409, {}],
  ['409 array', 409, [{ codigo: 'RESERVA_VIVA' }]],
  ['409 null', 409, null],
  ['409 JSON inválido', 409, '{"codigo":', true],
  ['400', 400, { codigo: 'RESERVA_VIVA' }],
  ['401', 401, { codigo: 'RESERVA_VIVA' }],
  ['403', 403, { codigo: 'RESERVA_VIVA' }],
  ['500', 500, { codigo: 'RESERVA_VIVA' }],
]) {
  test(`espera de preparação não repete ${name}`, async (t) => {
    const { config, calls } = await server(t, (_, res) => {
      if (raw) res.writeHead(status, { 'content-type': 'application/json' }).end(body);
      else json(res, status, body);
    });
    const report = await execute(planoPreparacao(config), config);
    assert.equal(report.sucesso, false);
    assert.equal(calls.length, 1);
    assert.equal(report.cenarios[0].passos[0].tentativas, 1);
  });
}

test('preparação CONTA-003 também aceita a espera explícita', async (t) => {
  const { config, calls } = await server(t, (_, res, all) =>
    all.length === 1
      ? json(res, 409, { codigo: 'RESERVA_VIVA' })
      : json(res, 200, { saldo: '100.00' }),
  );
  const report = await execute(
    planoPreparacao(config, { caminho: '/api/v1/preparacoes-risco/contas/CONTA-003' }),
    config,
  );
  assert.equal(report.sucesso, true);
  assert.equal(calls.length, 2);
});

test('preparação sem opt-in mantém uma única tentativa e 409 esperado é sucesso', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 409, { codigo: 'RESERVA_VIVA' }));
  const single = { intervaloMs: '', prazoMs: '' };
  const failed = await execute(planoPreparacao(config, single), config);
  assert.equal(failed.sucesso, false);
  assert.equal(calls.length, 1);
  const blocked = await execute(
    planoPreparacao(config, single, { status: 409, corpo: '{}' }),
    config,
  );
  assert.equal(blocked.sucesso, true);
  assert.equal(calls.length, 2);
});

test('espera de preparação esgota prazo sem executar próximo passo', async (t) => {
  const { config, calls } = await server(t, (_, res) => json(res, 409, { codigo: 'RESERVA_VIVA' }));
  const plan = planoPreparacao(config, { prazoMs: 250 });
  plan[0].steps.push({ ...plan[0].steps[0], id: 'NaoExecutar', path: '/api/v1/nunca' });
  const report = await execute(plan, config);
  assert.equal(report.sucesso, false);
  assert.equal(report.cenarios[0].passos[0].erro.codigo, 'POLLING_ESGOTADO');
  assert.equal(report.cenarios[0].passos[1].status, 'nao-executado');
  assert.ok(calls.length >= 1 && calls.length <= 5);
  assert.ok(calls.every((call) => call.url === '/api/v1/preparacoes-risco/contas/CONTA-002'));
});

test('cancelamento interrompe espera de preparação sem nova mutação', async (t) => {
  const controller = new AbortController();
  const { config, calls } = await server(t, (_, res) => {
    json(res, 409, { codigo: 'RESERVA_VIVA' });
    controller.abort();
  });
  const report = await execute(planoPreparacao(config), config, { signal: controller.signal });
  assert.equal(report.sucesso, false);
  assert.equal(calls.length, 1);
});

test('timeout de preparação não autoriza uma nova tentativa', async (t) => {
  const { config, calls } = await server(t, () => {});
  const report = await execute(planoPreparacao(config, { prazoMs: 150 }), config);
  assert.equal(report.sucesso, false);
  assert.equal(calls.length, 1);
});

test('falha de transporte de preparação não autoriza uma nova tentativa', async (t) => {
  const { config, calls } = await server(t, (_, res) => res.destroy());
  const report = await execute(planoPreparacao(config), config);
  assert.equal(report.sucesso, false);
  assert.equal(calls.length, 1);
});

test('planilha matching compila stops com espera observável, conservação e rejeições', async () => {
  const servicos = Object.fromEntries(
    ['ordens-a', 'ordens-b', 'risco-a', 'risco-b', 'preparacao-risco'].map((nome) => [
      nome,
      { origem: 'http://127.0.0.1:8081' },
    ]),
  );
  const sheets = await readWorkbook(new URL('./exemplos/matching.xlsx', import.meta.url).pathname);
  const plan = compile(sheets, { servicos });
  for (const name of [
    'StopExecutaAposGatilho',
    'StopParcialCancelaSaldo',
    'StopLimitRepousaEViraPassiva',
    'StopVendaConsomeCustodia',
  ]) {
    const scenario = plan.find((c) => c.name === name);
    assert.ok(scenario, name);
    const pending = scenario.steps.find((s) => s.expected.body['/estado'] === 'AGUARDANDO_GATILHO');
    assert.equal(pending.method, 'GET');
    assert.equal(pending.deadline, 15000);
    assert.ok(scenario.steps.some((s) => s.expected.body['/saldoReservado'] === '0.00'));
  }
  const invalid = plan.find((c) => c.name === 'StopsInvalidos');
  assert.equal(invalid.steps.filter((s) => s.expected.status === 422).length, 4);
  assert.equal(invalid.steps.at(-1).expected.body['/saldoReservado'], '0.00');
});

test('planilhas financeiras optam em preparação 200 e preservam a prova de 409', async () => {
  let bloqueados = 0;
  for (const name of ['matching', 'risco']) {
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.readFile(new URL(`./exemplos/${name}.xlsx`, import.meta.url).pathname);
    const expectations = new Map();
    workbook.getWorksheet('Resultados').eachRow((row, n) => {
      if (n > 1)
        expectations.set(`${row.getCell(1).value}/${row.getCell(2).value}`, row.getCell(3).value);
    });
    for (const sheetName of ['Carga', 'Passos']) {
      workbook.getWorksheet(sheetName).eachRow((row, n) => {
        if (
          n === 1 ||
          row.getCell(4).value !== 'preparacao-risco' ||
          row.getCell(5).value !== 'PUT'
        )
          return;
        const status = expectations.get(`${row.getCell(1).value}/${row.getCell(3).value}`);
        if (status === 200) {
          assert.equal(row.getCell(10).value, 200);
          assert.equal(row.getCell(11).value, 15000);
        } else {
          bloqueados++;
          assert.equal(status === 409 || status === 403, true);
          assert.equal(row.getCell(10).value ?? '', '');
          assert.equal(row.getCell(11).value ?? '', '');
        }
      });
    }
  }
  assert.equal(bloqueados, 2, 'PreparoBloqueado409 e ContaDaTela403 permanecem sem polling');
});
