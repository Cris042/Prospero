import importlib.util
import unittest
from pathlib import Path

ESPEC = importlib.util.spec_from_file_location("medir", Path(__file__).resolve().parents[1] / "medir-rnf04.py")
medir = importlib.util.module_from_spec(ESPEC)
ESPEC.loader.exec_module(medir)


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


class Origens(unittest.TestCase):
    def test_somente_http_com_host(self):
        self.assertEqual(medir.origem_http("http://127.0.0.1:8081/"), "http://127.0.0.1:8081")
        for invalida in ("file:///etc/passwd", "ftp://h", "http://"):
            with self.assertRaises(medir.argparse.ArgumentTypeError):
                medir.origem_http(invalida)


if __name__ == "__main__":
    unittest.main()
