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
const ROTA_ORDENS = /^\/servicos\/front-office\/api\/v1\/ordens(-oco)?(\/[0-9a-f-]{36})?(\?.*)?$/;
const ORIGEM_LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1)(:[0-9]+)?$/;

// Espelha o Nginx (ADR-010): GET/POST apenas; POST exige Origin local; Host local contra DNS rebinding.
function ordensPermitidas(req, res) {
  const host = (req.headers.host ?? '').replace(/:[0-9]+$/, '');
  const origemOk = req.method === 'GET' || ORIGEM_LOCAL.test(req.headers.origin ?? '');
  if (
    !['GET', 'POST'].includes(req.method) ||
    !['localhost', '127.0.0.1'].includes(host) ||
    !origemOk
  ) {
    res.statusCode = 403;
    res.end('{"erro":"Origem não permitida para ordens"}');
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
  },
  '/servicos/back-office/api/v1/posicoes': {
    ...servico(8084),
    pathRewrite: { '^/servicos/back-office': '' },
    headers: tokenPosicoes ? { Authorization: `Bearer ${tokenPosicoes}` } : {},
    bypass: posicoesPermitidas,
  },
  '/servicos/back-office': {
    ...servico(8084),
    pathRewrite: { '^/servicos/back-office': '' },
    bypass: semPreparacao,
  },
};
