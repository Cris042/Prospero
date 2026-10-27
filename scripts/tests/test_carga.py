"""Contratos CLI de carga com segredos exclusivamente sintéticos em diretório descartável."""
import base64
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
FILES = {"mercado.token": ("prospero-front-office", "mercado:leitura"),
         "ordens.token": ("prospero-front-office", "ordens:leitura"),
         "risco.token": ("prospero-middle-office", "risco:leitura"),
         "posicoes.token": ("prospero-back-office", "posicoes:leitura"),
         "razao.token": ("prospero-back-office", "razao:leitura")}


class CargaCli(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("carga.py", "mercado.py", "preparar-ambiente.py"):
            shutil.copyfile(SCRIPTS / name, scripts / name)
        self.cli = scripts / "carga.py"
        self.private = self.root / ".carga-local"
        self.secret = "sentinela-financeira-qa-chave-mais-de-32-bytes"

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(self.cli), *args], text=True,
                              capture_output=True, timeout=10, cwd=self.root)

    def assert_ok(self, result):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(self.secret, result.stdout + result.stderr)

    def test_preflight_cenario_vazio_usa_hash_legado_sem_mutar_plano(self):
        self.assert_ok(self.run_cli("preparar"))
        spec = importlib.util.spec_from_file_location("carga_qa", self.cli)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.host_budget = lambda: {"memoriaDisponivelMiB": 2048, "discoLivreMiB": 2048, "cpus": 2, "telemetria": True}
        original = {"servico": "front-office", "cenario": "", "estagios": [{"duracaoMs": 100}]}
        before = json.dumps(original, sort_keys=True)
        token = module.authorization(original, "instancia-qa")
        body = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
        normalized = {k: v for k, v in original.items() if k != "cenario"}
        expected = hashlib.sha256(json.dumps(normalized, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.assertEqual(claims["plano"], expected)
        self.assertEqual(json.dumps(original, sort_keys=True), before)
        normal_token = module.authorization(normalized, "instancia-qa")
        normal_body = normal_token.split(".")[1]
        normal_claims = json.loads(base64.urlsafe_b64decode(normal_body + "=" * (-len(normal_body) % 4)))
        self.assertEqual(normal_claims["plano"], claims["plano"])

    def test_preparar_sem_env_preserva_chaves_e_cria_placeholders(self):
        self.assert_ok(self.run_cli("preparar"))
        original = {n: (self.private / n).read_bytes() for n in ("controle.key", "preflight.key")}
        for name in FILES:
            self.assertEqual((self.private / name).read_bytes(), b"")
            self.assertEqual(stat.S_IMODE((self.private / name).stat().st_mode), 0o444)
        self.assertEqual(stat.S_IMODE(self.private.stat().st_mode), 0o700)
        self.assert_ok(self.run_cli("preparar"))
        self.assertEqual(original, {n: (self.private / n).read_bytes() for n in original})
        self.assertFalse((self.root / ".env").exists())

    def test_emissao_audiencia_escopo_conta_e_sigilo(self):
        (self.root / ".env").write_text("FO_JWT_CHAVE=" + self.secret + "\n")
        self.assert_ok(self.run_cli("preparar"))
        control = (self.private / "controle.key").read_bytes()
        result = self.run_cli("preparar-financeiro", "--conta", "CONTA-003", "--validade", "120")
        self.assert_ok(result)
        for name, (aud, group) in FILES.items():
            token = (self.private / name).read_text().strip()
            self.assertNotIn(token, result.stdout + result.stderr)
            header, body, signature = token.split(".")
            claims = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
            self.assertEqual(claims["aud"], aud)
            self.assertEqual(claims["groups"], [group])
            self.assertEqual(claims["exp"] - claims["iat"], 120)
            if name != "mercado.token":
                self.assertEqual(claims["sub"], "CONTA-003")
            expected = hmac.new(self.secret.encode(), (header + "." + body).encode(), hashlib.sha256).digest()
            self.assertEqual(base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4)), expected)
            self.assertEqual(stat.S_IMODE((self.private / name).stat().st_mode), 0o444)
        self.assertEqual((self.private / "controle.key").read_bytes(), control)

    def test_conta_e_validade_invalidas_nao_emitem(self):
        (self.root / ".env").write_text("FO_JWT_CHAVE=" + self.secret + "\n")
        self.assert_ok(self.run_cli("preparar"))
        for args in (("--conta", "CONTA-001"), ("--conta", "CONTA-999"),
                     ("--validade", "0"), ("--validade", "3601")):
            result = self.run_cli("preparar-financeiro", *args)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn(self.secret, result.stdout + result.stderr)
            for name in FILES:
                self.assertEqual((self.private / name).read_bytes(), b"")

    def test_emissao_sem_chave_nao_modifica_placeholders(self):
        self.assert_ok(self.run_cli("preparar"))
        result = self.run_cli("preparar-financeiro")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(self.secret, result.stdout + result.stderr)
        for name in FILES:
            self.assertEqual((self.private / name).read_bytes(), b"")

    def test_defaults_sao_conta_teste_e_dez_minutos(self):
        (self.root / ".env").write_text("FO_JWT_CHAVE=" + self.secret + "\n")
        self.assert_ok(self.run_cli("preparar-financeiro"))
        token = (self.private / "ordens.token").read_text().strip()
        body = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
        self.assertEqual(claims["sub"], "CONTA-002")
        self.assertEqual(claims["exp"] - claims["iat"], 600)

    def test_symlink_placeholder_nao_sobrescreve_arquivo_externo(self):
        self.private.mkdir()
        outside = self.root / "externo"
        outside.write_text("preservar")
        (self.private / "mercado.token").symlink_to(outside)
        result = self.run_cli("preparar")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(outside.read_text(), "preservar")


if __name__ == "__main__":
    unittest.main()
