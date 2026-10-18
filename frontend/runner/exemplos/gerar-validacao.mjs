import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF03 (ADR-011): fronteiras do túnel, lote/tick (OMEGA3), Stop Limit, OCO e Market. Perfil `ordens` do runner;
// chaves derivam de ${cenario}. Poucas ordens aceitas por execução para não pressionar o teto diário.
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

const ordem = (ticker, lado, tipo, validade, quantidade, precoLimite, precoGatilho) => ({
  ticker,
  lado,
  tipo,
  validade,
  quantidade,
  ...(precoLimite ? { precoLimite } : {}),
  ...(precoGatilho ? { precoGatilho } : {}),
});

// 1. Fronteiras do túnel em ALFA3 (base 10,00): 9,00 e 11,00 aceitos; 8,99 e 11,01 recusados.
{
  const c = 'FronteirasLimit';
  passo(carga, c, 'Minimo', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: ordem('ALFA3', 'COMPRA', 'LIMIT', 'DIA', 1, '9.00'),
    status: 202,
    esperado: { '/precoBaseTunel': '10.00', '/origemBase': 'REFERENCIA' },
  });
  passo(passos, c, 'Maximo', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-2',
    corpo: ordem('ALFA3', 'VENDA', 'LIMIT', 'DIA', 1, '11.00'),
    status: 202,
  });
  passo(passos, c, 'AbaixoDoMinimo', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-3',
    corpo: ordem('ALFA3', 'COMPRA', 'LIMIT', 'DIA', 1, '8.99'),
    status: 422,
    esperado: {
      '/codigo': 'PRECO_FORA_DO_TUNEL',
      '/detalhes/minimo': '9.00',
      '/detalhes/maximo': '11.00',
      '/detalhes/origemBase': 'REFERENCIA',
    },
  });
  passo(passos, c, 'AcimaDoMaximo', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-4',
    corpo: ordem('ALFA3', 'VENDA', 'LIMIT', 'DIA', 1, '11.01'),
    status: 422,
    esperado: { '/codigo': 'PRECO_FORA_DO_TUNEL' },
  });
}

// 2. Stop e Stop Limit: disparo no túnel e coerência limite × disparo.
{
  const c = 'StopEStopLimit';
  passo(carga, c, 'StopForaDoTunel', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: ordem('ALFA3', 'VENDA', 'STOP', 'DIA', 1, null, '8.99'),
    status: 422,
    esperado: { '/codigo': 'PRECO_FORA_DO_TUNEL' },
  });
  passo(passos, c, 'VendaLimiteAcima', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-2',
    corpo: ordem('ALFA3', 'VENDA', 'STOP_LIMIT', 'DIA', 1, '9.60', '9.50'),
    status: 422,
    esperado: { '/codigo': 'STOP_LIMIT_INCOERENTE' },
  });
  passo(passos, c, 'CompraCoerente', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-3',
    corpo: ordem('ALFA3', 'COMPRA', 'STOP_LIMIT', 'DIA', 1, '9.55', '9.50'),
    status: 202,
    esperado: { '/estado': 'RECEBIDA', '/origemBase': 'REFERENCIA' },
  });
}

// 3. OMEGA3 (lote 100, tick 0,05, base 50,00 → faixa 45,00–55,00).
{
  const c = 'LoteETick';
  passo(carga, c, 'ForaDoLote', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: ordem('OMEGA3', 'COMPRA', 'LIMIT', 'DIA', 150, '50.05'),
    status: 422,
    esperado: { '/codigo': 'QUANTIDADE_FORA_DO_LOTE', '/detalhes/lote': '100' },
  });
  passo(passos, c, 'ForaDoTick', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-2',
    corpo: ordem('OMEGA3', 'COMPRA', 'LIMIT', 'DIA', 100, '50.03'),
    status: 422,
    esperado: { '/codigo': 'PRECO_FORA_DO_TICK', '/detalhes/tick': '0.05' },
  });
  passo(passos, c, 'ForaDoTunel', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-3',
    corpo: ordem('OMEGA3', 'VENDA', 'LIMIT', 'DIA', 100, '55.05'),
    status: 422,
    esperado: { '/codigo': 'PRECO_FORA_DO_TUNEL', '/detalhes/maximo': '55.00' },
  });
  passo(passos, c, 'Valida', 'ordens-b', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-4',
    corpo: ordem('OMEGA3', 'COMPRA', 'LIMIT', 'DIA', 100, '50.05'),
    status: 202,
    esperado: { '/quantidade': 100, '/precoLimite': '50.05' },
  });
}

// 4. OCO com perna fora do túnel: recusa sem consumir a chave; corrigido, a mesma chave é aceita.
{
  const c = 'OcoPernaFora';
  const oco = (alvo) => ({
    ticker: 'ALFA3',
    lado: 'VENDA',
    quantidade: 1,
    validade: 'DIA',
    takeProfit: { precoLimite: alvo },
    stopLoss: { tipo: 'STOP', precoGatilho: '9.50' },
  });
  passo(carga, c, 'Recusado', 'ordens-a', 'POST', '/api/v1/ordens-oco', {
    chave: '${cenario}-1',
    corpo: oco('11.50'),
    status: 422,
    esperado: { '/codigo': 'PRECO_FORA_DO_TUNEL' },
  });
  passo(passos, c, 'Corrigido', 'ordens-a', 'POST', '/api/v1/ordens-oco', {
    chave: '${cenario}-1',
    corpo: oco('11.00'),
    status: 202,
    headers: { 'x-idempotencia-repetida': 'false' },
    esperado: { '/ordens/0/precoBaseTunel': '10.00', '/ordens/1/precoBaseTunel': '10.00' },
  });
}

// 5. Market não tem preço na entrada: sem base de túnel (proteção no matching, RF05).
{
  const c = 'MarketSemBase';
  passo(carga, c, 'Enviar', 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: '${cenario}-1',
    corpo: ordem('GAMA3', 'COMPRA', 'MARKET', 'IOC', 1),
    status: 202,
    esperado: { '/precoBaseTunel': null, '/origemBase': null },
  });
}

await workbook.xlsx.writeFile(fileURLToPath(new URL('./validacao.xlsx', import.meta.url)));
