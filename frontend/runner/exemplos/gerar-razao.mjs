import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF08b (ADR-016): negócios e razão D0 por APIs reais, logo após posicoes.xlsx e antes de outras negociações
// DELTA3. Abre e fecha 2 DELTA3 a 40,00 entre CONTA-002 e CONTA-003: posição/PnL voltam a zero e a cotação
// permanece 40. Repetível: identifica o negócio pela ordem capturada e compara datas com a própria resposta.
// A data de liquidação real depende do calendário do dia; o D+2 exato é provado pelos testes com datas fixas.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-24T00:00:00Z');
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
    'RazaoD0',
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
    'RazaoD0',
    id,
    status,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(esperado),
  ]);
}
const NAO_ENCONTRADO = {
  '/status': 404,
  '/codigo': 'NEGOCIO_NAO_ENCONTRADO',
  '/title': 'Negócio não encontrado para esta conta.',
  '/detail': 'Negócio não encontrado para esta conta.',
};
const INVALIDA = { '/status': 400, '/codigo': 'CONSULTA_INVALIDA' };

passo({ id: 'SemToken', servico: 'razao-anonimo', caminho: '/api/v1/razao', status: 401 });
passo({ id: 'AudienciaFO', servico: 'razao-token-fo', caminho: '/api/v1/negocios', status: 401 });
passo({
  id: 'EscopoPosicoes',
  servico: 'razao-token-posicoes',
  caminho: '/api/v1/razao',
  status: 403,
});
passo({
  id: 'LimiteExcedido',
  servico: 'razao-a',
  caminho: '/api/v1/razao?limite=101',
  status: 400,
  esperado: INVALIDA,
});
passo({
  id: 'CursorAdulterado',
  servico: 'razao-a',
  caminho: '/api/v1/negocios?cursor=djEucmF6YW8uOS4wMDAwMDAwMDAwMDAwMDAw',
  status: 400,
  esperado: INVALIDA,
});
passo({
  id: 'Inexistente',
  servico: 'razao-a',
  caminho: '/api/v1/negocios/DELTA3-2026-01-02-999999',
  status: 404,
  esperado: NAO_ENCONTRADO,
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

function negocio(id, vendedor, comprador) {
  const pedido = (lado, validade) => ({
    ticker: 'DELTA3',
    lado,
    tipo: 'LIMIT',
    validade,
    quantidade: 2,
    precoLimite: '40.00',
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
      esperado: { '/estado': 'EXECUTADA', '/quantidadeExecutada': 2 },
      captura: sufixo === 'C' ? { [`${id}Sessao`]: '/dataSessao' } : undefined,
      esperar: true,
    });
}
function participacao(id, servico, conta, lado, ordemCapturada, extra = {}, captura) {
  passo({
    id,
    servico,
    caminho: '/api/v1/negocios?limite=1',
    esperar: true,
    captura,
    esperado: {
      '/conta': conta,
      '/negocios/0/ordem': `\${${ordemCapturada}}`,
      '/negocios/0/lado': lado,
      '/negocios/0/ticker': 'DELTA3',
      '/negocios/0/preco': '40.00',
      '/negocios/0/quantidade': '2',
      '/negocios/0/valor': '80.00',
      '/negocios/0/liquidacao': 'PENDENTE',
      ...extra,
    },
  });
}
function partidas(linhas) {
  const esperado = {};
  linhas.forEach(([contaContabil, natureza, unidade, valor], i) => {
    Object.assign(esperado, {
      [`/lancamentos/${i}/negocio`]: '${negocio}',
      [`/lancamentos/${i}/momento`]: 'D0',
      [`/lancamentos/${i}/data`]: '${AbreSessao}',
      [`/lancamentos/${i}/contaContabil`]: contaContabil,
      [`/lancamentos/${i}/natureza`]: natureza,
      [`/lancamentos/${i}/unidade`]: unidade,
      [`/lancamentos/${i}/valor`]: valor,
    });
  });
  return esperado;
}

// D0: CONTA-002 compra 2 DELTA3 × 40,00 de CONTA-003 (plano de contas do ADR-016, mais recente primeiro).
negocio('Abre', 'b', 'a');
participacao(
  'NegocioComprador',
  'razao-a',
  'CONTA-002',
  'COMPRADOR',
  'AbreC',
  { '/negocios/0/dataSessao': '${AbreSessao}' },
  { negocio: '/negocios/0/negocio', liquidacao: '/negocios/0/dataLiquidacao' },
);
participacao('NegocioVendedor', 'razao-b', 'CONTA-003', 'VENDEDOR', 'AbreV', {
  '/negocios/0/negocio': '${negocio}',
  '/negocios/0/dataSessao': '${AbreSessao}',
  '/negocios/0/dataLiquidacao': '${liquidacao}',
});
passo({
  id: 'Detalhe',
  servico: 'razao-a',
  caminho: '/api/v1/negocios/${negocio}',
  esperado: {
    '/conta': 'CONTA-002',
    '/negocio': '${negocio}',
    '/valor': '80.00',
    '/dataLiquidacao': '${liquidacao}',
    '/lados/0/lado': 'COMPRADOR',
    '/lados/0/ordem': '${AbreC}',
    '/lados/0/liquidacao': 'PENDENTE',
  },
});
passo({
  id: 'NaoParticipa',
  servico: 'razao-c',
  caminho: '/api/v1/negocios/${negocio}',
  status: 404,
  esperado: NAO_ENCONTRADO,
});
const comprador = partidas([
  ['CAMARA', 'CREDITO', 'DELTA3', '2'],
  ['CUSTODIA_A_RECEBER', 'DEBITO', 'DELTA3', '2'],
  ['CAIXA', 'CREDITO', 'BRL', '80.00'],
  ['CAMARA', 'DEBITO', 'BRL', '80.00'],
]);
passo({
  id: 'RazaoComprador',
  servico: 'razao-a',
  caminho: '/api/v1/razao?limite=4',
  esperado: { '/conta': 'CONTA-002', ...comprador },
});
passo({
  id: 'RazaoPagina1',
  servico: 'razao-a',
  caminho: '/api/v1/razao?limite=2',
  captura: { cursor: '/proximoCursor' },
  esperado: { '/lancamentos/1/contaContabil': 'CUSTODIA_A_RECEBER' },
});
passo({
  id: 'RazaoPagina2',
  servico: 'razao-a',
  caminho: '/api/v1/razao?limite=2&cursor=${cursor}',
  esperado: {
    '/lancamentos/0/negocio': '${negocio}',
    '/lancamentos/0/contaContabil': 'CAIXA',
    '/lancamentos/0/valor': '80.00',
    '/lancamentos/1/contaContabil': 'CAMARA',
    '/lancamentos/1/natureza': 'DEBITO',
  },
});
passo({
  id: 'CursorOutraConta',
  servico: 'razao-b',
  caminho: '/api/v1/razao?cursor=${cursor}',
  status: 400,
  esperado: INVALIDA,
});
const vendedor = partidas([
  ['CAMARA', 'CREDITO', 'BRL', '80.00'],
  ['CAIXA_A_RECEBER', 'DEBITO', 'BRL', '80.00'],
  ['CUSTODIA', 'CREDITO', 'DELTA3', '2'],
  ['CAMARA', 'DEBITO', 'DELTA3', '2'],
]);
passo({
  id: 'RazaoVendedor',
  servico: 'razao-b',
  caminho: '/api/v1/razao?limite=4',
  esperado: { '/conta': 'CONTA-003', ...vendedor },
});

// Fechamento por negócio real inverso: posições e PnL de DELTA3 voltam a zero, cotação continua 40,00.
negocio('Fecha', 'a', 'b');
participacao('FechamentoVendedor', 'razao-a', 'CONTA-002', 'VENDEDOR', 'FechaV');
for (const [conta, servico] of [
  ['CONTA-002', 'posicoes-a'],
  ['CONTA-003', 'posicoes-b'],
])
  passo({
    id: `Posicao${conta.slice(-3)}`,
    servico,
    caminho: '/api/v1/posicoes',
    esperar: true,
    esperado: {
      '/conta': conta,
      '/posicoes/2/ticker': 'DELTA3',
      '/posicoes/2/quantidade': '0',
      '/posicoes/2/resultadoRealizado': '0.0000',
    },
  });
passo({
  id: 'Cotacao',
  servico: 'mercado',
  caminho: '/api/v1/ativos',
  esperado: { '/3/ticker': 'DELTA3', '/3/ultimoPreco': '40.00' },
  esperar: true,
});
for (const conta of ['a', 'b'])
  passo({
    id: `ReservaFinal${conta}`,
    servico: `risco-${conta}`,
    caminho: '/api/v1/risco/carteira',
    esperado: { '/saldoReservado': '0.00' },
    esperar: true,
  });
await workbook.xlsx.writeFile(fileURLToPath(new URL('./razao.xlsx', import.meta.url)));
