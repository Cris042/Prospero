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
        self.assertFalse(any("extrair método" in s["acao"] for s in relatorio["quando_abstrair"]),
                         "alerta não sugere abstração; só falha")
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


class CasosLimiteQa(unittest.TestCase):
    def test_text_block_com_aspas_escapadas_nao_esconde_o_arquivo(self):
        limpo = qualidade.limpar('class T { String s = """\n  a \\""" b """; int z() { if (x) {} return 0; } }')
        self.assertIn("int z()", limpo)
        self.assertIn("if (x)", limpo)

    def test_raiz_inexistente_retorna_erro(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(qualidade.main(["--raiz", "/nao/existe/prospero", "--sem-git"]), 2)

CAMPOS = """
package br.prospero.fo.ordens.dominio;

public class Carteira {
    private static final int LIMITE = 3;
    private final long saldo;
    private final long reservado;
    private int versao;

    long livre(boolean bloqueada) {
        if (bloqueada) {
            return 0;
        } else {
            return saldo - reservado + versao;
        }
    }
}
"""

SWITCHES = """
package br.prospero.mo.risco.helper;

final class Regras {
    static int peso(Tipo tipo) {
        return switch (tipo) {
            case Tipo.LIMIT -> 1;
            case MARKET, STOP -> switch (tipo) { case X -> 9; case Y -> 8; default -> 0; };
            default -> 0;
        };
    }
    static String nome(String texto) {
        switch (texto) {
            case "compra": return "C";
            case "venda": return "V";
            default: return "";
        }
    }
    boolean compra(Ordem o) { if (o.lado() == Lado.COMPRA) return true; return o instanceof Compra c; }
    boolean b(Ordem o) { if (o.lado() == Lado.COMPRA && o.ativa()) return true; if (o == null) return false; return false; }
}
"""

SWITCHES_2 = """
package br.prospero.mo.risco.actor;

class Usa {
    int preco(Tipo t) { return switch (t) { case MARKET -> 0; case LIMIT -> 1; case STOP -> 2; }; }
    String nome(String texto) { return switch (texto) { case "venda", "compra" -> "x"; default -> ""; }; }
    boolean compra(Ordem o) { if (o.lado()  ==  Lado.COMPRA) return true; if (o == null) return false; return false; }
    boolean tipo(Ordem o) { if (o.lado() == Lado.COMPRA) { return o instanceof Compra outra; } return o == null; }
}
"""


class EvolucaoFitnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.raiz = self.tmp.name
        self.cfg = qualidade.carregar_configuracao(None)

    def tearDown(self):
        self.tmp.cleanup()

    def analisar(self, **kwargs):
        return qualidade.analisar(self.raiz, self.cfg, usar_git=False, **kwargs)

    def regras(self, relatorio, regra):
        return [a for a in relatorio["achados"] if a["regra"] == regra]

    def executar(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as saida, contextlib.redirect_stderr(io.StringIO()):
            codigo = qualidade.main(["--raiz", self.raiz, "--sem-git", *args])
        return codigo, saida.getvalue()

    def test_configuracao_versionada_e_carregada_por_padrao(self):
        self.assertEqual(self.cfg["metodo_else"], {"alerta": 0, "falha": None})
        self.assertEqual(self.cfg["classe_campos"]["alerta"], 2)

    def test_else_e_campos_de_instancia_com_isencoes_do_adr_002(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.dominio", "Carteira", CAMPOS)
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.dominio", "Dados",
                 "package br.prospero.fo.ordens.dominio;\nrecord Dados(int a, int b, int c, int d) {}\n")
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.jpa", "OrdemEntity",
                 "package br.prospero.fo.ordens.jpa;\nclass OrdemEntity { int a; int b; int c; int d; }\n")
        relatorio = self.analisar()
        self.assertEqual(self.metodo(relatorio, "livre")["else"], 1)
        self.assertEqual([(a["valor"], a["chave"]) for a in self.regras(relatorio, "metodo_else")],
                         [(1, "front-office/src/main/java/br/prospero/fo/ordens/dominio/Carteira.java#Carteira.livre")])
        campos = self.regras(relatorio, "classe_campos")
        self.assertEqual([(a["alvo"].split("#")[1], a["valor"]) for a in campos], [("Carteira", 3)])
        classe = next(c for c in relatorio["classes"] if c["classe"] == "Carteira")
        self.assertEqual((classe["campos"], classe["lcom4"]), (3, None))

    def metodo(self, relatorio, nome):
        return next(m for m in relatorio["metodos"] if m["nome"] == nome)

    def test_switch_e_condicao_repetidos_no_mesmo_servico(self):
        escrever(self.raiz, "middle-office", "br.prospero.mo.risco.helper", "Regras", SWITCHES)
        escrever(self.raiz, "middle-office", "br.prospero.mo.risco.actor", "Usa", SWITCHES_2)
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Outro",
                 "package br.prospero.fo.ordens.helper;\nclass Outro { int f(Tipo t) { return switch (t) "
                 "{ case LIMIT -> 1; case MARKET -> 2; case STOP -> 3; }; } }\n")
        relatorio = self.analisar()
        switches = {a["alvo"]: a for a in self.regras(relatorio, "switch_repetido")}
        # Rótulo qualificado (Tipo.LIMIT) normaliza; switch aninhado não vaza; outro serviço não soma.
        self.assertEqual(sorted(switches), ['middle-office: case "compra", "venda"', "middle-office: case LIMIT, MARKET, STOP"])
        self.assertEqual(switches["middle-office: case LIMIT, MARKET, STOP"]["valor"], 2)
        condicoes = self.regras(relatorio, "condicao_repetida")
        # `o.lado() == Lado.COMPRA` aparece em 3 métodos; `o == null` não é discriminante; instanceof só em 2.
        self.assertEqual([(a["alvo"], a["valor"]) for a in condicoes], [("middle-office: if (o.lado() == Lado.COMPRA)", 3)])
        acoes = [s["acao"] for s in relatorio["quando_abstrair"]]
        self.assertTrue(any("levar o comportamento ao tipo" in a for a in acoes))
        self.assertTrue(any("encapsular a pergunta" in a for a in acoes))

    def test_interface_com_unica_implementacao_sem_eixo_de_mudanca(self):
        def interface(pacote, nome, extra=""):
            escrever(self.raiz, "back-office", pacote, nome, f"package {pacote};\n{extra}public interface {nome} {{ void f(); }}\n")

        def impl(pacote, nome, alvo, servico="back-office", raiz_src="main"):
            destino = Path(self.raiz, servico, f"src/{raiz_src}/java", *pacote.split("."), f"{nome}.java")
            destino.parent.mkdir(parents=True, exist_ok=True)
            destino.write_text(f"package {pacote};\nclass {nome} implements {alvo}, Comparable<{nome}> {{}}\n")

        interface("br.prospero.bo.a.helper", "Sozinha")
        impl("br.prospero.bo.a.actor", "SozinhaImpl", "br.prospero.bo.a.helper.Sozinha")
        interface("br.prospero.bo.a.dominio", "Porta")
        impl("br.prospero.bo.a.jpa", "PortaJpa", "Porta")
        interface("br.prospero.bo.a.helper", "ComDuble")
        impl("br.prospero.bo.a.actor", "ComDubleImpl", "ComDuble")
        impl("br.prospero.bo.a.actor", "DubleTeste", "ComDuble", raiz_src="test")
        interface("br.prospero.bo.a.helper", "Duas")
        impl("br.prospero.bo.a.actor", "Uma", "Duas")
        impl("br.prospero.bo.a.actor", "Outra", "Duas")
        interface("br.prospero.bo.a.rest", "Cliente", "@RegisterRestClient\n")
        impl("br.prospero.bo.a.actor", "ClienteFalso", "Cliente")
        achados = self.regras(self.analisar(), "interface_unica_implementacao")
        self.assertEqual([(a["alvo"].rsplit("/", 1)[1], a["locais"][0].rsplit("#", 1)[1]) for a in achados],
                         [("Sozinha.java#Sozinha", "SozinhaImpl")])

    def test_servico_filtra_e_desconhecido_retorna_2(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.dominio", "Carteira", CAMPOS)
        escrever(self.raiz, "middle-office", "br.prospero.mo.risco.helper", "Regras", SWITCHES)
        self.assertEqual(self.analisar(servicos=["middle-office"])["resumo"]["servicos"], ["middle-office"])
        codigo, saida = self.executar("--servico", "front-office")
        self.assertEqual(codigo, 0)
        self.assertIn("Serviços front-office", saida)
        self.assertNotIn("middle-office", saida)
        self.assertEqual(self.executar("--servico", "nao-existe")[0], 2)

    def test_diretorios_ocultos_como_worktrees_ficam_fora(self):
        escrever(self.raiz, "front-office", "br.prospero.fo.ordens.dominio", "Carteira", CAMPOS)
        escrever(Path(self.raiz, ".claude"), "worktrees", "br.prospero.fo.x", "Copia", CAMPOS)
        escrever(self.raiz, ".claude", "br.prospero.fo.x", "Copia", CAMPOS)
        self.assertEqual(self.analisar()["resumo"]["arquivos"], 1)

    def test_baseline_reprova_so_falha_nova_ou_pior(self):
        def gate(n_ifs, extra=""):
            corpo = "".join(f"        if (x == {i}) return {i};\n" for i in range(n_ifs))
            escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Gate",
                     f"package br.prospero.fo.ordens.helper;\nclass Gate {{\n\n    int f(int x) {{\n{corpo}"
                     f"        return 0;\n    }}\n{extra}}}\n")
        gate(25)
        baseline = str(Path(self.raiz, "baseline.json"))
        self.assertEqual(self.executar("--gate")[0], 1)
        self.assertEqual(self.executar("--gerar-baseline", baseline)[0], 0)
        self.assertEqual(json.loads(Path(baseline).read_text())["achados"][0]["chave"],
                         "front-office/src/main/java/br/prospero/fo/ordens/helper/Gate.java#Gate.f")
        gate(25, "\n")  # mesma falha deslocada: chave sem linha continua igual
        codigo, saida = self.executar("--gate", "--baseline", baseline)
        self.assertEqual(codigo, 0)
        self.assertIn("falhas novas vs. baseline 0", saida)
        gate(26)  # piorou
        self.assertEqual(self.executar("--gate", "--baseline", baseline)[0], 1)
        gate(25, "    int g(int x) {\n" + "".join(f"        if (x == {i}) return {i};\n" for i in range(25))
             + "        return 0;\n    }\n")  # nova falha
        codigo, saida = self.executar("--gate", "--baseline", baseline)
        self.assertEqual(codigo, 1)
        self.assertIn("[novo] metodo_complexidade 26 > 20", saida)

    def test_baseline_trata_sobrecarga_e_aponta_melhora(self):
        def gate(*complexidades):
            metodos = "".join(f"    int f({tipo} x) {{\n" + "".join(f"        if (y == {i}) return {i};\n"
                                                                        for i in range(n))
                              + "        return 0;\n    }\n" for tipo, n in zip(("int", "long", "char"), complexidades))
            escrever(self.raiz, "front-office", "br.prospero.fo.ordens.helper", "Gate",
                     f"package br.prospero.fo.ordens.helper;\nclass Gate {{\n    int y;\n{metodos}}}\n")
        baseline = str(Path(self.raiz, "baseline.json"))
        gate(26, 22)
        self.assertEqual(self.executar("--gerar-baseline", baseline)[0], 0)
        gate(26, 22, 21)  # sobrecarga nova com mesma chave
        codigo, saida = self.executar("--gate", "--baseline", baseline)
        self.assertEqual(codigo, 1)
        self.assertIn("[novo] metodo_complexidade 22 > 20", saida)
        gate(26, 5)  # uma sobrecarga melhorou
        codigo, saida = self.executar("--gate", "--baseline", baseline)
        self.assertEqual(codigo, 0)
        self.assertIn("Baseline desatualizada: 1 entrada(s)", saida)
        self.assertEqual(self.executar("--gerar-baseline", baseline, "--servico", "front-office")[0], 2)
        Path(baseline).write_text("{", encoding="utf-8")
        self.assertEqual(self.executar("--gate", "--baseline", baseline)[0], 2)
        self.assertEqual(self.executar("--gate", "--baseline", baseline + ".ausente")[0], 2)

    def test_hotspots_combinam_commits_e_complexidade(self):
        commits = [["a.java", "b.java"], ["a.java"], ["b.java"], ["a.java"]]
        metodos = [{"arquivo": "a.java", "complexidade": 4}, {"arquivo": "a.java", "complexidade": 9},
                   {"arquivo": "b.java", "complexidade": 20}]
        self.assertEqual(qualidade.hotspots(commits, metodos, {"min_commits": 2}), [
            {"arquivo": "b.java", "commits": 2, "cc_max": 20, "pontuacao": 40},
            {"arquivo": "a.java", "commits": 3, "cc_max": 9, "pontuacao": 27}])
        self.assertIsNone(qualidade.hotspots(None, metodos, {"min_commits": 2}))


if __name__ == "__main__":
    unittest.main()
