#!/usr/bin/env python3
"""Smoke test da fundação real. Não substitui cenários financeiros por planilha."""
import argparse
import json
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--projeto', help='Projeto Compose de CI; descobre as portas dinâmicas pelo Docker.')
args = parser.parse_args()


def endereco(service, container_port, local_port):
    if not args.projeto:
        return f'http://127.0.0.1:{local_port}'
    address = subprocess.check_output([
        'docker', 'compose', '-p', args.projeto, '-f', 'docker-compose.yml',
        '-f', 'docker-compose.ci.yml', 'port', service, str(container_port),
    ], text=True).strip()
    return f'http://{address}'


def ler(url):
    with urlopen(url, timeout=8) as response:
        body = response.read().decode()
        return body, response.headers


def aguardar(label, url, validar, prazo=90):
    end = time.monotonic() + prazo
    last = ''
    while time.monotonic() < end:
        try:
            body, headers = ler(url)
            if validar(body, headers):
                print('OK:', label, flush=True)
                return
            last = 'Resposta ainda não atende ao critério.'
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as error:
            last = type(error).__name__
        time.sleep(1)
    raise SystemExit(f'FALHA: {label}: {last}')


frontend = endereco('frontend', 8080, 4200)
aguardar('Angular/Nginx', frontend, lambda body, _: '<title>Próspero' in body)
for service, port in [('front-office', 8081), ('middle-office', 8082), ('matching-engine', 8083), ('back-office', 8084)]:
    base = endereco(service, 8080, port)
    aguardar(service + ': identidade', base + '/api/v1/sistema',
             lambda body, headers, s=service: json.loads(body).get('servico') == s
             and json.loads(body).get('etapa') == 'FUNDAÇÃO'
             and headers.get('Cache-Control') == 'no-store')
    aguardar(service + ': PostgreSQL/Kafka', base + '/q/health/ready',
             lambda body, _: json.loads(body).get('status') == 'UP')
    aguardar(service + ': Swagger', base + '/q/swagger-ui/', lambda body, _: 'swagger-ui-bundle.js' in body)
    aguardar(service + ': OpenAPI', base + '/q/openapi?format=json',
             lambda body, _: '/api/v1/sistema' in json.loads(body).get('paths', {}))
    aguardar(service + ': métricas', base + '/q/metrics', lambda body, _: '# TYPE' in body)
    aguardar(service + ': proxy Angular', frontend + f'/servicos/{service}/api/v1/sistema',
             lambda body, _, s=service: json.loads(body).get('servico') == s)

# RF08c: consumidor de liquidacao.eventos.v1 ativo e métricas de aguardando/recusas publicadas (labels finitos).
aguardar('middle-office: métricas de liquidação', endereco('middle-office', 8080, 8082) + '/q/metrics',
         lambda body, _: 'prospero_liquidacao_aguardando' in body
         and 'topico="liquidacao.eventos.v1"' in body)

prometheus = endereco('prometheus', 9090, 19090)


def alvos_servicos(body):
    # A ferramenta de carga é opt-in; seu alvo pode estar ausente sem afetar a fundação.
    return [item for item in json.loads(body)['data']['activeTargets']
            if item['labels'].get('job') != 'prospero-carga']


aguardar('Prometheus: quatro alvos UP', prometheus + '/api/v1/targets',
         lambda body, _: len(alvos_servicos(body)) == 4
         and all(item['health'] == 'up' for item in alvos_servicos(body)))
aguardar('Grafana', endereco('grafana', 3000, 13000) + '/api/health',
         lambda body, _: json.loads(body).get('database') == 'ok')
aguardar('Loki', endereco('loki', 3100, 13100) + '/ready', lambda body, _: 'ready' in body.lower())
aguardar('OpenTelemetry Collector', endereco('otel-collector', 13133, 14333), lambda body, _: bool(body))
aguardar('Jaeger: traces dos quatro serviços', endereco('jaeger', 16686, 16687) + '/api/v3/services',
         lambda body, _: {'front-office', 'middle-office', 'matching-engine', 'back-office'}.issubset(json.loads(body).get('services', [])))
aguardar('Loki: logs OTLP recebidos', endereco('loki', 3100, 13100) + '/loki/api/v1/label/service_name/values',
         lambda body, _: {'front-office', 'middle-office', 'matching-engine', 'back-office'}.issubset(json.loads(body).get('data', [])))
print('Fundação verificada. Fluxos financeiros e metas de latência ainda não implementados.', flush=True)
