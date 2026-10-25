import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AtualizacoesService } from './atualizacoes.service';
import { Boleta } from '../ordens/boleta';
import { Ordem, OrdensService, Pagina } from '../ordens/ordens.service';
import { Posicoes } from '../posicoes/posicoes';

const ORDEM: Ordem = {
  id: '11111111-1111-4111-8111-111111111111',
  ticker: 'ALFA3',
  lado: 'COMPRA',
  tipo: 'LIMIT',
  validade: 'DIA',
  quantidade: 10,
  precoLimite: '10.00',
  precoGatilho: null,
  grupoOco: null,
  papelOco: null,
  estado: 'RECEBIDA',
  motivoRejeicao: null,
  dataSessao: '2026-10-25',
  recebidaEm: '2026-10-25T10:00:00Z',
};

describe('Avisos integrados às consultas da tela', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function configurarBoleta() {
    const avisos = new Subject<void>();
    const pedidos: Subject<Pagina>[] = [];
    TestBed.configureTestingModule({
      imports: [Boleta],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AtualizacoesService,
          useValue: { avisos$: avisos.asObservable(), estado: signal('online') },
        },
        {
          provide: OrdensService,
          useValue: {
            listar: () => {
              const pedido = new Subject<Pagina>();
              pedidos.push(pedido);
              return pedido;
            },
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(Boleta);
    fixture.detectChanges();
    return { fixture, avisos, pedidos };
  }

  it('Boleta agrega aviso e atualização manual durante GET em uma consulta posterior', () => {
    const { fixture, avisos, pedidos } = configurarBoleta();
    expect(pedidos.length).toBe(1);
    for (let i = 0; i < 30; i++) avisos.next();
    fixture.componentInstance.atualizarLista();
    expect(pedidos.length).toBe(1);
    pedidos[0].next({ ordens: [ORDEM], proximoCursor: null });
    pedidos[0].complete();
    expect(pedidos.length).toBe(2);
    pedidos[1].next({ ordens: [{ ...ORDEM, estado: 'RESERVADA' }], proximoCursor: null });
    pedidos[1].complete();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Reservada');
    fixture.destroy();
  });

  it('botão da ordem abre sua trajetória pelo HTTP autorizado do proxy', () => {
    const { fixture, pedidos } = configurarBoleta();
    const http = TestBed.inject(HttpTestingController);
    pedidos[0].next({ ordens: [ORDEM], proximoCursor: null });
    pedidos[0].complete();
    fixture.detectChanges();
    const botao = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
      /ver trajet[oó]ria/i.test(b.textContent ?? ''),
    );
    expect(botao).toBeDefined();
    botao?.click();
    fixture.detectChanges();
    const pedido = http.expectOne((p) => p.url.endsWith(`/ordens/${ORDEM.id}/trajetoria`));
    expect(pedido.request.headers.has('Authorization')).toBe(false);
    pedido.flush({
      ordem: ORDEM.id,
      conta: 'CONTA-002',
      revisao: '0',
      historicoCompleto: false,
      disponivelDesde: null,
      eventos: [],
      proximoCursor: null,
    });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Histórico incompleto');
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });

  it('Posições agrega aviso durante GET e atualiza assim que a consulta termina', () => {
    vi.useFakeTimers();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    const avisos = new Subject<void>();
    TestBed.configureTestingModule({
      imports: [Posicoes],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AtualizacoesService,
          useValue: { avisos$: avisos.asObservable(), estado: signal('online') },
        },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Posicoes);
    fixture.detectChanges();
    vi.advanceTimersByTime(0);
    const inicial = http.expectOne('/servicos/back-office/api/v1/posicoes');
    for (let i = 0; i < 30; i++) avisos.next();
    fixture.componentInstance.atualizar();
    http.expectNone('/servicos/back-office/api/v1/posicoes');
    inicial.flush({
      conta: 'CONTA-002',
      base: 'NEGOCIOS_DESDE_ORIGEM',
      sincronizado: true,
      posicoes: [],
    });
    const posterior = http.expectOne('/servicos/back-office/api/v1/posicoes');
    expect(fixture.componentInstance.carregando()).toBe(true);
    posterior.flush({
      conta: 'CONTA-002',
      base: 'NEGOCIOS_DESDE_ORIGEM',
      sincronizado: true,
      posicoes: [],
    });
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });
});
