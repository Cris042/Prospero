import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  catchError,
  defer,
  distinctUntilChanged,
  EMPTY,
  finalize,
  fromEvent,
  map,
  merge,
  startWith,
  Subject,
  switchMap,
  timer,
} from 'rxjs';
import { AtualizacoesService } from '../atualizacoes/atualizacoes.service';
import { consultasSerializadas } from '../atualizacoes/consultas-serializadas';
import { formatarData, formatarDecimal } from '../mercado/mercado.service';
import { PosicoesResumo, PosicoesService } from './posicoes.service';

@Component({
  selector: 'app-posicoes',
  templateUrl: './posicoes.html',
  styleUrl: './posicoes.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Posicoes implements OnInit {
  readonly canal = inject(AtualizacoesService);
  private readonly servico = inject(PosicoesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly pedidos = new Subject<void>();
  private geracaoConsulta = 0;
  readonly resumo = signal<PosicoesResumo | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal(false);
  readonly decimal = formatarDecimal;
  readonly data = formatarData;

  ngOnInit(): void {
    fromEvent(document, 'visibilitychange')
      .pipe(
        startWith(null),
        map(() => document.visibilityState !== 'hidden'),
        distinctUntilChanged(),
        switchMap((visivel) =>
          visivel
            ? consultasSerializadas(merge(timer(0, 2000), this.pedidos, this.canal.avisos$), () =>
                this.consultar(),
              )
            : EMPTY,
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((resumo) => {
        this.resumo.set(resumo);
        this.erro.set(false);
      });
  }

  atualizar(): void {
    this.pedidos.next();
  }

  private consultar() {
    return defer(() => {
      const geracao = ++this.geracaoConsulta;
      this.carregando.set(true);
      return this.servico.consultar().pipe(
        catchError(() => {
          this.erro.set(true);
          return EMPTY;
        }),
        finalize(() => {
          if (geracao === this.geracaoConsulta) this.carregando.set(false);
        }),
      );
    });
  }
}
