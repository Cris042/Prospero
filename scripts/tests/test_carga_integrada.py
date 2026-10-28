"""Fronteiras do smoke B1: somente dublês e HTTP loopback descartável, sem stack/carga financeira."""
import base64
import copy
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]

def carregar(nome, arquivo):
    spec = importlib.util.spec_from_file_location(nome, SCRIPTS / arquivo)
    modulo = importlib.util.module_from_spec(spec)
    sys.modules[nome] = modulo
    spec.loader.exec_module(modulo)
    return modulo

ci = carregar('carga_integrada_qa', 'testar-carga-integrada.py')
carga = carregar('carga_preflight_qa', 'carga.py')
SERVICOS = {'ATIVOS': 'front-office', 'ORDENS': 'front-office', 'CARTEIRA': 'middle-office',
            'POSICOES': 'back-office', 'NEGOCIOS': 'back-office', 'RAZAO': 'back-office'}
INSTANCIA = 'b' * 32
IDENTIDADE = 'a' * 32
SEGREDO = 'sentinela-privada-qa-nao-publicar'

def plano_esperado(cenario):
    return {'cenario': cenario, 'servico': SERVICOS[cenario], 'modo': 'ABERTO',
            'estagios': [{'duracaoMs': 2000, 'rps': 1, 'vus': 0}], 'aquecimentoMs': 0,
            'concorrencia': 1, 'maxChamadas': 2, 'timeoutMs': 1000, 'maxRespostaBytes': 65536}

def relatorio(cenario='ATIVOS', identidade=IDENTIDADE):
    contagens = {nome: 0 for nome in ('oferecidas', 'iniciadas', 'naoIniciadas', 'descartadas',
                 'interrompidas', 'finalizadas', 'emVoo', 'respostasHTTP', 'errosHTTP', 'timeouts',
                 'falhasTransporte', 'canceladasEmVoo', 'limitesResposta', 'bytesLidos')}
    for nome in ('oferecidas', 'iniciadas', 'finalizadas', 'respostasHTTP'):
        contagens[nome] = 2
    contagens['bytesLidos'] = 200
    return {'id': identidade, 'instancia': INSTANCIA, 'estado': 'CONCLUIDA',
            'plano': plano_esperado(cenario), 'ofertasPrevistas': 2,
            'contagens': contagens, 'medicao': dict(contagens),
            'quantis': {'amostras': 2, 'p50Us': 10, 'p95Us': 20, 'p99Us': 20, 'maximoUs': 20}}


class ContratosFechados(unittest.TestCase):
    def test_projeto_recusa_identidade_arbitraria(self):
        self.assertEqual(ci.validar_projeto('prospero-ci-123-2'), 'prospero-ci-123-2')
        for projeto in ('horus-dev', 'prospero-ci-a-2', 'prospero-ci-1-2;id', '', 'prospero-ci-1-2\n'):
            with self.subTest(projeto=projeto), self.assertRaises(ValueError):
                ci.validar_projeto(projeto)

    def test_planos_limitam_exatamente_doze_gets_logicos(self):
        for cenario in SERVICOS:
            self.assertEqual(ci.plano(cenario), plano_esperado(cenario))
        for cenario in ('IDENTIDADE', 'ativos', 'http://externo', 'DESCONHECIDO'):
            with self.subTest(cenario=cenario), self.assertRaises(ValueError):
                ci.plano(cenario)

    def test_orcamento_reserva_limpeza_e_nao_conta_recusa(self):
        budget = ci.OrcamentoHttp()
        for _ in range(108):
            budget.consumir()
        with self.assertRaises(ValueError):
            budget.consumir()
        self.assertEqual(budget.usadas, 108)
        for _ in range(12):
            budget.consumir(limpeza=True)
        with self.assertRaises(ValueError):
            budget.consumir(limpeza=True)
        self.assertEqual(budget.usadas, 120)

    def test_relatorio_nominal_e_falhas_de_conservacao(self):
        ci.validar_relatorio(relatorio(), 'ATIVOS', INSTANCIA)
        for campo in ('oferecidas', 'iniciadas', 'finalizadas', 'respostasHTTP', 'emVoo', 'errosHTTP',
                      'descartadas', 'interrompidas', 'naoIniciadas', 'timeouts', 'falhasTransporte',
                      'canceladasEmVoo', 'limitesResposta'):
            r = relatorio()
            r['contagens'][campo] += 1
            with self.subTest(campo=campo), self.assertRaises(ValueError):
                ci.validar_relatorio(r, 'ATIVOS', INSTANCIA)

    def test_estado_instancia_id_plano_e_amostras_invalidos(self):
        alteracoes = [lambda r: r.update(estado='CANCELADA'), lambda r: r.update(instancia='c' * 32),
                      lambda r: r.update(id='nao-id'), lambda r: r.update(ofertasPrevistas=3),
                      lambda r: r['plano'].update(maxChamadas=3),
                      lambda r: r['quantis'].update(amostras=1), lambda r: r['medicao'].update(errosHTTP=1)]
        for alterar in alteracoes:
            r = relatorio(); alterar(r)
            with self.subTest(alteracao=alterar), self.assertRaises(ValueError):
                ci.validar_relatorio(r, 'ATIVOS', INSTANCIA)

class DescobertaDoProjeto(unittest.TestCase):
    cid = 'd' * 64

    def executar_fake(self, chamadas, *, projeto='prospero-ci-123-2', servico='api-carga', porta='127.0.0.1:49123\n'):
        def executar(comando, *args, **kwargs):
            chamadas.append(comando)
            if comando[:2] == ['docker', 'inspect']:
                valor = json.dumps({'com.docker.compose.project': projeto, 'com.docker.compose.service': servico})
            elif 'ps' in comando:
                valor = self.cid + '\n'
            elif 'port' in comando:
                valor = porta
            else:
                raise AssertionError('Comando fora da descoberta de recursos.')
            import subprocess
            return subprocess.CompletedProcess(comando, 0, stdout=valor, stderr='')
        return executar

    def test_mesmos_cinco_arquivos_e_perfil_em_toda_operacao(self):
        comando = ci.comando_compose('prospero-ci-123-2')
        self.assertEqual(comando[:2], ['docker', 'compose'])
        self.assertIn('prospero-ci-123-2', comando)
        arquivos = [Path(comando[i + 1]).name for i, item in enumerate(comando[:-1]) if item == '-f']
        self.assertEqual(arquivos, ['docker-compose.yml', 'docker-compose.ci.yml', 'docker-compose.robo.yml',
                                  'docker-compose.carga.yml', 'docker-compose.carga-ci.yml'])
        self.assertIn('--profile', comando)
        self.assertEqual(comando[comando.index('--profile') + 1], 'carga')

    def test_descobre_porta_so_apos_cid_e_labels_do_proprio_servico(self):
        for servico in ci.PORTAS:
            chamadas = []
            with self.subTest(servico=servico), patch('subprocess.run', side_effect=self.executar_fake(chamadas, servico=servico)):
                self.assertEqual(ci.descobrir_origem('prospero-ci-123-2', servico), 'http://127.0.0.1:49123')
            self.assertTrue(any('ps' in c for c in chamadas))
            self.assertTrue(any(c[:2] == ['docker', 'inspect'] and self.cid in c for c in chamadas))
            self.assertIn(str(ci.PORTAS[servico]), next(c for c in chamadas if 'port' in c))

    def test_labels_alheios_impedem_consulta_da_porta(self):
        for projeto, servico in [('horus-dev', 'api-carga'), ('prospero-ci-123-2', 'grafana')]:
            chamadas = []
            with self.subTest(projeto=projeto, servico=servico), patch('subprocess.run', side_effect=self.executar_fake(chamadas, projeto=projeto, servico=servico)):
                with self.assertRaises(ValueError):
                    ci.descobrir_origem('prospero-ci-123-2', 'api-carga')
            self.assertFalse(any('port' in c for c in chamadas))

    def test_servico_fora_da_allowlist_nao_chama_docker(self):
        for servico in ('postgres-front-office', 'horus-dev', 'grafana;id'):
            with self.subTest(servico=servico), patch('subprocess.run') as executar:
                with self.assertRaises(ValueError):
                    ci.descobrir_origem('prospero-ci-123-2', servico)
                executar.assert_not_called()

    def test_publicacao_ambigua_externa_ou_invalida_recusada(self):
        for porta in ('0.0.0.0:49123', '[::]:49123', '127.0.0.1:0', '127.0.0.1:65536',
                      '127.0.0.1:abc', '127.0.0.1:49123\n127.0.0.1:49124', ''):
            with self.subTest(porta=porta), patch('subprocess.run', side_effect=self.executar_fake([], porta=porta)):
                with self.assertRaises(ValueError):
                    ci.descobrir_origem('prospero-ci-123-2', 'api-carga')


class Relogio:
    def __init__(self):
        self.tempo = 0
    def __call__(self):
        return self.tempo
    def pausa(self, segundos):
        self.tempo += segundos


class FluxoLimitado(unittest.TestCase):
    def backend(self, *, falhar=False, pendente=False):
        eventos, conhecidos = [], {}
        def autorizar(plano, instancia):
            eventos.append(('atestar', plano['cenario'], instancia))
            return 'atestado-' + SEGREDO
        def requisitar(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            eventos.append((metodo, caminho, limpeza))
            if caminho == '/api/v1/sistema':
                return {'instancia': INSTANCIA}
            if caminho == '/api/v1/execucoes-carga':
                self.assertEqual(metodo, 'POST')
                self.assertEqual(corpo['autorizacao'], 'atestado-' + SEGREDO)
                cenario = corpo['plano']['cenario']
                self.assertEqual(corpo['plano'], plano_esperado(cenario))
                self.assertIn(('atestar', cenario, INSTANCIA), eventos)
                identidade = format(len(conhecidos) + 1, '032x')
                conhecidos[identidade] = cenario
                return {'id': identidade, 'instancia': INSTANCIA, 'estado': 'AGUARDANDO'}
            identidade = caminho.split('/')[4]
            self.assertIn(identidade, conhecidos)
            cenario = conhecidos[identidade]
            if caminho.endswith('/cancelamentos'):
                self.assertTrue(limpeza)
                return {'id': identidade, 'instancia': INSTANCIA, 'estado': 'CANCELADA', 'contagens': {'emVoo': 0}}
            if limpeza:
                return {'id': identidade, 'instancia': INSTANCIA, 'estado': 'CANCELADA', 'contagens': {'emVoo': 0}}
            if pendente:
                return {'id': identidade, 'instancia': INSTANCIA, 'estado': 'EXECUTANDO'}
            resultado = relatorio(cenario, identidade)
            if falhar:
                resultado['contagens']['errosHTTP'] = 1
            return resultado
        return requisitar, autorizar, eventos, conhecidos

    def test_seis_cenarios_relatorio_minimo_e_cleanup_somente_dos_ids_observados(self):
        req, autorizar, eventos, ids = self.backend()
        tempo = Relogio()
        resultado = ci.rodar_cenarios(req, autorizar, pausa=tempo.pausa, relogio=tempo)
        self.assertEqual(len(resultado), 6)
        self.assertEqual({r['cenario'] for r in resultado}, set(SERVICOS))
        for r in resultado:
            self.assertEqual(set(r), {'cenario', 'estado', 'contagens', 'amostras'})
            self.assertEqual((r['estado'], r['amostras']), ('CONCLUIDA', 2))
        self.assertNotIn(SEGREDO, json.dumps(resultado))
        cancelamentos = [e for e in eventos if e[0] == 'POST' and e[1].endswith('/cancelamentos')]
        self.assertEqual(len(cancelamentos), 6)
        self.assertEqual(len(ids), 6)

    def test_primeiro_erro_falha_e_limpa_sem_iniciar_outro_cenario(self):
        req, autorizar, eventos, ids = self.backend(falhar=True)
        tempo = Relogio()
        with self.assertRaises(ValueError):
            ci.rodar_cenarios(req, autorizar, pausa=tempo.pausa, relogio=tempo)
        self.assertEqual(len(ids), 1)
        self.assertEqual(len([e for e in eventos if e[0] == 'POST' and e[1].endswith('/cancelamentos')]), 1)

    def test_polling_tem_prazo_teto_e_cleanup_apos_expirar(self):
        req, autorizar, eventos, ids = self.backend(pendente=True)
        tempo = Relogio()
        with self.assertRaises(ValueError):
            ci.rodar_cenarios(req, autorizar, pausa=tempo.pausa, relogio=tempo)
        consultas = [e for e in eventos if e[0] == 'GET' and e[1] != '/api/v1/sistema' and not e[2]]
        self.assertLessEqual(len(consultas), 12)
        self.assertLessEqual(tempo.tempo, 12)
        self.assertEqual(len(ids), 1)
        self.assertTrue(any(e[0] == 'POST' and e[1].endswith('/cancelamentos') for e in eventos))

class TransporteDoControle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        import threading
        cls.pedidos = []
        cls.modo = 'normal'
        cls.budget = None
        class Handler(BaseHTTPRequestHandler):
            def atender(self):
                cls.pedidos.append((self.command, self.path, self.headers.get('Host'),
                                    self.headers.get('Authorization'), cls.budget.usadas if cls.budget else -1))
                if cls.modo == 'redirect':
                    self.send_response(302); self.send_header('Location', '/destino-proibido'); self.end_headers(); return
                if cls.modo == 'erro':
                    self.send_response(503); self.end_headers(); self.wfile.write(SEGREDO.encode()); return
                if cls.modo == 'lenta':
                    import time
                    body = b'{"ok":true,"valor":"lento"}'
                    self.send_response(200)
                    self.send_header('Content-Length', str(len(body))); self.end_headers()
                    try:
                        for byte in body:
                            self.wfile.write(bytes([byte])); self.wfile.flush()
                            time.sleep(.04)
                    except (BrokenPipeError, ConnectionResetError):
                        pass
                    return
                tamanho = 1024 * 1024 + (1 if cls.modo == 'grande' else 0)
                body = b'{"v":"' + b'x' * (tamanho - 8) + b'"}' if cls.modo in ('grande', 'limite') else b'{"ok":true}'
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            do_GET = atender
            do_POST = atender
            def log_message(self, *_):
                pass
        cls.servidor = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.servidor.serve_forever, daemon=True); cls.thread.start()
        cls.origem = f'http://127.0.0.1:{cls.servidor.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.servidor.shutdown(); cls.servidor.server_close(); cls.thread.join(timeout=2)

    def setUp(self):
        from types import SimpleNamespace
        self.pedidos.clear(); type(self).modo = 'normal'
        type(self).budget = ci.OrcamentoHttp()
        self.patch_carga = patch.object(ci, 'carga', SimpleNamespace(control_token=lambda: SEGREDO, NoRedirect=carga.NoRedirect), create=True)
        self.patch_carga.start(); self.addCleanup(self.patch_carga.stop)

    def test_host_literal_token_somente_controle_e_orcamento_antes_de_io(self):
        cliente = ci.ClienteCi(self.origem, self.budget)
        self.assertEqual(cliente('/api/v1/sistema'), {'ok': True})
        self.assertEqual(self.pedidos, [('GET', '/api/v1/sistema', 'api-carga:8080', 'Bearer ' + SEGREDO, 1)])
        self.assertEqual(cliente('/api/v1/execucoes-carga', 'POST', {'plano': {}, 'autorizacao': SEGREDO}), {'ok': True})
        self.assertEqual(cliente('/api/v1/execucoes-carga/' + IDENTIDADE), {'ok': True})
        self.assertEqual(cliente('/api/v1/execucoes-carga/' + IDENTIDADE + '/cancelamentos', 'POST', limpeza=True), {'ok': True})
        self.assertEqual(self.budget.usadas, 4)

    def test_origens_invalidas_recusadas_antes_da_requisicao(self):
        for origem in ('http://example.com:8080', 'http://localhost:8080', 'https://127.0.0.1:8080',
                       'http://127.0.0.2:8080', 'http://127.0.0.1', 'http://x:y@127.0.0.1:8080',
                       self.origem + '/outra', self.origem + '#fragmento', self.origem + '?query=1'):
            with self.subTest(origem=origem), self.assertRaises(ValueError):
                ci.ClienteCi(origem, self.budget)
        self.assertEqual(self.pedidos, [])

    def test_metodos_e_rotas_fora_do_controle_nao_geram_io(self):
        cliente = ci.ClienteCi(self.origem, self.budget)
        for caminho, metodo in [('/api/v1/ordens', 'POST'), ('/api/v1/sistema', 'POST'),
                                ('/api/v1/execucoes-carga', 'GET'), ('/api/v1/execucoes-carga/nao-id', 'GET'),
                                ('http://example.com/api/v1/sistema', 'GET'), ('/api/v1/sistema?x=1', 'GET'),
                                ('/api/v1/execucoes-carga/' + IDENTIDADE + '/cancelamentos', 'GET')]:
            with self.subTest(caminho=caminho, metodo=metodo), self.assertRaises(ValueError):
                cliente(caminho, metodo)
        self.assertEqual(self.pedidos, [])

    def test_proxy_do_ambiente_nao_intercepta_controle(self):
        with patch.dict(os.environ, {'http_proxy': 'http://127.0.0.1:9', 'HTTP_PROXY': 'http://127.0.0.1:9', 'no_proxy': '', 'NO_PROXY': ''}):
            self.assertEqual(ci.ClienteCi(self.origem, self.budget)('/api/v1/sistema'), {'ok': True})

    def test_redirect_nao_seguido_e_erro_nao_reflete_corpo_ou_token(self):
        for modo in ('redirect', 'erro'):
            type(self).modo = modo; self.pedidos.clear()
            with self.subTest(modo=modo), self.assertRaises(ValueError) as erro:
                ci.ClienteCi(self.origem, self.budget)('/api/v1/sistema')
            self.assertNotIn(SEGREDO, str(erro.exception))
            self.assertEqual(len(self.pedidos), 1)

    def test_limite_de_um_mib_aceito_e_um_byte_a_mais_recusado(self):
        type(self).modo = 'limite'
        self.assertEqual(len(ci.ClienteCi(self.origem, self.budget)('/api/v1/sistema')['v']), 1024 * 1024 - 8)
        type(self).modo = 'grande'
        with self.assertRaises(ValueError):
            ci.ClienteCi(self.origem, self.budget)('/api/v1/sistema')

    def test_exaustao_do_orcamento_impede_io(self):
        budget = ci.OrcamentoHttp(limite=13, reserva_limpeza=12)
        budget.consumir()
        with self.assertRaises(ValueError):
            ci.ClienteCi(self.origem, budget)('/api/v1/sistema')
        self.assertEqual(self.pedidos, [])

    def test_timeout_http_usa_somente_tempo_restante_antes_de_io(self):
        from unittest.mock import MagicMock
        tempo = Relogio(); tempo.tempo = 100
        cliente = ci.ClienteCi(self.origem, self.budget, relogio=tempo)
        resposta = MagicMock(); resposta.__enter__.return_value = resposta
        resposta.status = 200; resposta.read.return_value = b'{"ok":true}'
        with patch.object(cliente.opener, 'open', return_value=resposta) as abrir:
            self.assertEqual(cliente('/api/v1/sistema', prazo=100.25), {'ok': True})
            self.assertEqual(abrir.call_args.kwargs['timeout'], .25)
            self.assertEqual(cliente('/api/v1/sistema', prazo=200), {'ok': True})
            self.assertEqual(abrir.call_args.kwargs['timeout'], 5)
        self.assertEqual(self.budget.usadas, 2)
        self.assertEqual(self.pedidos, [])

    def test_prazo_expirado_recusa_antes_de_orcamento_e_io(self):
        tempo = Relogio(); tempo.tempo = 10
        cliente = ci.ClienteCi(self.origem, self.budget, relogio=tempo)
        with patch.object(cliente.opener, 'open') as abrir:
            for prazo in (9, 10):
                with self.subTest(prazo=prazo), self.assertRaises(ValueError):
                    cliente('/api/v1/sistema', prazo=prazo)
            abrir.assert_not_called()
        self.assertEqual(self.budget.usadas, 0)
        self.assertEqual(self.pedidos, [])

    def test_deadline_interrompe_corpo_lento_mesmo_recebendo_bytes(self):
        import signal
        import time
        type(self).modo = 'lenta'
        cliente = ci.ClienteCi(self.origem, self.budget)
        handler = signal.getsignal(signal.SIGALRM)
        inicio = time.monotonic()
        with self.assertRaises(ValueError):
            cliente('/api/v1/sistema', prazo=inicio + .15)
        self.assertLess(time.monotonic() - inicio, .8)
        self.assertEqual(self.budget.usadas, 1)
        self.assertEqual(len(self.pedidos), 1)
        self.assertEqual(signal.getsignal(signal.SIGALRM), handler)
        self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0, 0))

    def test_temporizador_e_handler_restaurados_apos_sucesso_e_http_erro(self):
        import signal
        cliente = ci.ClienteCi(self.origem, self.budget)
        handler = signal.getsignal(signal.SIGALRM)
        for modo in ('normal', 'erro'):
            type(self).modo = modo
            with self.subTest(modo=modo):
                if modo == 'erro':
                    with self.assertRaises(ValueError):
                        cliente('/api/v1/sistema')
                else:
                    self.assertEqual(cliente('/api/v1/sistema'), {'ok': True})
                self.assertEqual(signal.getsignal(signal.SIGALRM), handler)
                self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0, 0))

    def test_temporizador_preexistente_recusado_sem_alterar_handler_ou_fazer_io(self):
        import signal
        cliente = ci.ClienteCi(self.origem, self.budget)
        handler = signal.getsignal(signal.SIGALRM)
        anterior = signal.getitimer(signal.ITIMER_REAL)
        self.assertEqual(anterior, (0, 0))
        try:
            signal.setitimer(signal.ITIMER_REAL, 5)
            with patch.object(cliente.opener, 'open') as abrir:
                with self.assertRaises(ValueError):
                    cliente('/api/v1/sistema')
                abrir.assert_not_called()
            self.assertEqual(signal.getsignal(signal.SIGALRM), handler)
            restante, intervalo = signal.getitimer(signal.ITIMER_REAL)
            self.assertGreater(restante, 4)
            self.assertEqual(intervalo, 0)
            self.assertEqual(self.pedidos, [])
        finally:
            signal.setitimer(signal.ITIMER_REAL, *anterior)
            signal.signal(signal.SIGALRM, handler)


class PreflightAssinado(unittest.TestCase):
    endpoints = ('http://127.0.0.1:49101/-/ready', 'http://127.0.0.1:49102/api/health', 'http://127.0.0.1:49103/')

    def recursos(self):
        from contextlib import ExitStack
        from types import SimpleNamespace
        pilha = ExitStack()
        original = Path.read_text
        def ler(path, *args, **kwargs):
            valores = {'/proc/meminfo': 'MemAvailable: 2097152 kB\n',
                       '/sys/fs/cgroup/memory.max': 'max', '/sys/fs/cgroup/cpu.max': 'max 100000'}
            return valores[str(path)] if str(path) in valores else original(path, *args, **kwargs)
        pilha.enter_context(patch.object(Path, 'read_text', ler))
        pilha.enter_context(patch.object(carga.shutil, 'disk_usage', return_value=SimpleNamespace(free=2 * 1024**3)))
        pilha.enter_context(patch.object(carga.os, 'sched_getaffinity', return_value={0, 1}))
        return pilha

    def sondas(self):
        from unittest.mock import MagicMock
        resposta = MagicMock(); resposta.__enter__.return_value = resposta
        resposta.status = 200; resposta.read.return_value = b'{}'
        return resposta

    def test_portas_dinamicas_consumidas_antes_de_cada_sonda(self):
        eventos = []
        def abrir(url, timeout):
            eventos.append(('GET', url, timeout)); return self.sondas()
        with self.recursos(), patch.object(carga.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = abrir
            budget = carga.host_budget(self.endpoints, lambda: eventos.append(('consumir',)))
        self.assertEqual(eventos, [e for url in self.endpoints for e in [('consumir',), ('GET', url, 2)]])
        self.assertTrue(budget['telemetria']); self.assertEqual(budget['cpus'], 2)
        self.assertEqual(budget['memoriaDisponivelMiB'], 2048)

    def test_defaults_preservam_tres_sondas_legadas(self):
        with self.recursos(), patch.object(carga.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = self.sondas()
            carga.host_budget()
        self.assertEqual([c.args[0] for c in opener.return_value.open.call_args_list],
                         ['http://127.0.0.1:19090/-/ready', 'http://127.0.0.1:13000/api/health', 'http://127.0.0.1:14333/'])

    def test_origens_e_caminhos_de_telemetria_invalidos_nao_geram_http(self):
        invalidos = [self.endpoints[:2], ('http://localhost:49101/-/ready', *self.endpoints[1:]),
                     ('http://127.0.0.1:49101/api/health', *self.endpoints[1:]),
                     ('http://u:p@127.0.0.1:49101/-/ready', *self.endpoints[1:]),
                     ('http://127.0.0.1:49101/-/ready?x=1', *self.endpoints[1:])]
        with self.recursos(), patch.object(carga.urllib.request, 'build_opener') as opener:
            for endpoints in invalidos:
                with self.subTest(endpoints=endpoints), self.assertRaises(ValueError):
                    carga.host_budget(endpoints)
            opener.return_value.open.assert_not_called()

    def test_orcamento_esgotado_interrompe_antes_da_sonda(self):
        budget = ci.OrcamentoHttp(limite=13, reserva_limpeza=12); budget.consumir()
        with self.recursos(), patch.object(carga.urllib.request, 'build_opener') as opener:
            with self.assertRaises(ValueError):
                carga.host_budget(self.endpoints, budget.consumir)
            opener.return_value.open.assert_not_called()

    def test_atestado_assina_plano_instancia_medidas_e_validade_real(self):
        medidas = {'memoriaDisponivelMiB': 2048, 'discoLivreMiB': 2048, 'cpus': 2, 'telemetria': True}
        callback = lambda: None
        plano = plano_esperado('ORDENS'); antes = copy.deepcopy(plano)
        chave = b'k' * 32
        with patch.object(carga, 'host_budget', return_value=medidas) as medir, patch.object(carga, 'key', return_value=chave), patch.object(carga.time, 'time', return_value=12345):
            token = carga.authorization(plano, INSTANCIA, telemetria=self.endpoints, antes_http=callback)
        medir.assert_called_once_with(telemetria=self.endpoints, antes_http=callback)
        header, body, assinatura = token.split('.')
        decodificar = lambda t: base64.urlsafe_b64decode(t + '=' * (-len(t) % 4))
        claims = json.loads(decodificar(body))
        self.assertEqual(decodificar(assinatura), hmac.new(chave, (header + '.' + body).encode(), hashlib.sha256).digest())
        self.assertEqual((claims['iss'], claims['aud'], claims['iat'], claims['exp']), ('prospero-preflight', INSTANCIA, 12345, 12375))
        self.assertEqual(len(claims['nonce']), 32)
        digest = hashlib.sha256(json.dumps(plano, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
        self.assertEqual(claims['plano'], digest)
        self.assertTrue(all(claims[k] == v for k, v in medidas.items()))
        self.assertEqual(plano, antes)


class FalhasELimpeza(unittest.TestCase):
    def test_drenagem_confirma_resposta_terminal_do_quarto_get(self):
        tempo = Relogio(); consultas = []
        def req(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            if metodo == 'GET':
                consultas.append(caminho)
            terminal = len(consultas) == 4
            return {'estado': 'CANCELADA' if terminal else 'ENCERRANDO',
                    'contagens': {'emVoo': 0 if terminal else 1}}
        ci._drenar(req, IDENTIDADE, tempo.pausa, tempo)
        self.assertEqual(len(consultas), 4)

    def test_deadline_da_drenagem_inclui_post_e_eh_reutilizado_nos_gets(self):
        tempo = Relogio(); tempo.tempo = 100
        chamadas = []
        def req(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            chamadas.append((metodo, prazo, tempo()))
            if metodo == 'POST':
                tempo.tempo += 2
                return {'estado': 'ENCERRANDO', 'contagens': {'emVoo': 1}}
            return {'estado': 'CANCELADA', 'contagens': {'emVoo': 0}}
        ci._drenar(req, IDENTIDADE, tempo.pausa, tempo)
        self.assertEqual(chamadas, [('POST', 103, 100), ('GET', 103, 102.25)])

    def test_drenagem_nao_faz_get_quando_pausa_consumiu_prazo(self):
        tempo = Relogio(); chamadas = []
        def req(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            chamadas.append(metodo)
            return {'estado': 'ENCERRANDO', 'contagens': {'emVoo': 1}}
        def pausa(_segundos):
            tempo.tempo += 4
        with self.assertRaises(ValueError):
            ci._drenar(req, IDENTIDADE, pausa, tempo)
        self.assertEqual(chamadas, ['POST'])

    def test_drenagem_limita_pausa_ao_tempo_restante(self):
        tempo = Relogio(); pausas = []; metodos = []
        def req(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            metodos.append(metodo)
            tempo.tempo += 2.9
            return {'estado': 'ENCERRANDO', 'contagens': {'emVoo': 1}}
        def pausa(segundos):
            pausas.append(segundos); tempo.pausa(segundos)
        with self.assertRaises(ValueError):
            ci._drenar(req, IDENTIDADE, pausa, tempo)
        self.assertEqual(metodos, ['POST'])
        self.assertEqual(len(pausas), 1)
        self.assertAlmostEqual(pausas[0], .1)
        self.assertAlmostEqual(tempo(), 3)

    def test_polling_limita_pausa_ao_tempo_restante(self):
        tempo = Relogio(); pausas = []; consultas = []
        def req(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            if caminho.endswith('sistema'):
                return {'instancia': INSTANCIA}
            if caminho == '/api/v1/execucoes-carga':
                return {'id': IDENTIDADE}
            if limpeza:
                return {'estado': 'CANCELADA', 'contagens': {'emVoo': 0}}
            consultas.append(caminho); tempo.tempo += 7.9
            return {'estado': 'EXECUTANDO'}
        def pausa(segundos):
            pausas.append(segundos); tempo.pausa(segundos)
        with self.assertRaises(ValueError):
            ci.rodar_cenarios(req, lambda *args: 'atestado', pausa=pausa, relogio=tempo)
        self.assertEqual(len(consultas), 1)
        self.assertEqual(len(pausas), 1)
        self.assertAlmostEqual(pausas[0], .1)
        self.assertAlmostEqual(tempo(), 8)

    def test_polling_encaminha_deadline_absoluto_a_cada_get(self):
        tempo = Relogio(); prazos = []
        def req(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            if caminho.endswith('sistema'):
                return {'instancia': INSTANCIA}
            if caminho == '/api/v1/execucoes-carga':
                return {'id': IDENTIDADE}
            if limpeza:
                return {'estado': 'CANCELADA', 'contagens': {'emVoo': 0}}
            prazos.append(prazo)
            return {'estado': 'FALHA'}
        with self.assertRaises(ValueError):
            ci.rodar_cenarios(req, lambda *args: 'atestado', pausa=tempo.pausa, relogio=tempo)
        self.assertEqual(prazos, [8])

    def test_preflight_recusado_nao_inicia_execucao(self):
        eventos = []
        def req(caminho, *args, **kwargs):
            eventos.append(caminho); return {'instancia': INSTANCIA}
        def recusar(*args):
            raise ValueError('host sem margem')
        with self.assertRaises(ValueError):
            ci.rodar_cenarios(req, recusar)
        self.assertEqual(eventos, ['/api/v1/sistema'])

    def test_id_invalido_nao_gera_cancelamento_arbitrario(self):
        eventos = []
        def req(caminho, *args, **kwargs):
            eventos.append(caminho)
            return {'instancia': INSTANCIA} if caminho.endswith('sistema') else {'id': '../../ordens'}
        with self.assertRaises(ValueError):
            ci.rodar_cenarios(req, lambda *args: 'atestado')
        self.assertEqual(eventos, ['/api/v1/sistema', '/api/v1/execucoes-carga'])

    def test_cleanup_pendente_tem_quatro_polls_e_tres_segundos_no_maximo(self):
        eventos = []; tempo = Relogio()
        def req(caminho, metodo='GET', corpo=None, *, limpeza=False, prazo=None):
            eventos.append((caminho, metodo, limpeza))
            if caminho.endswith('sistema'):
                return {'instancia': INSTANCIA}
            if caminho == '/api/v1/execucoes-carga':
                return {'id': IDENTIDADE}
            if limpeza:
                return {'estado': 'EXECUTANDO', 'contagens': {'emVoo': 1}}
            return {'estado': 'FALHA'}
        with self.assertRaises(ValueError):
            ci.rodar_cenarios(req, lambda *args: 'atestado', pausa=tempo.pausa, relogio=tempo)
        limpeza = [e for e in eventos if e[2]]
        self.assertEqual(limpeza[0][1], 'POST')
        self.assertLessEqual(len([e for e in limpeza if e[1] == 'GET']), 4)
        self.assertLessEqual(tempo.tempo, 3)


class PrivadosECli(unittest.TestCase):
    def setUp(self):
        import shutil
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.raiz = Path(self.temp.name); scripts = self.raiz / 'scripts'; scripts.mkdir()
        for nome in ('testar-carga-integrada.py', 'carga.py', 'mercado.py', 'preparar-ambiente.py'):
            shutil.copyfile(Path(__file__).resolve().parents[1] / nome, scripts / nome)
        self.cli = scripts / 'testar-carga-integrada.py'
        self.privados = self.raiz / 'privados'; self.privados.mkdir(mode=0o700)
        self.financeira = 'sentinela-financeira-qa-chave-maior-32-bytes'
        (self.raiz / '.env').write_text('FO_JWT_CHAVE=' + self.financeira + '\n')

    def executar(self, *, projeto='prospero-ci-123-2', pasta=None, preparar=True, env=None):
        import subprocess
        comando = [sys.executable, '-B', str(self.cli), '--projeto', projeto, '--privados', str(pasta or self.privados)]
        if preparar:
            comando.append('--preparar')
        return subprocess.run(comando, capture_output=True, text=True, timeout=10, cwd=self.raiz,
                              env={**os.environ, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2', **(env or {})})

    def test_preparacao_emite_apenas_dois_segredos_e_cinco_bearers_minimos(self):
        resultado = self.executar()
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        self.assertNotIn(self.financeira, resultado.stdout + resultado.stderr)
        arquivos = set(p.name for p in self.privados.iterdir())
        self.assertEqual(arquivos, {'controle.key', 'preflight.key', 'mercado.token', 'ordens.token', 'risco.token', 'posicoes.token', 'razao.token', 'escrita.token'})
        chaves = [(self.privados / n).read_text() for n in ('controle.key', 'preflight.key')]
        self.assertTrue(all(len(c) >= 32 for c in chaves)); self.assertNotEqual(*chaves)
        grupos = {'mercado.token': ('prospero-front-office', 'mercado:leitura'),
                  'ordens.token': ('prospero-front-office', 'ordens:leitura'),
                  'risco.token': ('prospero-middle-office', 'risco:leitura'),
                  'posicoes.token': ('prospero-back-office', 'posicoes:leitura'),
                  'razao.token': ('prospero-back-office', 'razao:leitura'),
                  'escrita.token': ('prospero-front-office', 'ordens:enviar')}
        dec = lambda b: base64.urlsafe_b64decode(b + '=' * (-len(b) % 4))
        for arquivo in self.privados.iterdir():
            self.assertEqual(stat.S_IMODE(arquivo.stat().st_mode), 0o444)
            self.assertNotIn(self.financeira, arquivo.read_text())
            if arquivo.name not in grupos:
                continue
            token = arquivo.read_text(); header, body, signature = token.split('.')
            claims = json.loads(dec(body)); aud, grupo = grupos[arquivo.name]
            self.assertEqual((claims['aud'], claims['groups']), (aud, [grupo]))
            self.assertEqual(claims['exp'] - claims['iat'], ci.VALIDADE_TOKENS)
            if arquivo.name != 'mercado.token':
                self.assertEqual(claims['sub'], 'CONTA-002')
            self.assertEqual(dec(signature), hmac.new(self.financeira.encode(), (header + '.' + body).encode(), hashlib.sha256).digest())
            self.assertNotIn(token, resultado.stdout + resultado.stderr)

    def test_preparacao_nao_sobrescreve_nem_aceita_diretorio_aberto_ou_symlink(self):
        sentinela = self.privados / 'controle.key'; sentinela.write_text(SEGREDO)
        self.assertNotEqual(self.executar().returncode, 0)
        self.assertEqual(sentinela.read_text(), SEGREDO)
        sentinela.unlink(); self.privados.chmod(0o755)
        self.assertNotEqual(self.executar().returncode, 0); self.assertEqual(list(self.privados.iterdir()), [])
        self.privados.chmod(0o700)
        link = self.raiz / 'link'; link.symlink_to(self.privados, target_is_directory=True)
        self.assertNotEqual(self.executar(pasta=link).returncode, 0)
        self.assertEqual(list(self.privados.iterdir()), [])

    def test_namespace_divergente_ou_env_nao_numerico_recusado_antes_da_preparacao(self):
        for projeto, env in [('prospero-ci-999-1', {}), ('prospero-ci-123-2', {'GITHUB_RUN_ID': 'abc'}),
                             ('prospero-ci-123-2', {'GITHUB_RUN_ATTEMPT': ''}), ('horus-dev', {})]:
            with self.subTest(projeto=projeto, env=env):
                resultado = self.executar(projeto=projeto, env=env)
                self.assertNotEqual(resultado.returncode, 0)
                self.assertNotIn(self.financeira, resultado.stdout + resultado.stderr)
                self.assertNotIn('Traceback', resultado.stderr)
                self.assertEqual(list(self.privados.iterdir()), [])

    def test_erro_de_argumentos_nao_reflete_segredo(self):
        import subprocess
        resultado = subprocess.run([sys.executable, '-B', str(self.cli), '--invalido', SEGREDO],
                                   capture_output=True, text=True, timeout=10)
        self.assertNotEqual(resultado.returncode, 0)
        self.assertNotIn(SEGREDO, resultado.stdout + resultado.stderr)
        self.assertNotIn('Traceback', resultado.stderr)
