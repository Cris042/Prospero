"""Superfícies de conta da CONTA-005 (medição): aceita onde há contas de negócio; ausente na tela, robô, Nginx e WebSocket."""
import re
import unittest
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
TODAS = frozenset(f"CONTA-00{n}" for n in range(1, 6))
LITERAL = re.compile(r"CONTA-00\d")

# Pontos que aceitam contas de negócio (listas de conta dos quatro serviços, carga, scripts e Go).
ACEITAM = [
    "back-office/src/main/java/br/prospero/bo/posicoes/helper/EnvelopeMatching.java",
    "back-office/src/main/java/br/prospero/bo/posicoes/actor/ConsultarPosicoesActor.java",
    "back-office/src/main/java/br/prospero/bo/razao/actor/ConsultarRazaoActor.java",
    "matching-engine/src/main/java/br/prospero/me/matching/helper/EnvelopeMatching.java",
    "middle-office/src/main/java/br/prospero/mo/matching/helper/EnvelopeMatching.java",
    "middle-office/src/main/java/br/prospero/mo/liquidacao/helper/EnvelopeLiquidacao.java",
    "middle-office/src/main/java/br/prospero/mo/liquidacao/actor/ConsultarCreditoActor.java",
    "front-office/src/main/java/br/prospero/fo/ordens/helper/EnvelopeMatching.java",
    "front-office/src/main/java/br/prospero/fo/ordens/helper/EnvelopeContabilizacao.java",
    "front-office/src/main/java/br/prospero/fo/ordens/jpa/RepositorioTrajetoriaJpa.java",
    "front-office/src/main/java/br/prospero/fo/atualizacoes/jpa/RepositorioRevisoesJpa.java",
    "ferramentas/carga/cenario.go",
    "scripts/mercado.py",
]

# Superfícies que não conhecem a CONTA-005: WebSocket (lista de quatro contas), robô formador, Nginx.
WEBSOCKET_SEM_005 = [
    "front-office/src/main/java/br/prospero/fo/atualizacoes/dominio/ControleSessoes.java",
    "front-office/src/main/java/br/prospero/fo/atualizacoes/websocket/VerificarUpgrade.java",
]
NGINX_SEM_005 = ["frontend/nginx.conf", "frontend/nginx.template", "frontend/proxy.conf.mjs"]


def contas_em(caminho):
    return frozenset(LITERAL.findall((RAIZ / caminho).read_text(encoding="utf-8")))


class ContasDeNegocio(unittest.TestCase):
    def test_pontos_que_aceitam_contas_de_negocio_conhecem_as_cinco_contas(self):
        for caminho in ACEITAM:
            with self.subTest(caminho=caminho):
                self.assertTrue(TODAS <= contas_em(caminho), f"{caminho} não conhece {sorted(TODAS - contas_em(caminho))}")

    def test_websocket_mantem_somente_as_contas_de_001_a_004(self):
        for caminho in WEBSOCKET_SEM_005:
            with self.subTest(caminho=caminho):
                self.assertEqual(contas_em(caminho), TODAS - {"CONTA-005"})

    def test_nginx_e_proxy_nao_conhecem_a_conta_005(self):
        for caminho in NGINX_SEM_005:
            with self.subTest(caminho=caminho):
                self.assertNotIn("CONTA-005", (RAIZ / caminho).read_text(encoding="utf-8"))

    def test_filtro_de_variaveis_do_nginx_nao_expoe_a_conta_005(self):
        compose = (RAIZ / "docker-compose.yml").read_text(encoding="utf-8")
        filtros = [linha for linha in compose.splitlines() if "NGINX_ENVSUBST_FILTER" in linha]
        self.assertTrue(filtros)
        for linha in filtros:
            with self.subTest(linha=linha):
                self.assertNotIn("005", linha)
                self.assertNotIn("CONTA", linha)

    def test_robo_formador_nao_conhece_a_conta_005(self):
        arquivos = sorted((RAIZ / "ferramentas/robo").glob("*.go"))
        self.assertTrue(arquivos)
        for arquivo in arquivos:
            with self.subTest(arquivo=arquivo.name):
                self.assertNotIn("CONTA-005", arquivo.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
