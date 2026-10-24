import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Mercado } from './mercado/mercado';
import { Boleta } from './ordens/boleta';
import { Posicoes } from './posicoes/posicoes';
import { Historico } from './historico/historico';
import { TemaService } from './tema/tema.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
  imports: [Mercado, Boleta, Posicoes, Historico],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly tema = inject(TemaService);
}
