import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, Subject, TimeoutError } from 'rxjs';
import { Boleta } from './boleta';
import { mensagemErro, Ordem, OrdensService, PedidoOco, PedidoOrdem } from './ordens.service';

const ORDEM: Ordem = {
  id: '11111111-1111-4111-8111-111111111111',
  ticker: 'ALFA3',
  lado: 'COMPRA',
  tipo: 'LIMIT',
  validade: 'DIA',
  quantidade: 100,
  precoLimite: '10.25',
  precoGatilho: null,
  grupoOco: null,
  papelOco: null,
  estado: 'RECEBIDA',
  motivoRejeicao: null,
  dataSessao: '2026-10-17',
  recebidaEm: '2026-10-17T15:00:00Z',
};

describe('Boleta', () => {
  let envios: { pedido: PedidoOrdem; chave: string; resposta: Subject<Ordem> }[];
  let ocos: { pedido: PedidoOco; chave: string }[];

  beforeEach(async () => {
    envios = [];
    ocos = [];
    await TestBed.configureTestingModule({
      imports: [Boleta],
      providers: [
        {
          provide: OrdensService,
          useValue: {
            listar: () => of({ ordens: [ORDEM], proximoCursor: null }),
            enviar: (pedido: PedidoOrdem, chave: string) => {
              const resposta = new Subject<Ordem>();
              envios.push({ pedido, chave, resposta });
              return resposta;
            },
            enviarOco: (pedido: PedidoOco, chave: string) => {
              ocos.push({ pedido, chave });
              return of({ grupoOco: 'g', ordens: [ORDEM, { ...ORDEM, id: 'outra' }] });
            },
          },
        },
      ],
    }).compileComponents();
  });

  it('lista a perna OCO reduzida e a encerrada pela irmã', async () => {
    const reduzida: Ordem = {
      ...ORDEM,
      id: 'sl',
      tipo: 'STOP',
      grupoOco: 'g',
      papelOco: 'STOP_LOSS',
      estado: 'AGUARDANDO_GATILHO',
      quantidadeReduzida: 40,
    };
    const cancelada: Ordem = {
      ...ORDEM,
      id: 'tp',
      grupoOco: 'g',
      papelOco: 'TAKE_PROFIT',
      estado: 'CANCELADA',
      motivoEncerramento: 'CANCELADA_OCO',
    };
    TestBed.overrideProvider(OrdensService, {
      useValue: { listar: () => of({ ordens: [reduzida, cancelada], proximoCursor: null }) },
    });
    const fixture = TestBed.createComponent(Boleta);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texto).toContain('100 (reduzida em 40)');
    expect(texto).toContain('Cancelada · encerrada pela outra perna do OCO');
    expect(texto).not.toContain('OCO ainda não executa');
  });

  it('descreve o resultado do risco com o motivo da rejeição em pt-BR', async () => {
    const boleta = TestBed.createComponent(Boleta).componentInstance;
    expect(boleta.estadoRegistro([ORDEM])).toBe('Recebida');
    expect(boleta.estadoRegistro([{ ...ORDEM, estado: 'RESERVADA' }])).toBe('Reservada');
    expect(
      boleta.estadoRegistro([
        { ...ORDEM, estado: 'REJEITADA', motivoRejeicao: 'SALDO_INSUFICIENTE' },
      ]),
    ).toBe('Rejeitada (saldo insuficiente)');
  });

  it('identifica stop aguardando gatilho e rejeição pelo teto de pendentes', async () => {
    const boleta = TestBed.createComponent(Boleta).componentInstance;
    expect(boleta.estadoRegistro([{ ...ORDEM, tipo: 'STOP', estado: 'AGUARDANDO_GATILHO' }])).toBe(
      'Aguardando gatilho',
    );
    expect(
      boleta.estadoRegistro([
        { ...ORDEM, tipo: 'STOP', estado: 'REJEITADA', motivoRejeicao: 'LIMITE_STOPS_PENDENTES' },
      ]),
    ).toContain('limite');
  });

  it('oferece só as validades da matriz e os preços exigidos pelo tipo', async () => {
    const fixture = TestBed.createComponent(Boleta);
    await fixture.whenStable();
    const boleta = fixture.componentInstance;
    boleta.simples.controls.tipo.setValue('MARKET');
    expect(boleta.validades()).toEqual(['IOC', 'FOK']);
    expect(boleta.simples.controls.validade.value).toBe('IOC');
    boleta.simples.controls.tipo.setValue('STOP_LIMIT');
    expect(boleta.validades()).toEqual(['DIA']);
    fixture.detectChanges();
    const html = fixture.nativeElement as HTMLElement;
    expect(html.textContent).toContain('Preço limite');
    expect(html.textContent).toContain('Preço de disparo');
  });

  it('envia uma vez em clique duplo e não promete execução', async () => {
    const fixture = TestBed.createComponent(Boleta);
    await fixture.whenStable();
    const boleta = fixture.componentInstance;
    boleta.simples.patchValue({ precoLimite: '10.25' });
    boleta.enviar();
    boleta.enviar();
    expect(envios).toHaveLength(1);
    expect(envios[0].pedido).toEqual(
      expect.objectContaining({ tipo: 'LIMIT', precoLimite: '10.25', precoGatilho: null }),
    );
    envios[0].resposta.next(ORDEM);
    envios[0].resposta.complete();
    fixture.detectChanges();
    const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texto).toContain('Ordem registrada — estado Recebida.');
    expect(texto.toLowerCase()).not.toContain('executada');
  });

  it('reaproveita a chave após falha de rede e gera outra após recusa', async () => {
    const fixture = TestBed.createComponent(Boleta);
    await fixture.whenStable();
    const boleta = fixture.componentInstance;
    boleta.simples.patchValue({ precoLimite: '10.25' });
    boleta.enviar();
    envios[0].resposta.error(new HttpErrorResponse({ status: 0 }));
    boleta.enviar();
    expect(envios[1].chave).toBe(envios[0].chave);
    envios[1].resposta.error(
      new HttpErrorResponse({ status: 422, error: { codigo: 'PRECO_INVALIDO' } }),
    );
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent,
    ).toContain('Preço inválido');
    boleta.enviar();
    expect(envios[2].chave).not.toBe(envios[1].chave);
  });

  it('traduz Problem Details e trata erro de servidor como repetível', () => {
    expect(
      mensagemErro(
        new HttpErrorResponse({ status: 409, error: { codigo: 'IDEMPOTENCIA_CONFLITO' } }),
      ),
    ).toEqual({ texto: expect.stringContaining('outros dados'), repetivel: false });
    expect(mensagemErro(new HttpErrorResponse({ status: 503 })).repetivel).toBe(true);
    expect(mensagemErro(new TimeoutError()).repetivel).toBe(true);
    expect(
      mensagemErro(
        new HttpErrorResponse({
          status: 422,
          error: { codigo: 'PRECO_FORA_DO_TUNEL', detalhes: { minimo: '9.00', maximo: '11.00' } },
        }),
      ).texto,
    ).toBe('Preço fora do túnel permitido para o ativo. Faixa aceita: 9,00 a 11,00.');
    expect(
      mensagemErro(
        new HttpErrorResponse({
          status: 422,
          error: { codigo: 'QUANTIDADE_FORA_DO_LOTE', detalhes: { lote: '100' } },
        }),
      ).texto,
    ).toBe('Quantidade deve ser múltipla do lote do ativo. Lote: 100.');
    expect(
      mensagemErro(
        new HttpErrorResponse({
          status: 422,
          error: { codigo: 'PRECO_FORA_DO_TICK', detalhes: { tick: '0.05' } },
        }),
      ).texto,
    ).toBe('Preço deve ser múltiplo do tick do ativo. Tick: 0,05.');
    expect(mensagemErro(new HttpErrorResponse({ status: 401 })).texto).toContain(
      'preparar-ambiente',
    );
    expect(mensagemErro(new HttpErrorResponse({ status: 429 }))).toEqual({
      texto: expect.stringContaining('Aguarde'),
      repetivel: true,
    });
  });

  it('gera chave nova se os dados mudarem, mesmo após falha de comunicação', async () => {
    const fixture = TestBed.createComponent(Boleta);
    await fixture.whenStable();
    const boleta = fixture.componentInstance;
    boleta.simples.patchValue({ precoLimite: '10,25' });
    boleta.enviar();
    expect(envios[0].pedido.precoLimite).toBe('10.25');
    boleta.simples.patchValue({ quantidade: 200 });
    envios[0].resposta.error(new TimeoutError());
    boleta.enviar();
    expect(envios[1].chave).not.toBe(envios[0].chave);
  });

  it('monta o OCO com Stop Limit e mostra as duas pernas', async () => {
    const fixture = TestBed.createComponent(Boleta);
    await fixture.whenStable();
    const boleta = fixture.componentInstance;
    boleta.alternar('OCO');
    boleta.oco.patchValue({
      alvo: '11,00',
      tipoStop: 'STOP_LIMIT',
      gatilho: '9.50',
      limiteStop: '9,45',
    });
    boleta.enviar();
    expect(ocos[0].pedido).toEqual(
      expect.objectContaining({
        validade: 'DIA',
        takeProfit: { precoLimite: '11.00' },
        stopLoss: { tipo: 'STOP_LIMIT', precoGatilho: '9.50', precoLimite: '9.45' },
      }),
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Ordens registradas — estado Recebida.',
    );
  });
});
