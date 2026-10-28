#!/usr/bin/env python3
"""Smoke funcional B1: seis leituras pelo controlador, no projeto exclusivo da CI."""
import argparse
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import signal
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

_spec = importlib.util.spec_from_file_location('prospero_carga_operacao', Path(__file__).with_name('carga.py'))
carga = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(carga)
CENARIOS = {
    'ATIVOS': 'front-office', 'ORDENS': 'front-office', 'CARTEIRA': 'middle-office',
    'POSICOES': 'back-office', 'NEGOCIOS': 'back-office', 'RAZAO': 'back-office',
}
PORTAS = {'api-carga': 8080, 'prometheus': 9090, 'grafana': 3000, 'otel-collector': 13133}
ARQUIVOS_COMPOSE = ('docker-compose.yml', 'docker-compose.ci.yml', 'docker-compose.robo.yml',
                    'docker-compose.carga.yml', 'docker-compose.carga-ci.yml')
# Cobre build do controlador, smoke de leituras e o smoke de escrita (B2) no mesmo job.
VALIDADE_TOKENS = 1200
TERMINAIS = {'CONCLUIDA', 'CANCELADA', 'FALHA'}
CONTAGENS = ('oferecidas', 'iniciadas', 'naoIniciadas', 'descartadas', 'interrompidas', 'finalizadas',
             'emVoo', 'respostasHTTP', 'errosHTTP', 'timeouts', 'falhasTransporte', 'canceladasEmVoo',
             'limitesResposta', 'bytesLidos')


def validar_projeto(projeto):
    if not isinstance(projeto, str) or not re.fullmatch(r'prospero-ci-[0-9]+-[0-9]+', projeto):
        raise ValueError('projeto CI invalido')
    return projeto


def comando_compose(projeto):
    comando = ['docker', 'compose', '-p', validar_projeto(projeto)]
    for arquivo in ARQUIVOS_COMPOSE:
        comando.extend(('-f', arquivo))
    return comando + ['--profile', 'carga']


def _comando(argumentos):
    return subprocess.run(argumentos, cwd=carga.ROOT, capture_output=True, text=True,
                          check=True, timeout=10).stdout.strip()


def descobrir_origem(projeto, servico):
    compose = comando_compose(projeto)
    if servico not in PORTAS:
        raise ValueError('servico CI invalido')
    cid = _comando(compose + ['ps', '-q', servico])
    if not re.fullmatch(r'[0-9a-f]{12,64}', cid):
        raise ValueError('container CI invalido')
    labels = json.loads(_comando(['docker', 'inspect', '--format', '{{json .Config.Labels}}', cid]))
    if (not isinstance(labels, dict) or labels.get('com.docker.compose.project') != projeto
            or labels.get('com.docker.compose.service') != servico):
        raise ValueError('container fora do projeto CI')
    endereco = _comando(compose + ['port', servico, str(PORTAS[servico])])
    achado = re.fullmatch(r'127\.0\.0\.1:([0-9]{1,5})', endereco)
    if not achado or not 1 <= int(achado[1]) <= 65535:
        raise ValueError('porta CI invalida')
    return 'http://' + endereco


def plano(cenario):
    if cenario not in CENARIOS:
        raise ValueError('cenario invalido')
    return {'cenario': cenario, 'servico': CENARIOS[cenario], 'modo': 'ABERTO',
            'estagios': [{'duracaoMs': 2000, 'rps': 1, 'vus': 0}], 'aquecimentoMs': 0,
            'concorrencia': 1, 'maxChamadas': 2, 'timeoutMs': 1000, 'maxRespostaBytes': 65536}


def validar_relatorio(relatorio, cenario, instancia):
    if (not isinstance(relatorio, dict) or relatorio.get('estado') != 'CONCLUIDA'
            or relatorio.get('instancia') != instancia
            or not re.fullmatch(r'[0-9a-f]{32}', str(relatorio.get('id', '')))
            or relatorio.get('plano') != plano(cenario) or type(relatorio.get('ofertasPrevistas')) is not int
            or relatorio['ofertasPrevistas'] != 2):
        raise ValueError('resultado da execucao invalido')
    contagens = relatorio.get('contagens')
    if not isinstance(contagens, dict) or set(contagens) != set(CONTAGENS):
        raise ValueError('contagens incompletas')
    for nome in CONTAGENS:
        valor = contagens[nome]
        esperado = 2 if nome in ('oferecidas', 'iniciadas', 'finalizadas', 'respostasHTTP') else 0
        if type(valor) is not int or (nome != 'bytesLidos' and valor != esperado):
            raise ValueError('contagens inconsistentes')
    if not 0 <= contagens['bytesLidos'] <= 2 * 65536 or relatorio.get('medicao') != contagens:
        raise ValueError('populacao de medicao inconsistente')
    quantis = relatorio.get('quantis')
    if not isinstance(quantis, dict) or type(quantis.get('amostras')) is not int or quantis['amostras'] != 2:
        raise ValueError('amostras inconsistentes')


class OrcamentoHttp:
    def __init__(self, limite=120, reserva_limpeza=12):
        if (type(limite) is not int or type(reserva_limpeza) is not int
                or not 0 < reserva_limpeza < limite):
            raise ValueError('orcamento invalido')
        self.limite = limite
        self.reserva_limpeza = reserva_limpeza
        self.usadas = 0

    def consumir(self, limpeza=False):
        teto = self.limite if limpeza else self.limite - self.reserva_limpeza
        if self.usadas >= teto:
            raise ValueError('orcamento HTTP esgotado')
        self.usadas += 1


class ClienteCi:
    def __init__(self, origem, orcamento, *, relogio=time.monotonic):
        partes = urllib.parse.urlsplit(origem)
        if (partes.scheme != 'http' or not re.fullmatch(r'127\.0\.0\.1:[0-9]{1,5}', partes.netloc)
                or not 1 <= partes.port <= 65535 or partes.path not in ('', '/') or partes.query or partes.fragment):
            raise ValueError('origem de controle invalida')
        self.origem = origem.rstrip('/')
        self.orcamento = orcamento
        self.relogio = relogio
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), carga.NoRedirect())

    def __call__(self, caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
        sistema = caminho == '/api/v1/sistema' and metodo == 'GET'
        inicio = caminho == '/api/v1/execucoes-carga' and metodo == 'POST'
        consulta = re.fullmatch(r'/api/v1/execucoes-carga/[0-9a-f]{32}', caminho) and metodo == 'GET'
        cancelar = re.fullmatch(r'/api/v1/execucoes-carga/[0-9a-f]{32}/cancelamentos', caminho) and metodo == 'POST'
        if (not (sistema or inicio or consulta or cancelar) or (metodo == 'GET' and corpo is not None)
                or (limpeza and not (consulta or cancelar)) or (cancelar and not limpeza)):
            raise ValueError('rota de controle invalida')
        timeout = 5 if prazo is None else min(5, prazo - self.relogio())
        if timeout <= 0:
            raise ValueError('prazo HTTP excedido')
        self.orcamento.consumir(limpeza=limpeza)
        try:
            dados = None if corpo is None else json.dumps(corpo, separators=(',', ':')).encode()
            req = urllib.request.Request(self.origem + caminho, data=dados, method=metodo,
                                         headers={'Authorization': 'Bearer ' + carga.control_token(),
                                                  'Host': 'api-carga:8080', 'Content-Type': 'application/json'})
            with _limitar_http(timeout), self.opener.open(req, timeout=timeout) as resposta:
                if not 200 <= resposta.status < 300:
                    raise ValueError('status recusado')
                conteudo = resposta.read(1024 * 1024 + 1)
                if len(conteudo) > 1024 * 1024:
                    raise ValueError('resposta excessiva')
                resultado = json.loads(conteudo)
                if not isinstance(resultado, dict) or (prazo is not None and self.relogio() >= prazo):
                    raise ValueError('resposta invalida')
                return resultado
        except urllib.error.HTTPError as erro:
            erro.close()
            raise ValueError('controle indisponivel') from None
        except (OSError, ValueError, UnicodeError):
            raise ValueError('controle indisponivel') from None


@contextmanager
def _limitar_http(segundos):
    # Operador exclusivo Linux/main thread: o prazo abrange cabeçalhos e corpo,
    # inclusive resposta que envie bytes antes de cada timeout de socket.
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError('temporizador HTTP ocupado')
    anterior = signal.getsignal(signal.SIGALRM)
    def expirar(_sinal, _frame):
        raise TimeoutError('prazo HTTP excedido')
    signal.signal(signal.SIGALRM, expirar)
    try:
        signal.setitimer(signal.ITIMER_REAL, segundos)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, anterior)


def _drenar(requisitar, identidade, pausa, relogio):
    caminho = '/api/v1/execucoes-carga/' + identidade
    prazo = relogio() + 3
    resultado = requisitar(caminho + '/cancelamentos', 'POST', limpeza=True, prazo=prazo)
    for tentativa in range(5):
        if (resultado.get('estado') in TERMINAIS and isinstance(resultado.get('contagens'), dict)
                and type(resultado['contagens'].get('emVoo')) is int and resultado['contagens']['emVoo'] == 0):
            return
        if tentativa == 4 or relogio() >= prazo:
            break
        pausa(min(.25, max(0, prazo - relogio())))
        if relogio() >= prazo:
            break
        resultado = requisitar(caminho, limpeza=True, prazo=prazo)
    raise ValueError('execucao nao drenou')


def rodar_cenarios(requisitar, autorizar, *, pausa=time.sleep, relogio=time.monotonic):
    resultados = []
    for cenario in CENARIOS:
        identidade = None
        try:
            sistema = requisitar('/api/v1/sistema')
            instancia = sistema.get('instancia')
            if not isinstance(instancia, str) or not re.fullmatch(r'[0-9a-f]{32}', instancia):
                raise ValueError('instancia invalida')
            configuracao = plano(cenario)
            autorizacao = autorizar(configuracao, instancia)
            aceita = requisitar('/api/v1/execucoes-carga', 'POST',
                                 {'plano': configuracao, 'autorizacao': autorizacao})
            candidata = aceita.get('id')
            if not isinstance(candidata, str) or not re.fullmatch(r'[0-9a-f]{32}', candidata):
                raise ValueError('identidade da execucao invalida')
            identidade = candidata
            prazo = relogio() + 8
            for _ in range(12):
                if relogio() >= prazo:
                    break
                resultado = requisitar('/api/v1/execucoes-carga/' + identidade, prazo=prazo)
                if resultado.get('estado') in TERMINAIS:
                    validar_relatorio(resultado, cenario, instancia)
                    if resultado['id'] != identidade:
                        raise ValueError('identidade do resultado divergente')
                    resultados.append({'cenario': cenario, 'estado': resultado['estado'],
                                       'contagens': {nome: resultado['contagens'][nome] for nome in CONTAGENS},
                                       'amostras': 2})
                    break
                pausa(min(.5, max(0, prazo - relogio())))
            else:
                raise ValueError('prazo de acompanhamento excedido')
            if len(resultados) != list(CENARIOS).index(cenario) + 1:
                raise ValueError('prazo de acompanhamento excedido')
        finally:
            if identidade is not None:
                _drenar(requisitar, identidade, pausa, relogio)
    return resultados


def _diretorio_privado(pasta):
    pasta = Path(pasta)
    if (pasta.is_symlink() or not pasta.is_dir() or pasta.stat().st_uid != os.getuid()
            or stat.S_IMODE(pasta.stat().st_mode) != 0o700):
        raise ValueError('diretorio privado invalido')
    return pasta


def preparar_privados(pasta):
    pasta = _diretorio_privado(pasta)
    if any(pasta.iterdir()):
        raise ValueError('diretorio privado deve estar vazio')
    carga.PRIVATE = pasta
    for nome in ('controle.key', 'preflight.key'):
        carga.private_write(pasta / nome, secrets.token_hex(32))
        (pasta / nome).chmod(0o444)
    carga.financial_credentials('CONTA-002', VALIDADE_TOKENS)


class _Argumentos(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError('argumentos invalidos')


def main(argv=None):
    try:
        parser = _Argumentos(description=__doc__)
        parser.add_argument('--projeto', required=True)
        parser.add_argument('--privados', type=Path, required=True)
        parser.add_argument('--preparar', action='store_true')
        args = parser.parse_args(argv)
        validar_projeto(args.projeto)
        esperado = 'prospero-ci-' + os.environ.get('GITHUB_RUN_ID', '') + '-' + os.environ.get('GITHUB_RUN_ATTEMPT', '')
        if args.projeto != esperado:
            raise ValueError('projeto diverge da execucao')
        if args.preparar:
            preparar_privados(args.privados)
            return 0
        carga.PRIVATE = _diretorio_privado(args.privados)
        for nome in ('controle.key', 'preflight.key', *carga.FINANCIAL_FILES):
            arquivo = carga.PRIVATE / nome
            if (arquivo.is_symlink() or not arquivo.is_file() or arquivo.stat().st_uid != os.getuid()
                    or stat.S_IMODE(arquivo.stat().st_mode) != 0o444 or not 0 < arquivo.stat().st_size <= 4096):
                raise ValueError('arquivo privado invalido')
        orcamento = OrcamentoHttp()
        cliente = ClienteCi(descobrir_origem(args.projeto, 'api-carga'), orcamento)
        telemetria = tuple(descobrir_origem(args.projeto, servico) + caminho for servico, caminho in
                          (('prometheus', '/-/ready'), ('grafana', '/api/health'), ('otel-collector', '/')))
        autorizar = lambda plano, instancia: carga.authorization(
            plano, instancia, telemetria=telemetria, antes_http=orcamento.consumir)
        resultados = rodar_cenarios(cliente, autorizar)
        print(json.dumps({'tipo': 'smoke-integrado-leituras', 'getLogicosCatalogo': 12,
                          'chamadasAdministrativas': orcamento.usadas, 'resultados': resultados}, ensure_ascii=False))
        return 0
    except KeyboardInterrupt:
        print('Smoke interrompido; limpeza limitada solicitada.', file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('Smoke de leituras recusado; verificar projeto, credenciais, prontidao, orcamento e contagens. Dados sensiveis omitidos.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    def interromper(_sinal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interromper)
    raise SystemExit(main())
