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
await workbook.xlsx.writeFile(fileURLToPath(new URL('./matching.xlsx', import.meta.url)));
