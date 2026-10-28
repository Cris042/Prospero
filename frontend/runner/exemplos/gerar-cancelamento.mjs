import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF10: cancelamento manual só por APIs reais (PUT/GET .../cancelamento, ordens, carteira do MO).
// Perfil risco; cada cenário prepara CONTA-002/003 e termina sem reservas nem ofertas vivas, de modo que a
// CI pode repeti-lo. O saldo só é conferido depois do CONFIRMADO, que vem do middle-office.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-28T00:00:00Z');
workbook.addWorksheet('Carga').addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
const sequencias = new Map();
const SEM_CABECALHO_DE_CORPO = null;

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
      SEM_CABECALHO_DE_CORPO,
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
function enviarStop(c, id, servico, tipo, lado, quantidade, gatilho, limite) {
  passo(
    c,
    id,
    servico,
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
function consultar(c, id, servico, variavel, esperado) {
  passo(c, id, servico, 'GET', `/api/v1/ordens/\${${variavel}}`, null, esperado, null, 200, true);
}
function carteira(c, id, servico, esperado) {
  passo(c, id, servico, 'GET', '/api/v1/risco/carteira', null, esperado, null, 200, true);
}
function pedir(c, id, servico, variavel, esperado, status = 202, corpo = null) {
  passo(
    c,
    id,
    servico,
    'PUT',
    `/api/v1/ordens/\${${variavel}}/cancelamento`,
    corpo,
    esperado,
    null,
    status,
  );
}
function operacao(c, id, servico, variavel, esperado) {
  passo(
    c,
    id,
    servico,
    'GET',
    `/api/v1/ordens/\${${variavel}}/cancelamento`,
    null,
    esperado,
    null,
    200,
    true,
  );
}
function semOperacao(c, id, servico, variavel) {
  passo(
    c,
    id,
    servico,
    'GET',
    `/api/v1/ordens/\${${variavel}}/cancelamento`,
    null,
    { '/codigo': 'CANCELAMENTO_NAO_ENCONTRADO' },
    null,
    404,
  );
}
const terminal = (estado, executada, cancelada) => ({
  '/estado': estado,
  '/quantidadeExecutada': executada,
  '/quantidadeCancelada': cancelada,
});
const manual = (estado, executada, cancelada) => ({
  ...terminal(estado, executada, cancelada),
  '/motivoEncerramento': 'CANCELADA_MANUAL',
});
const confirmado = { '/estado': 'CONFIRMADO', '/decisao': 'APLICADO' };
// Fixa o último negócio em 10,00 (a: compra 1; b: venda 1) para o túnel e para os gatilhos.
function fixarUltimo(c) {
  enviar(c, 'SementeVenda', 'ordens-b', 'VENDA', 1);
  consultar(c, 'SementeAberta', 'ordens-b', 'SementeVenda', { '/estado': 'ABERTA' });
  enviar(c, 'SementeCompra', 'ordens-a', 'COMPRA', 1, 'IOC');
  consultar(c, 'SementeExecutada', 'ordens-a', 'SementeCompra', terminal('EXECUTADA', 1, 0));
  consultar(c, 'SementeVendaExecutada', 'ordens-b', 'SementeVenda', terminal('EXECUTADA', 1, 0));
}

{
  const c = 'CancelaSemExecucaoERepete';
  preparar(c);
  fixarUltimo(c);
  enviar(c, 'Venda', 'ordens-b', 'VENDA', 5);
  consultar(c, 'VendaAberta', 'ordens-b', 'Venda', { '/estado': 'ABERTA' });
  carteira(c, 'CustodiaReservada', 'risco-b', {
    '/custodias/0/total': 99,
    '/custodias/0/reservado': 5,
  });
  // Antes de existir operação: 404; corpo com campos: 400 e nada é criado; outra conta: 404 igual ao inexistente.
  semOperacao(c, 'SemPedido', 'ordens-b', 'Venda');
  pedir(
    c,
    'CorpoComDados',
    'ordens-b',
    'Venda',
    { '/codigo': 'CORPO_CANCELAMENTO_INVALIDO' },
    400,
    {
      quantidade: 1,
    },
  );
  pedir(c, 'OutraConta', 'ordens-a', 'Venda', { '/codigo': 'ORDEM_NAO_ENCONTRADA' }, 404);
  passo(
    c,
    'Inexistente',
    'ordens-b',
    'PUT',
    '/api/v1/ordens/00000000-0000-4000-8000-000000000000/cancelamento',
    null,
    { '/codigo': 'ORDEM_NAO_ENCONTRADA' },
    null,
    404,
  );
  consultar(c, 'AindaAberta', 'ordens-b', 'Venda', {
    '/estado': 'ABERTA',
    '/quantidadeCancelada': 0,
  });
  pedir(
    c,
    'Cancelar',
    'ordens-b',
    'Venda',
    { '/estado': 'SOLICITADO', '/ordem': '${Venda}' },
    202,
    {},
  );
  operacao(c, 'Confirmado', 'ordens-b', 'Venda', confirmado);
  consultar(c, 'VendaCancelada', 'ordens-b', 'Venda', {
    ...manual('CANCELADA', 0, 5),
    '/cancelamento/estado': 'CONFIRMADO',
  });
  // Repetição devolve a mesma operação já confirmada, sem novo efeito.
  pedir(c, 'Repete', 'ordens-b', 'Venda', { '/estado': 'CONFIRMADO', '/ordem': '${Venda}' });
  semOperacao(c, 'OutraContaNaoLe', 'ordens-a', 'Venda');
  carteira(c, 'CustodiaLiberada', 'risco-b', {
    '/saldoTotal': '1000.00',
    '/saldoReservado': '0.00',
    '/custodias/0/total': 99,
    '/custodias/0/reservado': 0,
    '/custodias/0/disponivel': 99,
  });
  carteira(c, 'CaixaIntacto', 'risco-a', { '/saldoTotal': '990.00', '/saldoReservado': '0.00' });
}
{
  const c = 'CancelaParcialPreservaNegocio';
  preparar(c);
  fixarUltimo(c);
  enviar(c, 'Venda', 'ordens-b', 'VENDA', 4);
  consultar(c, 'VendaAberta', 'ordens-b', 'Venda', { '/estado': 'ABERTA' });
  enviar(c, 'Compra', 'ordens-a', 'COMPRA', 3, 'IOC');
  consultar(c, 'CompraExecutada', 'ordens-a', 'Compra', terminal('EXECUTADA', 3, 0));
  consultar(c, 'VendaParcial', 'ordens-b', 'Venda', {
    '/estado': 'PARCIALMENTE_EXECUTADA',
    '/quantidadeExecutada': 3,
  });
  pedir(c, 'Cancelar', 'ordens-b', 'Venda', { '/estado': 'SOLICITADO' });
  operacao(c, 'Confirmado', 'ordens-b', 'Venda', confirmado);
  consultar(c, 'SaldoCancelado', 'ordens-b', 'Venda', manual('CANCELADA', 3, 1));
  // Os 3 negócios anteriores ao corte seguem valendo; só o saldo de 1 voltou à carteira.
  carteira(c, 'CaixaComprador', 'risco-a', { '/saldoTotal': '960.00', '/saldoReservado': '0.00' });
  carteira(c, 'CustodiaVendedor', 'risco-b', {
    '/saldoTotal': '1000.00',
    '/saldoReservado': '0.00',
    '/custodias/0/total': 96,
    '/custodias/0/reservado': 0,
    '/custodias/0/disponivel': 96,
  });
}
{
  const c = 'CancelaStopPendente';
  preparar(c);
  fixarUltimo(c);
  enviarStop(c, 'StopVenda', 'ordens-b', 'STOP', 'VENDA', 2, '9.90');
  consultar(c, 'StopPendente', 'ordens-b', 'StopVenda', { '/estado': 'AGUARDANDO_GATILHO' });
  carteira(c, 'CustodiaReservada', 'risco-b', {
    '/custodias/0/total': 99,
    '/custodias/0/reservado': 2,
  });
  pedir(c, 'Cancelar', 'ordens-b', 'StopVenda', { '/estado': 'SOLICITADO' });
  operacao(c, 'Confirmado', 'ordens-b', 'StopVenda', confirmado);
  consultar(c, 'StopCancelado', 'ordens-b', 'StopVenda', {
    ...manual('CANCELADA', 0, 2),
    '/tipo': 'STOP',
  });
  carteira(c, 'CustodiaLiberada', 'risco-b', {
    '/custodias/0/total': 99,
    '/custodias/0/reservado': 0,
    '/custodias/0/disponivel': 99,
  });
}
{
  const c = 'CancelaStopLimitAcionadoComSaldo';
  preparar(c);
  fixarUltimo(c);
  enviarStop(c, 'StopLimit', 'ordens-a', 'STOP_LIMIT', 'COMPRA', 3, '10.10', '10.10');
  consultar(c, 'StopLimitPendente', 'ordens-a', 'StopLimit', { '/estado': 'AGUARDANDO_GATILHO' });
  enviar(c, 'Liquidez', 'ordens-b', 'VENDA', 2, 'DIA', '10.10');
  consultar(c, 'LiquidezAberta', 'ordens-b', 'Liquidez', { '/estado': 'ABERTA' });
  enviar(c, 'Gatilho', 'ordens-a', 'COMPRA', 1, 'IOC', '10.10');
  consultar(c, 'GatilhoExecutado', 'ordens-a', 'Gatilho', terminal('EXECUTADA', 1, 0));
  // Acionado: já virou Limit, executou 1 e repousa com saldo de 2; ainda é cancelável.
  consultar(c, 'StopLimitAcionado', 'ordens-a', 'StopLimit', {
    '/estado': 'PARCIALMENTE_EXECUTADA',
    '/tipo': 'STOP_LIMIT',
    '/quantidadeExecutada': 1,
  });
  consultar(c, 'LiquidezConsumida', 'ordens-b', 'Liquidez', terminal('EXECUTADA', 2, 0));
  pedir(c, 'Cancelar', 'ordens-a', 'StopLimit', { '/estado': 'SOLICITADO' });
  operacao(c, 'Confirmado', 'ordens-a', 'StopLimit', confirmado);
  consultar(c, 'SaldoCancelado', 'ordens-a', 'StopLimit', {
    ...manual('CANCELADA', 1, 2),
    '/tipo': 'STOP_LIMIT',
  });
  carteira(c, 'CaixaFinal', 'risco-a', {
    '/saldoTotal': '969.80',
    '/saldoDisponivel': '969.80',
    '/saldoReservado': '0.00',
  });
  carteira(c, 'CustodiaFinal', 'risco-b', {
    '/custodias/0/total': 97,
    '/custodias/0/reservado': 0,
  });
}
{
  const c = 'RecusaOcoInclusiveSobrevivente';
  preparar(c);
  fixarUltimo(c);
  passo(
    c,
    'Oco',
    'ordens-b',
    'POST',
    '/api/v1/ordens-oco',
    {
      ticker: 'ALFA3',
      lado: 'VENDA',
      quantidade: 3,
      validade: 'DIA',
      takeProfit: { precoLimite: '10.20' },
      stopLoss: { tipo: 'STOP', precoGatilho: '9.90' },
    },
    {},
    { Oco: '/grupoOco' },
    202,
  );
  passo(
    c,
    'OcoAdmitido',
    'ordens-b',
    'GET',
    '/api/v1/ordens-oco/${Oco}',
    null,
    {
      '/ordens/0/papelOco': 'TAKE_PROFIT',
      '/ordens/0/estado': 'ABERTA',
      '/ordens/1/papelOco': 'STOP_LOSS',
      '/ordens/1/estado': 'AGUARDANDO_GATILHO',
    },
    { Alvo: '/ordens/0/id', Stop: '/ordens/1/id' },
    200,
    true,
  );
  const oco = { '/codigo': 'ORDEM_OCO_NAO_CANCELAVEL' };
  pedir(c, 'AlvoRecusado', 'ordens-b', 'Alvo', oco, 422);
  pedir(c, 'StopRecusado', 'ordens-b', 'Stop', oco, 422);
  passo(
    c,
    'SemOperacao',
    'ordens-b',
    'GET',
    '/api/v1/ordens/${Alvo}/cancelamento',
    null,
    {},
    null,
    404,
  );
  enviar(c, 'CompraParcial', 'ordens-a', 'COMPRA', 1, 'IOC', '10.20');
  consultar(c, 'CompraParcialExecutada', 'ordens-a', 'CompraParcial', terminal('EXECUTADA', 1, 0));
  consultar(c, 'AlvoParcial', 'ordens-b', 'Alvo', {
    '/estado': 'PARCIALMENTE_EXECUTADA',
    '/quantidadeExecutada': 1,
  });
  consultar(c, 'StopSobrevivente', 'ordens-b', 'Stop', {
    '/estado': 'AGUARDANDO_GATILHO',
    '/quantidadeReduzida': 1,
  });
  // A perna que sobrevive, reduzida, e a parcial seguem sem cancelamento manual.
  pedir(c, 'StopSobreviventeRecusado', 'ordens-b', 'Stop', oco, 422);
  pedir(c, 'AlvoParcialRecusado', 'ordens-b', 'Alvo', oco, 422);
  enviar(c, 'CompraResto', 'ordens-a', 'COMPRA', 2, 'IOC', '10.20');
  consultar(c, 'CompraRestoExecutada', 'ordens-a', 'CompraResto', terminal('EXECUTADA', 2, 0));
  consultar(c, 'AlvoExecutado', 'ordens-b', 'Alvo', terminal('EXECUTADA', 3, 0));
  consultar(c, 'StopEncerradoPeloPar', 'ordens-b', 'Stop', {
    '/estado': 'CANCELADA',
    '/motivoEncerramento': 'CANCELADA_OCO',
  });
  carteira(c, 'CaixaFinalOco', 'risco-a', { '/saldoTotal': '959.40', '/saldoReservado': '0.00' });
  carteira(c, 'CustodiaFinalOco', 'risco-b', {
    '/custodias/0/total': 96,
    '/custodias/0/reservado': 0,
  });
}
await workbook.xlsx.writeFile(fileURLToPath(new URL('./cancelamento.xlsx', import.meta.url)));
