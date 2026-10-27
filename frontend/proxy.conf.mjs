import { readFileSync } from 'node:fs';

// Desenvolvimento: injeta o token de leitura da RF01 lido do .env local, sem expô-lo ao navegador.
function tokenDoAmbiente(nome) {
  if (process.env[nome]) return process.env[nome];
  try {
    const linha = readFileSync(new URL('../.env', import.meta.url), 'utf8')
      .split('\n')
      .find((l) => l.startsWith(nome + '='));
    return linha?.slice(nome.length + 1).trim();
  } catch {
    return undefined;
  }
}

const token = tokenDoAmbiente('FO_TOKEN_LEITURA');
const tokenOrdens = tokenDoAmbiente('FO_TOKEN_ORDENS');
const tokenPosicoes = tokenDoAmbiente('BO_TOKEN_POSICOES');
const tokenRazao = tokenDoAmbiente('BO_TOKEN_RAZAO');
const ROTA_ORDENS =
  /^\/servicos\/front-office\/api\/v1\/(?:ordens(?:\/[0-9a-f-]{36}(?:\/trajetoria)?)?|ordens-oco(?:\/[0-9a-f-]{36})?)(?:\?.*)?$/;
const ROTA_ATUALIZACOES = '/servicos/front-office/api/v1/atualizacoes';
const ORIGEM_LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1)(:[0-9]+)?$/;

// Espelha o Nginx (ADR-010): GET/POST apenas; POST exige Origin local; Host local contra DNS rebinding.
function ordensPermitidas(req, res) {
  const host = (req.headers.host ?? '').replace(/:[0-9]+$/, '');
  const origemOk = req.method === 'GET' || ORIGEM_LOCAL.test(req.headers.origin ?? '');
  if (
    !ROTA_ORDENS.test(req.url ?? '') ||
    !['GET', 'POST'].includes(req.method) ||
    (req.url?.includes('/trajetoria') && req.method !== 'GET') ||
    req.headers.upgrade ||
    !['localhost', '127.0.0.1'].includes(host) ||
    !origemOk
  ) {
    res.statusCode = 403;
    res.end('{"erro":"Origem não permitida para ordens"}');
    return false;
  }
  delete req.headers.authorization;
  return undefined;
}
// Vite chama bypass de upgrade sem ServerResponse; false encerra a conexão antes do proxy.
function atualizacoesPermitidas(req, res) {
  delete req.headers.authorization;
  const host = req.headers.host ?? '';
  const origem = req.headers.origin ?? '';
  const permitido =
    req.method === 'GET' &&
    req.url === ROTA_ATUALIZACOES &&
    /^(localhost|127\.0\.0\.1)(:[0-9]{1,5})?$/.test(host) &&
    origem === `http://${host}` &&
    req.headers.upgrade?.toLowerCase() === 'websocket' &&
    !req.headers['sec-websocket-protocol'];
  if (!permitido) {
    if (res) {
      res.statusCode = 403;
      res.end('{"erro":"Upgrade não permitido"}');
    }
    return false;
  }
  return undefined;
}

function posicoesPermitidas(req, res) {
  if (!/^\/servicos\/back-office\/api\/v1\/posicoes(?:\?.*)?$/.test(req.url ?? '')) {
    res.statusCode = 403;
    res.end('{"erro":"Rota não permitida"}');
    return false;
  }
  return ativosPermitidos(req, res);
}
// RF08b: listagem, um negócio (segmento único; o BO responde 404 idêntico) e razão, somente leitura.
const ROTA_NEGOCIOS =
  /^\/servicos\/back-office\/api\/v1\/negocios(?:\/[A-Za-z0-9-]{1,40})?(?:\?.*)?$/;
const ROTA_RAZAO = /^\/servicos\/back-office\/api\/v1\/razao(?:\?.*)?$/;
function leituraBo(rota) {
  return (req, res) => {
    if (!rota.test(req.url ?? '')) {
      res.statusCode = 403;
      res.end('{"erro":"Rota não permitida"}');
      return false;
    }
    return ativosPermitidos(req, res);
  };
}
const PREPARACAO = /preparacoes-(mercado|risco)/i;

// Espelha a leitura do Nginx. A cota de taxa é aplicada somente no container Nginx.
function ativosPermitidos(req, res) {
  const host = (req.headers.host ?? '').replace(/:[0-9]+$/, '');
  if (!['localhost', '127.0.0.1'].includes(host)) {
    res.statusCode = 421;
    res.end('{"erro":"Host não permitido"}');
    return false;
  }
  if (!['GET', 'HEAD'].includes(req.method)) {
    res.statusCode = 403;
    res.end('{"erro":"Método não permitido para mercado"}');
    return false;
  }
  delete req.headers.authorization;
  return undefined;
}

// Rotas genéricas do FO e do MO: bloqueiam preparação mesmo codificada e descartam Authorization do cliente.
function semPreparacao(req, res) {
  delete req.headers.authorization;
  if (req.headers.upgrade) {
    if (res) {
      res.statusCode = 403;
      res.end('{"erro":"Upgrade não permitido"}');
    }
    return false;
  }
  let caminho = req.url ?? '';
  try {
    caminho = decodeURIComponent(caminho);
  } catch {
    caminho = 'preparacoes-mercado';
  }
  if (PREPARACAO.test(caminho)) {
    res.statusCode = 403;
    res.end('{"erro":"Preparação indisponível pelo navegador"}');
    return false;
  }
  delete req.headers.authorization;
  return undefined;
}
const servico = (porta) => ({ target: `http://localhost:${porta}`, secure: false });

export default {
  '/servicos/front-office/api/v1/preparacoes-mercado': {
    ...servico(8081),
    bypass: (req, res) => {
      res.statusCode = 403;
      res.end('{"erro":"Preparação indisponível pelo navegador"}');
      return false;
    },
  },
  [ROTA_ATUALIZACOES]: {
    ...servico(8081),
    ws: true,
    pathRewrite: { '^/servicos/front-office': '' },
    headers: tokenOrdens ? { Authorization: `Bearer ${tokenOrdens}` } : {},
    bypass: atualizacoesPermitidas,
  },
  '/servicos/front-office/api/v1/ordens': {
    ...servico(8081),
    pathRewrite: { '^/servicos/front-office': '' },
    headers: tokenOrdens ? { Authorization: `Bearer ${tokenOrdens}` } : {},
    bypass: ordensPermitidas,
  },
  '/servicos/front-office/api/v1/ativos': {
    ...servico(8081),
    pathRewrite: { '^/servicos/front-office': '' },
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    bypass: ativosPermitidos,
  },
  '/servicos/front-office': {
    ...servico(8081),
    pathRewrite: { '^/servicos/front-office': '' },
    bypass: semPreparacao,
  },
  '/servicos/middle-office': {
    ...servico(8082),
    pathRewrite: { '^/servicos/middle-office': '' },
    bypass: semPreparacao,
  },
  '/servicos/matching-engine': {
    ...servico(8083),
    pathRewrite: { '^/servicos/matching-engine': '' },
    bypass: semPreparacao,
  },
  '/servicos/back-office/api/v1/posicoes': {
    ...servico(8084),
    pathRewrite: { '^/servicos/back-office': '' },
    headers: tokenPosicoes ? { Authorization: `Bearer ${tokenPosicoes}` } : {},
    bypass: posicoesPermitidas,
  },
  '/servicos/back-office/api/v1/negocios': {
    ...servico(8084),
    pathRewrite: { '^/servicos/back-office': '' },
    headers: tokenRazao ? { Authorization: `Bearer ${tokenRazao}` } : {},
    bypass: leituraBo(ROTA_NEGOCIOS),
  },
  '/servicos/back-office/api/v1/razao': {
    ...servico(8084),
    pathRewrite: { '^/servicos/back-office': '' },
    headers: tokenRazao ? { Authorization: `Bearer ${tokenRazao}` } : {},
    bypass: leituraBo(ROTA_RAZAO),
  },
  '/servicos/back-office': {
    ...servico(8084),
    pathRewrite: { '^/servicos/back-office': '' },
    bypass: semPreparacao,
  },
};
