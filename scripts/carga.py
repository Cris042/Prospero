#!/usr/bin/env python3
"""Operação local P06: chaves privadas, JWT e preflight host; sem comandos remotos."""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import shutil
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / '.carga-local'
BASE = 'http://127.0.0.1:8090'


def private_write(path, data):
    path = Path(path)
    # Never follow a symlink while creating/replacing a private artifact.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(data)


def key(name):
    value = (PRIVATE / name).read_bytes().strip()
    if len(value) < 32:
        raise ValueError('chave indisponivel')
    return value


def jwt(payload, secret):
    encode = lambda b: base64.urlsafe_b64encode(b).rstrip(b'=').decode()
    header = encode(b'{"alg":"HS256","typ":"JWT"}')
    body = encode(json.dumps(payload, separators=(',', ':'), sort_keys=True).encode())
    unsigned = header + '.' + body
    return unsigned + '.' + encode(hmac.new(secret, unsigned.encode(), hashlib.sha256).digest())


def control_token():
    now = int(time.time())
    return jwt({'iss': 'prospero-local', 'aud': 'prospero-carga', 'iat': now,
                'nbf': now, 'exp': now + 900, 'scope': 'carga:controle'}, key('controle.key'))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(path, method='GET', body=None):
    data = None if body is None else json.dumps(body, separators=(',', ':')).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Authorization': 'Bearer ' + control_token(),
                                          'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(req, timeout=5) as response:
        content = response.read(1024 * 1024 + 1)
        if len(content) > 1024 * 1024:
            raise ValueError('resposta excessiva')
        return json.loads(content)


def host_budget():
    info = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    available = int(info['MemAvailable'].split()[0]) // 1024
    limit = Path('/sys/fs/cgroup/memory.max')
    current = Path('/sys/fs/cgroup/memory.current')
    if limit.exists() and current.exists() and limit.read_text().strip() != 'max':
        available = min(available, max(0, int(limit.read_text()) - int(current.read_text())) // 1024**2)
    disk = shutil.disk_usage(ROOT).free // 1024**2
    cpus = len(os.sched_getaffinity(0))
    quota = Path('/sys/fs/cgroup/cpu.max')
    if quota.exists():
        maximum, period = quota.read_text().split()
        if maximum != 'max':
            cpus = min(cpus, int(maximum) // int(period))
    if available < 1024 or disk < 1024 or cpus < 1:
        raise ValueError('host sem orcamento para carga; nenhum projeto foi interrompido')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    for endpoint in ('http://127.0.0.1:19090/-/ready',
                     'http://127.0.0.1:13000/api/health',
                     'http://127.0.0.1:14333/'):
        with opener.open(endpoint, timeout=2) as response:
            if response.status != 200 or len(response.read(65537)) > 65536:
                raise ValueError('telemetria indisponivel')
    return {'memoriaDisponivelMiB': available, 'discoLivreMiB': disk,
            'cpus': cpus, 'telemetria': True}


def authorization(plan, instance):
    budget = host_budget()
    now = int(time.time())
    digest = hashlib.sha256(json.dumps(plan, sort_keys=True, separators=(',', ':'),
                                      ensure_ascii=False).encode()).hexdigest()
    return jwt(dict(budget, iss='prospero-preflight', aud=instance, iat=now, exp=now + 30,
                    nonce=secrets.token_hex(16), plano=digest), key('preflight.key'))


def wait_terminal(identity, seconds=65):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        report = request('/api/v1/execucoes-carga/' + identity)
        if report['estado'] in ('CONCLUIDA', 'CANCELADA', 'FALHA'):
            return report
        time.sleep(.1)
    raise ValueError('prazo de consulta excedido')


def begin(plan):
    instance = request('/api/v1/sistema')['instancia']
    return request('/api/v1/execucoes-carga', 'POST',
                   {'plano': plan, 'autorizacao': authorization(plan, instance)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('acao', choices=['preparar', 'token', 'preflight', 'iniciar', 'consultar', 'cancelar', 'baseline'])
    parser.add_argument('--plano', type=Path, default=ROOT / 'ferramentas/carga/plano-exemplo.json')
    parser.add_argument('--id')
    parser.add_argument('--saida', type=Path)
    args = parser.parse_args()
    PRIVATE.mkdir(mode=0o700, exist_ok=True)
    if PRIVATE.is_symlink():
        raise ValueError('diretorio privado invalido')
    PRIVATE.chmod(0o700)
    if args.acao == 'preparar':
        for name in ('controle.key', 'preflight.key'):
            path = PRIVATE / name
            if not path.exists():
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o444)
                with os.fdopen(fd, 'w') as stream:
                    stream.write(secrets.token_hex(32))
                path.chmod(0o444)
        print('Chaves locais preparadas; valores omitidos. Nunca versionar .carga-local/.')
        return
    if args.acao == 'token':
        private_write(args.saida or PRIVATE / 'token.txt', control_token())
        print('Token gravado em arquivo privado; validade 15 minutos.')
        return
    if args.acao in ('consultar', 'cancelar'):
        if not args.id or len(args.id) != 32 or any(c not in '0123456789abcdef' for c in args.id):
            raise ValueError('id invalido')
        suffix = '/cancelamentos' if args.acao == 'cancelar' else ''
        report = request('/api/v1/execucoes-carga/' + args.id + suffix,
                         'POST' if suffix else 'GET')
    else:
        plan = json.loads(args.plano.read_text())
        if args.acao == 'preflight':
            instance = request('/api/v1/sistema')['instancia']
            private_write(args.saida or PRIVATE / 'requisicao.json', json.dumps(
                {'plano': plan, 'autorizacao': authorization(plan, instance)}, separators=(',', ':')))
            print('Requisicao com preflight assinada em arquivo privado; validade 30 segundos.')
            return
        if args.acao == 'baseline':
            reports = []
            for service in ('front-office', 'middle-office', 'matching-engine', 'back-office'):
                plan['servico'] = service
                identity = None
                try:
                    identity = begin(plan)['id']
                    result = wait_terminal(identity)
                    counts = result['contagens']
                    if result['estado'] != 'CONCLUIDA' or counts['respostasHTTP'] == 0 or counts['errosHTTP'] or counts['emVoo'] or counts['timeouts'] or counts['falhasTransporte'] or counts['limitesResposta']:
                        raise ValueError('baseline tecnico recusado por erros')
                    reports.append(result)
                finally:
                    if identity:
                        request('/api/v1/execucoes-carga/' + identity + '/cancelamentos', 'POST')
                time.sleep(1)
            report = {'tipo': 'baseline-tecnico-sem-negociacao', 'host': {'sistema': os.uname().sysname, 'arquitetura': os.uname().machine, 'kernel': os.uname().release}, 'resultados': reports}
        else:
            report = begin(plan)
    path = args.saida or PRIVATE / 'relatorio.json'
    private_write(path, json.dumps(report, ensure_ascii=False, indent=2))
    print('Relatorio gravado em arquivo privado; dados de controle omitidos.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, urllib.error.URLError):
        print('Operacao recusada; verificar configuracao, servico e orcamento locais. Dados sensiveis omitidos.', file=sys.stderr)
        sys.exit(1)
