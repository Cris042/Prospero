import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// Pré-condição: fase RF08e da CI negociou com CONTA-004 e conciliou os movimentos por APIs.
// Repetir verifica efeitos únicos com data monotônica já em D+2, sem tentar recriar D0 ou redefinir carteira.
const wb = new ExcelJS.Workbook();
wb.created = wb.modified = new Date('2026-10-24T00:00:00Z');
wb.addWorksheet('Carga').addRow(STEP_COLUMNS);
const passos = wb.addWorksheet('Passos');
const resultados = wb.addWorksheet('Resultados');
passos.addRow(STEP_COLUMNS);
resultados.addRow(RESULT_COLUMNS);
let numero = 0;
function passo(id, servico, caminho, esperado, captura = {}, corpo = null) {
  passos.addRow([
    'LiquidacaoD2',
    ++numero,
    id,
    servico,
    corpo ? 'POST' : 'GET',
    caminho,
    corpo ? JSON.stringify({ 'content-type': 'application/json' }) : '',
    corpo ? JSON.stringify(corpo) : '',
    JSON.stringify(captura),
    corpo ? '' : 200,
    corpo ? '' : 30000,
  ]);
  resultados.addRow([
    'LiquidacaoD2',
    id,
    200,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(esperado),
  ]);
}
passo(
  'Negocio',
  'razao-robo',
  '/api/v1/negocios?limite=1',
  {
    '/conta': 'CONTA-004',
    '/negocios/0/liquidacao': 'LIQUIDADO',
  },
  { negocio: '/negocios/0/negocio', data: '/negocios/0/dataLiquidacao' },
);
passo('CreditoAplicado', 'risco-robo', '/api/v1/risco/liquidacoes/${negocio}', {
  '/conta': 'CONTA-004',
  '/creditos/0/negocio': '${negocio}',
  '/creditos/0/creditado': true,
});
passo(
  'CarteiraAntes',
  'risco-robo',
  '/api/v1/risco/carteira',
  { '/conta': 'CONTA-004' },
  {
    caixa: '/saldoTotal',
    disponivel: '/saldoDisponivel',
    custodia: '/custodias',
  },
);
passo(
  'AvancoIdempotente',
  'movimento',
  '/api/v1/data-movimento',
  { '/dataMovimento': '${data}', '/liquidacoes': 0 },
  {},
  { dataAlvo: '${data}' },
);
passo('CarteiraDepois', 'risco-robo', '/api/v1/risco/carteira', {
  '/saldoTotal': '${caixa}',
  '/saldoDisponivel': '${disponivel}',
  '/custodias': '${custodia}',
});
passo('RazaoD2', 'razao-robo', '/api/v1/razao?limite=2', {
  '/lancamentos/0/momento': 'D2',
  '/lancamentos/1/momento': 'D2',
  '/lancamentos/0/negocio': '${negocio}',
  '/lancamentos/1/negocio': '${negocio}',
  '/lancamentos/0/natureza': 'CREDITO',
  '/lancamentos/1/natureza': 'DEBITO',
});
await wb.xlsx.writeFile(fileURLToPath(new URL('./liquidacao.xlsx', import.meta.url)));
