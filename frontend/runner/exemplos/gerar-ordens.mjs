import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF02: ordens-a (CONTA-002) e ordens-b (CONTA-003) vêm da configuração externa com tokens distintos.
// Chaves de idempotência derivam de ${cenario}, novo a cada execução; ordens não são apagadas.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-17T00:00:00Z');
const carga = workbook.addWorksheet('Carga');
carga.addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
const ordens = new Map();

function passo(sheet, c, id, servico, metodo, caminho, opcoes = {}) {
  const chave = sheet.name + c;
  const ordem = (ordens.get(chave) ?? 0) + 1;
  ordens.set(chave, ordem);
  const headers = { ...(opcoes.corpo ? { 'content-type': 'application/json' } : {}) };
  if (opcoes.chave) headers['x-idempotency-key'] = opcoes.chave;
  sheet.addRow([
    c,
    ordem,
    id,
    servico,
    metodo,
    caminho,
    Object.keys(headers).length ? JSON.stringify(headers) : '',
    opcoes.corpo ? JSON.stringify(opcoes.corpo) : '',
    opcoes.captura ? JSON.stringify(opcoes.captura) : '',
    '',
    '',
  ]);
  resultados.addRow([
    c,
    id,
    opcoes.status,
    JSON.stringify({ 'cache-control': 'no-store', ...(opcoes.headers ?? {}) }),
    JSON.stringify(opcoes.esperado ?? {}),
  ]);
}

const limit = (preco, extra = {}) => ({
  ticker: 'ALFA3',
  lado: 'COMPRA',
  tipo: 'LIMIT',
  validade: 'DIA',
  quantidade: 100,
  precoLimite: preco,
  ...extra,
});

// 1. Registro e consulta: RECEBIDA, sem execução.
{
  const c = 'LimitRegistrado';
  passo(carga, c, 'Enviar', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: limit('10.1'),
    status: 202,
    captura: { id: '/id' },
    headers: { 'x-idempotencia-repetida': 'false' },
    esperado: { '/estado': 'RECEBIDA', '/precoLimite': '10.10', '/precoGatilho': null },
  });
  passo(passos, c, 'Consultar', 'ordens-a', 'GET', '/api/v1/ordens/${id}', {
    status: 200,
    esperado: { '/id': '${id}', '/tipo': 'LIMIT', '/validade': 'DIA', '/estado': 'RECEBIDA' },
  });
}

// 2. Idempotência: repetição devolve a mesma ordem; corpo divergente com a mesma chave é 409.
{
  const c = 'Idempotencia';
  passo(carga, c, 'Primeiro', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: limit('10.00'),
    status: 202,
    captura: { id: '/id' },
  });
  passo(passos, c, 'Repetido', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: limit('10.0'),
    status: 202,
    headers: { 'x-idempotencia-repetida': 'true' },
    esperado: { '/id': '${id}' },
  });
  passo(passos, c, 'Divergente', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: limit('10.05'),
    status: 409,
    esperado: { '/codigo': 'IDEMPOTENCIA_CONFLITO' },
  });
}

// 3. Isolamento entre contas: B não vê a ordem de A (404) e a mesma chave em B cria outra ordem.
{
  const c = 'IsolamentoContas';
  passo(carga, c, 'EnviarA', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: limit('10.00'),
    status: 202,
    captura: { id: '/id' },
  });
  passo(passos, c, 'ConsultaDeB', 'ordens-b', 'GET', '/api/v1/ordens/${id}', {
    status: 404,
    esperado: { '/codigo': 'ORDEM_NAO_ENCONTRADA' },
  });
  passo(passos, c, 'MesmaChaveEmB', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: limit('10.00'),
    status: 202,
    headers: { 'x-idempotencia-repetida': 'false' },
  });
}

// 4. Rejeições estruturais e de combinação.
{
  const c = 'Rejeicoes';
  passo(carga, c, 'MarketDia', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: { ticker: 'ALFA3', lado: 'VENDA', tipo: 'MARKET', validade: 'DIA', quantidade: 10 },
    status: 422,
    esperado: { '/codigo': 'COMBINACAO_INVALIDA' },
  });
  passo(passos, c, 'StopLimitSemLimite', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-2',
    corpo: {
      ticker: 'ALFA3',
      lado: 'VENDA',
      tipo: 'STOP_LIMIT',
      validade: 'DIA',
      quantidade: 10,
      precoGatilho: '9.50',
    },
    status: 422,
    esperado: { '/codigo': 'CAMPO_EXIGIDO_PELO_TIPO' },
  });
  passo(passos, c, 'ContaNoCorpo', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-3',
    corpo: limit('10.00', { conta: 'CONTA-003' }),
    status: 400,
    esperado: { '/codigo': 'JSON_INVALIDO' },
  });
  passo(passos, c, 'QuantidadeTexto', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-4',
    corpo: limit('10.00', { quantidade: '100' }),
    status: 400,
    esperado: { '/codigo': 'JSON_INVALIDO' },
  });
  passo(passos, c, 'SemChave', 'ordens-a', 'POST', '/api/v1/ordens', {
    corpo: limit('10.00'),
    status: 400,
    esperado: { '/codigo': 'IDEMPOTENCIA_AUSENTE' },
  });
  passo(passos, c, 'AtivoDesconhecido', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-5',
    corpo: limit('10.00', { ticker: 'ZETA3' }),
    status: 422,
    esperado: { '/codigo': 'ATIVO_DESCONHECIDO' },
  });
}

// 5. OCO: duas pernas atômicas, consulta pelo grupo e isolamento.
{
  const c = 'Oco';
  passo(carga, c, 'Enviar', 'ordens-a', 'POST', '/api/v1/ordens-oco', {
    chave: '${cenario}-1',
    corpo: {
      ticker: 'BETA3',
      lado: 'VENDA',
      quantidade: 50,
      validade: 'DIA',
      takeProfit: { precoLimite: '21.00' },
      stopLoss: { tipo: 'STOP', precoGatilho: '19.00' },
    },
    status: 202,
    captura: { grupo: '/grupoOco' },
    esperado: { '/ordens/0/papelOco': 'TAKE_PROFIT', '/ordens/1/papelOco': 'STOP_LOSS' },
  });
  passo(passos, c, 'Consultar', 'ordens-a', 'GET', '/api/v1/ordens-oco/${grupo}', {
    status: 200,
    esperado: {
      '/grupoOco': '${grupo}',
      '/ordens/0/tipo': 'LIMIT',
      '/ordens/1/tipo': 'STOP',
      '/ordens/1/precoGatilho': '19.00',
    },
  });
  passo(passos, c, 'ConsultaDeB', 'ordens-b', 'GET', '/api/v1/ordens-oco/${grupo}', {
    status: 404,
    esperado: { '/codigo': 'GRUPO_OCO_NAO_ENCONTRADO' },
  });
}

await workbook.xlsx.writeFile(fileURLToPath(new URL('./ordens.xlsx', import.meta.url)));
