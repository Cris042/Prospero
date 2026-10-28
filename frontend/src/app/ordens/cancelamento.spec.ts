import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { Boleta } from './boleta';
import {
  mensagemErroCancelamento,
  OperacaoCancelamento,
  Ordem,
  OrdensService,
} from './ordens.service';

const BASE: Ordem = {
  id: '11111111-1111-4111-8111-111111111111',
  ticker: 'ALFA3',
  lado: 'VENDA',
  tipo: 'LIMIT',
  validade: 'DIA',
  quantidade: 10,
  precoLimite: '10.00',
  precoGatilho: null,
  grupoOco: null,
  papelOco: null,
  estado: 'ABERTA',
  motivoRejeicao: null,
  dataSessao: '2026-10-28',
  recebidaEm: '2026-10-28T15:00:00Z',
};

function botoes(raiz: HTMLElement): HTMLButtonElement[] {
  return Array.from(raiz.querySelectorAll<HTMLButtonElement>('button.cancelar'));
}

describe('Cancelamento manual na lista de ordens', () => {
  let ordensLista: Ordem[];
  let cancelar: (id: string) => unknown;

  async function montar() {
    TestBed.configureTestingModule({
      imports: [Boleta],
      providers: [
        {
          provide: OrdensService,
          useValue: {
            listar: () => of({ ordens: ordensLista, proximoCursor: null }),
            cancelar: (id: string) => cancelar(id),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(Boleta);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    ordensLista = [];
    cancelar = () => of({} as OperacaoCancelamento);
  });

  it('oferece Cancelar só às ordens elegíveis e explica a perna OCO', async () => {
    ordensLista = [
      BASE,
      { ...BASE, id: 'stop', tipo: 'STOP', estado: 'AGUARDANDO_GATILHO' },
      { ...BASE, id: 'exec', estado: 'EXECUTADA' },
      { ...BASE, id: 'rej', estado: 'REJEITADA', motivoRejeicao: 'SALDO_INSUFICIENTE' },
      { ...BASE, id: 'oco', grupoOco: 'g', papelOco: 'TAKE_PROFIT' },
    ];
    const fixture = await montar();
    const raiz = fixture.nativeElement as HTMLElement;
    expect(botoes(raiz).map((b) => b.getAttribute('aria-label'))).toEqual([
      `Cancelar ordem ${BASE.id}`,
      'Cancelar ordem stop',
    ]);
    expect(raiz.textContent).toContain('Ordem OCO: sem cancelamento manual');
  });

  it('solicita o cancelamento uma vez por clique em voo e mostra o estado devolvido pela lista', async () => {
    ordensLista = [BASE];
    const resposta = new Subject<OperacaoCancelamento>();
    const chamadas: string[] = [];
    cancelar = (id) => {
      chamadas.push(id);
      return resposta;
    };
    const fixture = await montar();
    const raiz = fixture.nativeElement as HTMLElement;
    const botao = botoes(raiz)[0];
    botao.click();
    fixture.detectChanges();
    botao.click();
    expect(chamadas).toEqual([BASE.id]);
    expect(botoes(raiz)[0].disabled).toBe(true);
    ordensLista = [
      { ...BASE, cancelamento: { estado: 'SOLICITADO', decisao: null, motivo: null } },
    ];
    resposta.next({ ordem: BASE.id, estado: 'SOLICITADO', decisao: null, motivo: null });
    resposta.complete();
    fixture.componentInstance.atualizarLista();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(botoes(raiz)).toHaveLength(0);
    expect(raiz.textContent).toContain('Cancelamento solicitado');
  });

  it('só CONFIRMADO afirma a confirmação; a recusa mostra o motivo', async () => {
    ordensLista = [
      { ...BASE, id: 'a', cancelamento: { estado: 'DECIDIDO', decisao: 'APLICADO', motivo: null } },
      {
        ...BASE,
        id: 'b',
        cancelamento: { estado: 'RECUSADO', decisao: 'SEM_SALDO_VIVO', motivo: 'SEM_SALDO_VIVO' },
      },
      {
        ...BASE,
        id: 'c',
        estado: 'CANCELADA',
        motivoEncerramento: 'CANCELADA_MANUAL',
        cancelamento: { estado: 'CONFIRMADO', decisao: 'APLICADO', motivo: null },
      },
    ];
    const fixture = await montar();
    const status = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-cancelamento]'),
    ).map((e) => e.textContent?.trim());
    expect(status).toEqual([
      'Cancelamento decidido; aguardando a liberação do saldo',
      'Cancelamento recusado (a ordem não tem mais saldo)',
      'Cancelamento confirmado',
    ]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Cancelada · cancelada a pedido',
    );
  });

  it('mostra erro acessível por ordem e permite tentar de novo', async () => {
    ordensLista = [BASE];
    cancelar = () =>
      throwError(
        () =>
          new HttpErrorResponse({
            status: 503,
            error: { codigo: 'CANCELAMENTO_INDISPONIVEL' },
          }),
      );
    const fixture = await montar();
    const raiz = fixture.nativeElement as HTMLElement;
    botoes(raiz)[0].click();
    fixture.detectChanges();
    const alerta = raiz.querySelector('td p[role="alert"]');
    expect(alerta?.textContent).toContain('temporariamente indisponível');
    expect(botoes(raiz)[0].disabled).toBe(false);
  });
});

describe('mensagemErroCancelamento', () => {
  it('traduz 422 de OCO, credencial e rede sem expor detalhes', () => {
    const http = (status: number, codigo?: string) =>
      new HttpErrorResponse({ status, error: codigo ? { codigo } : null });
    expect(mensagemErroCancelamento(http(422, 'ORDEM_OCO_NAO_CANCELAVEL'))).toContain('OCO');
    expect(mensagemErroCancelamento(http(404, 'ORDEM_NAO_ENCONTRADA'))).toContain('não encontrada');
    expect(mensagemErroCancelamento(http(403))).toContain('Credencial');
    expect(mensagemErroCancelamento(http(422, 'OUTRO'))).toBe(
      'Não foi possível solicitar o cancelamento.',
    );
    expect(mensagemErroCancelamento(new Error('rede'))).toContain('Falha de comunicação');
    expect(mensagemErroCancelamento(http(0))).toContain('Falha de comunicação');
  });
});
