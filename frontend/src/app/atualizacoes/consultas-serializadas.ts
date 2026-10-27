import { Observable, Subscription } from 'rxjs';

/** Mantém no máximo uma consulta ativa e um aviso pendente, inclusive sob rajadas. */
export function consultasSerializadas<T>(
  avisos: Observable<unknown>,
  consultar: () => Observable<T>,
): Observable<T> {
  return new Observable<T>((observador) => {
    const grupo = new Subscription();
    let ativo = false;
    let pendente = false;
    let terminou = false;
    let cancelado = false;
    const executar = () => {
      if (cancelado || observador.closed) return;
      if (ativo) {
        pendente = true;
        return;
      }
      ativo = true;
      pendente = false;
      try {
        grupo.add(
          consultar().subscribe({
            next: (valor) => observador.next(valor),
            error: (erro: unknown) => observador.error(erro),
            complete: () => {
              ativo = false;
              if (pendente) executar();
              else if (terminou) observador.complete();
            },
          }),
        );
      } catch (erro) {
        observador.error(erro);
      }
    };
    grupo.add(
      avisos.subscribe({
        next: executar,
        error: (erro: unknown) => observador.error(erro),
        complete: () => {
          terminou = true;
          if (!ativo) observador.complete();
        },
      }),
    );
    return () => {
      cancelado = true;
      pendente = false;
      grupo.unsubscribe();
    };
  });
}
