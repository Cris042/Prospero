import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import time
import unittest
from unittest import mock
from urllib.error import HTTPError

RAIZ = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("mercado", RAIZ / "scripts" / "mercado.py")
mercado = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mercado)


class PrepararCarteiraTest(unittest.TestCase):
    def setUp(self):
        self.respostas = []
        self.pedidos = []
        caso = self

        class Handler(BaseHTTPRequestHandler):
            def do_PUT(self):
                caso.pedidos.append((self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
                status, corpo = caso.respostas.pop(0) if len(caso.respostas) > 1 else caso.respostas[0]
                dados = corpo if isinstance(corpo, bytes) else json.dumps(corpo).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(dados)))
                self.end_headers()
                self.wfile.write(dados)

            def log_message(self, *_):
                pass

        self.servidor = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.servidor.serve_forever, kwargs={"poll_interval": 0.01})
        self.thread.start()
        self.origem = f"http://127.0.0.1:{self.servidor.server_port}"
        self.token = mock.patch.object(mercado, "token", return_value="token-sintetico")
        self.token.start()

    def tearDown(self):
        self.token.stop()
        self.servidor.shutdown()
        self.servidor.server_close()
        self.thread.join(timeout=2)

    def preparar(self, **kwargs):
        return mercado.preparar_carteira(self.origem, "CONTA-002", "1000.00", {"ALFA3": 100}, **kwargs)

    def test_repete_reserva_viva_transitoria_ate_preparar(self):
        self.respostas = [(409, {"codigo": "RESERVA_VIVA"}), (200, {"saldoTotal": "1000.00"})]
        self.assertEqual(self.preparar(prazo=1), {"saldoTotal": "1000.00"})
        self.assertEqual(len(self.pedidos), 2)
        self.assertEqual(self.pedidos[0], self.pedidos[1])
        self.assertEqual(self.pedidos[0][0], "/api/v1/preparacoes-risco/contas/CONTA-002")

    def test_prazo_padrao_nao_repete(self):
        self.respostas = [(409, {"codigo": "RESERVA_VIVA"}), (200, {})]
        with self.assertRaises(HTTPError) as erro:
            self.preparar()
        self.assertEqual(erro.exception.code, 409)
        erro.exception.close()
        self.assertEqual(len(self.pedidos), 1)

    def test_outro_conflito_e_erro_de_autorizacao_nao_repetem(self):
        for status, corpo in [(409, {"codigo": "CONTA_NAO_PREPARAVEL"}), (401, {"codigo": "RESERVA_VIVA"}),
                              (409, {"detail": "RESERVA_VIVA"}), (409, []), (409, b"nao-json")]:
            with self.subTest(status=status, corpo=corpo):
                self.pedidos.clear()
                self.respostas = [(status, corpo), (200, {})]
                with self.assertRaises(HTTPError) as erro:
                    self.preparar(prazo=1)
                self.assertEqual(erro.exception.code, status)
                erro.exception.close()
                self.assertEqual(len(self.pedidos), 1)

    def test_reserva_permanente_respeita_prazo(self):
        self.respostas = [(409, {"codigo": "RESERVA_VIVA"})]
        inicio = time.monotonic()
        with self.assertRaises((HTTPError, SystemExit)) as erro:
            self.preparar(prazo=0.05)
        if isinstance(erro.exception, HTTPError):
            erro.exception.close()
        self.assertLess(time.monotonic() - inicio, 1)
        self.assertGreaterEqual(len(self.pedidos), 1)


if __name__ == "__main__":
    unittest.main()
