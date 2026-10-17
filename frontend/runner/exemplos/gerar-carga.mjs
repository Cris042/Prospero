import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';
import { planosCarga } from './carga-planos.mjs';

const workbook = new ExcelJS.Workbook();
const carga = workbook.addWorksheet('Carga');
carga.addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);

function step(sheet, scenario, order, id, method, path, body, expected, options = {}) {
  sheet.addRow([
    scenario,
    order,
    id,
    'carga',
    method,
    path,
    '',
    body === undefined ? '' : JSON.stringify(body),
    options.capture ? JSON.stringify(options.capture) : '',
    options.poll ? 100 : '',
    options.poll ? 4000 : '',
  ]);
  resultados.addRow([
    scenario,
    id,
    expected.status,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(expected.body ?? {}),
  ]);
}

for (const scenario of ['Concluir', 'Cancelar']) {
  step(
    carga,
    scenario,
    1,
    'Iniciar',
    'POST',
    '/api/v1/execucoes-carga',
    { plano: planosCarga[scenario] },
    { status: 202 },
    { capture: { cargaId: '/id' } },
  );
}
step(
  passos,
  'Concluir',
  1,
  'Consultar',
  'GET',
  '/api/v1/execucoes-carga/${cargaId}',
  undefined,
  { status: 200, body: { '/estado': 'CONCLUIDA', '/contagens/emVoo': 0 } },
  { poll: true },
);
step(
  passos,
  'Cancelar',
  1,
  'CancelarCarga',
  'POST',
  '/api/v1/execucoes-carga/${cargaId}/cancelamentos',
  undefined,
  { status: 202 },
);
step(
  passos,
  'Cancelar',
  2,
  'Consultar',
  'GET',
  '/api/v1/execucoes-carga/${cargaId}',
  undefined,
  { status: 200, body: { '/estado': 'CANCELADA', '/contagens/emVoo': 0 } },
  { poll: true },
);
step(
  passos,
  'Cancelar',
  3,
  'CancelarNovamente',
  'POST',
  '/api/v1/execucoes-carga/${cargaId}/cancelamentos',
  undefined,
  { status: 200, body: { '/estado': 'CANCELADA' } },
);
step(
  passos,
  'Rejeitar',
  1,
  'RejeitarPlano',
  'POST',
  '/api/v1/execucoes-carga',
  { plano: { ...planosCarga.Concluir, concorrencia: 33 } },
  { status: 422, body: { '/codigo': 'PLANO_INVALIDO' } },
);

await workbook.xlsx.writeFile(fileURLToPath(new URL('./carga.xlsx', import.meta.url)));
