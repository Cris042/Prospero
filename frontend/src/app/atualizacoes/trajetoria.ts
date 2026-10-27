import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  catchError,
  EMPTY,
  expand,
  finalize,
  map,
  merge,
  Observable,
  of,
  reduce,
  Subject,
  switchMap,
} from 'rxjs';
import { AtualizacoesService } from './atualizacoes.service';
import { consultasSerializadas } from './consultas-serializadas';
import { EventoTrajetoria, PaginaTrajetoria, TrajetoriaService } from './trajetoria.service';

const ROTULOS: Record<string, string> = {
  ORDEM_RECEBIDA: 'Ordem recebida',
  RISCO_RESERVADO: 'Reserva confirmada',
  RISCO_REJEITADO: 'Risco rejeitado',
  ORDEM_ACEITA: 'Aceita no livro',
  OFERTA_NO_LIVRO: 'Oferta no livro',
  ORDEM_REJEITADA: 'Ordem rejeitada',
  ORDEM_ENCERRADA: 'Ordem encerrada',
  NEGOCIO: 'Negócio executado',
  STOP_PENDENTE: 'Aguardando gatilho',
  STOP_ACIONADO: 'Stop acionado',
  ORDEM_REDUZIDA: 'Saldo reduzido',
  NEGOCIO_CONTABILIZADO: 'Negócio contabilizado',
};
interface Consulta {
  pagina: PaginaTrajetoria;
  anexar: boolean;
  paginas: number;
}

@Component({
  selector: 'app-trajetoria',
  templateUrl: './trajetoria.html',
  styleUrl: './trajetoria.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Trajetoria implements OnInit {
  readonly ordem = input.required<string>();
  private readonly selecionada = toObservable(this.ordem);
  private readonly servico = inject(TrajetoriaService);
  private readonly destroyRef = inject(DestroyRef);
  readonly canal = inject(AtualizacoesService);
  private readonly pedidos = new Subject<void>();
  private cursorSolicitado: string | null = null;
  private paginasCarregadas = 1;
  private geracaoConsulta = 0;
  readonly pagina = signal<PaginaTrajetoria | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal(false);
  readonly eventos = computed(() => this.pagina()?.eventos ?? []);
  readonly podeCarregar = computed(
    () => !!this.pagina()?.proximoCursor && this.eventos().length < 100,
  );
  readonly rotulo = (tipo: string) => ROTULOS[tipo] ?? tipo.replaceAll('_', ' ');
  readonly instante = (valor: string | null) =>
    valor
      ? new Date(valor).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' })
      : 'Indisponível';

  ngOnInit(): void {
    this.selecionada
      .pipe(
        switchMap((ordem) => {
          this.pagina.set(null);
          this.erro.set(false);
          this.cursorSolicitado = null;
          this.paginasCarregadas = 1;
          return consultasSerializadas(merge(of(null), this.pedidos, this.canal.avisos$), () =>
            this.consultar(ordem),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ pagina, anexar, paginas }) => {
        const anteriores = anexar ? this.eventos() : [];
        const unicos = new Map<string, EventoTrajetoria>();
        for (const evento of [...anteriores, ...pagina.eventos])
          unicos.set(evento.sequencia, evento);
        this.pagina.set({ ...pagina, eventos: [...unicos.values()].slice(0, 100) });
        this.paginasCarregadas = anexar ? this.paginasCarregadas + 1 : paginas;
        this.erro.set(false);
      });
  }

  atualizar(): void {
    this.pedidos.next();
  }

  carregarMais(): void {
    if (this.carregando() || !this.podeCarregar()) return;
    this.cursorSolicitado = this.pagina()?.proximoCursor ?? null;
    this.pedidos.next();
  }

  private consultar(ordem: string): Observable<Consulta> {
    const geracao = ++this.geracaoConsulta;
    const cursor = this.cursorSolicitado;
    this.cursorSolicitado = null;
    this.carregando.set(true);
    const paginas = cursor ? 1 : this.paginasCarregadas;
    return this.servico.consultar(ordem, cursor).pipe(
      expand(
        (pagina, indice) =>
          !cursor && pagina.proximoCursor && indice + 1 < paginas
            ? this.servico.consultar(ordem, pagina.proximoCursor)
            : EMPTY,
        1,
      ),
      reduce(
        (total, pagina) =>
          total ? { ...pagina, eventos: [...total.eventos, ...pagina.eventos] } : pagina,
        null as PaginaTrajetoria | null,
      ),
      map((pagina) => {
        if (!pagina || pagina.ordem !== ordem) throw new Error('Trajetória indisponível');
        return { pagina, anexar: cursor !== null, paginas };
      }),
      catchError(() => {
        this.erro.set(true);
        return EMPTY;
      }),
      finalize(() => {
        if (geracao === this.geracaoConsulta) this.carregando.set(false);
      }),
    );
  }
}
