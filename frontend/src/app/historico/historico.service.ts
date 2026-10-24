import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { timeout } from 'rxjs';

export interface Negocio {
  negocio: string;
  ticker: string;
  lado: 'COMPRADOR' | 'VENDEDOR';
  quantidade: string;
  valor: string;
  dataSessao: string;
  dataLiquidacao: string;
  liquidacao: 'PENDENTE' | 'LIQUIDADO';
}
export interface Lancamento {
  transacao: string;
  negocio: string;
  lado: string;
  momento: 'D0' | 'D2';
  data: string;
  contaContabil: string;
  natureza: 'DEBITO' | 'CREDITO';
  unidade: string;
  valor: string;
}
export interface PaginaNegocios {
  conta: string;
  negocios: Negocio[];
  proximoCursor: string | null;
}
export interface PaginaRazao {
  conta: string;
  lancamentos: Lancamento[];
  proximoCursor: string | null;
}
@Injectable({ providedIn: 'root' })
export class HistoricoService {
  private readonly http = inject(HttpClient);
  negocios(cursor: string | null = null) {
    return this.http
      .get<PaginaNegocios>('/servicos/back-office/api/v1/negocios', {
        params: this.parametros(cursor),
      })
      .pipe(timeout(8000));
  }
  razao(cursor: string | null = null) {
    return this.http
      .get<PaginaRazao>('/servicos/back-office/api/v1/razao', { params: this.parametros(cursor) })
      .pipe(timeout(8000));
  }
  private parametros(cursor: string | null) {
    let params = new HttpParams().set('limite', '20');
    if (cursor !== null) params = params.set('cursor', cursor);
    return params;
  }
}
