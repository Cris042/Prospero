"""Snapshots de telemetria da carga (P06 B3): só leitura, sem coletor novo e sem socket Docker.

Cada fonte devolve valor coletado ou fica `indisponivel` com origem, instante e motivo genérico. Ausência nunca vira
zero. Leituras de PostgreSQL usam o papel `prospero_monitor` (pg_monitor, sem superusuário nem dono), sem senha no
relatório. Recursos fora do escopo B3 (fsync, JMX do broker, séries contínuas) aparecem sempre como indisponíveis."""
import datetime
import importlib.util
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path
import argparse

FORA_DO_ESCOPO = {
    'latenciaFsync': 'fora do escopo B3: exige coletor de I/O por fsync (V01)',
    'jmxBroker': 'fora do escopo B3: exige JMX do broker (V01)',
    'seriesContinuas': 'fora do escopo B3: exporters contínuos de banco e Kafka ficam para a V01',
}
PAPEL_MONITOR = 'prospero_monitor'
BANCOS = {'postgres-front-office': 'prospero_fo', 'postgres-middle-office': 'prospero_mo'}
DONOS = {'postgres-front-office': 'prospero_fo', 'postgres-middle-office': 'prospero_mo'}
UNIDADES = {'B': 1, 'kB': 1000, 'MB': 1000 ** 2, 'GB': 1000 ** 3, 'TB': 1000 ** 4,
            'KiB': 1024, 'MiB': 1024 ** 2, 'GiB': 1024 ** 3, 'TiB': 1024 ** 4, 'KB': 1000, 'K': 1000,
            'M': 1000 ** 2, 'G': 1000 ** 3, 'Ki': 1024, 'Mi': 1024 ** 2, 'Gi': 1024 ** 3}
SQL_WAL = 'SELECT wal_records, wal_fpi, wal_bytes FROM pg_stat_wal'
SQL_LOCKS = 'SELECT count(*), count(*) FILTER (WHERE NOT granted) FROM pg_locks'
SQL_DEADLOCKS = "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()"
SQL_TAMANHO = 'SELECT pg_database_size(current_database())'


def _inteiro(texto):
    if not re.fullmatch(r'[0-9]+', texto):
        raise ValueError('inteiro invalido')
    return int(texto)


def _linha_unica(saida):
    linhas = [linha.strip() for linha in saida.splitlines() if linha.strip()]
    if len(linhas) != 1:
        raise ValueError('saida inesperada')
    return linhas[0]


def interpretar_wal(saida):
    partes = _linha_unica(saida).split('|')
    if len(partes) != 3:
        raise ValueError('wal invalido')
    registros, imagens, bytes_ = (_inteiro(p) for p in partes)
    return {'registros': registros, 'imagensCompletas': imagens, 'bytes': bytes_}


def interpretar_locks(saida):
    partes = _linha_unica(saida).split('|')
    if len(partes) != 2:
        raise ValueError('locks invalido')
    total, aguardando = (_inteiro(p) for p in partes)
    if aguardando > total:
        raise ValueError('locks inconsistentes')
    return {'total': total, 'aguardando': aguardando}


def interpretar_deadlocks(saida):
    return _inteiro(_linha_unica(saida))


def interpretar_tamanho_banco(saida):
    return _inteiro(_linha_unica(saida))


def interpretar_du(saida):
    primeiro = _linha_unica(saida).split()
    if not primeiro:
        raise ValueError('du invalido')
    return _inteiro(primeiro[0])


def interpretar_df(saida):
    linhas = [linha.split() for linha in saida.splitlines() if linha.strip()]
    if len(linhas) < 2 or len(linhas[-1]) < 6:
        raise ValueError('df invalido')
    return _inteiro(linhas[-1][2])


def interpretar_lag(saida):
    linhas = [linha.split() for linha in saida.splitlines() if linha.strip()]
    cabecalho = next((linha for linha in linhas if linha[:1] == ['GROUP']), None)
    if cabecalho is None or 'LAG' not in cabecalho:
        raise ValueError('lag sem cabecalho')
    indice = cabecalho.index('LAG')
    # Linhas de aviso do CLI têm outro número de colunas; a linha de dados sempre tem todas as do cabeçalho.
    dados = [linha for linha in linhas if linha[:1] != ['GROUP'] and len(linha) == len(cabecalho)]
    if not dados:
        raise ValueError('lag sem linhas')
    total = 0
    for linha in dados:
        total += _inteiro(linha[indice])
    return total


def _bytes(texto):
    achado = re.fullmatch(r'([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z]+)', texto.strip())
    if not achado or achado[2] not in UNIDADES:
        raise ValueError('unidade invalida')
    return round(float(achado[1]) * UNIDADES[achado[2]])


def interpretar_docker_stats(saida):
    resultado = {}
    for linha in saida.splitlines():
        if not linha.strip():
            continue
        registro = json.loads(linha)
        rede = registro['NetIO'].split('/')
        bloco = registro['BlockIO'].split('/')
        if len(rede) != 2 or len(bloco) != 2:
            raise ValueError('docker stats invalido')
        resultado[str(registro['Name'])] = {
            'redeRecebidaBytes': _bytes(rede[0]), 'redeEnviadaBytes': _bytes(rede[1]),
            'blocoLidoBytes': _bytes(bloco[0]), 'blocoEscritoBytes': _bytes(bloco[1])}
    if not resultado:
        raise ValueError('docker stats vazio')
    return resultado


def interpretar_gauge(texto, nome):
    padrao = re.compile(r'^' + re.escape(nome) + r'(?:\{[^}]*\})?\s+(\S+)\s*$', re.MULTILINE)
    achados = padrao.findall(texto)
    if len(achados) != 1:
        raise ValueError('gauge ausente ou repetido')
    valor = float(achados[0])
    if valor != valor or valor in (float('inf'), float('-inf')):
        raise ValueError('gauge invalido')
    return valor


def _estado(origem, instante, valores=None, motivo=None):
    if motivo is not None:
        return {'estado': 'indisponivel', 'origem': origem, 'instante': instante, 'motivo': motivo}
    return {'estado': 'coletado', 'origem': origem, 'instante': instante, 'valores': valores}


def coletar(fontes, *, instante, origens):
    """Executa cada fonte (callable sem argumentos). Falha vira indisponivel com motivo genérico, sem a mensagem bruta."""
    resultado = {}
    for nome, fonte in fontes.items():
        origem = origens.get(nome, 'compose')
        try:
            resultado[nome] = _estado(origem, instante, valores=fonte())
        except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError):
            resultado[nome] = _estado(origem, instante, motivo='coleta recusada; ver logs do passo')
    for nome, motivo in FORA_DO_ESCOPO.items():
        resultado[nome] = _estado('nao coletado', instante, motivo=motivo)
    return {'instante': instante, 'fontes': resultado}


def delta(antes, depois):
    """Diferença depois − antes só para fontes coletadas nos dois instantes; o resto é None."""
    def subtrair(a, b):
        if isinstance(a, dict) and isinstance(b, dict) and a.keys() == b.keys():
            return {k: subtrair(a[k], b[k]) for k in a}
        if type(a) in (int, float) and type(b) in (int, float):
            return b - a
        return None

    saida = {}
    for nome, valor in antes['fontes'].items():
        posterior = depois['fontes'].get(nome, {})
        if valor.get('estado') == 'coletado' and posterior.get('estado') == 'coletado':
            saida[nome] = subtrair(valor['valores'], posterior['valores'])
        else:
            saida[nome] = None
    return saida


def fontes_postgres(run, servico):
    """Fontes do banco de um serviço, pelo papel de monitoramento via socket local do próprio contêiner."""
    banco = BANCOS[servico]
    base = ['exec', '-T', servico, 'psql', '-X', '-q', '-At', '-F', '|', '-U', PAPEL_MONITOR, '-d', banco, '-c']
    return {
        f'wal:{servico}': lambda: interpretar_wal(run(base + [SQL_WAL])),
        f'locks:{servico}': lambda: interpretar_locks(run(base + [SQL_LOCKS])),
        f'deadlocks:{servico}': lambda: interpretar_deadlocks(run(base + [SQL_DEADLOCKS])),
        f'tamanhoBanco:{servico}': lambda: interpretar_tamanho_banco(run(base + [SQL_TAMANHO])),
    }


def criar_papel_monitor(run, servico):
    """Cria o papel de leitura (uma vez por execução) com o dono do banco; idempotente, sem senha."""
    banco = BANCOS[servico]
    sql = ("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + PAPEL_MONITOR + "') "
           "THEN CREATE ROLE " + PAPEL_MONITOR + " LOGIN IN ROLE pg_monitor; END IF; END $$;")
    run(['exec', '-T', servico, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-U', DONOS[servico],
         '-d', banco, '-c', sql])


def _http_texto(origem):
    with urllib.request.urlopen(origem + '/q/metrics', timeout=10) as resposta:
        return resposta.read(4 * 1024 * 1024).decode()


def montar_fontes(run, run_host, *, projeto, origem_bo):
    """Fontes do snapshot. `run(args)` executa `docker compose <args>` do projeto; `run_host(argv)` roda no runner.
    Retorna (fontes, origens) com a origem textual de cada fonte, para o relatório."""
    fontes, origens = {}, {}
    for servico in BANCOS:
        for nome, fonte in fontes_postgres(run, servico).items():
            fontes[nome] = fonte
            origens[nome] = 'compose exec ' + servico
    fontes['lagPosicoes'] = lambda: interpretar_gauge(_http_texto(origem_bo), 'prospero_posicoes_lag_registros')
    origens['lagPosicoes'] = 'BO /q/metrics'
    fontes['lagKafkaGrupos'] = lambda: interpretar_lag(run(['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh',
                                                             '--bootstrap-server', 'kafka:9092', '--describe',
                                                             '--all-groups']))
    origens['lagKafkaGrupos'] = 'compose exec kafka'
    fontes['discoLogKafka'] = lambda: interpretar_du(run(['exec', '-T', 'kafka', 'du', '-sb', '/var/lib/kafka/data']))
    origens['discoLogKafka'] = 'compose exec kafka'
    fontes['discoRunner'] = lambda: interpretar_df(run_host(['df', '-P', '-B1', '/']))
    origens['discoRunner'] = 'runner df'

    def recursos():
        ids = run_host(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + projeto]).split()
        if not ids:
            raise ValueError('projeto sem contêineres')
        return interpretar_docker_stats(run_host(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids]))
    fontes['recursosContainers'] = recursos
    origens['recursosContainers'] = 'docker stats (CLI, sem socket montado)'
    return fontes, origens


def snapshot(run, run_host, *, projeto, origem_bo, rotulo, instante):
    if not re.fullmatch(r'[a-z0-9-]{1,40}', rotulo):
        raise ValueError('rotulo invalido')
    fontes, origens = montar_fontes(run, run_host, projeto=projeto, origem_bo=origem_bo)
    dados = coletar(fontes, instante=instante, origens=origens)
    dados['rotulo'] = rotulo
    return dados


def _executor_compose(projeto):
    aqui = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location('ci_telemetria', aqui / 'testar-carga-integrada.py')
    ci = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ci)
    base = ci.comando_compose(projeto)

    def run(args):
        return subprocess.run(base + list(args), cwd=ci.carga.ROOT, capture_output=True, text=True, check=True,
                              timeout=60).stdout
    return run


def _executor_host():
    def run_host(argv):
        return subprocess.run(list(argv), capture_output=True, text=True, check=True, timeout=60).stdout
    return run_host


def main(argv=None):
    parser = argparse.ArgumentParser(description='Telemetria da carga (B3): papel de leitura e snapshots.')
    sub = parser.add_subparsers(dest='comando', required=True)
    prep = sub.add_parser('preparar')
    prep.add_argument('--projeto', required=True)
    snap = sub.add_parser('snapshot')
    snap.add_argument('--projeto', required=True)
    snap.add_argument('--origem-bo', required=True)
    snap.add_argument('--rotulo', required=True)
    try:
        args = parser.parse_args(argv)
        run = _executor_compose(args.projeto)
        if args.comando == 'preparar':
            for servico in BANCOS:
                criar_papel_monitor(run, servico)
            return 0
        if not re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}', args.origem_bo):
            raise ValueError('origem do BO invalida')
        instante = datetime.datetime.now(datetime.timezone.utc).isoformat()
        dados = snapshot(run, _executor_host(), projeto=args.projeto, origem_bo=args.origem_bo,
                         rotulo=args.rotulo, instante=instante)
        print(json.dumps(dados, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('Telemetria recusada; verificar projeto e contêineres. Dados sensiveis omitidos.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
