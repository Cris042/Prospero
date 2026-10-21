import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

RAIZ = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("verificar_qualidade", RAIZ / "scripts" / "verificar-qualidade.py")
qualidade = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualidade)

COMPLEXO = """
package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.Valor;

public final class Complexo {
    // if (comentario) && || nao contam
    private static final String TEXTO = "if (x) { && }";

    static int decidir(int a, int b, java.util.List<? extends Number> c) {
        if (a > 0 && b > 0) {
            for (int i = 0; i < a; i++) {
                while (b > i) {
                    b--;
                }
            }
        }
        switch (a) {
            case 1 -> b = 2;
            case 2 -> b = 3;
            default -> b = 0;
        }
        try {
            return b > 1 ? b : a;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
"""

COESAO = """
package br.prospero.fo.ordens.dominio;

public class Mista {
    private int a;
    private int b;
    private final Runnable tarefa = () -> { a++; };

    public Mista(int a, int b) { this.a = a; this.b = b; }
    int lerA() { return a; }
    int dobrarA() { return lerA() * 2; }
    int lerB() { return b; }
    int incrementarB() { return this.b + 1; }
    static int utilitario() { return 1; }
}
"""

COESA = """
package br.prospero.fo.ordens.dominio;

public record Preco(long valor, int escala) {
    public Preco {
        if (escala < 0) throw new IllegalArgumentException();
    }
    long valor2() { return valor * 2; }
    int escala2() { return escala + (int) valor; }
}
"""

ENUM = """
package br.prospero.fo.comum.dominio;

public enum Valor {
    A(1) { int peso() { return 2; } },
    B(2);
    private final int codigo;
    Valor(int codigo) { this.codigo = codigo; }
    int peso() { return codigo; }
    interface Marcador { void marcar(); }
}
"""

DEPENDENTE = """
package br.prospero.fo.mercado.actor;

import br.prospero.fo.comum.dominio.*;
import static br.prospero.fo.ordens.helper.Complexo.decidir;

class Dependente {
    int usar(Valor v) { return decidir(1, 2, null); }
}
"""

BLOCO = """
        int total = 0;
        total += calcularParcela(entrada, 1);
        total += calcularParcela(entrada, 2);
        registrarAuditoria(total, entrada);
        validarLimite(total, entrada);
        publicarResultado(total, entrada);
"""


def escrever(raiz, servico, pacote, nome, fonte):
    destino = Path(raiz, servico, "src/main/java", *pacote.split("."), f"{nome}.java")
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(textwrap.dedent(fonte).lstrip(), encoding="utf-8")
    return destino


def classe_com_bloco(nome, vezes, pacote="br.prospero.fo.ordens.helper"):
    metodos = "".join(f"    int m{i}(int entrada) {{{BLOCO}        return total;\n    }}\n" for i in range(vezes))
    return f"package {pacote};\n\nclass {nome} {{\n{metodos}}}\n"


class VerificarQualidadeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.raiz = self.tmp.name
        self.cfg = qualidade.carregar_configuracao(None)

    def tearDown(self):
        self.tmp.cleanup()

    def analisar(self, cfg=None, git=False):
        return qualidade.analisar(self.raiz, cfg or self.cfg, usar_git=git)

    def metodo(self, relatorio, classe, nome):
        return next(m for m in relatorio["metodos"] if m["classe"] == classe and m["nome"] == nome)

    def test_limpar_preserva_posicoes_e_remove_comentarios_e_literais(self):
        fonte = 'int a; // if && \n String s = "if ||"; /* case\n */ char c = \'{\';'
        limpo = qualidade.limpar(fonte)
        self.assertEqual(len(limpo), len(fonte))
        self.assertEqual(limpo.count("\n"), fonte.count("\n"))
        self.assertNotRegex(limpo, r"\bif\b|&&|\|\||\bcase\b|\{")

    def test_metricas_de_metodo(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Complexo", COMPLEXO)
        m = self.metodo(self.analisar(), "Complexo", "decidir")
        # 1 + if + && + for + while + 2 case + ?: + catch; comentário, literal e curinga <? extends> não contam
        self.assertEqual(m["complexidade"], 9)
        self.assertEqual(m["parametros"], 3)
        self.assertEqual(m["aninhamento"], 3)
        self.assertEqual(m["linhas"], 19)
        self.assertTrue(m["estatico"])

    def test_lcom4_separa_grupos_e_ignora_construtor_estatico_e_lambda_de_campo(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.dominio", "Mista", COESAO)
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.dominio", "Preco", COESA)
        tipos = {t["nome"]: t for t in qualidade.Arquivo(Path(self.raiz), next(Path(self.raiz).rglob("Mista.java"))).tipos}
        self.assertEqual(qualidade.lcom4(tipos["Mista"]), [["dobrarA", "lerA"], ["incrementarB", "lerB"]])
        relatorio = self.analisar()
        self.assertEqual(self.metodo(relatorio, "Preco", "valor2")["lcom4"], 1)
        self.assertTrue(self.metodo(relatorio, "Preco", "Preco")["construtor"])
        self.assertNotIn("tarefa", [m["nome"] for m in relatorio["metodos"]])

    def test_alerta_de_coesao_respeita_minimo_de_metodos(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.dominio", "Mista", COESAO)
        self.assertFalse([a for a in self.analisar()["achados"] if a["regra"] == "coesao_lcom4"])
        self.cfg["coesao"]["min_metodos"] = 4
        achado = next(a for a in self.analisar()["achados"] if a["regra"] == "coesao_lcom4")
        self.assertEqual((achado["nivel"], achado["valor"]), ("alerta", 2))

    def test_enum_tipo_interno_e_acoplamento_por_pacote_e_feature(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Complexo", COMPLEXO)
        escrever(self.raiz, "front-office", "br.prospero.fo.comum.dominio", "Valor", ENUM)
        escrever(self.raiz, "front-office", "br.prospero.fo.mercado.actor", "Dependente", DEPENDENTE)
        relatorio = self.analisar()
        self.assertEqual(sorted(m["nome"] for m in relatorio["metodos"] if m["classe"] == "Valor"), ["Valor", "peso"])
        self.assertEqual(relatorio["resumo"]["tipos"], 4)
        pacotes = {p["unidade"]: p for p in relatorio["pacotes"]}
        self.assertEqual((pacotes["br.prospero.fo.mercado.actor"]["ce"], pacotes["br.prospero.fo.mercado.actor"]["ca"]), (2, 0))
        self.assertEqual(pacotes["br.prospero.fo.mercado.actor"]["instabilidade"], 1.0)
        # Complexo importa Valor e é usado por Dependente: Ca=1, Ce=1, I=0,5.
        self.assertEqual(pacotes["br.prospero.fo.ordens.helper"]["instabilidade"], 0.5)
        comum = {f["unidade"]: f for f in relatorio["features"]}["br.prospero.fo.comum"]
        self.assertEqual((comum["ca"], comum["ce"], comum["instabilidade"]), (2, 0, 0.0))
        self.assertEqual((comum["abstracao"], comum["distancia"]), (0.5, 0.5))

    def test_ciclo_entre_pacotes_gera_alerta(self):
        escrever(self.raiz, "back-office", "br.prospero.bo.a", "A", "package br.prospero.bo.a;\nimport br.prospero.bo.b.B;\nclass A {}\n")
        escrever(self.raiz, "back-office", "br.prospero.bo.b", "B", "package br.prospero.bo.b;\nimport br.prospero.bo.a.A;\nclass B {}\n")
        relatorio = self.analisar()
        self.assertEqual(relatorio["ciclos"]["pacotes"], [["br.prospero.bo.a", "br.prospero.bo.b"]])
        self.assertEqual([a["regra"] for a in relatorio["achados"]], ["ciclo_pacotes"])

    def test_duplicacao_so_sugere_abstracao_com_tres_ocorrencias_no_mesmo_servico(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Dupla", classe_com_bloco("Dupla", 2))
        escrever(self.raiz, "middle-office", "br.prospero.mo.risco.helper", "Outra",
                 classe_com_bloco("Outra", 1, "br.prospero.mo.risco.helper"))
        relatorio = self.analisar()
        self.assertEqual(relatorio["duplicacoes"][0]["ocorrencias"], 3)
        self.assertEqual(relatorio["duplicacoes"][0]["max_no_mesmo_servico"], 2)
        self.assertEqual(relatorio["quando_abstrair"], [])
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Dupla", classe_com_bloco("Dupla", 3))
        sugestoes = self.analisar()["quando_abstrair"]
        self.assertEqual(len(sugestoes), 1)
        self.assertIn("3x no mesmo serviço", sugestoes[0]["evidencia"])

    def test_gates_alerta_falha_configuracao_e_codigo_de_saida(self):
        corpo = "".join(f"        if (x == {i}) return {i};\n" for i in range(12))
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Gate",
                 f"package br.prospero.fo.ordens.helper;\nclass Gate {{\n    int f(int x) {{\n{corpo}        return 0;\n    }}\n}}\n")
        relatorio = self.analisar()
        self.assertEqual([(a["nivel"], a["regra"], a["valor"]) for a in relatorio["achados"]],
                         [("alerta", "metodo_complexidade", 13)])
        self.assertTrue(any("extrair método" in s["acao"] for s in relatorio["quando_abstrair"]))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(qualidade.main(["--raiz", self.raiz, "--gate", "--sem-git"]), 0)
            config = Path(self.raiz, "q.json")
            config.write_text(json.dumps({"metodo_complexidade": {"falha": 12}}), encoding="utf-8")
            self.assertEqual(qualidade.main(["--raiz", self.raiz, "--gate", "--sem-git", "--config", str(config)]), 1)
            self.assertEqual(qualidade.main(["--raiz", self.raiz, "--sem-git", "--config", str(config)]), 0)

    def test_saida_json_estavel(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Complexo", COMPLEXO)
        escrever(self.raiz, "front-office", "br.prospero.fo.mercado.actor", "Dependente", DEPENDENTE)
        saidas = []
        for _ in range(2):
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                qualidade.main(["--raiz", self.raiz, "--json", "--sem-git"])
            saidas.append(buffer.getvalue())
        self.assertEqual(saidas[0], saidas[1])
        self.assertEqual(json.loads(saidas[0])["resumo"]["metodos"], 2)

    def test_mudanca_conjunta_entre_features_pelo_git(self):
        env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
               "GIT_COMMITTER_EMAIL": "t@t", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}
        git = lambda *args: subprocess.run(["git", "-C", self.raiz, *args], check=True, capture_output=True, env=env)
        git("init", "-q")
        a = escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "A", "package br.prospero.fo.ordens.helper;\nclass A {}\n")
        b = escrever(self.raiz, "front-office", "br.prospero.fo.mercado.helper", "B", "package br.prospero.fo.mercado.helper;\nclass B {}\n")
        for i in range(3):
            a.write_text(a.read_text() + f"// {i}\n")
            b.write_text(b.read_text() + f"// {i}\n")
            git("add", "-A")
            git("commit", "-q", "-m", f"c{i}")
        relatorio = self.analisar(git=True)
        self.assertEqual(relatorio["mudanca_conjunta"], [{"arquivos": [
            "front-office/src/main/java/br/prospero/fo/mercado/helper/B.java",
            "front-office/src/main/java/br/prospero/fo/ordens/helper/A.java"], "commits": 3}])
        self.assertTrue(any("revisar fronteira" in s["acao"] for s in relatorio["quando_abstrair"]))
        self.assertIsNone(self.analisar(git=False)["mudanca_conjunta"])


if __name__ == "__main__":
    unittest.main()
