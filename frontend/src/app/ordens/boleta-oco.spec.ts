import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { Boleta } from './boleta';
import { Ordem, OrdensService } from './ordens.service';

// RF05c.3b2 (QA): marcas OCO aparecem só quando cabem; demais encerramentos não ganham sufixo.
const BASE: Ordem = {
  id: '22222222-2222-4222-8222-222222222222',
  ticker: 'ALFA3',
  lado: 'VENDA',
  tipo: 'LIMIT',
  validade: 'DIA',
  quantidade: 3,
  precoLimite: '10.20',
  precoGatilho: null,
  grupoOco: null,
  papelOco: null,
  estado: 'EXECUTADA',
  motivoRejeicao: null,
  dataSessao: '2026-10-18',
  recebidaEm: '2026-10-18T15:00:00Z',
};

async function renderizar(ordens: Ordem[]): Promise<string[]> {
  await TestBed.configureTestingModule({
    imports: [Boleta],
    providers: [
      {
        provide: OrdensService,
        useValue: {
          listar: () => of({ ordens, proximoCursor: null }),
          enviar: () => of(BASE),
          enviarOco: () => of({ grupoOco: 'g', ordens: [] }),
        },
      },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(Boleta);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  const linhas = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
  return Array.from(linhas).map((l) => (l.textContent ?? '').replace(/\s+/g, ' ').trim());
}

describe('Boleta OCO', () => {
  it('não marca redução zero, ausente ou encerramentos que não são OCO', async () => {
    const linhas = await renderizar([
      { ...BASE, id: 'a', quantidadeReduzida: 0, motivoEncerramento: 'EXECUTADA' },
      { ...BASE, id: 'b', estado: 'CANCELADA', motivoEncerramento: 'SALDO_CANCELADO_IOC' },
    ]);
    expect(linhas.length).toBe(2);
    for (const linha of linhas) {
      expect(linha).not.toContain('reduzida em');
      expect(linha).not.toContain('outra perna');
      expect(linha).not.toContain('SALDO_CANCELADO_IOC');
      expect(linha).not.toContain('EXECUTADA ·');
    }
  });

  it('marca o Stop Loss executado após redução e o Take Profit encerrado pela irmã na mesma linha', async () => {
    const linhas = await renderizar([
      {
        ...BASE,
        id: 'sl',
        tipo: 'STOP',
        precoLimite: null,
        precoGatilho: '9.90',
        grupoOco: 'g',
        papelOco: 'STOP_LOSS',
        estado: 'EXECUTADA',
        motivoEncerramento: 'EXECUTADA',
        quantidadeReduzida: 1,
      },
      {
        ...BASE,
        id: 'tp',
        grupoOco: 'g',
        papelOco: 'TAKE_PROFIT',
        estado: 'CANCELADA',
        motivoEncerramento: 'CANCELADA_OCO',
      },
    ]);
    const stop = linhas.find((l) => l.includes('9,90') || l.includes('9.90'));
    const alvo = linhas.find((l) => l !== stop);
    expect(stop).toContain('3 (reduzida em 1)');
    expect(stop).not.toContain('outra perna');
    expect(alvo).toContain('encerrada pela outra perna do OCO');
    expect(alvo).not.toContain('reduzida em');
  });
});
