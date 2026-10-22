#!/usr/bin/env python3
"""Diagnóstico determinístico de complexidade, coesão e acoplamento do Java em */src/main/java.

Ideias adaptadas de arch-fitness-review (MIT, commit a07ea4184b431308d6379ec5df2f5245b8590c63):
complexidade ciclomática, Ca/Ce/instabilidade, abstração/distância da sequência principal, ciclos,
LCOM4, duplicação, aridade e mudança conjunta. Reimplementação léxica com a stdlib, sem AST nem rede.
É diagnóstico: valores aproximados orientam revisão humana. `--gate` sai com 1 somente se houver falha.
"""
import argparse
import bisect
import copy
import json
import re
import subprocess
import sys
from collections import defaultdict
from itertools import combinations
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[1]
# Limiares estritos: valor > alerta gera alerta; valor > falha gera falha; None desativa o nível.
PADRAO = {
    "prefixo": "br.prospero.",
    "metodo_linhas": {"alerta": 30, "falha": 60},
    "metodo_complexidade": {"alerta": 10, "falha": 20},
    "metodo_aninhamento": {"alerta": 3, "falha": 5},
    "metodo_parametros": {"alerta": 4, "falha": 7},
    "classe_linhas": {"alerta": 300, "falha": 600},
    "classe_dependencias": {"alerta": 10, "falha": 20},
    "coesao": {"min_metodos": 7, "min_componentes": 2},
    "duplicacao": {"janela": 6, "min_ocorrencias": 3},
    "mudanca_conjunta": {"min_commits": 3, "historico": 300, "max_arquivos_commit": 30},
}
TIPO_RE = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_]\w*)")
DECISAO_RE = re.compile(r"\b(?:if|for|while|case|catch)\b|&&|\|\||\?(?!\s*(?:extends\b|super\b|[>,]))")
PALAVRAS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "try", "else", "do"}
IDENT_FIM_RE = re.compile(r"([A-Za-z_]\w*)\s*(?:\[\s*\]\s*)*$")
USO_RE = re.compile(r"(?:(?<![\w.])|(?<=this\.))([A-Za-z_]\w*)\b(?!\s*\()")
CHAMADA_RE = re.compile(r"(?:(?<![\w.])|(?<=this\.))([A-Za-z_]\w*)\s*\(")


def limpar(fonte):
    """Troca comentários e conteúdo de literais por espaços, preservando posições e quebras de linha."""
    saida, i, n = [], 0, len(fonte)
    branco = lambda trecho: re.sub(r"[^\n]", " ", trecho)
    while i < n:
        if fonte.startswith("//", i):
            j = fonte.find("\n", i)
            j = n if j < 0 else j
            saida.append(" " * (j - i))
        elif fonte.startswith("/*", i):
            j = fonte.find("*/", i + 2)
            j = n if j < 0 else j + 2
            saida.append(branco(fonte[i:j]))
        elif fonte.startswith('"""', i):
            j = fecha_text_block(fonte, i + 3)
            saida.append('"' + branco(fonte[i + 1:j - 1]) + '"')
        elif fonte[i] in "\"'":
            aspa, j = fonte[i], i + 1
            while j < n and fonte[j] not in (aspa, "\n"):
                j += 2 if fonte[j] == "\\" else 1
            j = min(j, n)
            if j < n and fonte[j] == aspa:
                j += 1
            trecho = fonte[i:j]
            saida.append(aspa + " " * (len(trecho) - 2) + aspa if len(trecho) >= 2 else trecho)
        else:
            j = i + 1
            saida.append(fonte[i])
        i = j
    return "".join(saida)


def fecha_text_block(fonte, inicio):
    """Fim do text block: o primeiro \"\"\" não escapado (número par de barras antes dele)."""
    j = inicio
    while True:
        k = fonte.find('"""', j)
        if k < 0:
            return len(fonte)
        barras = 0
        while k - barras - 1 >= inicio and fonte[k - barras - 1] == "\\":
            barras += 1
        if barras % 2 == 0:
            return k + 3
        j = k + 1


def pares(texto, abre="{", fecha="}"):
    pilha, resultado = [], {}
    for i, c in enumerate(texto):
        if c == abre:
            pilha.append(i)
        elif c == fecha and pilha:
            resultado[pilha.pop()] = i
    return resultado


def sem_anotacoes(trecho):
    """Apaga anotações (exceto @interface) mantendo o comprimento do trecho."""
    chars = list(trecho)
    for m in re.finditer(r"@(?!interface\b)[A-Za-z_][\w.]*", trecho):
        fim = m.end()
        k = fim
        while k < len(trecho) and trecho[k].isspace():
            k += 1
        if k < len(trecho) and trecho[k] == "(":
            profundidade = 0
            for k in range(k, len(trecho)):
                profundidade += {"(": 1, ")": -1}.get(trecho[k], 0)
                if profundidade == 0:
                    fim = k + 1
                    break
        for k in range(m.start(), min(fim, len(chars))):
            if chars[k] != "\n":
                chars[k] = " "
    return "".join(chars)


def membros(texto, chaves, inicio, fim):
    """Gera (espécie, início do cabeçalho, abre, fecha) para blocos e instruções do nível atual."""
    cabecalho, i, parenteses, atribuicao = inicio, inicio, 0, False
    while i < fim:
        c = texto[i]
        if c == "(":
            parenteses += 1
        elif c == ")":
            parenteses -= 1
        elif c == "=" and parenteses == 0 and texto[i + 1:i + 2] != "=" and texto[i - 1:i] not in "=!<>":
            atribuicao = True
        elif c == "{" and i in chaves:
            if parenteses > 0 or atribuicao:
                i = chaves[i] + 1
                continue
            yield "bloco", cabecalho, i, chaves[i]
            i = chaves[i] + 1
            cabecalho, atribuicao = i, False
            continue
        elif c == ";" and parenteses == 0:
            yield "instrucao", cabecalho, i, i
            cabecalho, atribuicao = i + 1, False
        i += 1


def dividir(trecho):
    partes, profundidade, atual = [], 0, []
    for c in trecho:
        if c in "<([{":
            profundidade += 1
        elif c in ">)]}":
            profundidade -= 1
        if c == "," and profundidade == 0:
            partes.append("".join(atual))
            atual = []
        else:
            atual.append(c)
    partes.append("".join(atual))
    return [p.strip() for p in partes if p.strip()]


def assinatura(cabecalho, nome_tipo):
    """Retorna (nome, parâmetros, posição do nome, construtor) ou None se não for método."""
    abre = cabecalho.find("(")
    if abre < 0:
        m = IDENT_FIM_RE.search(cabecalho)
        if m and m.group(1) == nome_tipo:  # construtor compacto de record
            return nome_tipo, 0, m.start(1), True
        return None
    m = IDENT_FIM_RE.search(cabecalho[:abre])
    if not m or m.group(1) in PALAVRAS or "=" in cabecalho[:abre] or "->" in cabecalho[:abre]:
        return None
    fecha = pares(cabecalho[abre:], "(", ")").get(0)
    if fecha is None:
        return None
    resto = cabecalho[abre + fecha + 1:].strip()
    if resto and not re.fullmatch(r"(?:throws\s+[\w.<>,\s]+|default\b.*)", resto, re.S):
        return None
    nome = m.group(1)
    return nome, len(dividir(cabecalho[abre + 1:abre + fecha])), m.start(1), nome == nome_tipo


def nomes_campos(cabecalho):
    antes = re.split(r"(?<![=!<>])=(?!=)", cabecalho, maxsplit=1)[0]
    nomes = []
    for parte in dividir(antes):
        m = IDENT_FIM_RE.search(parte)
        if m and m.group(1) not in PALAVRAS:
            nomes.append(m.group(1))
    return nomes


def aninhamento(corpo):
    atual = maximo = 0
    for c in corpo:
        if c == "{":
            atual += 1
            maximo = max(maximo, atual)
        elif c == "}":
            atual -= 1
    return maximo


class Arquivo:
    def __init__(self, raiz, caminho):
        self.caminho = caminho.relative_to(raiz).as_posix()
        self.servico = self.caminho.split("/")[0]
        self.texto = limpar(caminho.read_text(encoding="utf-8"))
        self.quebras = [i for i, c in enumerate(self.texto) if c == "\n"]
        self.chaves = pares(self.texto)
        m = re.search(r"^\s*package\s+([\w.]+)\s*;", self.texto, re.M)
        self.pacote = m.group(1) if m else ""
        self.imports = re.findall(r"^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;", self.texto, re.M)
        self.tipos = []
        for especie, cab, abre, fecha in membros(self.texto, self.chaves, 0, len(self.texto)):
            cabecalho = sem_anotacoes(self.texto[cab:abre])
            m = TIPO_RE.search(cabecalho) if especie == "bloco" else None
            if m:
                self.analisar_tipo(cab, abre, fecha, cabecalho, m, "")
        self.principal = next((t["nome"] for t in self.tipos if "." not in t["nome"]), Path(self.caminho).stem)

    def linha(self, posicao):
        return bisect.bisect_left(self.quebras, posicao) + 1

    def analisar_tipo(self, cab, abre, fecha, cabecalho, m, externo):
        palavra, nome = m.group(1), m.group(2)
        tipo = {
            "nome": f"{externo}.{nome}" if externo else nome,
            "especie": palavra,
            "abstrato": palavra == "interface" or bool(re.search(r"\babstract\b", cabecalho[:m.start()])),
            "linha": self.linha(cab + m.start()),
            "linhas": self.linha(fecha) - self.linha(cab + m.start()) + 1,
            "metodos": [],
            "campos": [],
        }
        self.tipos.append(tipo)
        if palavra == "record":
            depois = cabecalho[m.end():]
            abre_comp = depois.find("(")
            fecha_comp = pares(depois[abre_comp:], "(", ")").get(0) if abre_comp >= 0 else None
            if fecha_comp is not None:
                componentes = dividir(depois[abre_comp + 1:abre_comp + fecha_comp])
                tipo["campos"] += [(n, False) for c in componentes for n in nomes_campos(c)[-1:]]
        inicio = abre + 1
        if palavra == "enum":
            inicio = self.fim_constantes(abre + 1, fecha)
        for especie, c2, a2, f2 in membros(self.texto, self.chaves, inicio, fecha):
            cab2 = sem_anotacoes(self.texto[c2:a2])
            if especie == "bloco":
                interno = TIPO_RE.search(cab2)
                if interno:
                    self.analisar_tipo(c2, a2, f2, cab2, interno, tipo["nome"])
                    continue
            dados = assinatura(cab2, nome)
            if dados and especie == "bloco":
                nome_m, parametros, posicao, construtor = dados
                corpo = self.texto[a2 + 1:f2]
                inicio_linha = self.linha(c2 + posicao)
                tipo["metodos"].append({
                    "nome": nome_m,
                    "linha": inicio_linha,
                    "linhas": self.linha(f2) - inicio_linha + 1,
                    "complexidade": 1 + len(DECISAO_RE.findall(corpo)),
                    "aninhamento": aninhamento(corpo),
                    "parametros": parametros,
                    "estatico": bool(re.search(r"\bstatic\b", cab2[:posicao])),
                    "construtor": construtor,
                    "corpo": corpo,
                })
            elif especie == "instrucao" and not dados and cab2.strip():
                estatico = bool(re.search(r"\bstatic\b", cab2))
                tipo["campos"] += [(n, estatico) for n in nomes_campos(cab2)]

    def fim_constantes(self, inicio, fim):
        i, parenteses = inicio, 0
        while i < fim:
            c = self.texto[i]
            if c == "{" and i in self.chaves:
                i = self.chaves[i] + 1
                continue
            parenteses += {"(": 1, ")": -1}.get(c, 0)
            if c == ";" and parenteses == 0:
                return i + 1
            i += 1
        return fim


def lcom4(tipo):
    """Componentes conexos entre métodos de instância ligados por campo comum ou chamada interna."""
    campos = {n for n, estatico in tipo["campos"] if not estatico}
    metodos = [m for m in tipo["metodos"] if not m["estatico"] and not m["construtor"]]
    if not campos or len(metodos) < 2:
        return None
    pai = list(range(len(metodos)))

    def raiz(x):
        while pai[x] != x:
            pai[x] = pai[pai[x]]
            x = pai[x]
        return x

    indices = defaultdict(list)
    for i, m in enumerate(metodos):
        indices[m["nome"]].append(i)
    usos = [set(USO_RE.findall(m["corpo"])) & campos for m in metodos]
    for i, j in combinations(range(len(metodos)), 2):
        if usos[i] & usos[j]:
            pai[raiz(i)] = raiz(j)
    for i, m in enumerate(metodos):
        for chamado in set(CHAMADA_RE.findall(m["corpo"])):
            for j in indices.get(chamado, []):
                pai[raiz(i)] = raiz(j)
    for nomes in indices.values():  # sobrecargas pertencem à mesma responsabilidade nominal
        for j in nomes[1:]:
            pai[raiz(j)] = raiz(nomes[0])
    grupos = defaultdict(set)
    for i, m in enumerate(metodos):
        grupos[raiz(i)].add(m["nome"])
    return sorted(sorted(g) for g in grupos.values())


def nivel(valor, limites):
    if limites.get("falha") is not None and valor > limites["falha"]:
        return "falha"
    if limites.get("alerta") is not None and valor > limites["alerta"]:
        return "alerta"
    return None


def unidade_feature(pacote, prefixo):
    partes = pacote.split(".")
    return ".".join(partes[:len(prefixo.rstrip(".").split(".")) + 2])


def acoplamento(arestas, unidade_de, tipos_por_unidade):
    """Ca/Ce em classes (Martin), instabilidade I e distância D=|A+I-1| por unidade."""
    ce, ca = defaultdict(set), defaultdict(set)
    for origem, destino in arestas:
        uo, ud = unidade_de[origem], unidade_de[destino]
        if uo != ud:
            ce[uo].add(destino)
            ca[ud].add(origem)
    resultado = []
    for unidade in sorted(tipos_por_unidade):
        total, abstratos = tipos_por_unidade[unidade]
        a = round(abstratos / total, 2) if total else 0.0
        e, f = len(ce[unidade]), len(ca[unidade])
        i = round(e / (e + f), 2) if e + f else None
        d = round(abs(a + i - 1), 2) if i is not None else None
        resultado.append({"unidade": unidade, "ca": f, "ce": e, "instabilidade": i, "abstracao": a, "distancia": d})
    return resultado


def ciclos(arestas, unidade_de):
    grafo = defaultdict(set)
    for origem, destino in arestas:
        uo, ud = unidade_de[origem], unidade_de[destino]
        if uo != ud:
            grafo[uo].add(ud)
    indice, baixo, pilha, na_pilha, componentes, contador = {}, {}, [], set(), [], [0]

    def visitar(v):
        indice[v] = baixo[v] = contador[0]
        contador[0] += 1
        pilha.append(v)
        na_pilha.add(v)
        for w in sorted(grafo[v]):
            if w not in indice:
                visitar(w)
                baixo[v] = min(baixo[v], baixo[w])
            elif w in na_pilha:
                baixo[v] = min(baixo[v], indice[w])
        if baixo[v] == indice[v]:
            componente = []
            while True:
                w = pilha.pop()
                na_pilha.discard(w)
                componente.append(w)
                if w == v:
                    break
            if len(componente) > 1:
                componentes.append(sorted(componente))

    for v in sorted(set(grafo) | {u for d in grafo.values() for u in d}):
        if v not in indice:
            visitar(v)
    return sorted(componentes)


def linhas_normalizadas(arquivo):
    resultado = []
    for numero, linha in enumerate(arquivo.texto.split("\n"), 1):
        s = " ".join(linha.split())
        if (not s or s.startswith(("package ", "import ")) or re.fullmatch(r"[{}()\[\];,\s]*", s)
                or re.fullmatch(r"@[\w.]+(?:\(.*\))?", s)):
            continue
        resultado.append((numero, re.sub(r"\b\d[\w.]*\b", "0", s)))
    return resultado


def duplicacoes(arquivos, cfg):
    janela, ocorrencias = cfg["janela"], defaultdict(list)
    for arquivo in arquivos:
        linhas = linhas_normalizadas(arquivo)
        for i in range(len(linhas) - janela + 1):
            chave = "\n".join(t for _, t in linhas[i:i + janela])
            ocorrencias[chave].append((arquivo.caminho, i, linhas[i][0], linhas[i + janela - 1][0]))
    grupos = {}
    for lista in ocorrencias.values():
        aceitas, ultimo = [], {}
        for caminho, i, ini, fim in sorted(lista):
            if caminho in ultimo and i < ultimo[caminho] + janela:
                continue
            ultimo[caminho] = i
            aceitas.append([caminho, i, ini, fim])
        if len(aceitas) >= 2:
            grupos[tuple((c, i) for c, i, _, _ in aceitas)] = aceitas
    cadeias, resultado = {}, []
    for chave in sorted(grupos, key=lambda k: (sum(i for _, i in k), k)):
        anterior = tuple((c, i - 1) for c, i in chave)
        if anterior in cadeias:
            cadeia = cadeias.pop(anterior)
            for ocorrencia, nova in zip(cadeia["ocorrencias"], grupos[chave]):
                ocorrencia[2] = nova[3]
            cadeia["linhas_normalizadas"] += 1
        else:
            cadeia = {"ocorrencias": [[c, ini, fim] for c, _, ini, fim in grupos[chave]], "linhas_normalizadas": janela}
            resultado.append(cadeia)
        cadeias[chave] = cadeia
    saida = []
    for cadeia in resultado:
        locais = [{"arquivo": c, "inicio": ini, "fim": fim} for c, ini, fim in cadeia["ocorrencias"]]
        por_servico = defaultdict(int)
        for local in locais:
            por_servico[local["arquivo"].split("/")[0]] += 1
        saida.append({"ocorrencias": len(locais), "linhas_normalizadas": cadeia["linhas_normalizadas"],
                      "max_no_mesmo_servico": max(por_servico.values()), "locais": locais})
    return sorted(saida, key=lambda d: (-d["ocorrencias"], -d["linhas_normalizadas"],
                                        d["locais"][0]["arquivo"], d["locais"][0]["inicio"]))


def mudanca_conjunta(raiz, feature_de_arquivo, cfg):
    """Pares de arquivos de features diferentes alterados juntos no histórico local do Git."""
    try:
        saida = subprocess.run(
            ["git", "-C", str(raiz), "log", "--no-merges", "--relative", f"-n{cfg['historico']}",
             "--format=tformat:@@", "--name-only", "--", "*/src/main/java/*.java"],
            capture_output=True, text=True, check=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    contagem = defaultdict(int)
    for commit in saida.split("@@"):
        arquivos = sorted({a.strip() for a in commit.splitlines() if a.strip() in feature_de_arquivo})
        if len(arquivos) > cfg["max_arquivos_commit"]:
            continue
        for a, b in combinations(arquivos, 2):
            if feature_de_arquivo[a] != feature_de_arquivo[b]:
                contagem[(a, b)] += 1
    pares_ = [{"arquivos": [a, b], "commits": n} for (a, b), n in contagem.items() if n >= cfg["min_commits"]]
    return sorted(pares_, key=lambda p: (-p["commits"], p["arquivos"]))


def carregar_configuracao(caminho):
    cfg = copy.deepcopy(PADRAO)
    if caminho:
        for chave, valor in json.loads(Path(caminho).read_text(encoding="utf-8")).items():
            if chave not in cfg:
                raise SystemExit(f"ERRO: chave de configuração desconhecida: {chave}")
            if isinstance(valor, dict):
                cfg[chave].update(valor)
            else:
                cfg[chave] = valor
    return cfg


def analisar(raiz, cfg, usar_git=True):
    raiz = Path(raiz)
    # Symlinks ficam fora: o diagnóstico lê só arquivos reais dentro da raiz.
    arquivos = [Arquivo(raiz, p) for p in sorted(raiz.glob("*/src/main/java/**/*.java"))
                if not p.is_symlink() and p.resolve().is_relative_to(raiz.resolve())]
    conhecidos, por_pacote = {}, defaultdict(dict)
    for arquivo in arquivos:
        for tipo in arquivo.tipos:
            conhecidos[f"{arquivo.pacote}.{tipo['nome']}"] = arquivo.caminho
            if "." not in tipo["nome"]:
                por_pacote[arquivo.pacote][tipo["nome"]] = arquivo.caminho
    arestas = set()
    for arquivo in arquivos:
        palavras = set(re.findall(r"\b[A-Z]\w*", arquivo.texto))
        for importado in arquivo.imports:
            if not importado.startswith(cfg["prefixo"]):
                continue
            alvos = set()
            base = importado[:-2] if importado.endswith(".*") else importado
            if importado.endswith(".*") and base in por_pacote:
                alvos = {c for n, c in por_pacote[base].items() if n in palavras}
            else:
                partes = base.split(".")
                for k in range(len(partes), 0, -1):
                    if ".".join(partes[:k]) in conhecidos:
                        alvos = {conhecidos[".".join(partes[:k])]}
                        break
            arestas |= {(arquivo.caminho, alvo) for alvo in alvos if alvo != arquivo.caminho}
    pacote_de = {a.caminho: a.pacote for a in arquivos}
    feature_de = {a.caminho: unidade_feature(a.pacote, cfg["prefixo"]) for a in arquivos}
    tipos_pacote, tipos_feature = defaultdict(lambda: [0, 0]), defaultdict(lambda: [0, 0])
    for arquivo in arquivos:
        for tipo in arquivo.tipos:
            for contagem in (tipos_pacote[arquivo.pacote], tipos_feature[feature_de[arquivo.caminho]]):
                contagem[0] += 1
                contagem[1] += int(tipo["abstrato"])
    ce_classe, ca_classe = defaultdict(set), defaultdict(set)
    for origem, destino in arestas:
        ce_classe[origem].add(destino)
        ca_classe[destino].add(origem)

    metodos, classes, achados = [], [], []

    def registrar(regra, alvo, valor, limites):
        encontrado = nivel(valor, limites)
        if encontrado:
            limite = limites[encontrado]
            achados.append({"nivel": encontrado, "regra": regra, "alvo": alvo, "valor": valor, "limite": limite})

    for arquivo in arquivos:
        topo = next((t for t in arquivo.tipos if t["nome"] == arquivo.principal), None)
        linhas_classe = topo["linhas"] if topo else 0
        dependencias = len(ce_classe[arquivo.caminho])
        classes.append({"arquivo": arquivo.caminho, "classe": arquivo.principal, "linhas": linhas_classe,
                        "metodos": sum(len(t["metodos"]) for t in arquivo.tipos),
                        "dependencias": dependencias, "dependentes": len(ca_classe[arquivo.caminho])})
        registrar("classe_linhas", arquivo.caminho, linhas_classe, cfg["classe_linhas"])
        registrar("classe_dependencias", arquivo.caminho, dependencias, cfg["classe_dependencias"])
        for tipo in arquivo.tipos:
            componentes = lcom4(tipo)
            elegiveis = sum(1 for m in tipo["metodos"] if not m["estatico"] and not m["construtor"])
            if (componentes is not None and len(componentes) >= cfg["coesao"]["min_componentes"]
                    and elegiveis >= cfg["coesao"]["min_metodos"]):
                achados.append({"nivel": "alerta", "regra": "coesao_lcom4", "alvo": f"{arquivo.caminho}#{tipo['nome']}",
                                "valor": len(componentes), "limite": cfg["coesao"]["min_componentes"] - 1,
                                "componentes": componentes})
            for m in tipo["metodos"]:
                alvo = f"{arquivo.caminho}:{m['linha']} {tipo['nome']}.{m['nome']}"
                metodos.append({"arquivo": arquivo.caminho, "classe": tipo["nome"],
                                **{k: v for k, v in m.items() if k != "corpo"},
                                "lcom4": len(componentes) if componentes is not None else None})
                for regra, chave in (("metodo_linhas", "linhas"), ("metodo_complexidade", "complexidade"),
                                     ("metodo_aninhamento", "aninhamento"), ("metodo_parametros", "parametros")):
                    registrar(regra, alvo, m[chave], cfg[regra])
    ciclos_pacotes = ciclos(arestas, pacote_de)
    ciclos_features = ciclos(arestas, feature_de)
    for ciclo in ciclos_pacotes:
        achados.append({"nivel": "alerta", "regra": "ciclo_pacotes", "alvo": " <-> ".join(ciclo),
                        "valor": len(ciclo), "limite": 1})
    dups = duplicacoes(arquivos, cfg["duplicacao"])
    conjuntas = mudanca_conjunta(raiz, feature_de, cfg["mudanca_conjunta"]) if usar_git else None
    achados.sort(key=lambda a: (a["nivel"] != "falha", a["regra"], -a["valor"], a["alvo"]))
    metodos.sort(key=lambda m: (m["arquivo"], m["linha"], m["nome"]))
    classes.sort(key=lambda c: c["arquivo"])
    return {
        "configuracao": cfg,
        "resumo": {"arquivos": len(arquivos), "tipos": sum(len(a.tipos) for a in arquivos), "metodos": len(metodos),
                   "falhas": sum(a["nivel"] == "falha" for a in achados),
                   "alertas": sum(a["nivel"] == "alerta" for a in achados)},
        "achados": achados,
        "metodos": metodos,
        "classes": classes,
        "pacotes": acoplamento(arestas, pacote_de, tipos_pacote),
        "features": acoplamento(arestas, feature_de, tipos_feature),
        "ciclos": {"pacotes": ciclos_pacotes, "features": ciclos_features},
        "duplicacoes": dups,
        "mudanca_conjunta": conjuntas,
        "quando_abstrair": quando_abstrair(achados, dups, conjuntas, cfg),
    }


def quando_abstrair(achados, dups, conjuntas, cfg):
    """Sugestões apenas com evidência acima dos gates; abaixo deles a recomendação é não abstrair."""
    sugestoes = []
    for achado in achados:
        # Alerta é sinal para a revisão; extração só é sugerida acima do limite de falha (playbook).
        if achado["regra"] in ("metodo_complexidade", "metodo_linhas", "metodo_aninhamento") and achado["nivel"] == "falha":
            sugestoes.append({"evidencia": f"{achado['regra']}={achado['valor']} > {achado['limite']}",
                              "alvo": achado["alvo"], "acao": "extrair método privado ou Helper puro no mesmo contexto"})
        elif achado["regra"] == "coesao_lcom4":
            sugestoes.append({"evidencia": f"LCOM4={achado['valor']} componentes", "alvo": achado["alvo"],
                              "acao": "avaliar separar responsabilidades; confirmar com mudança conjunta antes de dividir"})
    minimo = cfg["duplicacao"]["min_ocorrencias"]
    for dup in dups:
        if dup["max_no_mesmo_servico"] >= minimo:
            local = dup["locais"][0]
            sugestoes.append({"evidencia": f"duplicação {dup['max_no_mesmo_servico']}x no mesmo serviço "
                                           f"({dup['linhas_normalizadas']} linhas)",
                              "alvo": f"{local['arquivo']}:{local['inicio']}",
                              "acao": "extrair Helper/método no próprio serviço (regra de três)"})
    for par in conjuntas or []:
        sugestoes.append({"evidencia": f"mudança conjunta em {par['commits']} commits entre features",
                          "alvo": " + ".join(par["arquivos"]),
                          "acao": "revisar fronteira/contrato (mover ou unir); não criar interface só por isso"})
    return sugestoes


def formatar(relatorio, limite):
    r, linhas = relatorio["resumo"], []
    linhas.append("Diagnóstico de qualidade (aproximação léxica; ver docs/playbook-desenvolvimento.md)")
    linhas.append(f"Arquivos {r['arquivos']} | tipos {r['tipos']} | métodos {r['metodos']} | "
                  f"falhas {r['falhas']} | alertas {r['alertas']}")

    def secao(titulo, itens, fmt):
        linhas.append("")
        linhas.append(f"## {titulo}")
        if not itens:
            linhas.append("  (nenhum)")
        for item in itens[:limite]:
            linhas.append("  " + fmt(item))
        if len(itens) > limite:
            linhas.append(f"  ... +{len(itens) - limite} (use --json ou --limite)")

    secao("Falhas", [a for a in relatorio["achados"] if a["nivel"] == "falha"],
          lambda a: f"{a['regra']} {a['valor']} > {a['limite']}: {a['alvo']}")
    secao("Alertas", [a for a in relatorio["achados"] if a["nivel"] == "alerta"],
          lambda a: f"{a['regra']} {a['valor']} > {a['limite']}: {a['alvo']}")
    top = sorted(relatorio["metodos"], key=lambda m: (-m["complexidade"], -m["linhas"], m["arquivo"], m["linha"]))
    secao("Métodos mais complexos (CC, linhas, aninhamento, parâmetros)", top,
          lambda m: f"CC {m['complexidade']:>3} L {m['linhas']:>3} N {m['aninhamento']} P {m['parametros']} "
                    f"{m['arquivo']}:{m['linha']} {m['classe']}.{m['nome']}")
    maiores = sorted(relatorio["classes"], key=lambda c: (-c["linhas"], c["arquivo"]))
    secao("Classes maiores (linhas, métodos, Ce classe, Ca classe)", maiores,
          lambda c: f"L {c['linhas']:>4} M {c['metodos']:>3} Ce {c['dependencias']:>2} Ca {c['dependentes']:>2} "
                    f"{c['arquivo']}")
    secao("Acoplamento por feature (Ca, Ce, I, A, D)", relatorio["features"],
          lambda f: f"Ca {f['ca']:>3} Ce {f['ce']:>3} I {f['instabilidade']} A {f['abstracao']} D {f['distancia']} "
                    f"{f['unidade']}")
    ciclos_ = [("pacotes", c) for c in relatorio["ciclos"]["pacotes"]] + \
              [("features", c) for c in relatorio["ciclos"]["features"]]
    secao("Ciclos de dependência", ciclos_, lambda c: f"{c[0]}: {' <-> '.join(c[1])}")
    secao("Duplicação textual normalizada", relatorio["duplicacoes"],
          lambda d: f"{d['ocorrencias']}x ({d['linhas_normalizadas']} linhas, máx. {d['max_no_mesmo_servico']} "
                    f"no mesmo serviço): " + ", ".join(f"{l['arquivo']}:{l['inicio']}" for l in d["locais"]))
    if relatorio["mudanca_conjunta"] is None:
        linhas += ["", "## Mudança conjunta entre features", "  (histórico Git indisponível ou desativado)"]
    else:
        secao("Mudança conjunta entre features", relatorio["mudanca_conjunta"],
              lambda p: f"{p['commits']} commits: {' + '.join(p['arquivos'])}")
    secao("Quando abstrair (somente com evidência acima dos gates)", relatorio["quando_abstrair"],
          lambda s: f"[{s['evidencia']}] {s['alvo']} -> {s['acao']}")
    linhas += ["", "Abaixo dos gates não se abstrai: duplicação com menos de "
                   f"{relatorio['configuracao']['duplicacao']['min_ocorrencias']} ocorrências no mesmo serviço, "
                   "métodos/classes dentro dos limites e mudança isolada não justificam interface, camada ou "
                   "biblioteca compartilhada; duplicação entre serviços é esperada (serviços independentes)."]
    return "\n".join(linhas)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--raiz", default=str(RAIZ), help="diretório com */src/main/java (padrão: repositório)")
    parser.add_argument("--config", help="JSON que sobrescreve limiares de PADRAO")
    parser.add_argument("--json", action="store_true", help="emite JSON ordenado")
    parser.add_argument("--gate", action="store_true", help="código de saída 1 somente se houver falhas")
    parser.add_argument("--sem-git", action="store_true", help="não lê o histórico para mudança conjunta")
    parser.add_argument("--limite", type=int, default=10, help="itens por seção no texto")
    args = parser.parse_args(argv)
    if not Path(args.raiz).is_dir():
        print(f"Raiz inexistente: {args.raiz}", file=sys.stderr)
        return 2
    relatorio = analisar(args.raiz, carregar_configuracao(args.config), usar_git=not args.sem_git)
    try:
        if args.json:
            print(json.dumps(relatorio, ensure_ascii=False, indent=2, sort_keys=True))
        else:
            print(formatar(relatorio, args.limite))
    except BrokenPipeError:
        # Saída cortada (ex.: `| head`): sem traceback.
        sys.stderr.close()
    return 1 if args.gate and relatorio["resumo"]["falhas"] else 0


if __name__ == "__main__":
    sys.exit(main())
