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
import { formatarData, formatarDecimal } from '../mercado/mercado.service';
import { HistoricoService, Lancamento, Negocio } from './historico.service';

@Component({
  selector: 'app-historico',
  templateUrl: './historico.html',
  styleUrl: './historico.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Historico implements OnInit {
  private readonly servico = inject(HistoricoService);
  private readonly destroyRef = inject(DestroyRef);
  private cursorNegocios: string | null = null;
  private cursorRazao: string | null = null;
  readonly conta = signal('CONTA-001');
  readonly negocios = signal<Negocio[]>([]);
  readonly lancamentos = signal<Lancamento[]>([]);
  readonly carregandoNegocios = signal(false);
  readonly carregandoRazao = signal(false);
  readonly erroNegocios = signal(false);
  readonly erroRazao = signal(false);
  readonly haMaisNegocios = signal(false);
  readonly haMaisRazao = signal(false);
  readonly decimal = formatarDecimal;
  readonly data = formatarData;

  ngOnInit(): void {
    this.atualizar();
  }
  atualizar(): void {
    if (this.carregandoNegocios() || this.carregandoRazao()) return;
    this.buscarNegocios(true);
    this.buscarRazao(true);
  }
  maisNegocios(): void {
    if (!this.carregandoNegocios() && this.haMaisNegocios()) this.buscarNegocios(false);
  }
  maisRazao(): void {
    if (!this.carregandoRazao() && this.haMaisRazao()) this.buscarRazao(false);
  }
  private buscarNegocios(reiniciar: boolean): void {
    this.carregandoNegocios.set(true);
    this.servico
      .negocios(reiniciar ? null : this.cursorNegocios)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.carregandoNegocios.set(false)),
      )
      .subscribe({
        next: (pagina) => {
          const anteriores = reiniciar ? [] : this.negocios();
          this.negocios.set([...anteriores, ...pagina.negocios].slice(0, 100));
          this.conta.set(pagina.conta);
          this.cursorNegocios = pagina.proximoCursor;
          this.haMaisNegocios.set(pagina.proximoCursor !== null && this.negocios().length < 100);
          this.erroNegocios.set(false);
        },
        error: () => this.erroNegocios.set(true),
      });
  }
  private buscarRazao(reiniciar: boolean): void {
    this.carregandoRazao.set(true);
    this.servico
      .razao(reiniciar ? null : this.cursorRazao)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.carregandoRazao.set(false)),
      )
      .subscribe({
        next: (pagina) => {
          const anteriores = reiniciar ? [] : this.lancamentos();
          this.lancamentos.set([...anteriores, ...pagina.lancamentos].slice(0, 100));
          this.conta.set(pagina.conta);
          this.cursorRazao = pagina.proximoCursor;
          this.haMaisRazao.set(pagina.proximoCursor !== null && this.lancamentos().length < 100);
          this.erroRazao.set(false);
        },
        error: () => this.erroRazao.set(true),
      });
  }
}
