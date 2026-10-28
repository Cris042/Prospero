#!/usr/bin/env python3
"""Cenário B3: cancelamento sob carga com saldo em repouso, liberação única e falha do MO durante o pedido.

Cada execução prepara CONTA-002 (compradora) e CONTA-003 (vendedora com M unidades). A compradora envia N compras
DIA de uma unidade: M executam e N−M ficam em repouso com reserva. Depois cancela as abertas por
`PUT /api/v1/ordens/{id}/cancelamento`, repete o pedido (cabeçalho de repetição), confere a recusa de uma ordem
executada e os negativos (404 de outra conta, 400 com corpo). Na segunda execução o MO para antes dos pedidos: o
gauge `prospero_cancelamento_pendente_segundos_max` do FO precisa acusar pendência e zerar depois do religamento.
Invariantes, todas sobre leituras reais: quantidades de cada ordem pela API (executada + cancelada = quantidade); saldos
finais do MO exatos em Decimal (total, disponível e reserva; sem segunda liberação) e custódia da compradora inalterada.
O relatório público traz contagens, digests dos identificadores (não os IDs), saldos lidos e o estado dos negativos."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from decimal import Decimal

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))


def _carregar(nome, arquivo):
    spec = importlib.util.spec_from_file_location(nome, AQUI / arquivo)
    modulo = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modulo)
    return modulo


esc = _carregar('prospero_carga_escrita_cancelamento', 'testar-carga-escrita.py')
ci = esc.ci
import mercado  # noqa: E402  (precisa do sys.path acima)
import telemetria_carga as tel  # noqa: E402

GAUGE = 'prospero_cancelamento_pendente_segundos_max'
TICKER, PRECO, INICIAL = 'ALFA3', '10.00', '1000000.00'
TERMINAIS_CANCELAMENTO = ('CONFIRMADO', 'RECUSADO')


def plano(n, m):
    if type(n) is not int or type(m) is not int or not 1 <= m < n <= 6:
        raise ValueError('plano de cancelamento invalido')
    return {'agressores': n, 'contraparte': m}


def repetido(cabecalhos):
    valor = cabecalhos.get('X-Cancelamento-Repetido')
    if valor == 'true':
        return True
    if valor == 'false':
        return False
    raise ValueError('cabecalho de repeticao invalido')


def classificar_desfecho(estado, operacao):
    """Só dois desfechos são aceitos para a corrida cancelamento × agressão; qualquer outra combinação recusa."""
    if (estado == 'CANCELADA' and operacao.get('estado') == 'CONFIRMADO' and operacao.get('decisao') == 'APLICADO'
            and operacao.get('motivo') is None):
        return 'CANCELADA'
    if (estado == 'EXECUTADA' and operacao.get('estado') == 'RECUSADO'
            and operacao.get('decisao') == 'SEM_SALDO_VIVO' and operacao.get('motivo') == 'SEM_SALDO_VIVO'):
        return 'EXECUTADA'
    raise ValueError('desfecho do cancelamento incoerente')


def _valor(texto):
    if not isinstance(texto, str) or not re.fullmatch(r'-?[0-9]+(\.[0-9]+)?', texto):
        raise ValueError('valor monetario invalido')
    return Decimal(texto)


def _saldos(carteira):
    """Lê os três saldos do MO como texto decimal; número JSON (float ou int) é recusado."""
    total = _valor(carteira['saldoTotal'])
    disponivel = _valor(carteira['saldoDisponivel'])
    reservado = _valor(carteira['saldoReservado'])
    return total, disponivel, reservado


def conferir_repouso(carteira, executadas, abertas, preco, inicial):
    """Com N−M ordens em repouso: debitado só o executado, reservado exatamente o preço vezes as abertas, e o
    disponível é o total menos a reserva."""
    total, disponivel, reservado = _saldos(carteira)
    if (total != Decimal(inicial) - Decimal(preco) * executadas or reservado != Decimal(preco) * abertas
            or disponivel != total - reservado):
        raise ValueError('saldo em repouso divergente')


def conferir_liberacao(carteira, executadas, preco, inicial):
    """Saldos finais exatos: total = inicial − preço × executadas, reserva zero e disponível igual ao total.
    Uma segunda liberação deixa reserva negativa ou disponível acima do total; ambas recusam."""
    total, disponivel, reservado = _saldos(carteira)
    esperado = Decimal(inicial) - Decimal(preco) * executadas
    if total != esperado or reservado != 0 or disponivel != esperado:
        raise ValueError('liberacao da reserva divergente')


def custodia_compradora(carteira, ticker=TICKER):
    """Entrada da custódia do ticker na compradora, lida do MO; None quando a conta não tem custódia dele."""
    achados = [c for c in carteira.get('custodias', []) if isinstance(c, dict) and c.get('ticker') == ticker]
    if not achados:
        return None
    if len(achados) != 1:
        raise ValueError('custodia duplicada')
    custodia = {nome: achados[0].get(nome) for nome in ('total', 'disponivel', 'reservado')}
    if any(type(v) is not int or v < 0 for v in custodia.values()):
        raise ValueError('custodia invalida')
    return custodia


def conferir_custodia(antes, depois):
    """O cancelamento só mexe no caixa reservado: a custódia da compradora fica igual à lida em repouso."""
    if antes != depois:
        raise ValueError('custodia alterada pelo cancelamento')


def conferir_quantidades(corpos, n):
    """Lê quantidadeExecutada e quantidadeCancelada de cada ordem: cada uma fecha a quantidade original e o total
    de executadas mais canceladas fecha n. Devolve (executadas, canceladas) lidas, não contadas."""
    executadas = canceladas = 0
    for corpo in corpos:
        valores = [corpo.get(nome) for nome in ('quantidade', 'quantidadeExecutada', 'quantidadeCancelada',
                                                  'quantidadeReduzida')]
        if any(type(v) is not int or v < 0 for v in valores):
            raise ValueError('quantidade invalida na ordem')
        quantidade, feita, cancelada, reduzida = valores
        if feita + cancelada + reduzida != quantidade:
            raise ValueError('executada + cancelada diverge do original')
        executadas += feita
        canceladas += cancelada
    if len(corpos) != n or executadas + canceladas != n:
        raise ValueError('executada + cancelada diverge do original')
    return executadas, canceladas


def conferir_invariante(estados, n):
    if len(estados) != n or any(estado not in ('EXECUTADA', 'CANCELADA') for estado in estados):
        raise ValueError('executada + cancelada diverge do original')


def _saldo_lido(carteira, custodia):
    return {'saldoTotal': carteira['saldoTotal'], 'saldoDisponivel': carteira['saldoDisponivel'],
            'saldoReservado': carteira['saldoReservado'], 'custodiaCompradora': custodia}


def pendencia_observada(texto):
    valor = tel.interpretar_gauge(texto, GAUGE)
    if valor <= 0:
        raise ValueError('sem pendencia observada')
    return valor


def conferir_recuperacao(texto):
    if tel.interpretar_gauge(texto, GAUGE) != 0:
        raise ValueError('cancelamento ainda pendente apos religar')


def relatorio_publico(execucoes):
    return {'tipo': 'smoke-cancelamento-carga', 'execucoes': execucoes}


# --- Operação contra o ambiente real (exercitada pela CI; sem teste local) ---

def _pedido(metodo, url, tok, corpo=None):
    """Devolve (status, cabeçalhos, corpo JSON); corpo de erro nunca é lido nem exposto."""
    cab = {'Authorization': 'Bearer ' + tok}
    dados = None
    if corpo is not None:
        dados = json.dumps(corpo).encode()
        cab['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, data=dados, method=metodo, headers=cab)
    try:
        with urllib.request.urlopen(req, timeout=10) as resposta:
            conteudo = resposta.read(1024 * 1024)
            return resposta.status, resposta.headers, json.loads(conteudo) if conteudo else None
    except urllib.error.HTTPError as erro:
        erro.close()
        return erro.code, erro.headers, None


def _metricas(origem):
    with urllib.request.urlopen(origem + '/q/metrics', timeout=10) as resposta:
        return resposta.read(4 * 1024 * 1024).decode()


def _tentar(funcao):
    """Converte recusa transitória de leitura (ValueError) em 'ainda não' para o polling."""
    try:
        return funcao()
    except ValueError:
        return None


def _aguardar(predicado, prazo, pausa=.5):
    limite = time.monotonic() + prazo
    while True:
        valor = predicado()
        if valor is not None:
            return valor
        if time.monotonic() >= limite:
            raise ValueError('prazo do cancelamento excedido')
        time.sleep(min(pausa, max(0, limite - time.monotonic())))


def _operacao(origem, tok, ordem):
    status, _, corpo = _pedido('GET', origem + '/api/v1/ordens/' + ordem + '/cancelamento', tok)
    if status != 200 or not isinstance(corpo, dict):
        raise ValueError('consulta do cancelamento recusada')
    return corpo


def _pedir_cancelamento(origem, tok, ordem):
    status, cabecalhos, corpo = _pedido('PUT', origem + '/api/v1/ordens/' + ordem + '/cancelamento', tok)
    if status != 202 or not isinstance(corpo, dict):
        raise ValueError('pedido de cancelamento recusado')
    return repetido(cabecalhos)


def _terminal(origem, tok, ordem):
    corpo = _operacao(origem, tok, ordem)
    return corpo if corpo.get('estado') in TERMINAIS_CANCELAMENTO else None


def _desfecho(origem, tok, ordem, operacao):
    estado = esc.estado_ordem(origem, tok, ordem)
    if estado not in ('CANCELADA', 'EXECUTADA'):
        return None
    return classificar_desfecho(estado, operacao)


def _carteira(origem_mo, tok):
    return mercado.http('GET', origem_mo + '/api/v1/risco/carteira', tok)


def _ordem(origem, tok, ordem):
    """Leitura da ordem pela API: quantidades reais, não contadas a partir dos estados."""
    status, _, corpo = _pedido('GET', origem + '/api/v1/ordens/' + ordem, tok)
    if status != 200 or not isinstance(corpo, dict):
        raise ValueError('consulta da ordem recusada')
    return corpo


def _repouso_conferido(origem_mo, tok, executadas, abertas):
    carteira = _carteira(origem_mo, tok)
    conferir_repouso(carteira, executadas, abertas, PRECO, INICIAL)
    custodia_compradora(carteira)
    return carteira


def _recusa_executada(origem, tok, ordem):
    """PUT sobre ordem já executada segue o contrato RF10: 202 e recusa SEM_SALDO_VIVO pelo MO."""
    _pedir_cancelamento(origem, tok, ordem)
    atual = _aguardar(lambda: _terminal(origem, tok, ordem), 60)
    if _desfecho(origem, tok, ordem, atual) != 'EXECUTADA':
        raise ValueError('ordem executada nao foi recusada')
    return atual['motivo']


def rodar_execucao(ctx, n, m, falha):
    plano(n, m)
    origem, origem_mo = ctx['origem'], ctx['origem_mo']()
    tok_compra = mercado.token('ordens', 900, 'CONTA-002')
    tok_venda = mercado.token('ordens', 900, 'CONTA-003')
    tok_risco = mercado.token('risco', 300, 'CONTA-002')
    for conta in ('CONTA-002', 'CONTA-003'):
        mercado.sem_pendencias(origem, conta)
    mercado.preparar_carteira(origem_mo, 'CONTA-002', INICIAL, {}, prazo=60)
    mercado.preparar_carteira(origem_mo, 'CONTA-003', '0.00', {TICKER: m}, prazo=60)
    oferta = _pedido('POST', origem + '/api/v1/ordens', tok_venda,
                     {'ticker': TICKER, 'lado': 'VENDA', 'tipo': 'LIMIT', 'validade': 'DIA', 'quantidade': m,
                      'precoLimite': PRECO})[2]
    mercado.aguardar_campos(origem, tok_venda, '/api/v1/ordens/' + oferta['id'], {'estado': 'ABERTA'})
    ordens = [_pedido('POST', origem + '/api/v1/ordens', tok_compra,
                      {'ticker': TICKER, 'lado': 'COMPRA', 'tipo': 'LIMIT', 'validade': 'DIA', 'quantidade': 1,
                       'precoLimite': PRECO}, )[2]['id'] for _ in range(n)]

    def assentadas():
        atuais = {i: esc.estado_ordem(origem, tok_compra, i) for i in ordens}
        executadas = sum(1 for e in atuais.values() if e == 'EXECUTADA')
        return atuais if executadas == m and all(e in ('EXECUTADA', 'ABERTA') for e in atuais.values()) else None

    atuais = _aguardar(assentadas, 60)
    abertas = [i for i in ordens if atuais[i] == 'ABERTA']
    executada_ref = next(i for i in ordens if atuais[i] == 'EXECUTADA')
    repouso = _aguardar(lambda: _tentar(lambda: _repouso_conferido(origem_mo, tok_risco, m, n - m)), 60)
    custodia_repouso = custodia_compradora(repouso)
    status_alheia, _, _ = _pedido('PUT', origem + '/api/v1/ordens/' + oferta['id'] + '/cancelamento', tok_compra)
    status_corpo, _, _ = _pedido('PUT', origem + '/api/v1/ordens/' + abertas[0] + '/cancelamento', tok_compra,
                                 {'x': 1})
    if (status_alheia, status_corpo) != (404, 400):
        raise ValueError('negativos do cancelamento divergentes')

    def pedidos():
        for ordem in abertas:
            if _pedir_cancelamento(origem, tok_compra, ordem):
                raise ValueError('primeiro pedido marcado como repetido')
            if not _pedir_cancelamento(origem, tok_compra, ordem):
                raise ValueError('repeticao sem cabecalho')

    pendentes = None
    if falha:
        def com_mo_parado():
            nonlocal pendentes
            pedidos()
            pendentes = sum(1 for i in abertas if _operacao(origem, tok_compra, i)['estado'] not in
                            TERMINAIS_CANCELAMENTO)
            if pendentes < 1:
                raise ValueError('falha ensaiada nao deixou cancelamento pendente')
            _aguardar(lambda: _tentar(lambda: pendencia_observada(_metricas(origem))), 30)

        esc.com_falha(ctx['parar_mo'], ctx['religar_mo'], com_mo_parado)
        _aguardar(lambda: _tentar(lambda: conferir_recuperacao(_metricas(origem)) or True), 120)
    else:
        pedidos()
    recusa = _recusa_executada(origem, tok_compra, executada_ref)
    finais = {i: atuais[i] for i in ordens if i not in abertas}
    for ordem in abertas:
        _aguardar(lambda o=ordem: _terminal(origem, tok_compra, o), 120 if falha else 60)
        finais[ordem] = _aguardar(lambda o=ordem: _desfecho(origem, tok_compra, o, _terminal(origem, tok_compra, o)),
                                  60)
    estados_finais = [finais[i] for i in ordens]
    conferir_invariante(estados_finais, n)
    executadas, canceladas = conferir_quantidades([_ordem(origem, tok_compra, i) for i in ordens], n)

    def liberado():
        carteira = _carteira(origem_mo, tok_risco)
        conferir_liberacao(carteira, executadas, PRECO, INICIAL)
        conferir_custodia(custodia_repouso, custodia_compradora(carteira))
        return carteira

    final = _aguardar(lambda: _tentar(liberado), 60)
    # Sem 'liberacoes' fixa: a liberação única é provada pelos saldos finais lidos, que uma segunda liberação quebra.
    # 'repeticaoConfirmada' vale porque pedidos() recusa a ausência do cabeçalho de repetição.
    return {'digestsOrdens': [hashlib.sha256(i.encode()).hexdigest() for i in ordens], 'ordens': n,
            'executadas': executadas, 'canceladas': canceladas, 'falhaEnsaiada': bool(falha),
            'pendentesDuranteFalha': pendentes, 'repeticaoConfirmada': True, 'recusaExecutada': recusa,
            'negativos': {'ordemAlheia': status_alheia, 'corpoInvalido': status_corpo},
            'saldoEmRepouso': _saldo_lido(repouso, custodia_repouso),
            'saldoAposLiberacao': _saldo_lido(final, custodia_compradora(final))}


class _Argumentos(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError('argumentos invalidos')


def main(argv=None):
    try:
        parser = _Argumentos(description=__doc__)
        parser.add_argument('--projeto', required=True)
        parser.add_argument('--origem', required=True, help='front-office loopback da CI')
        args = parser.parse_args(argv)
        ci.validar_projeto(args.projeto)
        esperado = 'prospero-ci-' + os.environ.get('GITHUB_RUN_ID', '') + '-' + os.environ.get('GITHUB_RUN_ATTEMPT', '')
        if args.projeto != esperado or not re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}', args.origem):
            raise ValueError('projeto ou origem divergem da execucao')
        ctx = {'origem': args.origem, 'origem_mo': esc._origem_mo(args.projeto),
               'parar_mo': lambda: subprocess.run(esc.comando_compose(args.projeto)
                                                  + ['stop', '--timeout', '8', 'middle-office'],
                                                  check=True, capture_output=True, timeout=60),
               'religar_mo': lambda: esc._religar_mo(args.projeto)}
        execucoes = [rodar_execucao(ctx, 4, 2, False), rodar_execucao(ctx, 4, 2, True)]
        print(json.dumps(relatorio_publico(execucoes), ensure_ascii=False))
        return 0
    except KeyboardInterrupt:
        print('Cenario de cancelamento interrompido; limpeza limitada solicitada.', file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, TypeError, StopIteration, subprocess.SubprocessError,
            urllib.error.URLError) as erro:
        # Só ValueError carrega mensagem própria (texto fixo deste script); nas demais, apenas o tipo.
        motivo = str(erro) if type(erro) is ValueError else type(erro).__name__
        print('Cenario de cancelamento recusado (' + motivo + '); verificar projeto, saldos, operacoes e gauge. '
              'Dados sensiveis omitidos.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    def interromper(_sinal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interromper)
    raise SystemExit(main())
