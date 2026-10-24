import assert from 'node:assert/strict';
import test from 'node:test';

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
