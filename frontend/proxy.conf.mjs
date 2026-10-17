import { readFileSync } from 'node:fs';

// Desenvolvimento: injeta o token de leitura da RF01 lido do .env local, sem expô-lo ao navegador.
function tokenLeitura() {
  if (process.env.FO_TOKEN_LEITURA) return process.env.FO_TOKEN_LEITURA;
  try {
    const linha = readFileSync(new URL('../.env', import.meta.url), 'utf8')
      .split('\n')
      .find((l) => l.startsWith('FO_TOKEN_LEITURA='));
    return linha?.slice('FO_TOKEN_LEITURA='.length).trim();
  } catch {
    return undefined;
  }
}

const token = tokenLeitura();
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
  '/servicos/front-office/api/v1/ativos': {
    ...servico(8081),
    pathRewrite: { '^/servicos/front-office': '' },
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  },
  '/servicos/front-office': { ...servico(8081), pathRewrite: { '^/servicos/front-office': '' } },
  '/servicos/middle-office': { ...servico(8082), pathRewrite: { '^/servicos/middle-office': '' } },
  '/servicos/matching-engine': {
    ...servico(8083),
    pathRewrite: { '^/servicos/matching-engine': '' },
  },
  '/servicos/back-office': { ...servico(8084), pathRewrite: { '^/servicos/back-office': '' } },
};
