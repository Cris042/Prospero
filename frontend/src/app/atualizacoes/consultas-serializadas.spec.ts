import { Observable, Subject } from 'rxjs';
import { consultasSerializadas } from './consultas-serializadas';

describe('Consultas serializadas por avisos', () => {
  it('agrega avisos durante HTTP em uma consulta posterior sem sobrepor pedidos', () => {
    const avisos = new Subject<void>();
    const pedidos: Subject<string>[] = [];
    const recebidos: string[] = [];
    let ativos = 0;
    let maximo = 0;
    const assinatura = consultasSerializadas(avisos, () => {
      const pedido = new Subject<string>();
      pedidos.push(pedido);
      return new Observable<string>((observador) => {
        ativos++;
        maximo = Math.max(maximo, ativos);
        let concluido = false;
        const subscricao = pedido.subscribe({
          next: (valor) => observador.next(valor),
          complete: () => {
            concluido = true;
            ativos--;
            observador.complete();
          },
        });
        return () => {
          if (!concluido) ativos--;
          subscricao.unsubscribe();
        };
      });
    }).subscribe((valor: string) => recebidos.push(valor));
    avisos.next();
    for (let i = 0; i < 100; i++) avisos.next();
    expect(pedidos.length).toBe(1);
    pedidos[0].next('primeira');
    pedidos[0].complete();
    expect(pedidos.length).toBe(2);
    pedidos[1].next('segunda');
    pedidos[1].complete();
    expect(pedidos.length).toBe(2);
    expect(maximo).toBe(1);
    expect(recebidos).toEqual(['primeira', 'segunda']);
    assinatura.unsubscribe();
  });

  it('unsubscribe cancela HTTP em andamento e descarta aviso pendente', () => {
    const avisos = new Subject<void>();
    let chamadas = 0;
    let canceladas = 0;
    const assinatura = consultasSerializadas(avisos, () => {
      chamadas++;
      return new Observable(() => () => canceladas++);
    }).subscribe();
    avisos.next();
    avisos.next();
    assinatura.unsubscribe();
    avisos.next();
    expect(chamadas).toBe(1);
    expect(canceladas).toBe(1);
  });
});
