import assert from 'node:assert/strict';
import test from 'node:test';

// Tokens exclusivamente fictícios: o teste não depende de .env nem imprime segredos locais.
process.env.FO_TOKEN_LEITURA = 'token-ficticio-leitura';
process.env.FO_TOKEN_ORDENS = 'token-ficticio-ordens';
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
