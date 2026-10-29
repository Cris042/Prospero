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
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import {
  catchError,
  EMPTY,
  finalize,
  map,
  merge,
  Observable,
  Subject,
  Subscription,
  take,
  takeWhile,
  timer,
} from 'rxjs';
import { AtualizacoesService } from '../atualizacoes/atualizacoes.service';
import { consultasSerializadas } from '../atualizacoes/consultas-serializadas';
import { Trajetoria } from '../atualizacoes/trajetoria';
import { formatarDecimal } from '../mercado/mercado.service';
import {
  exigeGatilho,
  exigeLimite,
  Lado,
  mensagemErro,
  mensagemErroCancelamento,
  normalizarPreco,
  Ordem,
  OrdensService,
  Tipo,
  Validade,
  VALIDADES,
} from './ordens.service';

const TICKERS = ['ALFA3', 'BETA3', 'GAMA3', 'DELTA3', 'OMEGA3'] as const;
const ROTULOS: Record<string, string> = {
  MARKET: 'Mercado',
  LIMIT: 'Limitada',
  STOP: 'Stop',
  STOP_LIMIT: 'Stop Limit',
  DIA: 'Dia',
  IOC: 'IOC (executa ou cancela)',
  FOK: 'FOK (tudo ou nada)',
  COMPRA: 'Compra',
  VENDA: 'Venda',
  TAKE_PROFIT: 'Take Profit',
  STOP_LOSS: 'Stop Loss',
  RECEBIDA: 'Recebida',
  RESERVADA: 'Reservada',
  REJEITADA: 'Rejeitada',
  AGUARDANDO_GATILHO: 'Aguardando gatilho',
  ABERTA: 'Aberta',
  PARCIALMENTE_EXECUTADA: 'Parcialmente executada',
  EXECUTADA: 'Executada',
  CANCELADA: 'Cancelada',
  SALDO_INSUFICIENTE: 'saldo insuficiente',
  CUSTODIA_INSUFICIENTE: 'custódia insuficiente',
  QUANTIDADE_MAXIMA_EXCEDIDA: 'quantidade acima do máximo por ordem',
  QUANTIDADE_FORA_DO_LOTE: 'quantidade fora do lote',
  ATIVO_DESCONHECIDO: 'ativo desconhecido',
  CONTA_DESCONHECIDA: 'conta desconhecida',
  PRECO_BASE_AUSENTE: 'sem preço de referência',
  LIMITE_STOPS_PENDENTES: 'limite de stops pendentes atingido',
  LIMITE_POSICAO_EXCEDIDO: 'limite de posição no ativo excedido',
  EXPOSICAO_VENDIDA_EXCEDIDA: 'limite de venda a descoberto da conta excedido',
  CANCELADA_OCO: 'encerrada pela outra perna do OCO',
  EXPIRADA_DIA: 'expirada no fim da sessão',
  CANCELADA_MANUAL: 'cancelada a pedido',
  AGUARDANDO_RISCO: 'Cancelamento aguardando a decisão de risco',
  SOLICITADO: 'Cancelamento solicitado',
  DECIDIDO: 'Cancelamento decidido; aguardando a liberação do saldo',
  CONFIRMADO: 'Cancelamento confirmado',
  RECUSADO: 'Cancelamento recusado',
  SEM_SALDO_VIVO: 'a ordem não tem mais saldo',
  ALVO_DIVERGENTE: 'dados da ordem divergentes',
  ORIGEM_OCO: 'ordem de grupo OCO',
  ADMISSAO_NAO_COMPROVADA: 'admissão no livro não comprovada',
  RISCO_REJEITADO: 'ordem rejeitada pelo risco',
  SESSAO_ENCERRADA: 'sessão do ativo encerrada',
  SESSAO_NAO_ENCERRADA: 'sessão anterior ainda aberta',
};

@Component({
  selector: 'app-boleta',
  templateUrl: './boleta.html',
  styleUrl: './boleta.css',
  imports: [ReactiveFormsModule, Trajetoria],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Boleta implements OnInit {
  readonly canal = inject(AtualizacoesService);
  readonly ordemSelecionada = signal<string | null>(null);
  private readonly ordens = inject(OrdensService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);
  /**
   * Chave reaproveitada só quando o envio anterior falhou por comunicação e o pedido é idêntico;
   * qualquer alteração, inclusive durante o envio, gera chave nova.
   */
  private pendente: { chave: string; assinatura: string } | null = null;
  private readonly recarregar = new Subject<void>();
  /** Acompanhamento do último envio; um novo envio ou troca de modo cancela o anterior. */
  private acompanhamento: Subscription | null = null;
  private acompanhamentoCancelamento: Subscription | null = null;

  readonly tickers = TICKERS;
  readonly tipos: readonly Tipo[] = ['LIMIT', 'MARKET', 'STOP', 'STOP_LIMIT'];
  readonly rotulo = (valor: string | null) => (valor ? (ROTULOS[valor] ?? valor) : '—');
  readonly decimal = formatarDecimal;
  readonly modo = signal<'SIMPLES' | 'OCO'>('SIMPLES');
  readonly enviando = signal(false);
  readonly erro = signal<string | null>(null);
  readonly registro = signal<readonly Ordem[] | null>(null);
  readonly lista = signal<readonly Ordem[]>([]);
  readonly erroLista = signal(false);
  /** Ordens com PUT de cancelamento em voo e erro acessível por ordem. */
  readonly cancelando = signal<ReadonlySet<string>>(new Set());
  readonly erroCancelamento = signal<Readonly<Record<string, string>>>({});

  readonly simples = this.fb.group({
    ticker: 'ALFA3',
    lado: 'COMPRA' as Lado,
    tipo: 'LIMIT' as Tipo,
    validade: 'DIA' as Validade,
    quantidade: 100,
    precoLimite: '',
    precoGatilho: '',
  });

  readonly oco = this.fb.group({
    ticker: 'ALFA3',
    lado: 'VENDA' as Lado,
    quantidade: 100,
    alvo: '',
    tipoStop: 'STOP' as 'STOP' | 'STOP_LIMIT',
    gatilho: '',
    limiteStop: '',
  });

  readonly tipoAtual = signal<Tipo>('LIMIT');
  readonly tipoStopAtual = signal<'STOP' | 'STOP_LIMIT'>('STOP');
  readonly validades = computed(() => VALIDADES[this.tipoAtual()]);
  readonly exigeLimite = exigeLimite;
  readonly exigeGatilho = exigeGatilho;

  ngOnInit(): void {
    this.simples.controls.tipo.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((tipo) => {
        this.tipoAtual.set(tipo);
        if (!VALIDADES[tipo].includes(this.simples.controls.validade.value)) {
          this.simples.controls.validade.setValue(VALIDADES[tipo][0]);
        }
      });
    this.oco.controls.tipoStop.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((tipo) => this.tipoStopAtual.set(tipo));
    consultasSerializadas(merge(this.recarregar, this.canal.avisos$), () =>
      this.ordens.listar().pipe(
        catchError(() => {
          this.erroLista.set(true);
          return EMPTY;
        }),
      ),
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((pagina) => {
        this.erroLista.set(false);
        this.lista.set(pagina.ordens);
        const registro = this.registro();
        if (registro) {
          const atuais = pagina.ordens.filter((o) => registro.some((r) => r.id === o.id));
          if (atuais.length === registro.length) this.registro.set(atuais);
        }
      });
    this.atualizarLista();
  }

  alternar(modo: 'SIMPLES' | 'OCO'): void {
    this.acompanhamento?.unsubscribe();
    this.modo.set(modo);
    this.erro.set(null);
    this.registro.set(null);
  }

  enviar(): void {
    if (this.enviando()) return;
    this.acompanhamento?.unsubscribe();
    this.enviando.set(true);
    this.erro.set(null);
    this.registro.set(null);
    const assinatura = JSON.stringify([
      this.modo(),
      this.simples.getRawValue(),
      this.oco.getRawValue(),
    ]);
    if (this.pendente?.assinatura !== assinatura) {
      this.pendente = { chave: crypto.randomUUID(), assinatura };
    }
    const chave = this.pendente.chave;
    const envio: Observable<readonly Ordem[]> =
      this.modo() === 'SIMPLES' ? this.enviarSimples(chave) : this.enviarOco(chave);
    envio
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.enviando.set(false)),
      )
      .subscribe({
        next: (ordens) => {
          this.pendente = null;
          this.registro.set(ordens);
          this.atualizarLista();
          this.acompanharRisco();
        },
        error: (falha) => {
          const { texto, repetivel } = mensagemErro(falha);
          if (!repetivel) this.pendente = null;
          this.erro.set(texto);
        },
      });
  }

  /** Elegível: sem pedido, fora de grupo OCO e ainda sem estado terminal (a decisão final é do matching). */
  podeCancelar(o: Ordem): boolean {
    return (
      !o.cancelamento &&
      !o.grupoOco &&
      ['RECEBIDA', 'RESERVADA', 'AGUARDANDO_GATILHO', 'ABERTA', 'PARCIALMENTE_EXECUTADA'].includes(
        o.estado,
      )
    );
  }

  cancelar(o: Ordem): void {
    if (this.cancelando().has(o.id)) return;
    this.cancelando.update((atual) => new Set(atual).add(o.id));
    this.erroCancelamento.update(({ [o.id]: _removido, ...resto }) => resto);
    this.ordens
      .cancelar(o.id)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() =>
          this.cancelando.update((atual) => {
            const novo = new Set(atual);
            novo.delete(o.id);
            return novo;
          }),
        ),
      )
      .subscribe({
        next: () => {
          this.atualizarLista();
          this.acompanharCancelamento();
        },
        error: (falha) =>
          this.erroCancelamento.update((atual) => ({
            ...atual,
            [o.id]: mensagemErroCancelamento(falha),
          })),
      });
  }

  /** Texto do estado do pedido; só CONFIRMADO diz que o saldo foi liberado pelo middle-office. */
  textoCancelamento(o: Ordem): string {
    const c = o.cancelamento;
    if (!c) return '';
    return this.rotulo(c.estado) + (c.motivo ? ` (${this.rotulo(c.motivo)})` : '');
  }

  atualizarLista(): void {
    this.recarregar.next();
  }

  /** Estado do envio registrado; o par OCO muda junto (ADR-012). */
  estadoRegistro(ordens: readonly Ordem[]): string {
    const [primeira] = ordens;
    const motivo = primeira.motivoRejeicao ? ` (${this.rotulo(primeira.motivoRejeicao)})` : '';
    return this.rotulo(primeira.estado) + motivo;
  }

  /**
   * O risco do MO responde de forma assíncrona: consulta a lista a cada 500 ms (até 10 s) enquanto as ordens do
   * envio estiverem RECEBIDA. Sem resposta, a ordem continua Recebida — não há liberação por timeout.
   * O timer só solicita recarga: acompanhamento, WebSocket e ação manual compartilham o mesmo GET.
   */
  private acompanharRisco(): void {
    this.acompanhamento = timer(500, 500)
      .pipe(
        take(20),
        takeWhile(() => this.registro()?.some((o) => o.estado === 'RECEBIDA') ?? false),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.atualizarLista());
  }

  /** Consulta a lista a cada segundo (até 30 s) enquanto houver cancelamento sem resultado final. */
  private acompanharCancelamento(): void {
    this.acompanhamentoCancelamento?.unsubscribe();
    this.acompanhamentoCancelamento = timer(1000, 1000)
      .pipe(
        take(30),
        // Os dois primeiros ciclos cobrem a latência da primeira recarga; depois só enquanto houver pendência.
        takeWhile(
          (ciclo) =>
            ciclo < 2 ||
            this.lista().some(
              (o) => o.cancelamento && !['CONFIRMADO', 'RECUSADO'].includes(o.cancelamento.estado),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.atualizarLista());
  }

  private enviarSimples(chave: string): Observable<readonly Ordem[]> {
    const v = this.simples.getRawValue();
    return this.ordens
      .enviar(
        {
          ticker: v.ticker,
          lado: v.lado,
          tipo: v.tipo,
          validade: v.validade,
          quantidade: Number(v.quantidade),
          precoLimite: exigeLimite(v.tipo) ? normalizarPreco(v.precoLimite) : null,
          precoGatilho: exigeGatilho(v.tipo) ? normalizarPreco(v.precoGatilho) : null,
        },
        chave,
      )
      .pipe(map((ordem) => [ordem]));
  }

  private enviarOco(chave: string): Observable<readonly Ordem[]> {
    const v = this.oco.getRawValue();
    return this.ordens
      .enviarOco(
        {
          ticker: v.ticker,
          lado: v.lado,
          quantidade: Number(v.quantidade),
          validade: 'DIA',
          takeProfit: { precoLimite: normalizarPreco(v.alvo) },
          stopLoss: {
            tipo: v.tipoStop,
            precoGatilho: normalizarPreco(v.gatilho),
            precoLimite: v.tipoStop === 'STOP_LIMIT' ? normalizarPreco(v.limiteStop) : null,
          },
        },
        chave,
      )
      .pipe(map((grupo) => grupo.ordens));
  }
}
