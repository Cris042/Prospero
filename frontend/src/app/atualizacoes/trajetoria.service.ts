import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, timeout } from 'rxjs';

export interface EventoTrajetoria {
  readonly sequencia: string;
  readonly origem: string;
  readonly tipo: string;
  readonly evento: string;
  readonly causacao: string | null;
  readonly negocio: string | null;
  readonly ocorridoEm: string | null;
  readonly observadoEm: string;
  readonly quantidade: string | null;
  readonly motivo: string | null;
  readonly traceId: string | null;
}
export interface PaginaTrajetoria {
  readonly ordem: string;
  readonly conta: string;
  readonly revisao: string;
  readonly historicoCompleto: boolean;
  readonly disponivelDesde: string | null;
  readonly eventos: readonly EventoTrajetoria[];
  readonly proximoCursor: string | null;
}

@Injectable({ providedIn: 'root' })
export class TrajetoriaService {
  private readonly http = inject(HttpClient);
  consultar(ordem: string, cursor: string | null = null): Observable<PaginaTrajetoria> {
    let params = new HttpParams().set('limite', '20');
    if (cursor !== null) params = params.set('cursor', cursor);
    return this.http
      .get<PaginaTrajetoria>(
        `/servicos/front-office/api/v1/ordens/${encodeURIComponent(ordem)}/trajetoria`,
        { params },
      )
      .pipe(timeout(8000));
  }
}
