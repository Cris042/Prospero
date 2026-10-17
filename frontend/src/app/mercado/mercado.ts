import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, finalize, of, Subject, switchMap, tap } from 'rxjs';
import {
  AtivoResumo,
  EstadoLivro,
  formatarData,
  formatarDecimal,
  formatarInteiro,
  formatarVariacao,
  Livro,
  MercadoService,
} from './mercado.service';

const DESCRICAO_ESTADO: Record<EstadoLivro, string> = {
  SEM_DADOS: 'Sem dados preparados',
  VAZIO: 'Livro vazio',
  SOMENTE_COMPRA: 'Somente compras',
  SOMENTE_VENDA: 'Somente vendas',
  COMPLETO: 'Compras e vendas',
};

@Component({
  selector: 'app-mercado',
  templateUrl: './mercado.html',
  styleUrl: './mercado.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Mercado implements OnInit {
  private readonly mercado = inject(MercadoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly selecao = new Subject<string>();
  readonly ativos = signal<AtivoResumo[]>([]);
  readonly carregando = signal(false);
  readonly erro = signal(false);
  readonly selecionado = signal<string | null>(null);
  readonly livro = signal<Livro | null>(null);
  readonly carregandoLivro = signal(false);
  readonly erroLivro = signal(false);
  readonly ativoSelecionado = computed(
    () => this.ativos().find((a) => a.ticker === this.selecionado()) ?? null,
  );
  readonly decimal = formatarDecimal;
  readonly variacao = formatarVariacao;
  readonly inteiro = formatarInteiro;
  readonly data = formatarData;
  readonly estado = (e: EstadoLivro) => DESCRICAO_ESTADO[e];

  ngOnInit(): void {
    // switchMap descarta a resposta tardia do ativo anterior ao trocar a seleção.
    this.selecao
      .pipe(
        tap(() => {
          this.carregandoLivro.set(true);
          this.erroLivro.set(false);
          this.livro.set(null);
        }),
        switchMap((ticker) =>
          this.mercado.livro(ticker).pipe(
            catchError(() => {
              this.erroLivro.set(true);
              return of(null);
            }),
            finalize(() => this.carregandoLivro.set(false)),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((livro) => this.livro.set(livro));
    this.atualizar();
  }

  atualizar(): void {
    if (this.carregando()) return;
    this.carregando.set(true);
    this.erro.set(false);
    this.mercado
      .listar()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.carregando.set(false)),
      )
      .subscribe({
        next: (ativos) => {
          this.ativos.set(ativos);
          const atual = this.selecionado() ?? ativos[0]?.ticker;
          if (atual) this.selecionar(atual);
        },
        error: () => this.erro.set(true),
      });
  }

  selecionar(ticker: string): void {
    this.selecionado.set(ticker);
    this.selecao.next(ticker);
  }

  sentido(texto: string | null): 'alta' | 'baixa' | '' {
    if (texto === null || /^0(\.0+)?$/.test(texto)) return '';
    return texto.startsWith('-') ? 'baixa' : 'alta';
  }
}
