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


if __name__ == "__main__":
    unittest.main()
