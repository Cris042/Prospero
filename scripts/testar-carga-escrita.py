#!/usr/bin/env python3
"""Smoke funcional B2: escrita controlada pela API de carga, falha ensaiada e reconciliação, no projeto da CI.

Cada execução prepara CONTA-002 (compradora) e CONTA-003 (vendedora, DIA, quantidade igual ao total de agressores),
dispara ORDEM_AGRESSOR pelo controlador, reconcilia todas as ordens novas até o estado terminal e confere o resultado
financeiro. A segunda execução para o middle-office durante a escrita e o religa em `finally`. O relatório público
traz só cenário, estado, contagens e reconciliação (ids contados, nunca listados)."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import uuid

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))


def _carregar(nome, arquivo):
    spec = importlib.util.spec_from_file_location(nome, AQUI / arquivo)
    modulo = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modulo)
    return modulo


ci = _carregar('prospero_carga_integrada', 'testar-carga-integrada.py')
carga = ci.carga
import mercado  # noqa: E402  (precisa do sys.path acima)

validar_projeto = ci.validar_projeto
comando_compose = ci.comando_compose
CONTAGENS = ci.CONTAGENS
TERMINAIS_ORDEM = ('EXECUTADA', 'CANCELADA', 'REJEITADA')  # CANCELADA: gancho da RF10, sem chamar o cancelamento
PENDENTES_ORDEM = ('RECEBIDA', 'RESERVADA', 'AGUARDANDO_GATILHO', 'ABERTA', 'PARCIALMENTE_EXECUTADA')
TICKER, PRECO = 'ALFA3', '10.00'
FONTES_AUSENTES = {'wal': 'indisponivel', 'locks': 'indisponivel', 'lag': 'indisponivel',
                   'disco': 'indisponivel', 'rede': 'indisponivel'}


def plano(n, rps):
    if type(n) is not int or type(rps) is not int or not 1 <= n <= 20 or not 1 <= rps <= 5:
        raise ValueError('plano de escrita invalido')
    return {'cenario': 'ORDEM_AGRESSOR', 'servico': 'front-office', 'modo': 'ABERTO',
            'estagios': [{'duracaoMs': n * 1000 // rps, 'rps': rps, 'vus': 0}], 'aquecimentoMs': 0,
            'concorrencia': 1, 'maxChamadas': n, 'timeoutMs': 2000, 'maxRespostaBytes': 65536}


def validar_relatorio(relatorio, instancia, n, rps):
    """Todas as ofertas iniciadas, finalizadas e aceitas; qualquer descarte, erro ou limite recusa."""
    if (not isinstance(relatorio, dict) or relatorio.get('estado') != 'CONCLUIDA'
            or relatorio.get('instancia') != instancia
            or not re.fullmatch(r'[0-9a-f]{32}', str(relatorio.get('id', '')))
            or relatorio.get('plano') != plano(n, rps) or type(relatorio.get('ofertasPrevistas')) is not int
            or relatorio['ofertasPrevistas'] != n):
        raise ValueError('resultado da execucao invalido')
    contagens = relatorio.get('contagens')
    if not isinstance(contagens, dict) or set(contagens) != set(CONTAGENS):
        raise ValueError('contagens incompletas')
    for nome in CONTAGENS:
        valor = contagens[nome]
        esperado = n if nome in ('oferecidas', 'iniciadas', 'finalizadas', 'respostasHTTP') else 0
        if type(valor) is not int or (nome != 'bytesLidos' and valor != esperado):
            raise ValueError('contagens inconsistentes')
    if not 0 <= contagens['bytesLidos'] <= n * 65536 or relatorio.get('medicao') != contagens:
        raise ValueError('populacao de medicao inconsistente')


def conferir_ids(relatorio, ids):
    """Ordens novas no FO = aceitas pelo gerador (respostas 2xx); incertas (timeout/transporte) só ampliam o teto."""
    c = relatorio['contagens']
    aceitas = c['respostasHTTP'] - c['errosHTTP']
    incertas = c['timeouts'] + c['falhasTransporte'] + c['canceladasEmVoo']
    if not aceitas <= len(ids) <= aceitas + incertas:
        raise ValueError('ordens do FO divergem das aceitas pelo gerador')


def classificar(estados):
    terminais, pendentes, ausentes = {}, 0, 0
    for estado in estados.values():
        if estado is None:
            ausentes += 1
        elif estado in TERMINAIS_ORDEM:
            terminais[estado] = terminais.get(estado, 0) + 1
        elif estado in PENDENTES_ORDEM:
            pendentes += 1
        else:
            raise ValueError('estado de ordem desconhecido')
    return {'terminais': terminais, 'pendentes': pendentes, 'ausentes': ausentes}


def reconciliar(ids, consultar, *, prazo, pausa=time.sleep, relogio=time.monotonic):
    """Cada id termina em estado terminal dentro do prazo; sem pendente, ausente ou id fora do conjunto."""
    ids = list(ids)
    if not ids or len(set(ids)) != len(ids):
        raise ValueError('conjunto de ordens invalido')
    limite = relogio() + prazo
    estados = {i: None for i in ids}
    abertos = set(ids)
    while True:
        for i in sorted(abertos):
            estados[i] = consultar(i)
        abertos = {i for i in ids if estados[i] is None or estados[i] in PENDENTES_ORDEM}
        if not abertos or relogio() >= limite:
            break
        pausa(min(.5, max(0, limite - relogio())))
    resumo = classificar(estados)
    if resumo['pendentes'] or resumo['ausentes']:
        raise ValueError('reconciliacao incompleta')
    return {'ids': len(ids), **resumo}


def com_falha(parar, religar, acao):
    """Para o serviço, executa a ação e o religa sempre; falha ao religar não mascara o erro da ação."""
    erro = None
    try:
        parar()
        return acao()
    except BaseException as e:
        erro = e
        raise
    finally:
        try:
            religar()
        except Exception:
            if erro is None:
                raise


def resumo_execucao(relatorio, falha, reconciliacao, financeiro, pendentes_durante_falha=None):
    resumo = {'cenario': 'ORDEM_AGRESSOR', 'estado': relatorio['estado'], 'falhaEnsaiada': bool(falha),
            'contagens': {nome: relatorio['contagens'][nome] for nome in CONTAGENS},
            'reconciliacao': reconciliacao, 'resultadoFinanceiro': financeiro}
    if falha:
        resumo['pendentesDuranteFalha'] = pendentes_durante_falha
    return resumo


def relatorio_publico(execucoes, administrativas):
    return {'tipo': 'smoke-integrado-escrita', 'chamadasAdministrativas': administrativas,
            'execucoes': execucoes, 'recursosNaoColetados': dict(FONTES_AUSENTES)}


# --- Operação contra o ambiente real (exercitada pela CI; sem teste local) ---

def ids_ordens(origem, tok):
    ids, cursor = set(), None
    for _ in range(10):
        parametros = {'limite': 100}
        if cursor is not None:
            parametros['cursor'] = cursor
        pagina = mercado.http('GET', origem + '/api/v1/ordens?' + urllib.parse.urlencode(parametros), tok)
        for ordem in pagina['ordens']:
            ids.add(str(uuid.UUID(ordem['id'])))
        cursor = pagina['proximoCursor']
        if cursor is None:
            return ids
    raise ValueError('listagem de ordens excede o limite')


def estado_ordem(origem, tok, identificador):
    try:
        return mercado.http('GET', origem + '/api/v1/ordens/' + identificador, tok)['estado']
    except urllib.error.HTTPError as erro:
        erro.close()
        if erro.code == 404:
            return None
        raise


def preparar_cenario(origem, origem_mo, n):
    for conta in ('CONTA-002', 'CONTA-003'):
        mercado.sem_pendencias(origem, conta)
    mercado.preparar_carteira(origem_mo, 'CONTA-002', '1000000.00', {}, prazo=60)
    mercado.preparar_carteira(origem_mo, 'CONTA-003', '0.00', {TICKER: n}, prazo=60)
    vendedor = mercado.token('ordens', 900, 'CONTA-003')
    oferta = mercado.http('POST', origem + '/api/v1/ordens', vendedor,
                          {'ticker': TICKER, 'lado': 'VENDA', 'tipo': 'LIMIT', 'validade': 'DIA',
                           'quantidade': n, 'precoLimite': PRECO},
                          {'X-Idempotency-Key': 'carga-b2-' + uuid.uuid4().hex})
    mercado.aguardar_campos(origem, vendedor, '/api/v1/ordens/' + oferta['id'], {'estado': 'ABERTA'})
    return vendedor, oferta['id']


def conferir_financeiro(origem, origem_mo, vendedor, oferta, n):
    mercado.aguardar_campos(origem, vendedor, '/api/v1/ordens/' + oferta, {'estado': 'EXECUTADA'}, 60)
    total = '%d.00' % (1000000 - 10 * n)
    mercado.aguardar_campos(origem_mo, mercado.token('risco', 300, 'CONTA-002'), '/api/v1/risco/carteira',
                            {'saldoTotal': total, 'saldoReservado': '0.00'}, 60)
    vendedora = mercado.aguardar_campos(origem_mo, mercado.token('risco', 300, 'CONTA-003'),
                                        '/api/v1/risco/carteira',
                                        {'saldoTotal': '%d.00' % (10 * n), 'saldoReservado': '0.00'}, 60)
    custodia = next((c for c in vendedora['custodias'] if c['ticker'] == TICKER), None)
    if custodia is not None and (custodia['total'], custodia['reservado']) != (0, 0):
        raise ValueError('custodia da vendedora divergente')
    return 'conferido'


def rodar_execucao(ctx, n, rps, falha):
    origem, token_ordens = ctx['origem'], mercado.token('ordens', 900, 'CONTA-002')
    antes = ids_ordens(origem, token_ordens)
    vendedor, oferta = preparar_cenario(origem, ctx['origem_mo'](), n)
    cliente, autorizar, pausa, relogio = ctx['cliente'], ctx['autorizar'], ctx['pausa'], ctx['relogio']
    configuracao = plano(n, rps)
    instancia = cliente('/api/v1/sistema')['instancia']
    aceita = cliente('/api/v1/execucoes-carga', 'POST',
                     {'plano': configuracao, 'autorizacao': autorizar(configuracao, instancia)})
    identidade = aceita.get('id')
    if not isinstance(identidade, str) or not re.fullmatch(r'[0-9a-f]{32}', identidade):
        raise ValueError('identidade da execucao invalida')
    caminho = '/api/v1/execucoes-carga/' + identidade

    def acompanhar():
        prazo = relogio() + n // rps + 25
        while relogio() < prazo:
            resultado = cliente(caminho, prazo=prazo)
            if resultado.get('estado') in ci.TERMINAIS:
                return resultado
            pausa(.5)
        raise ValueError('prazo de acompanhamento excedido')

    observado = {}

    def acompanhar_com_mo_fora():
        resultado = acompanhar()
        novos = ids_ordens(origem, token_ordens) - antes
        observado['pendentes'] = sum(1 for i in novos if estado_ordem(origem, token_ordens, i) == 'RECEBIDA')
        if observado['pendentes'] < 1:
            raise ValueError('falha ensaiada nao deixou ordem pendente')
        return resultado

    def com_mo_parado():
        # Primeira escrita aceita antes da parada: o resto da execução corre com o MO fora.
        prazo = relogio() + 20
        while cliente(caminho, prazo=prazo)['contagens']['finalizadas'] < 1:
            if relogio() >= prazo:
                raise ValueError('primeira escrita nao finalizou')
            pausa(.3)
        return com_falha(ctx['parar_mo'], ctx['religar_mo'], acompanhar_com_mo_fora)

    try:
        resultado = com_mo_parado() if falha else acompanhar()
    finally:
        ci._drenar(cliente, identidade, pausa, relogio)
    validar_relatorio(resultado, instancia, n, rps)
    novos = ids_ordens(origem, token_ordens) - antes
    conferir_ids(resultado, novos)
    reconciliacao = reconciliar(sorted(novos), lambda i: estado_ordem(origem, token_ordens, i),
                                prazo=120 if falha else 45)
    if reconciliacao['terminais'] != {'EXECUTADA': n}:
        raise ValueError('ordens agressoras nao executadas como previsto')
    financeiro = conferir_financeiro(origem, ctx['origem_mo'](), vendedor, oferta, n)
    return resumo_execucao(resultado, falha, reconciliacao, financeiro, observado.get('pendentes'))


def _religar_mo(projeto):
    base = comando_compose(projeto)
    subprocess.run(base + ['start', 'middle-office'], check=True, capture_output=True, timeout=60)
    limite = time.monotonic() + 180
    while time.monotonic() < limite:
        try:
            origem = 'http://' + subprocess.run(base + ['port', 'middle-office', '8080'], check=True,
                                                capture_output=True, text=True, timeout=10).stdout.strip()
            if mercado.http('GET', origem + '/q/health/ready', 'x').get('status') == 'UP':
                return
        except (OSError, ValueError, subprocess.SubprocessError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise ValueError('middle-office nao voltou a ficar pronto')


def _origem_mo(projeto):
    saida = subprocess.run(comando_compose(projeto) + ['port', 'middle-office', '8080'], check=True,
                           capture_output=True, text=True, timeout=10).stdout.strip()
    if not re.fullmatch(r'127\.0\.0\.1:[0-9]{1,5}', saida):
        raise ValueError('porta do middle-office invalida')
    return 'http://' + saida


class _Argumentos(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError('argumentos invalidos')


def main(argv=None):
    try:
        parser = _Argumentos(description=__doc__)
        parser.add_argument('--projeto', required=True)
        parser.add_argument('--privados', type=Path, required=True)
        parser.add_argument('--origem', required=True, help='front-office loopback da CI')
        args = parser.parse_args(argv)
        validar_projeto(args.projeto)
        esperado = 'prospero-ci-' + os.environ.get('GITHUB_RUN_ID', '') + '-' + os.environ.get('GITHUB_RUN_ATTEMPT', '')
        if args.projeto != esperado or not re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}', args.origem):
            raise ValueError('projeto ou origem divergem da execucao')
        carga.PRIVATE = ci._diretorio_privado(args.privados)
        for nome in ('controle.key', 'preflight.key', *carga.FINANCIAL_FILES):
            arquivo = carga.PRIVATE / nome
            if (arquivo.is_symlink() or not arquivo.is_file() or arquivo.stat().st_uid != os.getuid()
                    or stat.S_IMODE(arquivo.stat().st_mode) != 0o444 or not 0 < arquivo.stat().st_size <= 4096):
                raise ValueError('arquivo privado invalido')
        orcamento = ci.OrcamentoHttp()
        cliente = ci.ClienteCi(ci.descobrir_origem(args.projeto, 'api-carga'), orcamento)
        telemetria = tuple(ci.descobrir_origem(args.projeto, servico) + caminho for servico, caminho in
                           (('prometheus', '/-/ready'), ('grafana', '/api/health'), ('otel-collector', '/')))
        ctx = {'origem': args.origem, 'origem_mo': lambda: _origem_mo(args.projeto), 'cliente': cliente,
               'autorizar': lambda p, i: carga.authorization(p, i, telemetria=telemetria, antes_http=orcamento.consumir),
               'pausa': time.sleep, 'relogio': time.monotonic,
               'parar_mo': lambda: subprocess.run(comando_compose(args.projeto) + ['stop', '--timeout', '8', 'middle-office'],
                                                  check=True, capture_output=True, timeout=60),
               'religar_mo': lambda: _religar_mo(args.projeto)}
        execucoes = []
        for n, rps, falha in ((4, 2, False), (6, 2, True)):
            execucoes.append(rodar_execucao(ctx, n, rps, falha))
            time.sleep(1.2)  # intervalo mínimo entre execuções do controlador
        print(json.dumps(relatorio_publico(execucoes, orcamento.usadas), ensure_ascii=False))
        return 0
    except KeyboardInterrupt:
        print('Smoke interrompido; limpeza limitada solicitada.', file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, TypeError, StopIteration, subprocess.SubprocessError, urllib.error.URLError):
        print('Smoke de escrita recusado; verificar projeto, credenciais, reconciliacao e contagens. Dados sensiveis omitidos.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    def interromper(_sinal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interromper)
    raise SystemExit(main())
