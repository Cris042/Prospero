#!/usr/bin/env python3
"""RF09: prova integrada exclusiva de CI, por APIs e falhas recuperáveis sem apagar dados."""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from liquidacao import Cliente
from mercado import compose, preparar_carteira, token

SERVICOS = {'front-office', 'middle-office', 'matching-engine', 'back-office'}


def exigir_projeto_ci(projeto):
    if not isinstance(projeto, str) or not re.fullmatch(r'prospero-ci-[0-9]+-[0-9]+', projeto):
        raise ValueError('Somente projeto CI exclusivo é permitido.')
    return projeto


def servicos_processados(conteudo, trace_id):
    """Jaeger API v3: chunks OTLP em JSON por linha; presença de health não prova processamento.

    Contrato: https://github.com/jaegertracing/jaeger-idl/blob/main/proto/api_v3/query_service.proto
    """
    if not re.fullmatch(r'[0-9a-f]{32}', trace_id) or len(conteudo) > 4 * 1024 * 1024:
        raise ValueError('Trace ou resposta inválidos.')
    encontrados = set()
    for linha in conteudo.splitlines():
        if not linha.strip():
            continue
        documento = json.loads(linha)
        for recurso in documento.get('result', {}).get('resourceSpans', []):
            atributos = recurso.get('resource', {}).get('attributes', [])
            servico = next((a.get('value', {}).get('stringValue') for a in atributos if a.get('key') == 'service.name'), None)
            for escopo in recurso.get('scopeSpans', []):
                for span in escopo.get('spans', []):
                    identidade = span.get('traceId', '')
                    if identidade != trace_id:
                        try:
                            identidade = base64.b64decode(identidade, validate=True).hex()
                        except (ValueError, TypeError):
                            continue
                    nome, kind = span.get('name'), span.get('kind')
                    esperado = {'front-office': ('POST /api/v1/ordens', {2, 'SPAN_KIND_SERVER'}),
                                'middle-office': ('risco.avaliar', {5, 'SPAN_KIND_CONSUMER'}),
                                'matching-engine': ('matching.consumir', {5, 'SPAN_KIND_CONSUMER'}),
                                'back-office': ('posicoes.consumir', {5, 'SPAN_KIND_CONSUMER'})}.get(servico)
                    if identidade == trace_id and esperado and nome == esperado[0] and kind in esperado[1]:
                        encontrados.add(servico)
    return encontrados


class SemRedirecionamento(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def requisitar(origem, caminho, credencial=None, corpo=None, extras=None, bruto=False):
    Cliente(origem)  # Validação HTTP loopback sem userinfo, caminho, query ou fragmento.
    headers = dict(extras or {})
    if credencial:
        headers['Authorization'] = 'Bearer ' + credencial
    dados = None if corpo is None else json.dumps(corpo).encode()
    if dados is not None:
        headers['Content-Type'] = 'application/json'
    pedido = urllib.request.Request(origem.rstrip('/') + caminho, data=dados, headers=headers)
    try:
        with urllib.request.build_opener(SemRedirecionamento).open(pedido, timeout=10) as resposta:
            conteudo = resposta.read(4 * 1024 * 1024 + 1)
            if len(conteudo) > 4 * 1024 * 1024:
                raise ValueError('Resposta excede o orçamento do teste.')
            return conteudo if bruto else json.loads(conteudo)
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RuntimeError('API local indisponível ou operação recusada.') from None


def aguardar(condicao, prazo=60):
    limite = time.monotonic() + prazo
    while True:
        try:
            resultado = condicao()
            if resultado:
                return resultado
        except RuntimeError:
            pass
        if time.monotonic() >= limite:
            raise RuntimeError('Condição integrada não foi comprovada no prazo.')
        time.sleep(0.2)


class AmbienteCi:
    def __init__(self, projeto):
        self.projeto = exigir_projeto_ci(projeto)
        self.base = compose(projeto)
        for servico in ('front-office', 'back-office', 'otel-collector'):
            if not self.conferir(servico)['State'].get('Running'):
                raise ValueError('Os serviços da prova precisam estar ativos inicialmente.')
        estado = self.conferir('back-office')
        if 'PROSPERO_LIQUIDACAO_WORKER_HABILITADO=false' not in estado['Config'].get('Env', []):
            raise ValueError('O harness exige worker BO inicialmente suspenso.')

    def conferir(self, servico):
        identificador = subprocess.check_output(self.base + ['ps', '-q', '--all', servico], text=True).strip()
        if not re.fullmatch(r'[a-f0-9]{12,64}', identificador):
            raise ValueError('Container único da execução é obrigatório.')
        estado = json.loads(subprocess.check_output(['docker', 'inspect', identificador], text=True))[0]
        labels = estado['Config'].get('Labels', {})
        if (labels.get('com.docker.compose.project') != self.projeto
                or labels.get('com.docker.compose.service') != servico):
            raise ValueError('Container alheio à execução CI.')
        return estado

    def origem(self, servico, porta=8080):
        endereco = subprocess.check_output(self.base + ['port', servico, str(porta)], text=True).strip()
        if not re.fullmatch(r'127\.0\.0\.1:[1-9][0-9]{0,4}', endereco):
            raise ValueError('Porta loopback única da CI é obrigatória.')
        return 'http://' + endereco

    def executar(self, servico, operacao, worker=None):
        self.conferir(servico)
        env = dict(os.environ)
        if worker is not None:
            env['PROSPERO_CI_WORKER_LIQUIDACAO'] = str(worker).lower()
        argumentos = (['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', servico]
                      if operacao == 'recriar' else [operacao, servico])
        subprocess.run(self.base + argumentos, env=env, check=True, stdout=subprocess.DEVNULL)
        self.conferir(servico)


def verificar(args):
    ambiente = AmbienteCi(args.projeto)
    origens = {'front-office': args.origem, 'middle-office': args.origem_mo,
               'back-office': args.origem_bo, 'jaeger': args.origem_jaeger}
    for servico, origem in origens.items():
        Cliente(origem)
        if origem.rstrip('/') != ambiente.origem(servico, 16686 if servico == 'jaeger' else 8080):
            raise ValueError('Origem deve corresponder à porta do serviço desta execução CI.')
    fo = lambda: origens['front-office']

    def ordem(conta, lado, quantidade, validade, trace=None):
        extra = {'X-Idempotency-Key': 'traj-' + str(uuid.uuid4())}
        if trace:
            extra['traceparent'] = '00-' + trace + '-0123456789abcdef-01'
        resultado = requisitar(fo(), '/api/v1/ordens', token('ordens', 300, conta),
                               {'ticker': 'DELTA3', 'lado': lado, 'tipo': 'LIMIT', 'validade': validade,
                                'quantidade': quantidade, 'precoLimite': '40.00'}, extra)
        identificador = str(uuid.UUID(resultado['id']))
        return conta, identificador

    def consultar(alvo):
        conta, identificador = alvo
        return requisitar(fo(), '/api/v1/ordens/' + identificador, token('ordens', 300, conta))

    def trajetoria(alvo):
        conta, identificador = alvo
        pagina = requisitar(fo(), '/api/v1/ordens/' + identificador + '/trajetoria?limite=100', token('ordens', 300, conta))
        if pagina.get('proximoCursor') is not None:
            raise ValueError('Trajetória do cenário excedeu cem marcos.')
        return pagina

    def ida_volta(trace):
        venda = ordem('CONTA-003', 'VENDA', 1, 'DIA')
        aguardar(lambda: consultar(venda)['estado'] == 'ABERTA')
        compra = ordem('CONTA-002', 'COMPRA', 2, 'IOC', trace)
        parcial = aguardar(lambda: (r if r.get('quantidadeExecutada') == 1 and r.get('quantidadeCancelada') == 1 else None)
                          if (r := consultar(compra)) else None)
        if parcial['estado'] != 'CANCELADA':
            raise ValueError('IOC parcial não confirmou cancelamento do restante.')
        volta = ordem('CONTA-003', 'COMPRA', 1, 'DIA')
        aguardar(lambda: consultar(volta)['estado'] == 'ABERTA')
        fechamento = ordem('CONTA-002', 'VENDA', 1, 'IOC')
        aguardar(lambda: consultar(fechamento)['estado'] == 'EXECUTADA')
        return [venda, compra, volta, fechamento]

    def com_bo(alvos):
        paginas = [trajetoria(a) for a in alvos]
        if not all(any(e['tipo'] == 'NEGOCIO_CONTABILIZADO' for e in p['eventos']) for p in paginas):
            return None
        for pagina in paginas:
            if {e['origem'] for e in pagina['eventos']} != SERVICOS:
                return None
            marcos = [e for e in pagina['eventos'] if e['tipo'] == 'NEGOCIO_CONTABILIZADO']
            if len(marcos) != 1 or len({(e['origem'], e['evento']) for e in pagina['eventos']}) != len(pagina['eventos']):
                raise ValueError('Confirmação ou marco duplicado.')
        return paginas

    def posicoes_zeradas():
        for conta in ('CONTA-002', 'CONTA-003'):
            pagina = requisitar(origens['back-office'], '/api/v1/posicoes', token('posicoes', 300, conta))
            if any(p['ticker'] == 'DELTA3' and p['quantidade'] != '0' for p in pagina['posicoes']):
                return False
        return True

    preparar_carteira(args.origem_mo, 'CONTA-002', '10000.00', {'DELTA3': 2}, prazo=30)
    preparar_carteira(args.origem_mo, 'CONTA-003', '10000.00', {'DELTA3': 2}, prazo=30)
    aguardar(posicoes_zeradas)
    trace = uuid.uuid4().hex
    bo_alterado = coletor_parado = False
    try:
        bo_alterado = True
        ambiente.executar('back-office', 'stop')
        alvos = ida_volta(trace)
        if any(any(e['tipo'] == 'NEGOCIO_CONTABILIZADO' for e in trajetoria(a)['eventos']) for a in alvos):
            raise ValueError('Marco BO surgiu enquanto seu serviço estava parado.')
        ambiente.executar('back-office', 'recriar', worker=True)
        origens['back-office'] = ambiente.origem('back-office')
        paginas = aguardar(lambda: com_bo(alvos), 120)
        aguardar(posicoes_zeradas)
        aguardar(lambda: servicos_processados(requisitar(args.origem_jaeger, '/api/v3/traces/' + trace, bruto=True), trace) == SERVICOS, 120)
        antes = [(int(p['revisao']), p['eventos']) for p in paginas]
        ambiente.executar('front-office', 'restart')
        origens['front-office'] = ambiente.origem('front-office')
        aguardar(lambda: requisitar(fo(), '/q/health/ready').get('status') == 'UP')
        depois = [trajetoria(a) for a in alvos]
        if any(eventos != pagina['eventos'] or int(pagina['revisao']) < revisao
               for (revisao, eventos), pagina in zip(antes, depois)):
            raise ValueError('Reinício FO perdeu ou duplicou fatos/revisão.')
        coletor_parado = True
        ambiente.executar('otel-collector', 'stop')
        sem_telemetria = ida_volta(uuid.uuid4().hex)
        aguardar(lambda: com_bo(sem_telemetria), 120)
        aguardar(posicoes_zeradas)
        return {'sucesso': True, 'boIndisponivelSemMarco': True, 'parcialReal': True,
                'marcosUnicos': 8, 'reinicioFoPreservado': True, 'coletorIndisponivelSemEfeitoFinanceiro': True,
                'posicoesFinaisZero': True, 'trace': trace, 'servicosTrace': sorted(SERVICOS)}
    finally:
        try:
            if coletor_parado:
                ambiente.executar('otel-collector', 'start')
        finally:
            if bo_alterado:
                ambiente.executar('back-office', 'recriar', worker=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for nome in ('projeto', 'origem', 'origem-mo', 'origem-bo', 'origem-jaeger'):
        parser.add_argument('--' + nome, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verificar(args), ensure_ascii=False))
    except (ValueError, RuntimeError, subprocess.SubprocessError):
        print(json.dumps({'sucesso': False, 'erro': 'Prova integrada de trajetória não concluída.'}), file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
