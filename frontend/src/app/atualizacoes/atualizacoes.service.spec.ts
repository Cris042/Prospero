import { TestBed } from '@angular/core/testing';
import { AtualizacoesService, WS_FACTORY } from './atualizacoes.service';

class SocketFalso extends EventTarget {
  readyState = 0;
  fechamentos = 0;
  onopen: ((evento: Event) => void) | null = null;
  onmessage: ((evento: MessageEvent) => void) | null = null;
  onclose: ((evento: CloseEvent) => void) | null = null;
  onerror: ((evento: Event) => void) | null = null;
  abrir() {
    this.readyState = 1;
    const evento = new Event('open');
    this.dispatchEvent(evento);
    this.onopen?.(evento);
  }
  receber(dados: unknown) {
    const evento = new MessageEvent('message', { data: dados });
    this.dispatchEvent(evento);
    this.onmessage?.(evento);
  }
  frame(tipo: string, revisao: string) {
    this.receber(JSON.stringify({ versao: 1, tipo, revisao }));
  }
  close() {
    this.readyState = 3;
    this.fechamentos++;
    const evento = new CloseEvent('close');
    this.dispatchEvent(evento);
    this.onclose?.(evento);
  }
}

describe('Canal de avisos de atualização', () => {
  let sockets: SocketFalso[];
  let urls: string[];
  let visibilidade: DocumentVisibilityState;
  let servico: AtualizacoesService;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    visibilidade = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibilidade);
    sockets = [];
    urls = [];
    TestBed.configureTestingModule({
      providers: [
        {
          provide: WS_FACTORY,
          useValue: (url: string) => {
            urls.push(url);
            const socket = new SocketFalso();
            sockets.push(socket);
            return socket as unknown as WebSocket;
          },
        },
      ],
    });
    servico = TestBed.inject(AtualizacoesService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('compartilha um socket enquanto houver assinantes e URL não contém credencial', () => {
    const primeira = servico.avisos$.subscribe();
    const segunda = servico.avisos$.subscribe();
    expect(sockets.length).toBe(1);
    const url = new URL(urls[0]);
    expect(url.pathname).toBe('/servicos/front-office/api/v1/atualizacoes');
    expect(url.search).toBe('');
    expect(url.username).toBe('');
    expect(url.password).toBe('');
    sockets[0].abrir();
    expect(servico.estado()).toBe('online');
    primeira.unsubscribe();
    expect(sockets[0].fechamentos).toBe(0);
    segunda.unsubscribe();
    expect(sockets[0].fechamentos).toBe(1);
    vi.advanceTimersByTime(20000);
    expect(sockets.length).toBe(1);
  });

  it('valida frames, ignora duplicados/antigos e compara revisões maiores que inteiro seguro', () => {
    let avisos = 0;
    const assinatura = servico.avisos$.subscribe(() => avisos++);
    sockets[0].abrir();
    sockets[0].frame('RESSINCRONIZAR', '9007199254740992');
    sockets[0].frame('ATUALIZACAO', '9007199254740992');
    sockets[0].frame('ATUALIZACAO', '9007199254740991');
    sockets[0].frame('ATUALIZACAO', '9007199254740993');
    expect(avisos).toBe(2);
    for (const invalido of [
      'nao-json',
      JSON.stringify({ versao: 2, tipo: 'ATUALIZACAO', revisao: '9007199254740994' }),
      JSON.stringify({ versao: 1, tipo: 'ATUALIZACAO', revisao: 9007199254740994 }),
      JSON.stringify({ versao: 1, tipo: 'ATUALIZACAO', revisao: '-1' }),
      JSON.stringify({ versao: 1, tipo: 'ATUALIZACAO', revisao: '01' }),
      JSON.stringify({ versao: 1, tipo: 'OUTRO', revisao: '9007199254740994' }),
      ' '.repeat(1025),
    ])
      sockets[0].receber(invalido);
    expect(avisos).toBe(2);
    assinatura.unsubscribe();
  });

  it('reconecta com recuo limitado e RESSINCRONIZAR reconcilia mesmo revisão repetida', () => {
    let avisos = 0;
    const assinatura = servico.avisos$.subscribe(() => avisos++);
    sockets[0].abrir();
    sockets[0].frame('RESSINCRONIZAR', '7');
    sockets[0].close();
    expect(servico.estado()).toBe('indisponivel');
    vi.advanceTimersByTime(999);
    expect(sockets.length).toBe(1);
    vi.advanceTimersByTime(1);
    expect(sockets.length).toBe(2);
    sockets[1].abrir();
    sockets[1].frame('RESSINCRONIZAR', '7');
    expect(avisos).toBe(2);
    sockets[1].close();
    vi.advanceTimersByTime(1000);
    expect(sockets.length).toBe(3);
    sockets[2].close();
    vi.advanceTimersByTime(1999);
    expect(sockets.length).toBe(3);
    vi.advanceTimersByTime(1);
    expect(sockets.length).toBe(4);
    sockets[3].close();
    vi.advanceTimersByTime(4000);
    expect(sockets.length).toBe(5);
    sockets[4].close();
    vi.advanceTimersByTime(8000);
    expect(sockets.length).toBe(6);
    assinatura.unsubscribe();
  });

  it('ocultar fecha canal e voltar cria conexão que força reconciliação; destroy cancela tudo', () => {
    let avisos = 0;
    servico.avisos$.subscribe(() => avisos++);
    sockets[0].abrir();
    sockets[0].frame('RESSINCRONIZAR', '7');
    visibilidade = 'hidden';
    document.dispatchEvent(new Event('visibilitychange'));
    expect(servico.estado()).toBe('oculto');
    expect(sockets[0].fechamentos).toBe(1);
    vi.advanceTimersByTime(20000);
    expect(sockets.length).toBe(1);
    visibilidade = 'visible';
    document.dispatchEvent(new Event('visibilitychange'));
    expect(sockets.length).toBe(2);
    sockets[1].abrir();
    sockets[1].frame('RESSINCRONIZAR', '7');
    expect(avisos).toBe(2);
    TestBed.resetTestingModule();
    vi.advanceTimersByTime(20000);
    expect(sockets[1].fechamentos).toBe(1);
    expect(sockets.length).toBe(2);
  });
});
