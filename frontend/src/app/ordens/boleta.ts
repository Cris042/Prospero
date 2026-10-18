import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, map, Observable } from 'rxjs';
import { formatarDecimal } from '../mercado/mercado.service';
import {
  exigeGatilho,
  exigeLimite,
  Lado,
  mensagemErro,
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
};

@Component({
  selector: 'app-boleta',
  templateUrl: './boleta.html',
  styleUrl: './boleta.css',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Boleta implements OnInit {
  private readonly ordens = inject(OrdensService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);
  /** Chave reaproveitada só quando o envio anterior falhou por comunicação. */
  private chavePendente: string | null = null;

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
  validades = () => VALIDADES[this.tipoAtual()];
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
    this.simples.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.novoPedido());
    this.oco.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.novoPedido());
    this.atualizarLista();
  }

  alternar(modo: 'SIMPLES' | 'OCO'): void {
    this.modo.set(modo);
    this.novoPedido();
  }

  enviar(): void {
    if (this.enviando()) return;
    this.enviando.set(true);
    this.erro.set(null);
    this.registro.set(null);
    const chave = (this.chavePendente ??= crypto.randomUUID());
    const envio: Observable<readonly Ordem[]> =
      this.modo() === 'SIMPLES' ? this.enviarSimples(chave) : this.enviarOco(chave);
    envio
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.enviando.set(false)),
      )
      .subscribe({
        next: (ordens) => {
          this.chavePendente = null;
          this.registro.set(ordens);
          this.atualizarLista();
        },
        error: (falha) => {
          const { texto, repetivel } = mensagemErro(falha);
          if (!repetivel) this.chavePendente = null;
          this.erro.set(texto);
        },
      });
  }

  atualizarLista(): void {
    this.ordens
      .listar()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (pagina) => {
          this.erroLista.set(false);
          this.lista.set(pagina.ordens);
        },
        error: () => this.erroLista.set(true),
      });
  }

  private novoPedido(): void {
    // Dados alterados: um novo envio não é repetição do anterior.
    if (!this.enviando()) this.chavePendente = null;
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
          precoLimite: exigeLimite(v.tipo) ? v.precoLimite.trim() : null,
          precoGatilho: exigeGatilho(v.tipo) ? v.precoGatilho.trim() : null,
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
          takeProfit: { precoLimite: v.alvo.trim() },
          stopLoss: {
            tipo: v.tipoStop,
            precoGatilho: v.gatilho.trim(),
            precoLimite: v.tipoStop === 'STOP_LIMIT' ? v.limiteStop.trim() : null,
          },
        },
        chave,
      )
      .pipe(map((grupo) => grupo.ordens));
  }
}
