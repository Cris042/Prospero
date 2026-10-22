import base64
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock

RAIZ = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preparar_ambiente", RAIZ / "scripts" / "preparar-ambiente.py")
ambiente = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ambiente)


def decodificar(parte):
    return base64.urlsafe_b64decode(parte + "=" * (-len(parte) % 4))


class PrepararAmbienteTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.env = Path(self.dir.name) / ".env"
        self.patch = mock.patch.object(ambiente, "path", self.env)
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.dir.cleanup()

    def test_cria_todas_as_chaves_com_permissao_privada(self):
        ambiente.main()
        valores = ambiente.ler(self.env)
        for chave in ambiente.SENHAS + ["FO_JWT_CHAVE", "FO_JWT_JWK", "FO_TOKEN_LEITURA", "FO_TOKEN_ORDENS"]:
            self.assertIn(chave, valores)
        self.assertEqual(stat.S_IMODE(os.stat(self.env).st_mode), 0o600)
        self.assertGreaterEqual(len(valores["FO_JWT_CHAVE"]), 32)

    def test_preserva_existentes_e_acrescenta_ausentes(self):
        self.env.write_text("FO_DB_PASSWORD=antiga\n")
        ambiente.main()
        primeira = ambiente.ler(self.env)
        self.assertEqual(primeira["FO_DB_PASSWORD"], "antiga")
        ambiente.main()
        self.assertEqual(ambiente.ler(self.env), primeira)

    def test_token_de_leitura_e_jwk_correspondem_a_chave(self):
        ambiente.main()
        valores = ambiente.ler(self.env)
        cabecalho, corpo, assinatura = valores["FO_TOKEN_LEITURA"].split(".")
        esperado = hmac.new(valores["FO_JWT_CHAVE"].encode(), f"{cabecalho}.{corpo}".encode(), hashlib.sha256).digest()
        self.assertEqual(decodificar(assinatura), esperado)
        self.assertEqual(json.loads(decodificar(cabecalho))["alg"], "HS256")
        claims = json.loads(decodificar(corpo))
        self.assertEqual(claims["groups"], ["mercado:leitura"])
        self.assertEqual((claims["iss"], claims["aud"]), ("prospero-local", "prospero-front-office"))
        jwk = json.loads(decodificar(valores["FO_JWT_JWK"]))["keys"][0]
        self.assertEqual((jwk["kty"], jwk["alg"]), ("oct", "HS256"))
        self.assertEqual(decodificar(jwk["k"]), valores["FO_JWT_CHAVE"].encode())


class TokenOrdensTest(PrepararAmbienteTest):
    def test_token_de_ordens_da_conta_demo_com_validade_curta_e_renovacao(self):
        ambiente.main()
        valores = ambiente.ler(self.env)
        claims = json.loads(decodificar(valores["FO_TOKEN_ORDENS"].split(".")[1]))
        self.assertEqual(claims["sub"], "CONTA-001")
        self.assertEqual(sorted(claims["groups"]), ["ordens:enviar", "ordens:leitura"])
        self.assertLessEqual(claims["exp"] - claims["iat"], 7 * 24 * 3600)
        # Token prestes a expirar é regenerado; os demais valores ficam intactos.
        prestes = ambiente.jwt({"exp": 1}, valores["FO_JWT_CHAVE"])
        self.env.write_text(self.env.read_text().replace(valores["FO_TOKEN_ORDENS"], prestes))
        ambiente.main()
        renovados = ambiente.ler(self.env)
        self.assertNotEqual(renovados["FO_TOKEN_ORDENS"], prestes)
        self.assertEqual(renovados["FO_TOKEN_LEITURA"], valores["FO_TOKEN_LEITURA"])


class TokenRoboTest(PrepararAmbienteTest):
    def test_token_do_robo_formador_restrito_a_conta_004_e_renovado(self):
        ambiente.main()
        valores = ambiente.ler(self.env)
        claims = json.loads(decodificar(valores["FO_TOKEN_ROBO"].split(".")[1]))
        self.assertEqual(claims["sub"], "CONTA-004")
        self.assertEqual(sorted(claims["groups"]), ["ordens:enviar", "ordens:leitura"])
        self.assertEqual(claims["aud"], "prospero-front-office")
        self.assertLessEqual(claims["exp"] - claims["iat"], 7 * 24 * 3600)
        prestes = ambiente.jwt({"exp": 1}, valores["FO_JWT_CHAVE"])
        self.env.write_text(self.env.read_text().replace(valores["FO_TOKEN_ROBO"], prestes))
        ambiente.main()
        renovados = ambiente.ler(self.env)
        self.assertNotEqual(renovados["FO_TOKEN_ROBO"], prestes)
        self.assertEqual(renovados["FO_TOKEN_ORDENS"], valores["FO_TOKEN_ORDENS"])
        self.assertEqual((self.env.parent / ".robo-local" / "token").read_text(), renovados["FO_TOKEN_ROBO"])

    def test_token_do_robo_exportado_para_bind_do_uid_do_container(self):
        ambiente.main()
        privado = self.env.parent / ".robo-local"
        arquivo = privado / "token"
        self.assertEqual(stat.S_IMODE(os.stat(privado).st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(os.stat(arquivo).st_mode), 0o444)
        self.assertEqual(arquivo.read_text(), ambiente.ler(self.env)["FO_TOKEN_ROBO"])
        arquivo.chmod(0o600)
        arquivo.write_text("adulterado")
        ambiente.main()
        self.assertEqual(arquivo.read_text(), ambiente.ler(self.env)["FO_TOKEN_ROBO"])

    def test_symlink_no_lugar_do_diretorio_privado_falha_fechado(self):
        alvo = Path(self.dir.name) / "alheio"
        alvo.mkdir()
        (self.env.parent / ".robo-local").symlink_to(alvo)
        with self.assertRaises(SystemExit):
            ambiente.main()
        self.assertEqual(list(alvo.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
