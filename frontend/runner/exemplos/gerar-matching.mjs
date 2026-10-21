import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF05b: apenas APIs; cada cenário inicia e termina sem reservas/ofertas, por negócios reais.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-18T00:00:00Z');
const carga = workbook.addWorksheet('Carga');
carga.addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
const sequencias = new Map();
function passo(
  c,
  id,
  servico,
  metodo,
  caminho,
  corpo,
  esperado,
  captura,
  status = 200,
  polling = false,
) {
  const ordem = (sequencias.get(c) ?? 0) + 1;
  sequencias.set(c, ordem);
  passos.addRow([
    c,
    ordem,
    id,
    servico,
    metodo,
    caminho,
    corpo
      ? JSON.stringify({
          'content-type': 'application/json',
          ...(metodo === 'POST' ? { 'x-idempotency-key': `\${cenario}-${id}` } : {}),
        })
      : '',
    corpo ? JSON.stringify(corpo) : '',
    captura ? JSON.stringify(captura) : '',
    polling ? 200 : '',
    polling ? 15000 : '',
  ]);
  resultados.addRow([
    c,
    id,
    status,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(esperado ?? {}),
  ]);
}
function preparar(c) {
  for (const n of ['002', '003'])
    passo(
      c,
      `Preparar${n}`,
      'preparacao-risco',
      'PUT',
      `/api/v1/preparacoes-risco/contas/CONTA-${n}`,
      { saldo: '1000.00', custodia: { ALFA3: 100 } },
      { '/saldoDisponivel': '1000.00', '/saldoReservado': '0.00' },
      null,
      200,
      true,
    );
}
function enviar(c, id, servico, lado, quantidade, validade = 'DIA', precoLimite = '10.00') {
  passo(
    c,
    id,
    servico,
    'POST',
    '/api/v1/ordens',
    { ticker: 'ALFA3', lado, tipo: 'LIMIT', validade, quantidade, precoLimite },
    {},
    { [id]: '/id' },
    202,
  );
}
function consultar(c, id, servico, variavel, esperado) {
  passo(c, id, servico, 'GET', `/api/v1/ordens/\${${variavel}}`, null, esperado, null, 200, true);
}
function carteira(c, id, servico, esperado) {
  passo(c, id, servico, 'GET', '/api/v1/risco/carteira', null, esperado, null, 200, true);
}
const terminal = (estado, executada, cancelada) => ({
  '/estado': estado,
  '/quantidadeExecutada': executada,
  '/quantidadeCancelada': cancelada,
});
{
  const c = 'ParcialMelhoria';
  preparar(c);
  enviar(c, 'VendaMelhor', 'ordens-b', 'VENDA', 3, 'DIA', '9.90');
  consultar(c, 'MelhorAberta', 'ordens-b', 'VendaMelhor', { '/estado': 'ABERTA' });
  enviar(c, 'VendaLimite', 'ordens-b', 'VENDA', 4);
  consultar(c, 'LimiteAberta', 'ordens-b', 'VendaLimite', { '/estado': 'ABERTA' });
  // A preparação recusa a reserva viva e preserva a carteira; não é mecanismo de limpeza.
  passo(
    c,
    'PreparoBloqueado',
    'preparacao-risco',
    'PUT',
    '/api/v1/preparacoes-risco/contas/CONTA-003',
    { saldo: '1.00', custodia: {} },
    {},
    null,
    409,
  );
  enviar(c, 'Compra', 'ordens-a', 'COMPRA', 10, 'IOC');
  consultar(c, 'CompraEncerrada', 'ordens-a', 'Compra', {
    ...terminal('CANCELADA', 7, 3),
    '/motivoEncerramento': 'SALDO_CANCELADO_IOC',
  });
  consultar(c, 'MelhorExecutada', 'ordens-b', 'VendaMelhor', terminal('EXECUTADA', 3, 0));
  consultar(c, 'LimiteExecutada', 'ordens-b', 'VendaLimite', terminal('EXECUTADA', 4, 0));
  carteira(c, 'CaixaCompra', 'risco-a', {
    '/saldoTotal': '930.30',
    '/saldoDisponivel': '930.30',
    '/saldoReservado': '0.00',
    '/custodias/0/total': 100,
  });
  carteira(c, 'CustodiaVenda', 'risco-b', {
    '/saldoTotal': '1000.00',
    '/saldoReservado': '0.00',
    '/custodias/0/total': 93,
    '/custodias/0/disponivel': 93,
    '/custodias/0/reservado': 0,
  });
}
{
  const c = 'FokAtomico';
  preparar(c);
  enviar(c, 'Venda', 'ordens-b', 'VENDA', 2);
  consultar(c, 'VendaAberta', 'ordens-b', 'Venda', { '/estado': 'ABERTA' });
  enviar(c, 'Fok', 'ordens-a', 'COMPRA', 3, 'FOK');
  consultar(c, 'FokCancelado', 'ordens-a', 'Fok', {
    ...terminal('CANCELADA', 0, 3),
    '/motivoEncerramento': 'FOK_SEM_LIQUIDEZ',
  });
  carteira(c, 'SemDebito', 'risco-a', { '/saldoTotal': '1000.00', '/saldoReservado': '0.00' });
  consultar(c, 'PassivaPreservada', 'ordens-b', 'Venda', {
    '/estado': 'ABERTA',
    '/quantidadeExecutada': 0,
  });
  enviar(c, 'Limpar', 'ordens-a', 'COMPRA', 2, 'IOC');
  consultar(c, 'LimpezaExecutada', 'ordens-a', 'Limpar', terminal('EXECUTADA', 2, 0));
  consultar(c, 'VendaExecutada', 'ordens-b', 'Venda', terminal('EXECUTADA', 2, 0));
  carteira(c, 'CaixaFinal', 'risco-a', { '/saldoTotal': '980.00', '/saldoReservado': '0.00' });
  carteira(c, 'CustodiaFinal', 'risco-b', {
    '/custodias/0/total': 98,
    '/custodias/0/reservado': 0,
  });
}
{
  const c = 'MarketVazio';
  preparar(c);
  passo(
    c,
    'Market',
    'ordens-a',
    'POST',
    '/api/v1/ordens',
    { ticker: 'ALFA3', lado: 'COMPRA', tipo: 'MARKET', validade: 'IOC', quantidade: 2 },
    {},
    { Market: '/id' },
    202,
  );
  consultar(c, 'SemLiquidez', 'ordens-a', 'Market', terminal('CANCELADA', 0, 2));
  carteira(c, 'ReservaLiberada', 'risco-a', {
    '/saldoTotal': '1000.00',
    '/saldoDisponivel': '1000.00',
    '/saldoReservado': '0.00',
  });
}
// RF05c.2: cada cenário fixa o último negócio em10,00 via APIs e consome todos os seus stops/ofertas.
function fixarUltimo(c) {
  enviar(c, 'SementeVenda', 'ordens-b', 'VENDA', 1);
  consultar(c, 'SementeAberta', 'ordens-b', 'SementeVenda', { '/estado': 'ABERTA' });
  enviar(c, 'SementeCompra', 'ordens-a', 'COMPRA', 1, 'IOC');
  consultar(c, 'SementeExecutada', 'ordens-a', 'SementeCompra', terminal('EXECUTADA', 1, 0));
  consultar(c, 'SementeVendaExecutada', 'ordens-b', 'SementeVenda', terminal('EXECUTADA', 1, 0));
}
function enviarStop(c, id, tipo, lado, quantidade, gatilho, limite) {
  passo(
    c,
    id,
    lado === 'COMPRA' ? 'ordens-a' : 'ordens-b',
    'POST',
    '/api/v1/ordens',
    {
      ticker: 'ALFA3',
      lado,
      tipo,
      validade: 'DIA',
      quantidade,
      precoGatilho: gatilho,
      ...(limite ? { precoLimite: limite } : {}),
    },
    {},
    { [id]: '/id' },
    202,
  );
}
for (const parcial of [false, true]) {
  const c = parcial ? 'StopParcialCancelaSaldo' : 'StopExecutaAposGatilho';
  preparar(c);
  fixarUltimo(c);
  const quantidade = parcial ? 3 : 2;
  enviarStop(c, 'Stop', 'STOP', 'COMPRA', quantidade, '10.10');
  consultar(c, 'StopPendente', 'ordens-a', 'Stop', {
    '/estado': 'AGUARDANDO_GATILHO',
    '/tipo': 'STOP',
    '/validade': 'DIA',
    '/precoGatilho': '10.10',
    '/quantidadeExecutada': 0,
  });
  carteira(c, 'ReservaPendente', 'risco-a', {
    '/saldoTotal': '990.00',
    '/saldoReservado': parcial ? '33.33' : '22.22',
  });
  enviar(c, 'Liquidez', 'ordens-b', 'VENDA', parcial ? 2 : 3, 'DIA', '10.10');
  consultar(c, 'LiquidezAberta', 'ordens-b', 'Liquidez', { '/estado': 'ABERTA' });
  enviar(c, 'Gatilho', 'ordens-a', 'COMPRA', 1, 'IOC', '10.10');
  consultar(c, 'GatilhoExecutado', 'ordens-a', 'Gatilho', terminal('EXECUTADA', 1, 0));
  consultar(c, 'StopTerminal', 'ordens-a', 'Stop', {
    ...terminal(parcial ? 'CANCELADA' : 'EXECUTADA', parcial ? 1 : 2, parcial ? 2 : 0),
    '/motivoEncerramento': parcial ? 'SEM_LIQUIDEZ' : 'EXECUTADA',
    '/tipo': 'STOP',
    '/validade': 'DIA',
  });
  consultar(
    c,
    'LiquidezConsumida',
    'ordens-b',
    'Liquidez',
    terminal('EXECUTADA', parcial ? 2 : 3, 0),
  );
  carteira(c, 'CaixaFinalStop', 'risco-a', {
    '/saldoTotal': parcial ? '969.80' : '959.70',
    '/saldoDisponivel': parcial ? '969.80' : '959.70',
    '/saldoReservado': '0.00',
  });
  carteira(c, 'CustodiaFinalStop', 'risco-b', {
    '/custodias/0/total': parcial ? 97 : 96,
    '/custodias/0/reservado': 0,
  });
}
{
  const c = 'StopLimitRepousaEViraPassiva';
  preparar(c);
  fixarUltimo(c);
  enviarStop(c, 'StopLimit', 'STOP_LIMIT', 'COMPRA', 2, '10.10', '10.10');
  consultar(c, 'StopLimitPendente', 'ordens-a', 'StopLimit', { '/estado': 'AGUARDANDO_GATILHO' });
  enviar(c, 'LiquidezGatilho', 'ordens-b', 'VENDA', 1, 'DIA', '10.10');
  consultar(c, 'LiquidezAberta', 'ordens-b', 'LiquidezGatilho', { '/estado': 'ABERTA' });
  enviar(c, 'Gatilho', 'ordens-a', 'COMPRA', 1, 'IOC', '10.10');
  consultar(c, 'GatilhoExecutado', 'ordens-a', 'Gatilho', terminal('EXECUTADA', 1, 0));
  consultar(c, 'StopLimitNoLivro', 'ordens-a', 'StopLimit', {
    '/estado': 'ABERTA',
    '/tipo': 'STOP_LIMIT',
    '/validade': 'DIA',
    '/quantidadeExecutada': 0,
  });
  carteira(c, 'ReservaNoLivro', 'risco-a', { '/saldoReservado': '20.20' });
  enviar(c, 'VendaContraStopPassiva', 'ordens-b', 'VENDA', 2, 'IOC', '10.10');
  consultar(c, 'VendaExecutada', 'ordens-b', 'VendaContraStopPassiva', terminal('EXECUTADA', 2, 0));
  consultar(c, 'StopLimitExecutada', 'ordens-a', 'StopLimit', terminal('EXECUTADA', 2, 0));
  carteira(c, 'CaixaFinalStopLimit', 'risco-a', {
    '/saldoTotal': '959.70',
    '/saldoDisponivel': '959.70',
    '/saldoReservado': '0.00',
  });
  carteira(c, 'CustodiaFinalStopLimit', 'risco-b', {
    '/custodias/0/total': 96,
    '/custodias/0/reservado': 0,
  });
}
{
  const c = 'StopVendaConsomeCustodia';
  preparar(c);
  fixarUltimo(c);
  enviarStop(c, 'StopVenda', 'STOP', 'VENDA', 2, '9.90');
  consultar(c, 'VendaPendente', 'ordens-b', 'StopVenda', { '/estado': 'AGUARDANDO_GATILHO' });
  carteira(c, 'CustodiaPendente', 'risco-b', {
    '/custodias/0/total': 99,
    '/custodias/0/reservado': 2,
  });
  enviar(c, 'CompraLiquida', 'ordens-a', 'COMPRA', 3, 'DIA', '9.90');
  consultar(c, 'CompraAberta', 'ordens-a', 'CompraLiquida', { '/estado': 'ABERTA' });
  enviar(c, 'GatilhoVenda', 'ordens-b', 'VENDA', 1, 'IOC', '9.90');
  consultar(c, 'GatilhoExecutado', 'ordens-b', 'GatilhoVenda', terminal('EXECUTADA', 1, 0));
  consultar(c, 'StopVendaExecutada', 'ordens-b', 'StopVenda', terminal('EXECUTADA', 2, 0));
  consultar(c, 'CompraExecutada', 'ordens-a', 'CompraLiquida', terminal('EXECUTADA', 3, 0));
  carteira(c, 'CaixaFinalVendaStop', 'risco-a', {
    '/saldoTotal': '960.30',
    '/saldoReservado': '0.00',
  });
  carteira(c, 'CustodiaFinalVendaStop', 'risco-b', {
    '/custodias/0/total': 96,
    '/custodias/0/reservado': 0,
  });
}
{
  const c = 'StopsInvalidos';
  preparar(c);
  for (const [id, tipo, validade, limite, gatilho] of [
    ['StopIoc', 'STOP', 'IOC', null, '10.00'],
    ['StopFok', 'STOP', 'FOK', null, '10.00'],
    ['StopLimitIncoerente', 'STOP_LIMIT', 'DIA', '9.90', '10.00'],
    ['StopSemGatilho', 'STOP', 'DIA', null, null],
  ]) {
    passo(
      c,
      id,
      'ordens-a',
      'POST',
      '/api/v1/ordens',
      {
        ticker: 'ALFA3',
        lado: 'COMPRA',
        tipo,
        validade,
        quantidade: 2,
        ...(limite ? { precoLimite: limite } : {}),
        ...(gatilho ? { precoGatilho: gatilho } : {}),
      },
      {},
      null,
      422,
    );
  }
  carteira(c, 'SemReservasInvalidas', 'risco-a', {
    '/saldoTotal': '1000.00',
    '/saldoReservado': '0.00',
  });
}
await workbook.xlsx.writeFile(fileURLToPath(new URL('./matching.xlsx', import.meta.url)));
