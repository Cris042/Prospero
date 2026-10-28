import importlib.util
import json
import math
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

ESPEC = importlib.util.spec_from_file_location("medir", Path(__file__).resolve().parents[1] / "medir-rnf04.py")
medir = importlib.util.module_from_spec(ESPEC)
ESPEC.loader.exec_module(medir)


class ClassificacaoEstrita(unittest.TestCase):
    def test_limite_superior_igual_a_meta_nao_comprova_desigualdade_estrita(self):
        for meta in (0.050, 0.001):
            with self.subTest(meta=meta):
                self.assertEqual(medir.classificar(meta, meta), "não cumprido")

    def test_valores_imediatamente_abaixo_e_acima_da_meta(self):
        for meta in (0.050, 0.001):
            with self.subTest(meta=meta):
                self.assertEqual(medir.classificar(math.nextafter(meta, 0), meta), "cumprido")
                self.assertEqual(medir.classificar(math.nextafter(meta, math.inf), meta), "não cumprido")

    def test_hospedado_permanece_indicativo_e_ausencia_permanece_sem_veredito(self):
        self.assertEqual(medir.classificar(0.050, 0.050, "ci-hospedado"), "indicativo: não cumprido")
        self.assertEqual(medir.classificar(0.049, 0.050, "ci-hospedado"), "indicativo: cumprido")
        self.assertIsNone(medir.classificar(None, 0.050))
        self.assertIsNone(medir.classificar(None, 0.050, "ci-hospedado"))


class MargemDeOrdens(unittest.TestCase):
    origem = "http://127.0.0.1:8081"
    token = "bearer-sintetico-nao-refletir"

    def pagina(self, inicio=1, quantidade=0, cursor=None):
        return {"ordens": [{"id": str(uuid.UUID(int=i)), "estado": "CANCELADA",
                            "dataSessao": "2020-01-01"}
                           for i in range(inicio, inicio + quantidade)], "proximoCursor": cursor}

    def paginas(self, quantidade):
        return [self.pagina(inicio + 1, min(100, quantidade - inicio),
                            str(inicio + 100) if inicio + 100 < quantidade else None)
                for inicio in range(0, quantidade, 100)] or [self.pagina()]

    def conferir_leituras(self, chamadas):
        for chamada in chamadas:
            self.assertEqual(chamada.args[0], "GET")
            self.assertEqual(chamada.args[2], self.token)
            url = urlsplit(chamada.args[1])
            self.assertEqual((url.scheme, url.netloc, url.path, url.fragment),
                             ("http", "127.0.0.1:8081", "/api/v1/ordens", ""))
            self.assertEqual(parse_qs(url.query)["limite"], ["100"])
            self.assertTrue(len(chamada.args) <= 3 or chamada.args[3] is None)
        self.assertLessEqual(len(chamadas), 10)

    def test_vazio_aceita_necessidade_entre_um_e_mil_sem_preparar_ou_escrever(self):
        for necessarias in (1, 1000):
            with self.subTest(necessarias=necessarias), patch.object(medir.mercado, "http", return_value=self.pagina()) as http, patch.object(medir.mercado, "preparar_carteira") as preparar:
                self.assertEqual(medir.verificar_orcamento(self.origem, self.token, necessarias), 0)
                self.assertEqual(http.call_count, 1)
                self.conferir_leituras(http.call_args_list)
                preparar.assert_not_called()

    def test_conta_todas_as_ordens_inclusive_encerradas_ou_de_outra_data(self):
        with patch.object(medir.mercado, "http", return_value=self.pagina(1, 3)) as http:
            self.assertEqual(medir.verificar_orcamento(self.origem, self.token, 997), 3)
            self.conferir_leituras(http.call_args_list)

    def test_fronteira_mil_e_estouro_de_uma_ordem(self):
        for necessarias, recusa in ((1, False), (2, True)):
            with self.subTest(necessarias=necessarias), patch.object(medir.mercado, "http", side_effect=self.paginas(999)) as http:
                if recusa:
                    with self.assertRaises(SystemExit):
                        medir.verificar_orcamento(self.origem, self.token, necessarias)
                else:
                    self.assertEqual(medir.verificar_orcamento(self.origem, self.token, necessarias), 999)
                    self.assertEqual(http.call_count, 10)
                self.conferir_leituras(http.call_args_list)

    def test_cursor_e_opaco_url_encoded_e_limite_de_2048_aceito(self):
        cursor = "a+/=&?cursor=outro/#" + "x" * (2048 - len("a+/=&?cursor=outro/#"))
        with patch.object(medir.mercado, "http", side_effect=[self.pagina(1, 1, cursor), self.pagina(2, 1)]) as http:
            self.assertEqual(medir.verificar_orcamento(self.origem, self.token, 1), 2)
            self.assertEqual(parse_qs(urlsplit(http.call_args_list[1].args[1]).query)["cursor"], [cursor])
            self.conferir_leituras(http.call_args_list)

    def test_necessidade_invalida_recusada_antes_de_io(self):
        for valor in (0, -1, 1001, 1.5, "1", None, True):
            with self.subTest(valor=valor), patch.object(medir.mercado, "http") as http:
                with self.assertRaises(SystemExit):
                    medir.verificar_orcamento(self.origem, self.token, valor)
                http.assert_not_called()

    def test_shape_invalido_ou_pagina_acima_de_cem_recusados(self):
        for pagina in (None, [], {}, {"ordens": []}, {"ordens": None, "proximoCursor": None},
                       {"ordens": [None], "proximoCursor": None}, self.pagina(1, 101)):
            with self.subTest(pagina=type(pagina).__name__), patch.object(medir.mercado, "http", return_value=pagina):
                with self.assertRaises(SystemExit):
                    medir.verificar_orcamento(self.origem, self.token, 1)

    def test_ids_nao_canonicos_e_duplicados_falham_fechado(self):
        for id_invalido in (None, 1, True, "nao-uuid", "ABCDEFAB-CDEF-4ABC-8ABC-ABCDEFABCDEF"):
            with self.subTest(id=id_invalido), patch.object(medir.mercado, "http", return_value={"ordens": [{"id": id_invalido}], "proximoCursor": None}):
                with self.assertRaises(SystemExit):
                    medir.verificar_orcamento(self.origem, self.token, 1)
        duplicado = self.pagina(1, 1)
        duplicado["ordens"] *= 2
        for paginas in ([duplicado], [self.pagina(1, 1, "proxima"), self.pagina(1, 1)]):
            with self.subTest(paginas=len(paginas)), patch.object(medir.mercado, "http", side_effect=paginas):
                with self.assertRaises(SystemExit):
                    medir.verificar_orcamento(self.origem, self.token, 1)

    def test_cursor_invalido_ou_ciclo_recusado_sem_uma_nova_consulta(self):
        for cursor in ("x" * 2049, "", 1, True):
            with self.subTest(cursor=type(cursor).__name__), patch.object(medir.mercado, "http", return_value=self.pagina(1, 1, cursor)) as http:
                with self.assertRaises(SystemExit):
                    medir.verificar_orcamento(self.origem, self.token, 1)
                self.assertEqual(http.call_count, 1)
        with patch.object(medir.mercado, "http", side_effect=[self.pagina(1, 1, "A"), self.pagina(2, 1, "B"), self.pagina(3, 1, "A")]) as http:
            with self.assertRaises(SystemExit):
                medir.verificar_orcamento(self.origem, self.token, 1)
            self.assertEqual(http.call_count, 3)

    def test_nao_consulta_decima_primeira_pagina_sem_margem_comprovada(self):
        paginas = [self.pagina(i + 1, 1, f"pagina-{i + 1}") for i in range(10)]
        with patch.object(medir.mercado, "http", side_effect=paginas) as http:
            with self.assertRaises(SystemExit):
                medir.verificar_orcamento(self.origem, self.token, 1)
            self.assertEqual(http.call_count, 10)

    def test_falha_de_leitura_recusa_sem_refletir_credencial(self):
        with patch.object(medir.mercado, "http", side_effect=ConnectionError(self.token)):
            with self.assertRaises(SystemExit) as erro:
                medir.verificar_orcamento(self.origem, self.token, 1)
            self.assertNotIn(self.token, str(erro.exception))


class CompradoresPreparaveis(unittest.TestCase):
    def test_recusa_conta_nao_preparavel_antes_de_qualquer_http(self):
        for conta in ("CONTA-001", "CONTA-003", "CONTA-004"):
            argumentos = SimpleNamespace(origem="http://127.0.0.1:8081", origem_mo="http://127.0.0.1:8082",
                                         origem_me="http://127.0.0.1:8083", ticker="ALFA3", preco="10.00",
                                         amostras=3, aquecimento=2, contexto="ci-hospedado",
                                         compradores=f"CONTA-002,{conta}", origem_jaeger=None)
            with patch.object(medir.mercado, "http", side_effect=AssertionError("HTTP antes da validação")):
                with self.assertRaises(SystemExit) as erro:
                    medir.executar(argumentos)
            self.assertIn("preparáveis", str(erro.exception))


class MargemAntesDePreparar(unittest.TestCase):
    def argumentos(self):
        return SimpleNamespace(origem="http://127.0.0.1:8081", origem_mo="http://127.0.0.1:8082",
                               origem_me="http://127.0.0.1:8083", ticker="ALFA3", preco="10.00",
                               amostras=3, aquecimento=2, contexto="ci-hospedado",
                               compradores="CONTA-002", origem_jaeger=None)

    def test_verifica_comprador_e_vendedor_antes_da_primeira_preparacao(self):
        class InterromperAntesDaPreparacao(Exception):
            pass
        eventos = []

        def verificar(origem, token, necessarias):
            self.assertEqual(origem, self.argumentos().origem)
            eventos.append((token, necessarias))
            return 0

        def preparar(*args, **kwargs):
            eventos.append("preparar")
            raise InterromperAntesDaPreparacao()

        with patch.object(medir.mercado, "token", side_effect=lambda escopo, validade, conta: "token-" + conta), patch.object(medir, "verificar_orcamento", side_effect=verificar), patch.object(medir.mercado, "preparar_carteira", side_effect=preparar):
            with self.assertRaises(InterromperAntesDaPreparacao):
                medir.executar(self.argumentos())
        self.assertCountEqual(eventos[:2], [("token-CONTA-002", 5), ("token-CONTA-003", 1)])
        self.assertEqual(eventos[2:], ["preparar"])

    def test_recusa_de_margem_impede_preparacao_e_envio(self):
        with patch.object(medir.mercado, "token", return_value="token-sintetico"), patch.object(medir, "verificar_orcamento", side_effect=SystemExit("margem insuficiente")), patch.object(medir.mercado, "preparar_carteira") as preparar, patch.object(medir.mercado, "http", side_effect=SystemExit("envio interceptado pelo teste")) as http:
            with self.assertRaises(SystemExit):
                medir.executar(self.argumentos())
            preparar.assert_not_called()
            http.assert_not_called()


class PercentilPorBuckets(unittest.TestCase):
    def test_diferenca_exclui_aquecimento_e_usa_limite_superior(self):
        antes = {0.001: 5, 0.05: 10, float("inf"): 10}
        depois = {0.001: 5, 0.05: 109, float("inf"): 110}
        self.assertEqual(medir.percentil(antes, depois, 0.50), (0.05, 100))
        self.assertEqual(medir.percentil(antes, depois, 0.99), (0.05, 100))
        self.assertEqual(medir.percentil(antes, depois, 1.0), (float("inf"), 100))

    def test_sem_amostras_novas(self):
        self.assertEqual(medir.percentil({float("inf"): 3}, {float("inf"): 3}, 0.99), (None, 0))


class LeituraDosBuckets(unittest.TestCase):
    def test_exemplar_nao_substitui_a_contagem(self):
        # Formato observado no /q/metrics do Quarkus com OpenTelemetry: exemplar após a contagem do bucket.
        texto = "\n".join([
            '# TYPE x_seconds histogram',
            'x_seconds_bucket{le="0.05"} 0.0',
            'x_seconds_bucket{le="0.357913941"} 20.0 # {span_id="4d52",trace_id="1f94"} 0.3 1792683254.640',
            'x_seconds_bucket{application="fo",le="+Inf"} 20.0',
            'x_seconds_count 20.0',
            'x_seconds_sum 6.0',
            'outro_seconds_bucket{le="0.05"} 7.0',
        ])
        self.assertEqual(medir.ler_buckets(texto, "x_seconds"),
                         {"soma": 6.0, "contagem": 20.0, "ignoradas": 0, 0.05: 0.0, 0.357913941: 20.0, float("inf"): 20.0})

    def test_series_somadas_por_le_e_rotulo_terminado_em_le_nao_confunde(self):
        texto = "\n".join([
            'x_bucket{handle="a",le="0.1"} 3',
            'x_bucket{handle="b",uri="/a}b",le="0.1"} 4',
            'x_bucket{handle="a",le="+Inf"} 3',
            'x_bucket{handle="b",uri="/a}b",le="+Inf"} 5',
        ])
        self.assertEqual(medir.ler_buckets(texto, "x"),
                         {"soma": None, "contagem": None, "ignoradas": 0, 0.1: 7.0, float("inf"): 8.0})

    def test_linhas_malformadas_sao_ignoradas_e_contadas(self):
        texto = "\n".join(['x_bucket{handle="a"} 3', 'x_bucket{le="abc"} 1', 'x_bucket{le="0.1"} NaN',
                           'x_bucket{le="0.1"}', 'x_bucket{le="+Inf"} 2'])
        self.assertEqual(medir.ler_buckets(texto, "x"),
                         {"soma": None, "contagem": None, "ignoradas": 3, float("inf"): 2.0})


class MediaIndependente(unittest.TestCase):
    def test_media_por_sum_e_count(self):
        self.assertAlmostEqual(medir.media_independente({"soma": 1.0, "contagem": 10}, {"soma": 3.0, "contagem": 30}), 0.1)

    def test_sem_sum_ou_contador_reiniciado_nao_inventa_media(self):
        self.assertIsNone(medir.media_independente({"soma": None, "contagem": 1}, {"soma": None, "contagem": 2}))
        self.assertIsNone(medir.media_independente({"soma": 5.0, "contagem": 50}, {"soma": 1.0, "contagem": 4}))


class BucketFinoNaMeta(unittest.TestCase):
    META = 0.050

    def test_limite_exato_em_50_ms_nao_comprova_e_45_ms_comprova(self):
        antes = {0.045: 0, 0.05: 0, 0.06: 0, float("inf"): 0}
        no_limite = {0.045: 90, 0.05: 995, 0.06: 1000, float("inf"): 1000}
        self.assertEqual(medir.percentil(antes, no_limite, 0.99), (0.05, 1000))
        self.assertEqual(medir.classificar(0.05, self.META), "não cumprido")
        abaixo = {0.045: 995, 0.05: 1000, 0.06: 1000, float("inf"): 1000}
        self.assertEqual(medir.percentil(antes, abaixo, 0.99), (0.045, 1000))
        self.assertEqual(medir.classificar(0.045, self.META), "cumprido")

    def test_resolucao_exige_bucket_exatamente_na_meta(self):
        self.assertTrue(medir.tem_resolucao({0.045: 1, 0.05: 1, float("inf"): 1}, self.META))
        self.assertFalse(medir.tem_resolucao({0.0671: 1, float("inf"): 1}, self.META))
        self.assertFalse(medir.tem_resolucao({"soma": 1.0, float("inf"): 1}, self.META))


class PlanoDeAmostras(unittest.TestCase):
    def test_dedicado_recusa_menos_de_mil_amostras(self):
        for amostras in (1, 150, 999):
            with self.subTest(amostras=amostras), self.assertRaises(SystemExit):
                medir.validar_plano(amostras, 30, ["CONTA-002", "CONTA-001"], "dedicado")
        self.assertEqual(medir.validar_plano(1000, 30, ["CONTA-002", "CONTA-001"], "dedicado"), 515)

    def test_ci_hospedado_aceita_plano_menor_mas_respeita_o_teto_por_conta(self):
        self.assertEqual(medir.validar_plano(10, 0, ["CONTA-002"], "ci-hospedado"), 10)
        with self.assertRaises(SystemExit):
            medir.validar_plano(1000, 30, ["CONTA-002"], "ci-hospedado")

    def test_linha_de_comando_dedicada_com_150_amostras_e_recusada(self):
        with self.assertRaises(SystemExit):
            medir.main(["--amostras", "150"])


class AtribuicaoPorSalto(unittest.TestCase):
    TRACE = "0123456789abcdef0123456789abcdef"

    def linha(self, servico, nome, inicio_ms, fim_ms):
        span = {"traceId": self.TRACE, "name": nome, "startTimeUnixNano": str(inicio_ms * 1_000_000),
                "endTimeUnixNano": str(fim_ms * 1_000_000)}
        recurso = {"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": servico}}]},
                   "scopeSpans": [{"spans": [span, {**span, "name": "outro"}]}]}
        return json.dumps({"result": {"resourceSpans": [recurso]}})

    def test_extrai_somente_os_spans_de_entrada(self):
        texto = "\n".join([self.linha("front-office", "POST /api/v1/ordens", 100, 112),
                           self.linha("matching-engine", "matching.consumir", 125, 126),
                           self.linha("middle-office", "nome-errado", 110, 120)])
        achados = medir.duracoes_por_salto(texto, self.TRACE)
        self.assertEqual(set(achados), {"front-office", "matching-engine"})
        self.assertAlmostEqual(achados["front-office"][1], 0.012)
        self.assertEqual(medir.duracoes_por_salto(texto, "f" * 32), {})

    def test_agrega_duracao_e_atraso_de_inicio(self):
        traces = [{"front-office": (0, 0.010), "matching-engine": (20_000_000, 0.001)},
                  {"front-office": (0, 0.030), "matching-engine": (40_000_000, 0.003)}]
        resultado = medir.atribuicao(traces)
        self.assertEqual(resultado["traces_consultados"], 2)
        self.assertEqual(resultado["saltos"]["matching-engine"]["inicio_apos_post_p99_s"], 0.04)
        self.assertEqual(resultado["saltos"]["front-office"]["duracao_p50_s"], 0.03)
        self.assertEqual(resultado["saltos"]["back-office"]["traces"], 0)


class Origens(unittest.TestCase):
    def test_somente_http_com_host(self):
        self.assertEqual(medir.origem_http("http://127.0.0.1:8081/"), "http://127.0.0.1:8081")
        for invalida in ("file:///etc/passwd", "ftp://h", "http://"):
            with self.assertRaises(medir.argparse.ArgumentTypeError):
                medir.origem_http(invalida)


if __name__ == "__main__":
    unittest.main()
