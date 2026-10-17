"""Contratos de recusa do preflight; sem instalar ou acessar Docker/GitHub."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "runner", Path(__file__).resolve().parents[1] / "verificar-runner-dedicado.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class RequisitosRunnerTest(unittest.TestCase):
    def setUp(self):
        self.fatos = {
            "usuario": True, "plataforma": True, "home": True,
            "credenciais": True, "ferramentas": True, "node": True,
            "compose": True, "memoria": True, "disco": True,
            "docker_local": True, "docker_servidor": True,
            "docker_containers": True, "chromium": True,
        }

    def test_exige_atestado_mesmo_quando_inventario_esta_limpo(self):
        self.assertTrue(runner.validar(self.fatos))

    def test_aceita_requisitos_e_atestado_juntos(self):
        self.assertEqual([], runner.validar(self.fatos, atestado=True))

    def test_atestado_nao_supera_requisito_reprovado(self):
        for requisito in self.fatos:
            with self.subTest(requisito=requisito):
                fatos = dict(self.fatos, **{requisito: False})
                self.assertTrue(runner.validar(fatos, atestado=True))

    def test_dado_ausente_e_recusa(self):
        self.assertTrue(runner.validar({}, atestado=True))


class FronteirasRunnerTest(unittest.TestCase):
    def test_memoria_minima_nao_pode_ser_reduzida_por_ambiente(self):
        self.assertFalse(runner.memoria_suficiente(4095))
        self.assertTrue(runner.memoria_suficiente(4096))

    def test_daemon_recusa_container_sem_projeto_ou_de_outro_projeto(self):
        self.assertTrue(runner.containers_permitidos([]))
        self.assertTrue(runner.containers_permitidos([
            {"Labels": "com.docker.compose.project=prospero-ci-123-1"}]))
        for labels in ("", "com.docker.compose.project=horus", "com.docker.compose.project=prospero"):
            with self.subTest(labels=labels):
                self.assertFalse(runner.containers_permitidos([{"Labels": labels}]))

    def test_home_sem_credenciais_e_recusa_configuracao_pessoal(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            self.assertTrue(runner.sem_credenciais(home, {}))
            (home / ".netrc").write_text("segredo-sentinela")
            self.assertFalse(runner.sem_credenciais(home, {}))

    def test_config_docker_sem_autenticacao_nao_e_credencial(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / ".docker").mkdir()
            config = home / ".docker/config.json"
            config.write_text('{"currentContext":"default"}')
            self.assertTrue(runner.sem_credenciais(home, {}))
            config.write_text('{"auths":{"registry":{"auth":"segredo-sentinela"}}}')
            self.assertFalse(runner.sem_credenciais(home, {}))
            config.write_text("invalido")
            self.assertFalse(runner.sem_credenciais(home, {}))

    def test_recusa_token_e_agente_ssh_sem_expor_valores(self):
        with tempfile.TemporaryDirectory() as directory:
            for variable in ("GH_TOKEN", "GITHUB_TOKEN", "SSH_AUTH_SOCK"):
                with self.subTest(variable=variable):
                    self.assertFalse(runner.sem_credenciais(
                        Path(directory), {variable: "segredo-sentinela"}))


if __name__ == "__main__":
    unittest.main()
