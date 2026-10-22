import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { Mercado } from './mercado';
import {
  AtivoResumo,
  formatarData,
  formatarDecimal,
  formatarInteiro,
  formatarVariacao,
  Livro,
  MercadoService,
} from './mercado.service';

function ativo(ticker: string, extra: Partial<AtivoResumo> = {}): AtivoResumo {
  return {
    ticker,
    nome: ticker + ' S.A.',
    precoReferencia: '10.00',
    ultimoPreco: null,
    vwap: null,
    sessao: null,
    revisao: null,
    variacao: null,
    variacaoPercentual: null,
    melhorCompra: null,
    melhorVenda: null,
    spread: null,
    volumeQuantidade: null,
    volumeFinanceiro: null,
    estadoLivro: 'SEM_DADOS',
    preparadoEm: null,
    defasado: false,
    origem: null,
    ...extra,
  };
}

function livro(ticker: string, preco: string): Livro {
  return {
    ticker,
    estadoLivro: 'COMPLETO',
    compra: {
      niveis: [{ preco, quantidade: 100, ofertas: 1, quantidadeAcumulada: 100 }],
      niveisTotal: 1,
      quantidadeTotal: 100,
    },
    venda: { niveis: [], niveisTotal: 0, quantidadeTotal: 0 },
    revisao: 3,
    sessao: '2026-10-16',
    preparadoEm: '2026-10-16T12:00:00Z',
    defasado: true,
    origem: 'PREPARACAO_SINTETICA',
  };
}

describe('Formatação decimal textual', () => {
  it('formata em pt-BR sem converter para number', () => {
    expect(formatarDecimal('1234567.89')).toBe('1.234.567,89');
    expect(formatarDecimal('-0.35')).toBe('−0,35');
    expect(formatarDecimal('9007199254740993.01')).toBe('9.007.199.254.740.993,01');
    expect(formatarDecimal(null)).toBe('—');
    expect(formatarVariacao('3.50', '%')).toBe('+3,50%');
    expect(formatarVariacao('0.00')).toBe('0,00');
    expect(formatarInteiro(1500)).toBe('1.500');
    expect(formatarInteiro(null)).toBe('—');
    expect(formatarData('2026-10-17')).toBe('17/10/2026');
  });
});

describe('Mercado', () => {
  let livros: Record<string, Subject<Livro>>;
  let listar: () => ReturnType<MercadoService['listar']>;

  beforeEach(async () => {
    livros = { ALFA3: new Subject<Livro>(), BETA3: new Subject<Livro>() };
    listar = () =>
      of([
        ativo('ALFA3', {
          ultimoPreco: '10.35',
          variacao: '0.35',
          variacaoPercentual: '3.50',
          volumeQuantidade: 400,
          volumeFinanceiro: '4125.00',
          estadoLivro: 'COMPLETO',
          origem: 'PREPARACAO_SINTETICA',
        }),
        ativo('BETA3'),
      ]);
    await TestBed.configureTestingModule({
      imports: [Mercado],
      providers: [
        {
          provide: MercadoService,
          useValue: { listar: () => listar(), livro: (t: string) => livros[t] },
        },
      ],
    }).compileComponents();
  });

  it('exibe indicadores formatados e ausências como travessão', async () => {
    const fixture = TestBed.createComponent(Mercado);
    await fixture.whenStable();
    const linhas = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    expect(linhas[0].textContent).toContain('10,35');
    expect(linhas[0].textContent).toContain('+3,50%');
    expect(linhas[0].textContent).toContain('4.125,00');
    expect(linhas[1].textContent).toContain('—');
    expect(linhas[1].textContent).not.toContain('0,00');
  });

  it('descarta a resposta tardia do ativo anterior ao trocar a seleção', async () => {
    const fixture = TestBed.createComponent(Mercado);
    await fixture.whenStable();
    const componente = fixture.componentInstance;
    componente.selecionar('BETA3');
    livros['ALFA3'].next(livro('ALFA3', '9.99'));
    expect(componente.livro()).toBeNull();
    livros['BETA3'].next(livro('BETA3', '19.99'));
    expect(componente.livro()?.ticker).toBe('BETA3');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Livro de BETA3');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('19,99');
    // Selos vêm da resposta do livro (mesma revisão), não da listagem.
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Defasado');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Sessão 16/10/2026 · revisão 3',
    );
  });

  it('informa erro de consulta sem quebrar a tela', async () => {
    listar = () => throwError(() => new Error('indisponível'));
    const fixture = TestBed.createComponent(Mercado);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent,
    ).toContain('Não foi possível consultar o mercado');
  });
});
