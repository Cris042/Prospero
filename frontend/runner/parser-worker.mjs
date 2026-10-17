import { parentPort, workerData } from 'node:worker_threads';
import ExcelJS from 'exceljs';
import yauzl from 'yauzl';
import { LIMITS, ensure } from './limits.mjs';

async function inspectZip(buffer) {
  await new Promise((resolve, reject) => {
    yauzl.fromBuffer(buffer, { lazyEntries: true, validateEntrySizes: true }, (error, zip) => {
      if (error) return reject(new Error('ZIP_INVALIDO'));
      let total = 0;
      let entries = 0;
      const names = new Set();
      const stop = () => {
        zip.close();
        reject(new Error('ZIP_INVALIDO_OU_LIMITE'));
      };
      zip.on('error', stop);
      zip.on('end', resolve);
      zip.on('entry', (entry) => {
        if (
          ++entries > LIMITS.entries ||
          names.has(entry.fileName) ||
          entry.uncompressedSize > LIMITS.entry ||
          entry.generalPurposeBitFlag & 1 ||
          !/^(?:\[Content_Types\]\.xml|[A-Za-z0-9_./-]+)$/.test(entry.fileName) ||
          entry.fileName.split('/').includes('..') ||
          /(?:vba|activeX|externalLinks|embeddings)/i.test(entry.fileName)
        )
          return stop();
        names.add(entry.fileName);
        zip.openReadStream(entry, (streamError, stream) => {
          if (streamError) return stop();
          let size = 0;
          stream.on('error', stop);
          stream.on('data', (chunk) => {
            size += chunk.length;
            total += chunk.length;
            if (size > LIMITS.entry || total > LIMITS.expanded) {
              stream.destroy();
              stop();
            }
          });
          stream.on('end', () => zip.readEntry());
        });
      });
      zip.readEntry();
    });
  });
}

let location;
try {
  const buffer = Buffer.from(workerData);
  await inspectZip(buffer);
  const workbook = new ExcelJS.Workbook();
  await workbook.xlsx.load(buffer);
  ensure(workbook.worksheets.length === 3, 'ABAS_INVALIDAS');
  const sheets = {};
  for (const sheet of workbook.worksheets) {
    location = undefined;
    ensure(
      ['Carga', 'Passos', 'Resultados'].includes(sheet.name) && sheet.state === 'visible',
      'ABAS_INVALIDAS',
    );
    ensure(sheet.rowCount <= LIMITS.rows + 1 && sheet.columnCount <= 11, 'LIMITE_CELULAS');
    ensure(Object.keys(sheet._merges).length === 0, 'CELULA_MESCLADA');
    const rows = [];
    sheet.eachRow({ includeEmpty: true }, (row, rowIndex) => {
      location = { aba: sheet.name, linha: rowIndex };
      const values = [];
      row.eachCell({ includeEmpty: true }, (cell, index) => {
        const value = cell.value;
        ensure(
          value === null ||
            typeof value === 'string' ||
            (typeof value === 'number' && Number.isSafeInteger(value)),
          'CELULA_INVALIDA',
        );
        ensure(typeof value !== 'string' || value.length <= LIMITS.cell, 'LIMITE_CELULA');
        values[index - 1] = value ?? '';
      });
      rows.push(values);
    });
    sheets[sheet.name] = rows;
  }
  parentPort.postMessage({ sheets });
} catch (error) {
  // Never forward parser internals: XML values and file paths may contain secrets.
  const code =
    /^(?:ABAS_INVALIDAS|LIMITE_CELULAS|CELULA_MESCLADA|CELULA_INVALIDA|LIMITE_CELULA|ZIP_INVALIDO(?:_OU_LIMITE)?)$/.test(
      error.message,
    )
      ? error.message
      : 'XLSX_INVALIDO';
  parentPort.postMessage({ error: code, location });
}
