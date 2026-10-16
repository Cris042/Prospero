import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, forkJoin, map, Observable, of, timeout } from 'rxjs';

export interface Servico {
  readonly id: string;
  readonly nome: string;
  readonly papel: string;
  readonly porta: number;
}

interface Identidade {
  servico: string;
  versao: string;
  etapa: string;
}

export interface EstadoServico extends Servico {
  disponivel: boolean;
  versao: string;
  etapa: string;
}

export const SERVICOS: readonly Servico[] = [
  {
    id: 'front-office',
    nome: 'Front office',
    papel: 'Entrada e acompanhamento de ordens',
    porta: 8081,
  },
  { id: 'middle-office', nome: 'Middle office', papel: 'Risco, limites e reservas', porta: 8082 },
  {
    id: 'matching-engine',
    nome: 'Matching engine',
    papel: 'Livro de ofertas e encontro de ordens',
    porta: 8083,
  },
  { id: 'back-office', nome: 'Back office', papel: 'Posições, razão e liquidação', porta: 8084 },
];

@Injectable({ providedIn: 'root' })
export class SistemaService {
  private readonly http = inject(HttpClient);

  consultar(): Observable<EstadoServico[]> {
    return forkJoin(SERVICOS.map((servico) => this.consultarServico(servico)));
  }

  private consultarServico(servico: Servico): Observable<EstadoServico> {
    const base = `/servicos/${servico.id}`;
    return forkJoin({
      identidade: this.http.get<Identidade>(`${base}/api/v1/sistema`),
      saude: this.http.get<{ status: string }>(`${base}/q/health/ready`),
    }).pipe(
      timeout(8000),
      map(({ identidade, saude }) => ({
        ...servico,
        disponivel: saude.status === 'UP' && identidade.servico === servico.id,
        versao: identidade.versao,
        etapa: identidade.etapa,
      })),
      catchError(() =>
        of({
          ...servico,
          disponivel: false,
          versao: '—',
          etapa: 'Não foi possível consultar',
        }),
      ),
    );
  }
}
