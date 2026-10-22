import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF06a: somente APIs reais; BETA3 a 20,00 mantém VWAP verificável também na repetição.
// Pré-condição CI: executar antes de matching/RNF04, sem negócios ALFA3 e sem histórico BETA3
// em outros preços. A repetição é permitida; termina sem reservas/ofertas nas contas002/003.
// A prova REFERENCIA só recusa uma ordem: não cria negócio ALFA3 nem depende de limpeza.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-22T00:00:00Z');
const carga = workbook.addWorksheet('Carga');
carga.addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
let cenario = 'TunelAntesDoPrimeiroNegocio';
let ordem = 0;
function passo(
  id,
  servico,
  metodo,
  caminho,
  corpo,
  esperado = {},
  captura,
  status = 200,
  polling = false,
  chave = id,
) {
  passos.addRow([
    cenario,
    ++ordem,
    id,
    servico,
    metodo,
    caminho,
    corpo
      ? JSON.stringify({
          'content-type': 'application/json',
          ...(metodo === 'POST' ? { 'x-idempotency-key': `\${cenario}-${chave}` } : {}),
        })
      : '',
    corpo ? JSON.stringify(corpo) : '',
    captura ? JSON.stringify(captura) : '',
    polling ? 200 : '',
    polling ? 15000 : '',
  ]);
  resultados.addRow([
    cenario,
    id,
    status,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(esperado),
  ]);
}
passo(
  'ReferenciaSemNegocio',
  'ordens-a',
  'POST',
  '/api/v1/ordens',
  {
    ticker: 'ALFA3',
    lado: 'COMPRA',
    tipo: 'LIMIT',
    validade: 'DIA',
    quantidade: 1,
    precoLimite: '8.99',
  },
  {
    '/codigo': 'PRECO_FORA_DO_TUNEL',
    '/detalhes/base': '10.00',
    '/detalhes/origemBase': 'REFERENCIA',
  },
  null,
  422,
);
cenario = 'CotacaoNegocioLivroIdempotencia';
ordem = 0;
for (const conta of ['002', '003']) {
  passo(
    `Preparar${conta}`,
    'preparacao-risco',
    'PUT',
    `/api/v1/preparacoes-risco/contas/CONTA-${conta}`,
    { saldo: '1000.00', custodia: { BETA3: 100 } },
    { '/saldoReservado': '0.00' },
    null,
    200,
    true,
  );
}
const pedido = (lado, quantidade, validade = 'DIA') => ({
  ticker: 'BETA3',
  lado,
  tipo: 'LIMIT',
  validade,
  quantidade,
  precoLimite: '20.00',
});
function enviar(id, servico, corpo, esperado = {}, chave = id) {
  passo(id, servico, 'POST', '/api/v1/ordens', corpo, esperado, { [id]: '/id' }, 202, false, chave);
}
function estado(id, servico, variavel, esperado) {
  passo(id, servico, 'GET', `/api/v1/ordens/\${${variavel}}`, null, esperado, null, 200, true);
}
function livro(id, esperado, captura) {
  passo(
    id,
    'mercado',
    'GET',
    '/api/v1/ativos/BETA3/livro',
    null,
    { '/origem': 'MATCHING', ...esperado },
    captura,
    200,
    true,
  );
}
function resumo(id, esperado, captura) {
  passo(
    id,
    'mercado',
    'GET',
    '/api/v1/ativos',
    null,
    { '/1/ticker': 'BETA3', '/1/origem': 'MATCHING', '/1/defasado': false, ...esperado },
    captura,
    200,
    true,
  );
}
enviar('Venda', 'ordens-b', pedido('VENDA', 10));
estado('VendaAberta', 'ordens-b', 'Venda', { '/estado': 'ABERTA' });
livro('OfertaSemNegocio', {
  '/estadoLivro': 'SOMENTE_VENDA',
  '/venda/niveis/0/preco': '20.00',
  '/venda/niveis/0/quantidade': 10,
  '/venda/niveis/0/ofertas': 1,
  '/compra/niveisTotal': 0,
});
resumo('Ask', { '/1/melhorVenda': '20.00', '/1/melhorCompra': null });
enviar('CompraParcial', 'ordens-a', pedido('COMPRA', 3, 'IOC'));
estado('ParcialExecutada', 'ordens-a', 'CompraParcial', {
  '/estado': 'EXECUTADA',
  '/quantidadeExecutada': 3,
});
livro('ProfundidadeParcial', { '/venda/niveis/0/quantidade': 7 });
resumo('UltimoVWAP', {
  '/1/ultimoPreco': '20.00',
  '/1/vwap': '20.0000',
  '/1/melhorVenda': '20.00',
});
passo(
  'UltimoRealForaDoTunel',
  'ordens-a',
  'POST',
  '/api/v1/ordens',
  { ...pedido('COMPRA', 1), precoLimite: '17.99' },
  {
    '/codigo': 'PRECO_FORA_DO_TUNEL',
    '/detalhes/base': '20.00',
    '/detalhes/origemBase': 'ULTIMO',
    '/detalhes/minimo': '18.00',
    '/detalhes/maximo': '22.00',
  },
  null,
  422,
);
enviar('CompraIoc', 'ordens-a', pedido('COMPRA', 9, 'IOC'), {
  '/precoBaseTunel': '20.00',
  '/origemBase': 'ULTIMO',
});
estado('IocSaldoCancelado', 'ordens-a', 'CompraIoc', {
  '/estado': 'CANCELADA',
  '/quantidadeExecutada': 7,
  '/quantidadeCancelada': 2,
});
estado('VendaConsumida', 'ordens-b', 'Venda', {
  '/estado': 'EXECUTADA',
  '/quantidadeExecutada': 10,
});
livro('LivroVazio', { '/estadoLivro': 'VAZIO', '/compra/niveisTotal': 0, '/venda/niveisTotal': 0 });
enviar('CompraLivro', 'ordens-a', pedido('COMPRA', 4));
estado('CompraAberta', 'ordens-a', 'CompraLivro', { '/estado': 'ABERTA' });
livro('BidSemNegocio', {
  '/estadoLivro': 'SOMENTE_COMPRA',
  '/compra/niveis/0/preco': '20.00',
  '/compra/niveis/0/quantidade': 4,
});
resumo('Bid', { '/1/melhorCompra': '20.00', '/1/melhorVenda': null, '/1/vwap': '20.0000' });
enviar('VendaIoc', 'ordens-b', pedido('VENDA', 4, 'IOC'));
estado('VendaIocExecutada', 'ordens-b', 'VendaIoc', {
  '/estado': 'EXECUTADA',
  '/quantidadeExecutada': 4,
});
estado('CompraConsumida', 'ordens-a', 'CompraLivro', {
  '/estado': 'EXECUTADA',
  '/quantidadeExecutada': 4,
});
livro('LivroFinal', { '/estadoLivro': 'VAZIO' }, { Revisao: '/revisao' });
resumo(
  'AcumuladoresFinais',
  {
    '/1/ultimoPreco': '20.00',
    '/1/vwap': '20.0000',
    '/1/melhorCompra': null,
    '/1/melhorVenda': null,
  },
  { Volume: '/1/volumeQuantidade', Financeiro: '/1/volumeFinanceiro' },
);
// Mesma chave/corpo deve retornar a ordem original, sem novo comando ou negócio.
passo(
  'RepetirVendaIoc',
  'ordens-b',
  'POST',
  '/api/v1/ordens',
  pedido('VENDA', 4, 'IOC'),
  { '/id': '${VendaIoc}' },
  null,
  202,
  false,
  'VendaIoc',
);
livro('RevisaoPreservada', { '/estadoLivro': 'VAZIO', '/revisao': '${Revisao}' });
resumo('VolumePreservado', {
  '/1/vwap': '20.0000',
  '/1/volumeQuantidade': '${Volume}',
  '/1/volumeFinanceiro': '${Financeiro}',
});
for (const servico of ['risco-a', 'risco-b']) {
  passo(
    `ReservaFinal${servico === 'risco-a' ? 'A' : 'B'}`,
    servico,
    'GET',
    '/api/v1/risco/carteira',
    null,
    { '/saldoReservado': '0.00', '/custodias/0/reservado': 0 },
    null,
    200,
    true,
  );
}
await workbook.xlsx.writeFile(fileURLToPath(new URL('./cotacao.xlsx', import.meta.url)));
