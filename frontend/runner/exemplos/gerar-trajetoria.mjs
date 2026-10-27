import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF09a: fatos de recebimento/rejeição MO, privacidade e paginação somente por APIs.
// Perfil risco em CI isolada; saldo zero impede negócio/reserva, sem apagar histórico.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-25T00:00:00Z');
workbook.addWorksheet('Carga').addRow(STEP_COLUMNS);
const passos = workbook.addWorksheet('Passos');
passos.addRow(STEP_COLUMNS);
const resultados = workbook.addWorksheet('Resultados');
resultados.addRow(RESULT_COLUMNS);
let ordem = 0;
function passo(id, servico, metodo, caminho, opcoes = {}) {
  passos.addRow([
    'TrajetoriaRejeitada',
    ++ordem,
    id,
    servico,
    metodo,
    caminho,
    JSON.stringify({
      ...(opcoes.corpo ? { 'content-type': 'application/json' } : {}),
      ...(metodo === 'POST' ? { 'x-idempotency-key': '${cenario}-trajetoria' } : {}),
    }),
    opcoes.corpo ? JSON.stringify(opcoes.corpo) : '',
    opcoes.captura ? JSON.stringify(opcoes.captura) : '',
    opcoes.esperar ? 200 : '',
    opcoes.esperar ? 15000 : '',
  ]);
  resultados.addRow([
    'TrajetoriaRejeitada',
    id,
    opcoes.status ?? 200,
    JSON.stringify({ 'cache-control': 'no-store' }),
    JSON.stringify(opcoes.esperado ?? {}),
  ]);
}
passo('Preparar', 'preparacao-risco', 'PUT', '/api/v1/preparacoes-risco/contas/CONTA-002', {
  corpo: { saldo: '0.00', custodia: {} },
  esperar: true,
  esperado: { '/conta': 'CONTA-002', '/saldoReservado': '0.00' },
});
passo('Enviar', 'ordens-a', 'POST', '/api/v1/ordens', {
  corpo: { ticker: 'DELTA3', lado: 'COMPRA', tipo: 'MARKET', validade: 'IOC', quantidade: 1 },
  status: 202,
  captura: { ordem: '/id' },
  esperado: { '/estado': 'RECEBIDA' },
});
passo('Rejeitada', 'ordens-a', 'GET', '/api/v1/ordens/${ordem}', {
  esperar: true,
  esperado: { '/estado': 'REJEITADA', '/motivoRejeicao': 'SALDO_INSUFICIENTE' },
});
passo('Recebimento', 'ordens-a', 'GET', '/api/v1/ordens/${ordem}/trajetoria?limite=1', {
  captura: { cursor: '/proximoCursor' },
  esperado: {
    '/ordem': '${ordem}',
    '/conta': 'CONTA-002',
    '/historicoCompleto': true,
    '/eventos/0/origem': 'front-office',
    '/eventos/0/tipo': 'ORDEM_RECEBIDA',
    '/eventos/0/quantidade': '1',
  },
});
passo('Risco', 'ordens-a', 'GET', '/api/v1/ordens/${ordem}/trajetoria?cursor=${cursor}&limite=1', {
  esperado: {
    '/eventos/0/origem': 'middle-office',
    '/eventos/0/tipo': 'RISCO_REJEITADO',
    '/eventos/0/motivo': 'SALDO_INSUFICIENTE',
    '/proximoCursor': null,
  },
});
passo('OutraConta', 'ordens-b', 'GET', '/api/v1/ordens/${ordem}/trajetoria', { status: 404 });
passo('LimiteInvalido', 'ordens-a', 'GET', '/api/v1/ordens/${ordem}/trajetoria?limite=101', {
  status: 400,
});
passo('CursorInvalido', 'ordens-a', 'GET', '/api/v1/ordens/${ordem}/trajetoria?cursor=invalido', {
  status: 400,
});
await workbook.xlsx.writeFile(fileURLToPath(new URL('./trajetoria.xlsx', import.meta.url)));
