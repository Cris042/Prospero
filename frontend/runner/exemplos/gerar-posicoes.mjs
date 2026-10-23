import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// Perfil CI isolado, antes de outras negociações em DELTA3. Cada execução fecha posições e PnL em zero;
// repetir não apaga fatos, cotação, deduplicação ou checkpoint. Não usar contra histórico arbitrário.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-23T00:00:00Z');
workbook.addWorksheet('Carga').addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
let ordem = 0;
function passo({
  id,
  servico,
  metodo = 'GET',
  caminho,
  corpo,
  esperado = {},
  captura,
  status = 200,
  esperar = false,
}) {
  passos.addRow([
    'PosicoesExatas',
    ++ordem,
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
    esperar ? 200 : '',
    esperar ? 15000 : '',
  ]);
  resultados.addRow([
    'PosicoesExatas',
    id,
    status,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(esperado),
  ]);
}
function posicoes(id, conta, quantidade, medio, realizado, naoRealizado) {
  passo({
    id,
    servico: `posicoes-${conta}`,
    caminho: '/api/v1/posicoes',
    esperar: true,
    esperado: {
      '/conta': conta === 'a' ? 'CONTA-002' : 'CONTA-003',
      '/sincronizado': true,
      '/base': 'NEGOCIOS_DESDE_ORIGEM',
      '/posicoes/2/ticker': 'DELTA3',
      '/posicoes/2/quantidade': quantidade,
      '/posicoes/2/precoMedio': medio,
      '/posicoes/2/resultadoRealizado': realizado,
      ...(naoRealizado === undefined ? {} : { '/posicoes/2/resultadoNaoRealizado': naoRealizado }),
    },
  });
}
posicoes('InicialA', 'a', '0', null, '0.0000');
posicoes('InicialB', 'b', '0', null, '0.0000');
passo({ id: 'SemToken', servico: 'posicoes-anonimo', caminho: '/api/v1/posicoes', status: 401 });
passo({
  id: 'AudienciaFO',
  servico: 'posicoes-token-fo',
  caminho: '/api/v1/posicoes',
  status: 401,
});
passo({
  id: 'OutraContaIgnorada',
  servico: 'posicoes-a',
  caminho: '/api/v1/posicoes?conta=CONTA-003',
  esperado: { '/conta': 'CONTA-002' },
});
for (const conta of ['002', '003'])
  passo({
    id: `Preparar${conta}`,
    servico: 'preparacao-risco',
    metodo: 'PUT',
    caminho: `/api/v1/preparacoes-risco/contas/CONTA-${conta}`,
    corpo: { saldo: '10000.00', custodia: { DELTA3: 100 } },
    esperado: { '/saldoReservado': '0.00' },
    esperar: true,
  });

function negocio(id, ladoA, quantidade, preco, auto = false) {
  const vendedor = auto ? 'a' : ladoA === 'COMPRA' ? 'b' : 'a';
  const comprador = auto ? 'a' : ladoA === 'COMPRA' ? 'a' : 'b';
  const pedido = (lado, validade) => ({
    ticker: 'DELTA3',
    lado,
    tipo: 'LIMIT',
    validade,
    quantidade,
    precoLimite: preco,
  });
  passo({
    id: `${id}Venda`,
    servico: `ordens-${vendedor}`,
    metodo: 'POST',
    caminho: '/api/v1/ordens',
    corpo: pedido('VENDA', 'DIA'),
    status: 202,
    captura: { [`${id}V`]: '/id' },
  });
  passo({
    id: `${id}Aberta`,
    servico: `ordens-${vendedor}`,
    caminho: `/api/v1/ordens/\${${id}V}`,
    esperado: { '/estado': 'ABERTA' },
    esperar: true,
  });
  passo({
    id: `${id}Compra`,
    servico: `ordens-${comprador}`,
    metodo: 'POST',
    caminho: '/api/v1/ordens',
    corpo: pedido('COMPRA', 'IOC'),
    status: 202,
    captura: { [`${id}C`]: '/id' },
  });
  for (const [sufixo, conta] of [
    ['V', vendedor],
    ['C', comprador],
  ])
    passo({
      id: `${id}Executada${sufixo}`,
      servico: `ordens-${conta}`,
      caminho: `/api/v1/ordens/\${${id}${sufixo}}`,
      esperado: { '/estado': 'EXECUTADA', '/quantidadeExecutada': quantidade },
      esperar: true,
    });
  passo({
    id: `${id}Cotacao`,
    servico: 'mercado',
    caminho: '/api/v1/ativos',
    esperado: { '/3/ticker': 'DELTA3', '/3/ultimoPreco': preco },
    esperar: true,
  });
}

negocio('Abre', 'COMPRA', 2, '40.00');
posicoes('AbreA', 'a', '2', '40.0000', '0.0000', '0.0000');
posicoes('AbreB', 'b', '-2', '40.0000', '0.0000', '0.0000');
negocio('Amplia', 'COMPRA', 1, '41.00');
posicoes('AmpliaA', 'a', '3', '40.3333', '0.0000', '2.0000');
posicoes('AmpliaB', 'b', '-3', '40.3333', '0.0000', '-2.0000');
negocio('Reduz', 'VENDA', 1, '41.00');
posicoes('ReduzA', 'a', '2', '40.3333', '0.6667', '1.3333');
posicoes('ReduzB', 'b', '-2', '40.3333', '-0.6667', '-1.3333');
negocio('Zera', 'VENDA', 2, '40.00');
posicoes('ZeraA', 'a', '0', null, '0.0000', '0.0000');
posicoes('ZeraB', 'b', '0', null, '0.0000', '0.0000');
negocio('Nova', 'COMPRA', 2, '40.00');
negocio('Lucro', 'VENDA', 1, '41.00');
posicoes('LucroA', 'a', '1', '40.0000', '1.0000', '1.0000');
negocio('ViraShort', 'VENDA', 2, '39.00');
posicoes('ShortA', 'a', '-1', '39.0000', '0.0000', '0.0000');
posicoes('LongB', 'b', '1', '39.0000', '0.0000', '0.0000');
negocio('ViraLong', 'COMPRA', 2, '38.00');
posicoes('LongA', 'a', '1', '38.0000', '1.0000', '0.0000');
posicoes('ShortB', 'b', '-1', '38.0000', '-1.0000', '0.0000');
negocio('Fecha', 'VENDA', 1, '37.00');
posicoes('FechaA', 'a', '0', null, '0.0000', '0.0000');
posicoes('FechaB', 'b', '0', null, '0.0000', '0.0000');
// Restaura a referência40 por negócio real e cobre autonegociação neutra.
negocio('Auto', 'COMPRA', 1, '40.00', true);
posicoes('AutoA', 'a', '0', null, '0.0000', '0.0000');
posicoes('AutoB', 'b', '0', null, '0.0000', '0.0000');
for (const conta of ['a', 'b'])
  passo({
    id: `ReservaFinal${conta}`,
    servico: `risco-${conta}`,
    caminho: '/api/v1/risco/carteira',
    esperado: { '/saldoReservado': '0.00' },
    esperar: true,
  });
await workbook.xlsx.writeFile(fileURLToPath(new URL('./posicoes.xlsx', import.meta.url)));
