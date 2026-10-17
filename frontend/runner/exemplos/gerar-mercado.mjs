import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF01: cada cenário prepara e limpa somente o próprio namespace (${cenario}) por API.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-17T00:00:00Z');
const carga = workbook.addWorksheet('Carga');
carga.addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
const SESSAO = '2026-10-16';
const JSON_CT = { 'content-type': 'application/json' };
const ordens = new Map();

function passo(sheet, cenario, id, metodo, caminho, corpo, status, esperado = {}) {
  const chave = sheet.name + cenario;
  const ordem = (ordens.get(chave) ?? 0) + 1;
  ordens.set(chave, ordem);
  sheet.addRow([
    cenario,
    ordem,
    id,
    'front-office',
    metodo,
    caminho,
    corpo === undefined ? '' : JSON.stringify(JSON_CT),
    corpo === undefined ? '' : JSON.stringify(corpo),
    '',
    '',
    '',
  ]);
  resultados.addRow([
    cenario,
    id,
    status,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(esperado),
  ]);
}

const preparar = (ns, ticker) => `/api/v1/preparacoes-mercado/${ns}/ativos/${ticker}`;
const livro = (ns, ticker) => `/api/v1/ativos/${ticker}/livro?namespace=${ns}`;
const oferta = (lado, preco, quantidade) => ({ lado, preco, quantidade });

// 1. Níveis, agregação, corte em cinco e indicadores.
{
  const c = 'LivroComNiveis';
  const compras = ['9.99', '9.98', '9.97', '9.96', '9.95', '9.94', '9.93'].map((p) =>
    oferta('COMPRA', p, 100),
  );
  passo(
    carga,
    c,
    'LimparInicio',
    'DELETE',
    '/api/v1/preparacoes-mercado/${cenario}',
    undefined,
    204,
  );
  passo(
    carga,
    c,
    'Preparar',
    'PUT',
    preparar('${cenario}', 'ALFA3'),
    {
      sessao: SESSAO,
      ofertas: [...compras, oferta('COMPRA', '9.99', 50), oferta('VENDA', '10.02', 300)],
      negocios: [
        { preco: '10.20', quantidade: 100 },
        { preco: '10.35', quantidade: 300 },
      ],
    },
    200,
    { '/ticker': 'ALFA3', '/revisao': 1 },
  );
  passo(passos, c, 'Ativos', 'GET', '/api/v1/ativos?namespace=${cenario}', undefined, 200, {
    '/0/ticker': 'ALFA3',
    '/0/ultimoPreco': '10.35',
    '/0/variacao': '0.35',
    '/0/variacaoPercentual': '3.50',
    '/0/melhorCompra': '9.99',
    '/0/melhorVenda': '10.02',
    '/0/spread': '0.03',
    '/0/volumeQuantidade': 400,
    '/0/volumeFinanceiro': '4125.00',
    '/0/estadoLivro': 'COMPLETO',
    '/0/origem': 'PREPARACAO_SINTETICA',
    '/1/estadoLivro': 'SEM_DADOS',
    '/1/ultimoPreco': null,
  });
  passo(passos, c, 'Livro', 'GET', livro('${cenario}', 'ALFA3'), undefined, 200, {
    '/compra/niveis/0/preco': '9.99',
    '/compra/niveis/0/quantidade': 150,
    '/compra/niveis/0/ofertas': 2,
    '/compra/niveis/4/preco': '9.95',
    '/compra/niveis/4/quantidadeAcumulada': 550,
    '/compra/niveisTotal': 7,
    '/compra/quantidadeTotal': 750,
    '/venda/niveis/0/preco': '10.02',
  });
  passo(passos, c, 'Limpar', 'DELETE', '/api/v1/preparacoes-mercado/${cenario}', undefined, 204);
  passo(passos, c, 'SemDados', 'GET', livro('${cenario}', 'ALFA3'), undefined, 200, {
    '/estadoLivro': 'SEM_DADOS',
    '/revisao': null,
  });
}

// 2. Livro vazio, unilateral e nova revisão.
{
  const c = 'VazioEUnilateral';
  passo(
    carga,
    c,
    'Vazio',
    'PUT',
    preparar('${cenario}', 'BETA3'),
    { sessao: SESSAO, ofertas: [], negocios: [] },
    200,
    {
      '/revisao': 1,
    },
  );
  passo(passos, c, 'LivroVazio', 'GET', livro('${cenario}', 'BETA3'), undefined, 200, {
    '/estadoLivro': 'VAZIO',
    '/compra/niveisTotal': 0,
  });
  passo(
    passos,
    c,
    'SomenteVenda',
    'PUT',
    preparar('${cenario}', 'BETA3'),
    { sessao: SESSAO, ofertas: [oferta('VENDA', '20.10', 10)], negocios: [] },
    200,
    { '/revisao': 2 },
  );
  passo(passos, c, 'Unilateral', 'GET', '/api/v1/ativos?namespace=${cenario}', undefined, 200, {
    '/1/estadoLivro': 'SOMENTE_VENDA',
    '/1/melhorVenda': '20.10',
    '/1/spread': null,
    '/1/ultimoPreco': null,
    '/1/volumeQuantidade': null,
    '/1/revisao': 2,
  });
  passo(passos, c, 'Limpar', 'DELETE', '/api/v1/preparacoes-mercado/${cenario}', undefined, 204);
}

// 3. Rejeições sem escrita parcial.
{
  const c = 'Rejeicoes';
  passo(
    carga,
    c,
    'Base',
    'PUT',
    preparar('${cenario}', 'GAMA3'),
    {
      sessao: SESSAO,
      ofertas: [oferta('COMPRA', '29.90', 10)],
      negocios: [],
    },
    200,
    { '/revisao': 1 },
  );
  passo(
    passos,
    c,
    'Cruzado',
    'PUT',
    preparar('${cenario}', 'GAMA3'),
    {
      sessao: SESSAO,
      ofertas: [oferta('COMPRA', '30.10', 1), oferta('VENDA', '30.00', 1)],
      negocios: [],
    },
    422,
    { '/codigo': 'LIVRO_CRUZADO' },
  );
  passo(
    passos,
    c,
    'ForaDoTick',
    'PUT',
    preparar('${cenario}', 'GAMA3'),
    { sessao: SESSAO, ofertas: [oferta('VENDA', '30.005', 1)], negocios: [] },
    422,
    { '/codigo': 'PRECO_INVALIDO' },
  );
  passo(
    passos,
    c,
    'CampoDesconhecido',
    'PUT',
    preparar('${cenario}', 'GAMA3'),
    { sessao: SESSAO, ofertas: [], negocios: [], extra: true },
    400,
    { '/codigo': 'JSON_INVALIDO' },
  );
  passo(
    passos,
    c,
    'AtivoDesconhecido',
    'PUT',
    preparar('${cenario}', 'ZETA3'),
    {
      sessao: SESSAO,
      ofertas: [],
      negocios: [],
    },
    404,
    { '/codigo': 'ATIVO_DESCONHECIDO' },
  );
  passo(
    passos,
    c,
    'NamespaceInvalido',
    'PUT',
    preparar('nao-uuid', 'GAMA3'),
    {
      sessao: SESSAO,
      ofertas: [],
      negocios: [],
    },
    400,
    { '/codigo': 'NAMESPACE_INVALIDO' },
  );
  passo(passos, c, 'Preservado', 'GET', livro('${cenario}', 'GAMA3'), undefined, 200, {
    '/revisao': 1,
    '/estadoLivro': 'SOMENTE_COMPRA',
  });
  passo(passos, c, 'Limpar', 'DELETE', '/api/v1/preparacoes-mercado/${cenario}', undefined, 204);
}

// 4. Isolamento entre namespaces: ${execucao} nunca recebe o snapshot de ${cenario}.
{
  const c = 'Isolamento';
  passo(
    carga,
    c,
    'Preparar',
    'PUT',
    preparar('${cenario}', 'DELTA3'),
    {
      sessao: SESSAO,
      ofertas: [oferta('VENDA', '40.50', 5)],
      negocios: [{ preco: '40.40', quantidade: 5 }],
    },
    200,
  );
  passo(passos, c, 'Proprio', 'GET', livro('${cenario}', 'DELTA3'), undefined, 200, {
    '/estadoLivro': 'SOMENTE_VENDA',
  });
  passo(passos, c, 'Outro', 'GET', livro('${execucao}', 'DELTA3'), undefined, 200, {
    '/estadoLivro': 'SEM_DADOS',
  });
  passo(
    passos,
    c,
    'LimparRepetido',
    'DELETE',
    '/api/v1/preparacoes-mercado/${cenario}',
    undefined,
    204,
  );
  passo(
    passos,
    c,
    'LimparDeNovo',
    'DELETE',
    '/api/v1/preparacoes-mercado/${cenario}',
    undefined,
    204,
  );
}

await workbook.xlsx.writeFile(fileURLToPath(new URL('./mercado.xlsx', import.meta.url)));
