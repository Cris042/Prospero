"""Snapshots de telemetria da carga (B3): interpretação, separação coletado/indisponivel e deltas, só com dublês."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
_spec = importlib.util.spec_from_file_location('telemetria_carga_qa', SCRIPTS / 'telemetria_carga.py')
tel = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tel)

INSTANTE = '2026-10-28T12:00:00+00:00'


class Interpretacao(unittest.TestCase):
    def test_wal_locks_deadlocks_e_tamanho_em_formato_de_consulta(self):
        self.assertEqual(tel.interpretar_wal('120|3|98304\n'),
                         {'registros': 120, 'imagensCompletas': 3, 'bytes': 98304})
        self.assertEqual(tel.interpretar_locks('17|2\n'), {'total': 17, 'aguardando': 2})
        self.assertEqual(tel.interpretar_deadlocks('4\n'), 4)
        self.assertEqual(tel.interpretar_tamanho_banco('8192000\n'), 8192000)

    def test_saida_malformada_ou_vazia_recusa_em_vez_de_virar_zero(self):
        casos = (
            (tel.interpretar_wal, ''), (tel.interpretar_wal, '1|2'), (tel.interpretar_wal, 'a|b|c'),
            (tel.interpretar_wal, '-1|0|0'), (tel.interpretar_locks, ''), (tel.interpretar_locks, '1|x'),
            (tel.interpretar_deadlocks, ''), (tel.interpretar_deadlocks, '1\n2'),
            (tel.interpretar_tamanho_banco, 'x'),
        )
        for funcao, saida in casos:
            with self.subTest(funcao=funcao.__name__, saida=saida), self.assertRaises(ValueError):
                funcao(saida)

    def test_du_e_df_do_disco(self):
        self.assertEqual(tel.interpretar_du('5242880\t/var/lib/kafka/data\n'), 5242880)
        df = ('Filesystem     1B-blocks        Used   Available Use% Mounted on\n'
              '/dev/sdc      100000000   40000000    60000000  40% /\n')
        self.assertEqual(tel.interpretar_df(df), 40000000)
        with self.assertRaises(ValueError):
            tel.interpretar_df('cabecalho apenas\n')

    def test_lag_do_consumidor_soma_linhas_e_recusa_lag_ausente(self):
        saida = ('\nGROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID\n'
                 'g1 t 0 10 15 5 -\n'
                 'g1 t 1 20 22 2 -\n')
        self.assertEqual(tel.interpretar_lag(saida), 7)
        for ruim in ('', 'GROUP TOPIC PARTITION LAG\n', 'GROUP TOPIC PARTITION LAG\ng t 0 -\n'):
            with self.subTest(saida=ruim), self.assertRaises(ValueError):
                tel.interpretar_lag(ruim)

    def test_docker_stats_converte_unidades_decimais_e_binarias(self):
        linha = ('{"Name":"prospero-ci-1-1-front-office-1","NetIO":"1.5MB / 2kB",'
                 '"BlockIO":"1MiB / 512kB"}')
        valores = tel.interpretar_docker_stats(linha + '\n')
        self.assertEqual(valores['prospero-ci-1-1-front-office-1'], {
            'redeRecebidaBytes': 1500000, 'redeEnviadaBytes': 2000,
            'blocoLidoBytes': 1048576, 'blocoEscritoBytes': 512000})
        with self.assertRaises(ValueError):
            tel.interpretar_docker_stats('{"Name":"x","NetIO":"1 XB / 1B","BlockIO":"0B / 0B"}\n')

    def test_gauge_do_prometheus_exige_presenca_e_valor_numerico(self):
        texto = ('# TYPE prospero_cancelamento_pendente_segundos_max gauge\n'
                 'prospero_cancelamento_pendente_segundos_max{servico="front-office"} 42.5\n')
        self.assertEqual(tel.interpretar_gauge(texto, 'prospero_cancelamento_pendente_segundos_max'), 42.5)
        with self.assertRaises(ValueError):
            tel.interpretar_gauge(texto, 'prospero_posicoes_lag_registros')
        with self.assertRaises(ValueError):
            tel.interpretar_gauge('prospero_x{a="b"} NaN\n', 'prospero_x')


class Snapshot(unittest.TestCase):
    def test_fonte_que_falha_fica_indisponivel_e_nunca_zero(self):
        def ok():
            return tel.interpretar_deadlocks('3\n')

        def falha():
            raise subprocess.CalledProcessError(1, ['psql', 'senha=sentinela'])

        fontes = {'deadlocks': ok, 'wal': falha}
        dados = tel.coletar(fontes, instante=INSTANTE, origens={'deadlocks': 'pg', 'wal': 'pg'})
        self.assertEqual(dados['fontes']['deadlocks'], {'estado': 'coletado', 'origem': 'pg',
                                                        'instante': INSTANTE, 'valores': 3})
        self.assertEqual(dados['fontes']['wal']['estado'], 'indisponivel')
        self.assertEqual(dados['fontes']['wal']['origem'], 'pg')
        self.assertNotIn('valores', dados['fontes']['wal'])
        self.assertNotIn('sentinela', repr(dados))  # nenhuma mensagem de erro bruta vaza para o relatório

    def test_recursos_fora_do_escopo_aparecem_como_indisponivel_com_motivo(self):
        dados = tel.coletar({}, instante=INSTANTE, origens={})
        for nome in ('latenciaFsync', 'jmxBroker', 'seriesContinuas'):
            with self.subTest(nome=nome):
                self.assertEqual(dados['fontes'][nome]['estado'], 'indisponivel')
                self.assertTrue(dados['fontes'][nome]['motivo'])
                self.assertEqual(dados['fontes'][nome]['instante'], INSTANTE)

    def test_delta_so_existe_quando_os_dois_lados_foram_coletados(self):
        antes = {'fontes': {'wal': {'estado': 'coletado', 'valores': {'bytes': 100, 'registros': 1}},
                            'lag': {'estado': 'indisponivel', 'motivo': 'x'}}}
        depois = {'fontes': {'wal': {'estado': 'coletado', 'valores': {'bytes': 150, 'registros': 4}},
                             'lag': {'estado': 'coletado', 'valores': 0}}}
        delta = tel.delta(antes, depois)
        self.assertEqual(delta['wal'], {'bytes': 50, 'registros': 3})
        self.assertIsNone(delta['lag'])


class Montagem(unittest.TestCase):
    RESPOSTAS = (
        ('pg_stat_wal', '1|0|8\n'), ('pg_locks', '3|0\n'), ('deadlocks', '0\n'), ('pg_database_size', '100\n'),
        ('kafka-consumer-groups.sh', 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID\n'
                                     'g t 0 1 3 2 -\n'),
        ('du', '4096\t/var/lib/kafka/data\n'),
    )

    def _run(self, chamadas):
        def run(args):
            chamadas.append(list(args))
            texto = ' '.join(args)
            for chave, resposta in self.RESPOSTAS:
                if chave in texto:
                    return resposta
            raise AssertionError('comando inesperado: ' + texto)
        return run

    def _run_host(self, chamadas):
        def run_host(argv):
            chamadas.append(list(argv))
            if argv[:2] == ['docker', 'ps']:
                return 'abc123\ndef456\n'
            if argv[:2] == ['docker', 'stats']:
                return ('{"Name":"front","NetIO":"1kB / 2kB","BlockIO":"0B / 1MB"}\n'
                        '{"Name":"mid","NetIO":"3MB / 4MB","BlockIO":"5MB / 6MB"}\n')
            if argv[0] == 'df':
                return 'Filesystem 1 Used Avail Use% Mounted\n/dev/x 10 7 3 70% /\n'
            raise AssertionError('comando de host inesperado')
        return run_host

    def test_snapshot_so_le_com_papel_monitor_sem_senha_e_marca_fora_do_escopo(self):
        from unittest import mock
        compose, host = [], []
        with mock.patch.object(tel, '_http_texto', return_value='prospero_posicoes_lag_registros 9\n'):
            dados = tel.snapshot(self._run(compose), self._run_host(host), projeto='prospero-ci-1-1',
                                 origem_bo='http://127.0.0.1:9', rotulo='antes-escrita-1', instante=INSTANTE)
        psql = [c for c in compose if 'psql' in c]
        self.assertTrue(psql)
        for argv in psql:
            self.assertEqual(argv[argv.index('-U') + 1], tel.PAPEL_MONITOR)
            self.assertNotIn('PGPASSWORD', ' '.join(argv))
        self.assertEqual(dados['rotulo'], 'antes-escrita-1')
        fontes = dados['fontes']
        self.assertEqual(fontes['lagKafkaGrupos']['valores'], 2)
        self.assertEqual(fontes['lagPosicoes']['valores'], 9.0)
        self.assertEqual(fontes['discoLogKafka']['valores'], 4096)
        self.assertEqual(fontes['discoRunner']['valores'], 7)  # coluna Used da linha de dados
        self.assertEqual(fontes['recursosContainers']['estado'], 'coletado')
        self.assertEqual(fontes['latenciaFsync']['estado'], 'indisponivel')
        self.assertTrue(all(c[:2] == ['docker', 'ps'] or c[:2] == ['docker', 'stats'] or c[0] == 'df'
                            for c in host))

    def test_rotulo_invalido_e_recusado(self):
        with self.assertRaises(ValueError):
            tel.snapshot(lambda a: '', lambda a: '', projeto='p', origem_bo='x', rotulo='Com Espaco',
                         instante=INSTANTE)

    def test_preparar_papel_e_idempotente_e_so_pg_monitor(self):
        chamadas = []
        tel.criar_papel_monitor(lambda args: chamadas.append(list(args)) or '', 'postgres-front-office')
        sql = chamadas[0][-1]
        self.assertIn('IF NOT EXISTS', sql)
        self.assertIn('IN ROLE pg_monitor', sql)
        self.assertNotIn('SUPERUSER', sql.upper())
        self.assertNotIn('PASSWORD', sql.upper())


if __name__ == '__main__':
    unittest.main()
