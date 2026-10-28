import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

// Tokens exclusivamente fictícios: o teste não depende de .env nem imprime segredos locais.
process.env.FO_TOKEN_LEITURA = 'token-ficticio-leitura';
process.env.FO_TOKEN_ORDENS = 'token-ficticio-ordens';
process.env.BO_TOKEN_POSICOES = 'token-ficticio-posicoes';
process.env.BO_TOKEN_RAZAO = 'token-ficticio-razao';
const { default: proxy } = await import('./proxy.conf.mjs');
const leitura = proxy['/servicos/front-office/api/v1/ativos'];

function consultar(method, host = 'localhost:4200') {
  const req = { method, headers: { host, authorization: 'Bearer cliente-nao-confiavel' } };
  const res = {
    statusCode: 200,
    end(corpo) {
      this.corpo = corpo;
    },
  };
  const resultado = leitura.bypass(req, res);
  return { req, res, resultado };
}

test('GET/HEAD locais descartam Authorization do navegador e usam token do servidor', () => {
  for (const host of ['localhost:4200', '127.0.0.1:4200']) {
    for (const metodo of ['GET', 'HEAD']) {
      const { req, res, resultado } = consultar(metodo, host);
      assert.equal(resultado, undefined);
      assert.equal(res.statusCode, 200);
      assert.equal(req.headers.authorization, undefined);
    }
  }
  assert.equal(leitura.headers.Authorization, 'Bearer token-ficticio-leitura');
});

test('consulta recusa Host externo, ausente e nomes que apenas começam com localhost', () => {
  for (const host of ['evil.example', 'localhost.evil.example', '127.0.0.1.evil.example', '']) {
    const { res, resultado } = consultar('GET', host);
    assert.equal(resultado, false);
    assert.equal(res.statusCode, 421);
  }
});

test('mercado recusa todos os métodos de escrita antes de encaminhar', () => {
  for (const metodo of ['POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS', 'TRACE', 'CONNECT']) {
    const { res, resultado } = consultar(metodo);
    assert.equal(resultado, false);
    assert.equal(res.statusCode, 403);
  }
});

function consultarPosicoes(
  method,
  url = '/servicos/back-office/api/v1/posicoes',
  host = 'localhost:4200',
) {
  const req = { method, url, headers: { host, authorization: 'Bearer cliente-nao-confiavel' } };
  const res = {
    statusCode: 200,
    end(corpo) {
      this.corpo = corpo;
    },
  };
  return { req, res, resultado: proxy['/servicos/back-office/api/v1/posicoes'].bypass(req, res) };
}

test('posições GET/HEAD usam credencial BO somente no servidor e descartam a do cliente', () => {
  for (const metodo of ['GET', 'HEAD']) {
    for (const caminho of [
      '/servicos/back-office/api/v1/posicoes',
      '/servicos/back-office/api/v1/posicoes?conta=CONTA-999',
    ]) {
      const { req, res, resultado } = consultarPosicoes(metodo, caminho);
      assert.equal(resultado, undefined);
      assert.equal(res.statusCode, 200);
      assert.equal(req.headers.authorization, undefined);
    }
  }
  assert.equal(
    proxy['/servicos/back-office/api/v1/posicoes'].headers.Authorization,
    'Bearer token-ficticio-posicoes',
  );
});

test('posições recusam escrita, Host externo e extensões de rota', () => {
  for (const metodo of ['POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS', 'TRACE', 'CONNECT']) {
    const { res, resultado } = consultarPosicoes(metodo);
    assert.equal(resultado, false);
    assert.equal(res.statusCode, 403);
  }
  for (const host of ['evil.example', 'localhost.evil.example', '']) {
    const { res, resultado } = consultarPosicoes('GET', undefined, host);
    assert.equal(resultado, false);
    assert.equal(res.statusCode, 421);
  }
  for (const caminho of [
    '/servicos/back-office/api/v1/posicoes/admin',
    '/servicos/back-office/api/v1/posicoes-extra',
  ]) {
    const { res, resultado } = consultarPosicoes('GET', caminho);
    assert.equal(resultado, false);
    assert.equal(res.statusCode, 403);
  }
});

test('rota genérica do BO não repassa Authorization recebido do navegador', () => {
  const req = {
    method: 'GET',
    url: '/servicos/back-office/api/v1/sistema',
    headers: { authorization: 'Bearer cliente' },
  };
  const res = { end() {} };
  assert.equal(proxy['/servicos/back-office'].bypass(req, res), undefined);
  assert.equal(req.headers.authorization, undefined);
});

function consultarRazao(rota, method, url, host = 'localhost:4200') {
  const req = { method, url, headers: { host, authorization: 'Bearer cliente-nao-confiavel' } };
  const res = {
    statusCode: 200,
    end(corpo) {
      this.corpo = corpo;
    },
  };
  return { req, res, resultado: proxy[rota].bypass(req, res) };
}
const NEGOCIOS = '/servicos/back-office/api/v1/negocios';
const RAZAO = '/servicos/back-office/api/v1/razao';

test('negócios e razão GET/HEAD usam credencial de leitura BO só no servidor e descartam a do cliente', () => {
  for (const metodo of ['GET', 'HEAD']) {
    for (const [rota, caminho] of [
      [NEGOCIOS, NEGOCIOS],
      [NEGOCIOS, `${NEGOCIOS}?limite=20&cursor=djEucmF6YW8uOS5hYmM`],
      [NEGOCIOS, `${NEGOCIOS}/ALFA3-2026-10-23-1`],
      [NEGOCIOS, `${NEGOCIOS}/nao-e-um-negocio`],
      [RAZAO, RAZAO],
      [RAZAO, `${RAZAO}?limite=100`],
    ]) {
      const { req, res, resultado } = consultarRazao(rota, metodo, caminho);
      assert.equal(resultado, undefined, caminho);
      assert.equal(res.statusCode, 200);
      assert.equal(req.headers.authorization, undefined);
    }
  }
  for (const rota of [NEGOCIOS, RAZAO]) {
    assert.equal(proxy[rota].headers.Authorization, 'Bearer token-ficticio-razao');
  }
});

test('negócios e razão recusam escrita, Host externo e extensões de rota', () => {
  for (const rota of [NEGOCIOS, RAZAO]) {
    for (const metodo of ['POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS', 'TRACE', 'CONNECT']) {
      const { res, resultado } = consultarRazao(rota, metodo, rota);
      assert.equal(resultado, false);
      assert.equal(res.statusCode, 403);
    }
    for (const host of ['evil.example', 'localhost.evil.example', '']) {
      const { res, resultado } = consultarRazao(rota, 'GET', rota, host);
      assert.equal(resultado, false);
      assert.equal(res.statusCode, 421);
    }
  }
  for (const [rota, caminho] of [
    [NEGOCIOS, `${NEGOCIOS}-extra`],
    [NEGOCIOS, `${NEGOCIOS}/ALFA3-2026-10-23-1/lancamentos`],
    [NEGOCIOS, `${NEGOCIOS}/..%2Fposicoes`],
    [NEGOCIOS, `${NEGOCIOS}/`],
    [RAZAO, `${RAZAO}/admin`],
    [RAZAO, `${RAZAO}-extra`],
  ]) {
    const { res, resultado } = consultarRazao(rota, 'GET', caminho);
    assert.equal(resultado, false, caminho);
    assert.equal(res.statusCode, 403);
  }
});

test('data de movimento descarta Authorization e nunca recebe token administrativo no proxy', () => {
  for (const method of ['POST', 'GET', 'HEAD', 'PUT', 'DELETE']) {
    const req = {
      method,
      url: '/servicos/back-office/api/v1/data-movimento',
      headers: { authorization: 'Bearer credencial-administrativa-do-cliente' },
    };
    const res = { end() {} };
    assert.equal(proxy['/servicos/back-office'].bypass(req, res), undefined);
    assert.equal(req.headers.authorization, undefined);
    assert.equal(proxy['/servicos/back-office'].headers?.Authorization, undefined);
  }
  for (const arquivo of ['proxy.conf.mjs', 'nginx.template']) {
    const fonte = readFileSync(new URL(arquivo, import.meta.url), 'utf8');
    assert.doesNotMatch(fonte, /BO_TOKEN_(?:ADMIN|LIQUIDACAO|RELOGIO|MOVIMENTO)/);
    assert.doesNotMatch(fonte, /liquidacao:administrar/);
  }
});

const ATUALIZACOES = '/servicos/front-office/api/v1/atualizacoes';
function upgradeAtualizacoes(opcoes = {}) {
  const req = {
    method: 'GET',
    url: ATUALIZACOES,
    headers: {
      host: 'localhost:4200',
      origin: 'http://localhost:4200',
      upgrade: 'websocket',
      authorization: 'Bearer cliente-nao-confiavel',
      ...opcoes.headers,
    },
    ...opcoes,
  };
  const res = { statusCode: 200, end() {} };
  return { req, res, resultado: proxy[ATUALIZACOES].bypass(req, res) };
}

test('WS atualizações usa rota exata e token de ordens do servidor descartando Authorization cliente', () => {
  assert.ok(proxy[ATUALIZACOES]);
  assert.equal(proxy[ATUALIZACOES].ws, true);
  assert.equal(proxy[ATUALIZACOES].headers.Authorization, 'Bearer token-ficticio-ordens');
  const { req, res, resultado } = upgradeAtualizacoes();
  assert.equal(resultado, undefined);
  assert.equal(res.statusCode, 200);
  assert.equal(req.headers.authorization, undefined);
});

test('WS atualizações recusa método rota query Origin e Host divergentes antes do upgrade', () => {
  for (const opcoes of [
    { method: 'POST' },
    { method: 'HEAD' },
    { url: `${ATUALIZACOES}/admin` },
    { url: `${ATUALIZACOES}-extra` },
    { url: `${ATUALIZACOES}?token=cliente` },
    { headers: { host: 'localhost:4200', origin: 'http://localhost:4201', upgrade: 'websocket' } },
    { headers: { host: 'localhost:4200', origin: 'http://127.0.0.1:4200', upgrade: 'websocket' } },
    { headers: { host: 'evil.example', origin: 'http://evil.example', upgrade: 'websocket' } },
    { headers: { host: 'localhost:4200', upgrade: 'websocket' } },
    { headers: { host: 'localhost:4200', origin: 'null', upgrade: 'websocket' } },
    { headers: { host: 'localhost:4200', origin: 'http://localhost:4200' } },
  ]) {
    const { res, resultado } = upgradeAtualizacoes(opcoes);
    assert.equal(resultado, false);
    assert.ok(res.statusCode >= 400);
  }
});

test('rotas genéricas não encaminham upgrade autenticado', () => {
  for (const rota of [
    '/servicos/front-office',
    '/servicos/middle-office',
    '/servicos/back-office',
  ]) {
    assert.notEqual(proxy[rota].ws, true);
    const req = {
      method: 'GET',
      url: `${rota}/api/v1/atualizacoes`,
      headers: { upgrade: 'websocket' },
    };
    const res = { statusCode: 200, end() {} };
    assert.equal(proxy[rota].bypass(req, res), false);
    assert.ok(res.statusCode >= 400);
  }
});

const ID_ORDEM = '0a1b2c3d-0a1b-4c3d-8a1b-0a1b2c3d4e5f';
function ordensDev(method, url, origin = 'http://localhost:4200', host = 'localhost:4200') {
  const req = {
    method,
    url,
    headers: { host, origin, authorization: 'Bearer cliente-nao-confiavel' },
  };
  const res = { statusCode: 200, end() {} };
  const chave = Object.keys(proxy).find((k) => proxy[k].bypass && k.includes('ordens'));
  return { req, res, resultado: proxy[chave].bypass(req, res) };
}

test('cancelamento aceita GET e PUT locais; PUT exige Origin local e POST/DELETE são recusados', () => {
  const rota = `/servicos/front-office/api/v1/ordens/${ID_ORDEM}/cancelamento`;
  for (const metodo of ['GET', 'PUT']) {
    const { req, res, resultado } = ordensDev(metodo, rota);
    assert.equal(resultado, undefined, metodo);
    assert.equal(res.statusCode, 200);
    assert.equal(req.headers.authorization, undefined);
  }
  for (const [metodo, origem] of [
    ['PUT', 'https://evil.example'],
    ['PUT', ''],
    ['POST', 'http://localhost:4200'],
    ['DELETE', 'http://localhost:4200'],
  ]) {
    const { res, resultado } = ordensDev(metodo, rota, origem);
    assert.equal(resultado, false, `${metodo} ${origem}`);
    assert.equal(res.statusCode, 403);
  }
});

test('PUT só alcança /cancelamento: ordens, trajetória e ordens-oco continuam sem PUT', () => {
  for (const caminho of [
    `/servicos/front-office/api/v1/ordens/${ID_ORDEM}`,
    `/servicos/front-office/api/v1/ordens/${ID_ORDEM}/trajetoria`,
    '/servicos/front-office/api/v1/ordens',
    `/servicos/front-office/api/v1/ordens-oco/${ID_ORDEM}`,
    `/servicos/front-office/api/v1/ordens/${ID_ORDEM}/cancelamento/extra`,
    `/servicos/front-office/api/v1/ordens/${ID_ORDEM}/outra?x=/cancelamento`,
  ]) {
    const { res, resultado } = ordensDev('PUT', caminho);
    assert.equal(resultado, false, caminho);
    assert.equal(res.statusCode, 403);
  }
});
