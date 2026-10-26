#!/usr/bin/env python3
"""Confere fontes, consultas, alertas e provisionamento no projeto CI, sem alterar dados financeiros."""
import argparse
import base64
import importlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LIMITE_RESPOSTA = 2 * 1024 * 1024
PORTAS = {'front-office': 8080, 'middle-office': 8080, 'matching-engine': 8080,
          'back-office': 8080, 'prometheus': 9090, 'grafana': 3000}
PAINEIS = {'ordens': 'prospero-ordens', 'risco': 'prospero-risco',
           'matching': 'prospero-matching', 'back-office': 'prospero-back-office'}
ALERTAS = {'ProsperoSemScrape', 'ProsperoProcessamentoIndisponivel',
           'ProsperoMetricaProntidaoAusente', 'ProsperoQuarentenaRecente',
           'ProsperoOutboxPendente', 'ProsperoCreditoAguardando',
           'ProsperoBoletaNegocioP99Alto', 'ProsperoMatchingP99Alto'}


class SemRedirecionamento(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def origem_local(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or
            not parsed.port or parsed.username is not None or parsed.password is not None or parsed.fragment):
        raise ValueError('origem local invalida')
    return parsed


def ler_json(url, cabecalhos=None):
    origem_local(url)
    if len(url) > 16384:
        raise ValueError('URL excessiva')
    req = urllib.request.Request(url, headers={'Accept': 'application/json', **(cabecalhos or {})})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), SemRedirecionamento())
    try:
        with opener.open(req, timeout=5) as resposta:
            corpo = resposta.read(LIMITE_RESPOSTA + 1)
    except urllib.error.HTTPError as erro:
        erro.close()
        raise
    if len(corpo) > LIMITE_RESPOSTA:
        raise ValueError('resposta excessiva')
    return json.loads(corpo)


def origem_compose(projeto, servico, porta):
    if (not re.fullmatch(r'prospero-ci-[0-9]+-[0-9]+', projeto or '') or
            servico not in PORTAS or PORTAS[servico] != porta):
        raise ValueError('projeto ou servico invalido')
    endereco = subprocess.check_output([
        'docker', 'compose', '-p', projeto, '-f', 'docker-compose.yml',
        '-f', 'docker-compose.ci.yml', 'port', servico, str(porta),
    ], cwd=ROOT, text=True, timeout=15).strip()
    if not re.fullmatch(r'127\.0\.0\.1:[0-9]{1,5}', endereco):
        raise ValueError('porta publicada invalida')
    origem = 'http://' + endereco
    origem_local(origem)
    return origem


def consultar(prometheus, expr):
    resposta = ler_json(prometheus + '/api/v1/query?' + urllib.parse.urlencode({'query': expr}))
    if resposta.get('status') != 'success' or resposta.get('data', {}).get('resultType') != 'vector':
        raise ValueError('consulta Prometheus recusada')
    return resposta['data']['result']


def conferir(prometheus, grafana, headers):
    grupos = ler_json(prometheus + '/api/v1/rules?type=alert').get('data', {}).get('groups', [])
    regras = [r for g in grupos if g.get('name') == 'prospero-operacao' for r in g.get('rules', [])]
    if {r.get('name') for r in regras} != ALERTAS or any(r.get('health') != 'ok' for r in regras):
        raise ValueError('regras ausentes ou sem avaliacao valida')
    # Sinais registrados desde a inicialização; counters preguiçosos ausentes continuam sem dados.
    fontes = {'front-office': 'prospero_cotacao_em_dia', 'middle-office': 'prospero_liquidacao_aguardando',
              'matching-engine': 'prospero_matching_pronto', 'back-office': 'prospero_posicoes_em_dia'}
    for servico, nome in fontes.items():
        if not consultar(prometheus, f'{nome}{{job="prospero",servico="{servico}"}}'):
            raise ValueError('fonte esperada ausente')
    consultas = 0
    sem_dados = 0
    for arquivo, uid in PAINEIS.items():
        esperado = json.loads((ROOT / 'infra/grafana/dashboards' / (arquivo + '.json')).read_text())
        recebido = ler_json(grafana + '/api/dashboards/uid/' + uid, headers)
        model = recebido.get('dashboard', {})
        def contrato(paineis):
            return [(p.get('id'), p.get('type'), p.get('title'), p.get('datasource'),
                     [(t.get('refId'), t.get('expr')) for t in p.get('targets', [])]) for p in paineis]
        if (model.get('uid') != uid or model.get('title') != esperado['title'] or
                not recebido.get('meta', {}).get('provisioned') or
                contrato(model.get('panels', [])) != contrato(esperado['panels'])):
            raise ValueError('dashboard provisionado divergente')
        dados = False
        for painel in esperado['panels']:
            for alvo in painel.get('targets', []):
                resultado = consultar(prometheus, alvo['expr'])
                consultas += 1
                dados |= bool(resultado)
                sem_dados += not bool(resultado)
        if not dados:
            raise ValueError('dashboard inteiro sem fontes observadas')
    return {'sucesso': True, 'dashboards': len(PAINEIS), 'regras': len(regras),
            'consultasValidas': consultas, 'consultasSemDados': sem_dados,
            'nota': 'Ausencia preservada; gauges e contadores nao comprovam conciliacao financeira.'}


def exportar_config(arquivo, grafana, senha):
    arquivo = Path(arquivo)
    if arquivo.parent.is_symlink() or arquivo.parent.stat().st_mode & 0o077:
        raise ValueError('diretorio de credencial deve ser privado')
    fd = os.open(arquivo, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump({'origem': grafana, 'usuario': 'admin', 'senha': senha}, stream)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--projeto', required=True)
    parser.add_argument('--config-e2e', type=Path, required=True)
    args = parser.parse_args()
    prometheus = origem_compose(args.projeto, 'prometheus', 9090)
    grafana = origem_compose(args.projeto, 'grafana', 3000)
    ambiente = importlib.import_module('preparar-ambiente').ler(ROOT / '.env')
    senha = ambiente.get('GRAFANA_ADMIN_PASSWORD', '')
    if not senha:
        raise ValueError('credencial Grafana indisponivel')
    basic = base64.b64encode(('admin:' + senha).encode()).decode()
    headers = {'Authorization': 'Basic ' + basic}
    prazo = time.monotonic() + 90
    while True:
        try:
            resultado = conferir(prometheus, grafana, headers)
            break
        except (ValueError, OSError):
            if time.monotonic() >= prazo:
                raise ValueError('observabilidade nao confirmou fontes e provisionamento') from None
            time.sleep(1)
    exportar_config(args.config_e2e, grafana, senha)
    print(json.dumps(resultado, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as erro:
        # Mensagens/corpos/URLs de exceções podem conter dados; emitir apenas classe e orientação fixa.
        print('Observabilidade recusada (' + type(erro).__name__ + '); conferir fontes e provisionamento.', file=sys.stderr)
        sys.exit(1)
