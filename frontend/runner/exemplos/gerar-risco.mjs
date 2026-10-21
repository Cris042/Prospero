import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF04 (ADR-012): reserva de caixa e custódia pela SAGA FO→MO, teto do túnel na Market, OCO com reserva única,
// quantidade máxima e isolamento da carteira. Perfil `risco` do runner; cada cenário prepara a carteira da CONTA-002
// pela API local do MO; simples encerram por negócios reais. OCO final exige carteira003 sem reserva prévia (RF05c).
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-18T00:00:00Z');
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
    opcoes.polling ? 200 : '',
    opcoes.polling ? 15000 : '',
  ]);
  resultados.addRow([
    c,
    id,
    opcoes.status,
    JSON.stringify({ 'cache-control': 'no-store', ...(opcoes.headers ?? {}) }),
    JSON.stringify(opcoes.esperado ?? {}),
  ]);
}

const preparar = (c, saldo, custodia = {}, conta = 'CONTA-002') =>
  passo(
    carga,
    c,
    'Preparar',
    'preparacao-risco',
    'PUT',
    `/api/v1/preparacoes-risco/contas/${conta}`,
    {
      corpo: { saldo, custodia },
      status: 200,
      polling: true,
      esperado: { '/conta': conta, '/saldoReservado': '0.00' },
    },
  );

const enviar = (c, id, n, corpo, captura = 'id') =>
  passo(passos, c, id, 'ordens-a', 'POST', '/api/v1/ordens', {
    chave: `\${cenario}-${n}`,
    corpo,
    status: 202,
    captura: { [captura]: '/id' },
    esperado: { '/estado': 'RECEBIDA' },
  });

const aguardar = (c, id, variavel, estado, motivo = null) =>
  passo(passos, c, id, 'ordens-a', 'GET', `/api/v1/ordens/\${${variavel}}`, {
    status: 200,
    polling: true,
    esperado: { '/estado': estado, '/motivoRejeicao': motivo },
  });

const carteira = (c, id, esperado, servico = 'risco-a') =>
  passo(passos, c, id, servico, 'GET', '/api/v1/risco/carteira', {
    status: 200,
    esperado,
    polling: true,
  });

const limit = (ticker, lado, quantidade, precoLimite) => ({
  ticker,
  lado,
  tipo: 'LIMIT',
  validade: 'DIA',
  quantidade,
  precoLimite,
});

// 1. Caixa: 7 × 10,00 sobre 100,00 reserva 70,00; a segunda compra igual é rejeitada sem alterar o saldo.
{
  const c = 'ReservaCaixa';
  preparar(c, '100.00', { ALFA3: 7 });
  enviar(c, 'Primeira', 1, limit('ALFA3', 'COMPRA', 7, '10.00'));
  aguardar(c, 'PrimeiraReservada', 'id', 'ABERTA');
  carteira(c, 'Reservado', { '/saldoDisponivel': '30.00', '/saldoReservado': '70.00' });
  enviar(c, 'Segunda', 2, limit('ALFA3', 'COMPRA', 7, '10.00'), 'id2');
  aguardar(c, 'SegundaRejeitada', 'id2', 'REJEITADA', 'SALDO_INSUFICIENTE');
  carteira(c, 'Inalterado', { '/saldoDisponivel': '30.00', '/saldoReservado': '70.00' });
  enviar(
    c,
    'Contraparte',
    3,
    { ...limit('ALFA3', 'VENDA', 7, '10.00'), validade: 'IOC' },
    'limpeza',
  );
  aguardar(c, 'Executada', 'limpeza', 'EXECUTADA');
  carteira(c, 'Liberado', { '/saldoTotal': '30.00', '/saldoReservado': '0.00' });
}

// 2. Custódia (sem short): vender 6 com 5 em custódia é rejeitado; vender 5 reserva toda a custódia.
{
  const c = 'ReservaCustodia';
  preparar(c, '150.00', { GAMA3: 5 });
  enviar(c, 'Excedente', 1, limit('GAMA3', 'VENDA', 6, '30.00'));
  aguardar(c, 'ExcedenteRejeitada', 'id', 'REJEITADA', 'CUSTODIA_INSUFICIENTE');
  enviar(c, 'Total', 2, limit('GAMA3', 'VENDA', 5, '30.00'), 'id2');
  aguardar(c, 'TotalReservada', 'id2', 'ABERTA');
  // Custódias em ordem alfabética: ALFA3, BETA3, DELTA3, GAMA3, OMEGA3.
  carteira(c, 'CustodiaReservada', {
    '/custodias/3/ticker': 'GAMA3',
    '/custodias/3/disponivel': 0,
    '/custodias/3/reservado': 5,
  });
  enviar(
    c,
    'Contraparte',
    3,
    { ...limit('GAMA3', 'COMPRA', 5, '30.00'), validade: 'IOC' },
    'limpeza',
  );
  aguardar(c, 'Executada', 'limpeza', 'EXECUTADA');
  carteira(c, 'Liberado', {
    '/saldoTotal': '0.00',
    '/saldoReservado': '0.00',
    '/custodias/3/reservado': 0,
  });
}

// 3. Market compra reserva pelo teto do túnel: 10 × ⌊20,00 × 1,10⌋ = 220,00; 219,99 não basta.
{
  const c = 'MarketTeto';
  const market = {
    ticker: 'BETA3',
    lado: 'COMPRA',
    tipo: 'MARKET',
    validade: 'IOC',
    quantidade: 10,
  };
  preparar(c, '219.99');
  enviar(c, 'Insuficiente', 1, market);
  aguardar(c, 'InsuficienteRejeitada', 'id', 'REJEITADA', 'SALDO_INSUFICIENTE');
  passo(
    passos,
    c,
    'Completar',
    'preparacao-risco',
    'PUT',
    '/api/v1/preparacoes-risco/contas/CONTA-002',
    {
      corpo: { saldo: '220.00' },
      status: 200,
      polling: true,
      esperado: { '/saldoDisponivel': '220.00' },
    },
  );
  enviar(c, 'Suficiente', 2, market, 'id2');
  aguardar(c, 'SuficienteReservada', 'id2', 'CANCELADA');
  carteira(c, 'TetoLiberado', { '/saldoDisponivel': '220.00', '/saldoReservado': '0.00' });
}

// 5. Quantidade máxima por ordem (100.000, inclusiva) no MO.
{
  const c = 'QuantidadeMaxima';
  preparar(c, '2000000.00', { ALFA3: 100000 });
  enviar(c, 'Acima', 1, limit('ALFA3', 'COMPRA', 100001, '10.00'));
  aguardar(c, 'AcimaRejeitada', 'id', 'REJEITADA', 'QUANTIDADE_MAXIMA_EXCEDIDA');
  enviar(c, 'NoLimite', 2, limit('ALFA3', 'COMPRA', 100000, '10.00'), 'id2');
  aguardar(c, 'NoLimiteReservada', 'id2', 'ABERTA');
  enviar(
    c,
    'Contraparte',
    3,
    { ...limit('ALFA3', 'VENDA', 100000, '10.00'), validade: 'IOC' },
    'limpeza',
  );
  aguardar(c, 'Executada', 'limpeza', 'EXECUTADA');
  carteira(c, 'Liberado', { '/saldoTotal': '1000000.00', '/saldoReservado': '0.00' });
}

// 6. Isolamento: cada token lê só a própria carteira; a conta da tela não é preparável.
{
  const c = 'Isolamento';
  carteira(c, 'OutraConta', { '/conta': 'CONTA-003' }, 'risco-b');
  passo(
    passos,
    c,
    'ContaDaTela',
    'preparacao-risco',
    'PUT',
    '/api/v1/preparacoes-risco/contas/CONTA-001',
    {
      corpo: { saldo: '1.00' },
      status: 403,
      headers: {},
      esperado: { '/codigo': 'CONTA_NAO_PREPARAVEL' },
    },
  );
}

// 4. OCO de compra: uma reserva pelo maior custo (Stop 21,00 → teto 23,10 × 10 = 231,00 > alvo 19,00 × 10).
{
  const c = 'OcoReservaUnica';
  preparar(c, '1000.00', {}, 'CONTA-003');
  passo(passos, c, 'Enviar', 'ordens-b', 'POST', '/api/v1/ordens-oco', {
    chave: '${cenario}-1',
    corpo: {
      ticker: 'BETA3',
      lado: 'COMPRA',
      quantidade: 10,
      validade: 'DIA',
      takeProfit: { precoLimite: '19.00' },
      stopLoss: { tipo: 'STOP', precoGatilho: '21.00' },
    },
    status: 202,
    captura: { grupo: '/grupoOco' },
  });
  passo(passos, c, 'ParReservado', 'ordens-b', 'GET', '/api/v1/ordens-oco/${grupo}', {
    status: 200,
    polling: true,
    esperado: { '/ordens/0/estado': 'RESERVADA', '/ordens/1/estado': 'RESERVADA' },
  });
  carteira(
    c,
    'ReservaUnica',
    { '/saldoDisponivel': '769.00', '/saldoReservado': '231.00' },
    'risco-b',
  );
}

await workbook.xlsx.writeFile(fileURLToPath(new URL('./risco.xlsx', import.meta.url)));
