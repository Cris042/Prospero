import { DOCUMENT } from '@angular/common';
import { inject, Injectable, InjectionToken, OnDestroy, signal } from '@angular/core';
import { Observable, Subscriber } from 'rxjs';

export const WS_FACTORY = new InjectionToken<(url: string) => WebSocket>('WS_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new WebSocket(url),
});
type EstadoCanal = 'conectando' | 'online' | 'indisponivel' | 'oculto';

/** Um canal por tela/origem, sem credenciais no JavaScript e sem estado financeiro nos frames. */
@Injectable({ providedIn: 'root' })
export class AtualizacoesService implements OnDestroy {
  private readonly documento = inject(DOCUMENT);
  private readonly criarSocket = inject(WS_FACTORY);
  private readonly assinantes = new Set<Subscriber<void>>();
  private socket: WebSocket | null = null;
  private espera: ReturnType<typeof setTimeout> | null = null;
  private tentativa = 0;
  private encerrado = false;
  readonly estado = signal<EstadoCanal>('oculto');
  readonly avisos$ = new Observable<void>((observador) => {
    this.assinantes.add(observador);
    if (this.assinantes.size === 1) {
      this.documento.addEventListener('visibilitychange', this.visibilidade);
      this.visibilidade();
    }
    return () => {
      this.assinantes.delete(observador);
      if (this.assinantes.size === 0) {
        this.documento.removeEventListener('visibilitychange', this.visibilidade);
        this.fechar();
        this.estado.set('oculto');
      }
    };
  });

  ngOnDestroy(): void {
    this.encerrado = true;
    this.documento.removeEventListener('visibilitychange', this.visibilidade);
    this.fechar();
    this.assinantes.clear();
  }

  private readonly visibilidade = () => {
    this.fechar();
    if (this.documento.visibilityState === 'hidden') {
      this.estado.set('oculto');
    } else {
      this.tentativa = 0;
      this.conectar();
    }
  };

  private conectar(): void {
    if (this.encerrado || this.assinantes.size === 0 || this.documento.visibilityState === 'hidden')
      return;
    this.estado.set('conectando');
    try {
      const url = new URL(
        '/servicos/front-office/api/v1/atualizacoes',
        this.documento.location.href,
      );
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      const socket = this.criarSocket(url.toString());
      this.socket = socket;
      let ultima = -1n;
      let sincronizou = false;
      socket.onopen = () => {
        if (this.socket === socket) this.estado.set('online');
      };
      socket.onmessage = (mensagem) => {
        if (this.socket !== socket) return;
        const frame = this.frame(mensagem.data);
        if (!frame || (!sincronizou && frame.tipo !== 'RESSINCRONIZAR')) {
          this.falhar(socket);
          return;
        }
        const revisao = BigInt(frame.revisao);
        if (revisao <= ultima) return;
        ultima = revisao;
        sincronizou = true;
        this.tentativa = 0;
        for (const observador of this.assinantes) observador.next();
      };
      socket.onerror = () => this.falhar(socket);
      socket.onclose = () => this.falhar(socket);
    } catch {
      this.reconectar();
    }
  }

  private frame(dados: unknown): { tipo: string; revisao: string } | null {
    if (typeof dados !== 'string' || dados.length > 1024) return null;
    try {
      const frame: unknown = JSON.parse(dados);
      if (
        !frame ||
        typeof frame !== 'object' ||
        Object.keys(frame).length !== 3 ||
        !('versao' in frame) ||
        frame.versao !== 1 ||
        !('tipo' in frame) ||
        !['RESSINCRONIZAR', 'ATUALIZACAO'].includes(String(frame.tipo)) ||
        !('revisao' in frame) ||
        typeof frame.revisao !== 'string' ||
        !/^(0|[1-9][0-9]{0,18})$/.test(frame.revisao) ||
        BigInt(frame.revisao) > 9223372036854775807n
      )
        return null;
      return { tipo: String(frame.tipo), revisao: frame.revisao };
    } catch {
      return null;
    }
  }

  private falhar(socket: WebSocket): void {
    if (this.socket !== socket) return;
    this.fechar();
    this.reconectar();
  }

  private reconectar(): void {
    if (this.encerrado || this.assinantes.size === 0 || this.documento.visibilityState === 'hidden')
      return;
    this.estado.set('indisponivel');
    const atraso = Math.min(8, 2 ** this.tentativa) * 1000 * (0.8 + Math.random() * 0.4);
    this.tentativa = Math.min(3, this.tentativa + 1);
    this.espera = setTimeout(() => {
      this.espera = null;
      this.conectar();
    }, atraso);
  }

  private fechar(): void {
    if (this.espera !== null) clearTimeout(this.espera);
    this.espera = null;
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
      socket.close();
    }
  }
}
