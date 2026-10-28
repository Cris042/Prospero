"""Manifesto SHA-256 das fixtures de contrato: aceita o consistente e recusa alteração, extra e ausente."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "contratos", Path(__file__).resolve().parents[1] / "verificar-contratos.py"
)
contratos = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contratos)


def montar(base: Path, arquivos: dict[str, bytes]) -> None:
    linhas = []
    for nome, conteudo in arquivos.items():
        destino = base / nome
        destino.parent.mkdir(parents=True, exist_ok=True)
        destino.write_bytes(conteudo)
        linhas.append(f"{hashlib.sha256(conteudo).hexdigest()}  {nome}")
    (base / "MANIFESTO.sha256").write_text("\n".join(linhas) + "\n", encoding="utf-8")


class ManifestoDeContratos(unittest.TestCase):
    def setUp(self):
        self.pasta = tempfile.TemporaryDirectory()
        self.base = Path(self.pasta.name) / "v9"
        self.base.mkdir()
        montar(self.base, {"a/um.json": b'{"a":1}', "b/dois.json": b"{}"})

    def tearDown(self):
        self.pasta.cleanup()

    def test_aceita_manifesto_consistente(self):
        self.assertEqual([], contratos.verificar(self.base))

    def test_recusa_hash_divergente(self):
        (self.base / "a/um.json").write_bytes(b'{"a":2}')
        self.assertEqual(["v9: hash divergente: a/um.json"], contratos.verificar(self.base))

    def test_recusa_arquivo_fora_do_manifesto_e_ausente(self):
        (self.base / "extra.json").write_bytes(b"{}")
        (self.base / "b/dois.json").unlink()
        erros = contratos.verificar(self.base)
        self.assertIn("v9: arquivo fora do manifesto: extra.json", erros)
        self.assertIn("v9: arquivo do manifesto ausente: b/dois.json", erros)

    def test_recusa_caminho_fora_da_raiz_e_linha_malformada(self):
        (self.base / "MANIFESTO.sha256").write_text(f"{'0' * 64}  ../fora.json\nlixo\n", encoding="utf-8")
        erros = contratos.verificar(self.base)
        self.assertTrue(any("caminho inválido" in e for e in erros))
        self.assertTrue(any("fora do formato" in e for e in erros))

    def test_manifesto_ausente(self):
        (self.base / "MANIFESTO.sha256").unlink()
        self.assertEqual(["v9: MANIFESTO.sha256 ausente"], contratos.verificar(self.base))


if __name__ == "__main__":
    unittest.main()
