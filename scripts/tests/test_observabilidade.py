"""Fronteiras HTTP/Docker do smoke de observabilidade, sem stack financeira."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "observabilidade", Path(__file__).resolve().parents[1] / "verificar-observabilidade.py"
)
observabilidade = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(observabilidade)


class FronteiraHttp(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pedidos = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                cls.pedidos.append((self.path, self.headers.get("Authorization")))
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/destino-proibido")
                    self.end_headers()
                    return
                if self.path in {"/grande", "/grande-sem-tamanho", "/limite"}:
                    tamanho = 2 * 1024 * 1024 + (0 if self.path == "/limite" else 1)
                    body = b'"' + b"x" * (tamanho - 2) + b'"'
                    self.send_response(200)
                    if self.path != "/grande-sem-tamanho":
                        self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    try:
                        self.wfile.write(body)
                    except (BrokenPipeError, ConnectionResetError):
                        pass
                    return
                body = json.dumps({"status": "success", "data": [1, 2]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        self.pedidos.clear()

    def test_json_local_e_header_explicito(self):
        result = observabilidade.ler_json(self.base + "/api", {"Authorization": "Bearer sintetico"})
        self.assertEqual(result, {"status": "success", "data": [1, 2]})
        self.assertEqual(self.pedidos, [("/api", "Bearer sintetico")])

    def test_proxy_do_ambiente_nao_intercepta_requisicao(self):
        with patch.dict(os.environ, {"http_proxy": "http://127.0.0.1:9", "HTTP_PROXY": "http://127.0.0.1:9", "no_proxy": "", "NO_PROXY": ""}):
            self.assertEqual(observabilidade.ler_json(self.base + "/api")["status"], "success")

    def test_origens_invalidas_recusadas_antes_de_io(self):
        for url in ("http://example.com/api", "https://127.0.0.1:9090/api",
                    "http://localhost:9090/api", "http://127.0.0.2:9090/api",
                    "http://usuario:senha@127.0.0.1:9090/api", self.base + "/api#fragmento",
                    "file:///etc/passwd", "http://127.0.0.1/api"):
            with self.subTest(url=url), patch("socket.create_connection") as conectar:
                with self.assertRaises((ValueError, RuntimeError)):
                    observabilidade.ler_json(url)
                conectar.assert_not_called()

    def test_redirect_nao_seguido_nem_credencial_enviada_ao_destino(self):
        with self.assertRaises(Exception):
            observabilidade.ler_json(self.base + "/redirect", {"Authorization": "Bearer sintetico"})
        self.assertEqual(self.pedidos, [("/redirect", "Bearer sintetico")])

    def test_corpo_acima_de_dois_mib_recusado(self):
        for path in ("/grande", "/grande-sem-tamanho"):
            with self.subTest(path=path), self.assertRaises((ValueError, RuntimeError)):
                observabilidade.ler_json(self.base + path)

    def test_corpo_json_no_limite_aceito(self):
        self.assertEqual(len(observabilidade.ler_json(self.base + "/limite")), 2 * 1024 * 1024 - 2)


class FronteiraCompose(unittest.TestCase):
    def test_porta_publicada_loopback(self):
        result = subprocess.CompletedProcess([], 0, stdout="127.0.0.1:49123\n", stderr="")
        with patch("subprocess.run", return_value=result):
            self.assertEqual(observabilidade.origem_compose("prospero-ci-123-1", "prometheus", 9090), "http://127.0.0.1:49123")

    def test_identidades_nao_autorizadas_bloqueadas_antes_do_docker(self):
        for project, service, port in (("horus-dev", "prometheus", 9090),
                                       ("prospero-ci-1-1;id", "prometheus", 9090),
                                       ("prospero-ci-1-1", "postgres-front-office", 8080),
                                       ("prospero-ci-1-1", "grafana;id", 3000),
                                       ("prospero-ci-1-1", "prometheus", 22)):
            with self.subTest(project=project, service=service, port=port), patch("subprocess.run") as docker:
                with self.assertRaises((ValueError, RuntimeError)):
                    observabilidade.origem_compose(project, service, port)
                docker.assert_not_called()

    def test_saida_docker_externa_malformada_ou_ambigua_recusada(self):
        for output in ("0.0.0.0:49123\n", "[::]:49123\n", "127.0.0.1:0\n",
                       "127.0.0.1:65536\n", "127.0.0.1:abc\n", "",
                       "127.0.0.1:49123\n127.0.0.1:49124\n"):
            with self.subTest(output=output), patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout=output, stderr="")):
                with self.assertRaises((ValueError, RuntimeError)):
                    observabilidade.origem_compose("prospero-ci-123-1", "prometheus", 9090)


class SaudeDoDatasource(unittest.TestCase):
    def test_backend_grafana_com_erro_reprova_mesmo_com_prometheus_e_modelos_validos(self):
        saude = {"status": "OK"}
        modelos = {
            uid: json.loads((observabilidade.ROOT / "infra/grafana/dashboards" / (nome + ".json")).read_text())
            for nome, uid in observabilidade.PAINEIS.items()
        }

        def responder(url, cabecalhos=None):
            if url.endswith("/api/datasources/uid/prometheus/health"):
                return saude
            if "/api/v1/rules?" in url:
                return {"data": {"groups": [{"name": "prospero-operacao", "rules": [
                    {"name": nome, "health": "ok"} for nome in observabilidade.ALERTAS
                ]}]}}
            if "/api/v1/query?" in url:
                return {"status": "success", "data": {"resultType": "vector", "result": [
                    {"metric": {}, "value": [0, "1"]}
                ]}}
            if "/api/dashboards/uid/" in url:
                return {"meta": {"provisioned": True}, "dashboard": modelos[url.rsplit("/", 1)[1]]}
            raise AssertionError("Requisição não prevista pelo teste de fronteira.")

        with patch.object(observabilidade, "ler_json", side_effect=responder):
            # Controle positivo comprova que as respostas das demais fronteiras são suficientes.
            self.assertTrue(observabilidade.conferir("http://127.0.0.1:9090", "http://127.0.0.1:3000", {})["sucesso"])
            saude["status"] = "ERROR"
            with self.assertRaises(ValueError):
                observabilidade.conferir("http://127.0.0.1:9090", "http://127.0.0.1:3000", {})


if __name__ == "__main__":
    unittest.main()
