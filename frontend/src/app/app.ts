import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { Mercado } from './mercado/mercado';
import { EstadoServico, SistemaService } from './sistema/sistema.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
  imports: [Mercado],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App implements OnInit {
  private readonly sistema = inject(SistemaService);
  private readonly destroyRef = inject(DestroyRef);
  readonly servicos = signal<EstadoServico[]>([]);
  readonly carregando = signal(false);

  ngOnInit(): void {
    this.atualizar();
  }

  atualizar(): void {
    if (this.carregando()) return;
    this.carregando.set(true);
    this.sistema
      .consultar()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.carregando.set(false)),
      )
      .subscribe((servicos) => this.servicos.set(servicos));
  }
}
