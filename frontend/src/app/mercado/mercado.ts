import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
  WritableSignal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  BehaviorSubject,
  catchError,
  combineLatest,
  defer,
  distinctUntilChanged,
  EMPTY,
  endWith,
  expand,
  filter,
  finalize,
  fromEvent,
  ignoreElements,
  map,
  merge,
  Observable,
  of,
  shareReplay,
  startWith,
  Subject,
  switchMap,
  tap,
  timer,
} from 'rxjs';
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
  SEM_DADOS: 'Sem dados de mercado',
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
  private readonly documento = inject(DOCUMENT);
  private readonly selecao = new BehaviorSubject<string | null>(null);
  private readonly atualizacao = new Subject<void>();
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
    const visivel = fromEvent(this.documento, 'visibilitychange').pipe(
      startWith(null),
      map(() => this.documento.visibilityState !== 'hidden'),
      distinctUntilChanged(),
      shareReplay({ bufferSize: 1, refCount: true }),
    );

    visivel
      .pipe(
        switchMap((ativa) =>
          ativa
            ? this.acompanhar(
                () => this.mercado.listar(),
                this.carregando,
                this.erro,
                (ativos) => {
                  this.ativos.set(ativos);
                  const atual = ativos.find((a) => a.ticker === this.selecionado())?.ticker;
                  const proximo = atual ?? ativos[0]?.ticker ?? null;
                  if (proximo !== this.selecionado()) this.selecionar(proximo);
                },
              )
            : EMPTY,
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();

    // Cada recurso recupera independentemente; trocar ticker cancela só o livro anterior.
    combineLatest([visivel, this.selecao])
      .pipe(
        switchMap(([ativa, ticker]) =>
          ativa && ticker
            ? this.acompanhar(
                () => this.mercado.livro(ticker),
                this.carregandoLivro,
                this.erroLivro,
                (livro) => this.livro.set(livro),
              )
            : EMPTY,
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  atualizar(): void {
    this.atualizacao.next();
  }

  selecionar(ticker: string | null): void {
    if (ticker === this.selecionado()) return;
    this.livro.set(null);
    this.erroLivro.set(false);
    this.selecionado.set(ticker);
    this.selecao.next(ticker);
  }

  private acompanhar<T>(
    consultar: () => Observable<T>,
    carregando: WritableSignal<boolean>,
    erro: WritableSignal<boolean>,
    aplicar: (resposta: T) => void,
  ): Observable<null> {
    return merge(of(undefined), this.atualizacao.pipe(filter(() => !carregando() && !erro()))).pipe(
      switchMap(() =>
        defer(() => {
          let falhas = 0;
          const tentar = () =>
            defer(() => {
              carregando.set(true);
              return consultar().pipe(
                tap({
                  next: (resposta) => {
                    aplicar(resposta);
                    falhas = 0;
                    erro.set(false);
                  },
                  error: () => {
                    falhas += 1;
                    erro.set(true);
                  },
                }),
                // Só a conclusão (ou erro/timeout) agenda o próximo GET, nunca cada emissão.
                ignoreElements(),
                endWith(null),
                catchError(() => of(null)),
                finalize(() => carregando.set(false)),
              );
            });
          return tentar().pipe(
            expand(() =>
              timer(1000 * 2 ** Math.min(Math.max(falhas - 1, 0), 3)).pipe(switchMap(tentar)),
            ),
          );
        }),
      ),
    );
  }

  sentido(texto: string | null): 'alta' | 'baixa' | '' {
    if (texto === null || /^0(\.0+)?$/.test(texto)) return '';
    return texto.startsWith('-') ? 'baixa' : 'alta';
  }
}
