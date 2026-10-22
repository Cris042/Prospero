import ExcelJS from 'exceljs';
import { fileURLToPath } from 'node:url';
import { STEP_COLUMNS, RESULT_COLUMNS } from '../contract.mjs';

// RF05c.4: encerramento manual de sessão só por APIs. Executado por último: após encerrar, o ativo recusa novas
// ordens na mesma data (uma sessão por log), por isso não é repetível no mesmo ambiente.
const workbook = new ExcelJS.Workbook();
workbook.created = workbook.modified = new Date('2026-10-21T00:00:00Z');
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
    corpo || metodo === 'POST'
      ? JSON.stringify({
          ...(corpo ? { 'content-type': 'application/json' } : {}),
          ...(metodo === 'POST' && corpo ? { 'x-idempotency-key': `\${cenario}-${id}` } : {}),
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
const ordem = (quantidade) => ({
  ticker: 'GAMA3',
  lado: 'COMPRA',
  tipo: 'LIMIT',
  validade: 'DIA',
  quantidade,
  precoLimite: '29.00',
});
{
  const c = 'EncerramentoExpiraEPassaARecusar';
  passo(
    c,
    'Oferta',
    'ordens-a',
    'POST',
    '/api/v1/ordens',
    ordem(1),
    {},
    { Oferta: '/id', Dia: '/dataSessao' },
    202,
  );
  passo(
    c,
    'OfertaAberta',
    'ordens-a',
    'GET',
    '/api/v1/ordens/${Oferta}',
    null,
    { '/estado': 'ABERTA' },
    null,
    200,
    true,
  );
  passo(
    c,
    'Encerrar',
    'sessao',
    'POST',
    '/api/v1/sessoes/GAMA3/${Dia}/encerramento',
    null,
    {
      '/ticker': 'GAMA3',
      '/repetido': false,
    },
    null,
    202,
  );
  passo(
    c,
    'OfertaExpirada',
    'ordens-a',
    'GET',
    '/api/v1/ordens/${Oferta}',
    null,
    {
      '/estado': 'CANCELADA',
      '/motivoEncerramento': 'EXPIRADA_DIA',
      '/quantidadeExecutada': 0,
      '/quantidadeCancelada': 1,
    },
    null,
    200,
    true,
  );
  passo(
    c,
    'EncerrarDeNovo',
    'sessao',
    'POST',
    '/api/v1/sessoes/GAMA3/${Dia}/encerramento',
    null,
    {
      '/repetido': true,
    },
    null,
    202,
  );
  passo(c, 'Tardia', 'ordens-a', 'POST', '/api/v1/ordens', ordem(2), {}, { Tardia: '/id' }, 202);
  passo(
    c,
    'TardiaRejeitada',
    'ordens-a',
    'GET',
    '/api/v1/ordens/${Tardia}',
    null,
    {
      '/estado': 'REJEITADA',
      '/motivoRejeicao': 'SESSAO_ENCERRADA',
    },
    null,
    200,
    true,
  );
}
{
  const c = 'EncerramentoSemPermissao';
  passo(
    c,
    'DataInvalida',
    'sessao',
    'POST',
    '/api/v1/sessoes/GAMA3/18-10-2026/encerramento',
    null,
    {
      '/codigo': 'DATA_INVALIDA',
    },
    null,
    400,
  );
  passo(
    c,
    'AtivoDesconhecido',
    'sessao',
    'POST',
    '/api/v1/sessoes/ZZZZ3/2026-10-18/encerramento',
    null,
    {
      '/codigo': 'ATIVO_DESCONHECIDO',
    },
    null,
    404,
  );
}
await workbook.xlsx.writeFile(fileURLToPath(new URL('./sessao.xlsx', import.meta.url)));
