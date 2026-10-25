import base64
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch, call
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('testar_trajetoria', Path(__file__).parents[1] / 'testar-trajetoria.py')
trajetoria = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trajetoria)
TRACE = '0123456789abcdef0123456789abcdef'


def recurso(servico, nome, kind=5, trace=TRACE):
    return {'resource': {'attributes': [{'key': 'service.name', 'value': {'stringValue': servico}}]},
            'scopeSpans': [{'spans': [{'name': nome, 'kind': kind, 'traceId': trace, 'spanId': '0123456789abcdef'}]}]}


class ContratoTrajetoria(unittest.TestCase):
    def test_projeto_invalido_nao_chega_ao_docker(self):
        with patch.object(trajetoria.subprocess, 'check_output') as docker:
            with self.assertRaises(ValueError):
                trajetoria.AmbienteCi('horus')
            docker.assert_not_called()

    def test_label_alheia_recusa_antes_de_qualquer_mutacao(self):
        estado = [{'Config': {'Labels': {'com.docker.compose.project': 'horus',
                    'com.docker.compose.service': 'front-office'}}}]
        with patch.object(trajetoria.subprocess, 'check_output', side_effect=['a' * 64, json.dumps(estado)]), \
                patch.object(trajetoria.subprocess, 'run') as executar:
            with self.assertRaises(ValueError):
                trajetoria.AmbienteCi('prospero-ci-123-1')
            executar.assert_not_called()

    def test_falha_apos_parar_bo_restaura_worker_false(self):
        portas = {'front-office': 'http://127.0.0.1:8001', 'middle-office': 'http://127.0.0.1:8002',
                  'back-office': 'http://127.0.0.1:8003', 'jaeger': 'http://127.0.0.1:8004'}
        args = SimpleNamespace(projeto='prospero-ci-123-1', origem=portas['front-office'],
                               origem_mo=portas['middle-office'], origem_bo=portas['back-office'],
                               origem_jaeger=portas['jaeger'])
        ambiente = Mock()
        ambiente.origem.side_effect = lambda servico, porta=8080: portas[servico]
        def api(origem, caminho, *resto, **opcoes):
            if caminho == '/api/v1/posicoes':
                return {'posicoes': []}
            raise RuntimeError('falha sintética antes do primeiro envio')
        with patch.object(trajetoria, 'AmbienteCi', return_value=ambiente), \
                patch.object(trajetoria, 'preparar_carteira'), patch.object(trajetoria, 'token', return_value='teste'), \
                patch.object(trajetoria, 'requisitar', side_effect=api):
            with self.assertRaises(RuntimeError):
                trajetoria.verificar(args)
        self.assertEqual([call('back-office', 'stop'), call('back-office', 'recriar', worker=False)],
                         ambiente.executar.call_args_list)

    def test_mutacoes_restritas_ao_projeto_ci_exato(self):
        self.assertEqual('prospero-ci-123-1', trajetoria.exigir_projeto_ci('prospero-ci-123-1'))
        for projeto in (None, '', 'prospero', 'horus', 'prospero-ci-1', 'prospero-ci-1-1-extra', 'prospero-ci-1-1\n'):
            with self.subTest(projeto=projeto), self.assertRaises(ValueError):
                trajetoria.exigir_projeto_ci(projeto)

    def test_jaeger_v3_stream_exige_spans_reais_dos_quatro_servicos(self):
        linhas = [json.dumps({'result': {'resourceSpans': [recurso(servico, nome, kind)]}}) for servico, nome, kind in (
            ('front-office', 'POST /api/v1/ordens', 2), ('middle-office', 'risco.avaliar', 5),
            ('matching-engine', 'matching.consumir', 5), ('back-office', 'posicoes.consumir', 5))]
        self.assertEqual({'front-office', 'middle-office', 'matching-engine', 'back-office'},
                         trajetoria.servicos_processados(('\n'.join(linhas)).encode(), TRACE))

    def test_health_http_ou_trace_alheio_nao_sao_prova_de_processamento(self):
        dados = {'result': {'resourceSpans': [recurso('back-office', 'GET /q/health/ready', 2),
                recurso('matching-engine', 'matching.consumir', 5, 'a' * 32),
                recurso('middle-office', 'risco.avaliar', 2)]}}
        self.assertEqual(set(), trajetoria.servicos_processados(json.dumps(dados).encode(), TRACE))

    def test_otlp_traceid_bytes_base64_e_enum_textual_sao_interpretados(self):
        trace = base64.b64encode(bytes.fromhex(TRACE)).decode()
        dados = {'result': {'resourceSpans': [recurso('back-office', 'posicoes.consumir', 'SPAN_KIND_CONSUMER', trace)]}}
        self.assertEqual({'back-office'}, trajetoria.servicos_processados(json.dumps(dados).encode(), TRACE))

    def test_trace_invalido_ou_documento_excessivo_falham_controladamente(self):
        for corpo, trace in ((b'{}', 'invalido'), (b'x' * (4 * 1024 * 1024 + 1), TRACE), (b'nao-json', TRACE)):
            with self.subTest(trace=trace), self.assertRaises(ValueError):
                trajetoria.servicos_processados(corpo, trace)
