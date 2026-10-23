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
  exhaustMap,
  finalize,
  fromEvent,
  map,
  merge,
  startWith,
  Subject,
  switchMap,
  timer,
} from 'rxjs';
import { formatarData, formatarDecimal } from '../mercado/mercado.service';
import { PosicoesResumo, PosicoesService } from './posicoes.service';

@Component({
  selector: 'app-posicoes',
  templateUrl: './posicoes.html',
  styleUrl: './posicoes.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Posicoes implements OnInit {
  private readonly servico = inject(PosicoesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly pedidos = new Subject<void>();
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
            ? merge(timer(0, 2000), this.pedidos).pipe(exhaustMap(() => this.consultar()))
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
      this.carregando.set(true);
      return this.servico.consultar().pipe(
        catchError(() => {
          this.erro.set(true);
          return EMPTY;
        }),
        finalize(() => this.carregando.set(false)),
      );
    });
  }
}
