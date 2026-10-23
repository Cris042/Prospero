import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, timeout } from 'rxjs';

export interface PosicaoResumo {
  readonly ticker: string;
  readonly quantidade: string;
  readonly lado: 'COMPRADA' | 'VENDIDA' | 'ZERADA';
  readonly precoMedio: string | null;
  readonly resultadoRealizado: string;
  readonly resultadoNaoRealizado: string | null;
  readonly cotacao: string | null;
  readonly sessaoCotacao: string | null;
  readonly cotacaoDefasada: boolean;
  readonly revisaoCotacao: number;
  readonly atualizadaEm: string | null;
}

export interface PosicoesResumo {
  readonly conta: string;
  readonly base: 'NEGOCIOS_DESDE_ORIGEM';
  readonly sincronizado: boolean;
  readonly posicoes: readonly PosicaoResumo[];
}

@Injectable({ providedIn: 'root' })
export class PosicoesService {
  private readonly http = inject(HttpClient);

  consultar(): Observable<PosicoesResumo> {
    return this.http
      .get<PosicoesResumo>('/servicos/back-office/api/v1/posicoes')
      .pipe(timeout(8000));
  }
}
