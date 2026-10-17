import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';
const workbook = new ExcelJS.Workbook();
const carga = workbook.addWorksheet('Carga');
carga.addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
for (const service of ['front-office', 'middle-office', 'matching-engine', 'back-office']) {
  // Carga técnica usa GET; criação financeira será demonstrada pelos cenários das RFs.
  carga.addRow([
    service,
    1,
    'Preparar',
    service,
    'GET',
    '/api/v1/sistema',
    '',
    '',
    JSON.stringify({ nome: '/servico' }),
    '',
    '',
  ]);
  passos.addRow([service, 1, 'Consultar', service, 'GET', '/api/v1/sistema', '', '', '', '', '']);
  resultados.addRow([
    service,
    'Preparar',
    200,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify({ '/servico': service, '/etapa': 'FUNDAÇÃO' }),
  ]);
  resultados.addRow([
    service,
    'Consultar',
    200,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify({ '/servico': '${nome}', '/versao': '0.1.0-SNAPSHOT' }),
  ]);
}
await workbook.xlsx.writeFile(fileURLToPath(new URL('./fundacao.xlsx', import.meta.url)));
