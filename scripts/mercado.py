#!/usr/bin/env python3
"""RF01–RF05b: emite tokens curtos (mercado e ordens por conta), configuração do runner XLSX e verificações contra o
ambiente real; sem exibir a chave."""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import queue
import re
import subprocess
import threading
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from importlib import import_module

ambiente = import_module("preparar-ambiente")
ESCOPOS = {"leitura": ["mercado:leitura"], "preparacao": ["mercado:leitura", "mercado:preparacao"],
           "ordens": ["mercado:leitura", "ordens:enviar", "ordens:leitura"],
           "risco": ["risco:leitura"], "risco-preparacao": ["risco:preparacao"], "sessao": ["sessao:encerrar"], "posicoes": ["posicoes:leitura"]}
# Escopos do MO usam audiência própria (RF04b): token do FO não vale no MO e vice-versa.
AUDIENCIAS = {"risco": "prospero-middle-office", "risco-preparacao": "prospero-middle-office",
              "sessao": "prospero-middle-office", "posicoes": "prospero-back-office"}
POR_CONTA = {"ordens", "risco", "posicoes"}
ATIVOS = ["ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3"]
CONTAS = {"CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004"}
VALIDADE_MAXIMA = 3600
# Fixture histórica da tela: namespace separado do mercado real (ADR-014).
NAMESPACE_DEMO = "8d3df0f3-76d3-4ac0-b9a8-26f040e5721c"


def token(escopo, validade, conta=None):
    if escopo not in ESCOPOS or not 1 <= validade <= VALIDADE_MAXIMA:
        raise SystemExit("Escopo ou validade inválidos.")
    if (escopo in POR_CONTA) != (conta is not None) or (conta is not None and conta not in CONTAS):
        raise SystemExit("Escopos ordens e risco exigem --conta CONTA-001..004; demais escopos não aceitam conta.")
    chave = ambiente.ler(ambiente.path).get("FO_JWT_CHAVE")
    if not chave or len(chave) < 32:
        raise SystemExit("FO_JWT_CHAVE ausente; execute python3 scripts/preparar-ambiente.py.")
    agora = int(time.time())
    claims = {"iss": "prospero-local", "aud": AUDIENCIAS.get(escopo, "prospero-front-office"), "iat": agora,
              "exp": agora + validade, "groups": ESCOPOS[escopo]}
    if conta:
        claims["sub"] = conta
    return ambiente.jwt(claims, chave)


DEMO = {
    "ALFA3": ([("COMPRA", "10.30", 100), ("COMPRA", "10.29", 300), ("COMPRA", "10.28", 200), ("COMPRA", "10.25", 500),
               ("COMPRA", "10.20", 800), ("COMPRA", "10.10", 1000), ("VENDA", "10.36", 200), ("VENDA", "10.38", 400),
               ("VENDA", "10.40", 300), ("VENDA", "10.45", 700), ("VENDA", "10.50", 900)],
              [("10.20", 100), ("10.35", 300)]),
    "BETA3": ([("COMPRA", "19.80", 50), ("VENDA", "19.95", 120)], [("19.90", 500)]),
    "GAMA3": ([("VENDA", "30.50", 40)], []),
    "DELTA3": ([], []),
}


def preparar_demo(origem):
    # Namespace sintético exclusivo da demonstração; OMEGA3 permanece sem dados.
    token_prep = token("preparacao", 120)
    sessao = datetime.datetime.now(ZoneInfo("America/Sao_Paulo")).date().isoformat()
    for ticker, (ofertas, negocios) in DEMO.items():
        corpo = json.dumps({"sessao": sessao,
                            "ofertas": [{"lado": l, "preco": p, "quantidade": q} for l, p, q in ofertas],
                            "negocios": [{"preco": p, "quantidade": q} for p, q in negocios]}).encode()
        pedido = urllib.request.Request(f"{origem}/api/v1/preparacoes-mercado/{NAMESPACE_DEMO}/ativos/{ticker}",
                                        data=corpo, method="PUT", headers={
                                            "Authorization": "Bearer " + token_prep, "Content-Type": "application/json"})
        with urllib.request.urlopen(pedido, timeout=5) as resposta:
            if resposta.status != 200:
                raise SystemExit(f"Falha ao preparar {ticker}: HTTP {resposta.status}")
    print(f"Namespace sintético de demonstração preparado: {NAMESPACE_DEMO}")
    return NAMESPACE_DEMO


def verificar_ordens(origem):
    """Concorrência real no PostgreSQL (ADR-010): mesma chave → uma ordem; divergência → um 202 e demais 409."""
    tok = token("ordens", 120, "CONTA-003")

    def enviar(chave, corpo, caminho="/api/v1/ordens"):
        pedido = urllib.request.Request(origem + caminho, data=json.dumps(corpo).encode(), method="POST", headers={
            "Authorization": "Bearer " + tok, "Content-Type": "application/json", "X-Idempotency-Key": chave})
        try:
            with urllib.request.urlopen(pedido, timeout=10) as resposta:
                return resposta.status, json.load(resposta)
        except urllib.error.HTTPError as erro:
            return erro.code, None

    def limit(preco):
        return {"ticker": "GAMA3", "lado": "VENDA", "tipo": "LIMIT", "validade": "FOK", "quantidade": 3,
                "precoLimite": preco}

    with concurrent.futures.ThreadPoolExecutor(20) as executor:
        chave = "conc-" + uuid.uuid4().hex[:16]
        iguais = list(executor.map(lambda _: enviar(chave, limit("30.00")), range(20)))
        chave = "conc-" + uuid.uuid4().hex[:16]
        divergentes = list(executor.map(lambda i: enviar(chave, limit(f"30.{i:02d}")), range(10)))
        chave = "conc-" + uuid.uuid4().hex[:16]
        oco = {"ticker": "DELTA3", "lado": "VENDA", "quantidade": 5, "validade": "DIA",
               "takeProfit": {"precoLimite": "42.00"}, "stopLoss": {"tipo": "STOP", "precoGatilho": "38.00"}}
        grupos = list(executor.map(lambda _: enviar(chave, oco, "/api/v1/ordens-oco"), range(10)))
    falhas = []
    if {s for s, _ in iguais} != {202} or len({c["id"] for _, c in iguais}) != 1:
        falhas.append("mesma chave e mesmo corpo deveriam gerar uma única ordem")
    if sorted(s for s, _ in divergentes).count(202) != 1 or {s for s, _ in divergentes} - {202, 409}:
        falhas.append("corpos divergentes com a mesma chave deveriam gerar um 202 e 409 nos demais")
    if {s for s, _ in grupos} != {202} or len({c["grupoOco"] for _, c in grupos}) != 1:
        falhas.append("OCO concorrente com a mesma chave deveria gerar um único grupo")
    if falhas:
        raise SystemExit("Falha na verificação de ordens: " + "; ".join(falhas))
    print("Ordens: concorrência idempotente verificada (20 iguais, 10 divergentes, 10 OCO).")


def http(metodo, url, tok, corpo=None, cabecalhos=None):
    extra = {"Authorization": "Bearer " + tok, **(cabecalhos or {})}
    if corpo is not None:
        extra["Content-Type"] = "application/json"
    pedido = urllib.request.Request(url, method=metodo, data=None if corpo is None else json.dumps(corpo).encode(),
                                    headers=extra)
    with urllib.request.urlopen(pedido, timeout=10) as resposta:
        return json.load(resposta)


def preparar_carteira(origem_mo, conta, saldo, custodia, prazo=0):
    """Redefine carteira sem reservas vivas. Após limpeza conhecida, pode esperar os terminais do MO."""
    limite = time.monotonic() + prazo
    tok = token("risco-preparacao", 60)
    while True:
        try:
            return http("PUT", f"{origem_mo}/api/v1/preparacoes-risco/contas/{conta}", tok,
                        {"saldo": saldo, "custodia": custodia})
        except urllib.error.HTTPError as erro:
            if erro.code != 409 or time.monotonic() >= limite:
                raise
            try:
                problema = json.load(erro)
            except (ValueError, UnicodeError):
                raise erro
            if not isinstance(problema, dict) or problema.get("codigo") != "RESERVA_VIVA":
                raise
            erro.close()
            time.sleep(min(0.2, max(0, limite - time.monotonic())))


def carteira(origem_mo, conta):
    return http("GET", f"{origem_mo}/api/v1/risco/carteira", token("risco", 60, conta))


def aguardar_decisao(origem, tok, caminho, prazo=20.0):
    limite = time.monotonic() + prazo
    while time.monotonic() < limite:
        corpo = http("GET", origem + caminho, tok)
        ordens = corpo.get("ordens", [corpo])
        if all(o["estado"] != "RECEBIDA" for o in ordens):
            return ordens
        time.sleep(0.2)
    raise SystemExit(f"Risco sem resposta em {prazo:.0f} s para {caminho} (MO, Kafka ou tópicos indisponíveis?).")


def verificar_risco(origem, origem_mo):
    """RF04/RF05b: concorrência acima do saldo, rejeições, Market sem livro e OCO final.
    Limpa a Limit vencedora por negócio real na mesma conta; o OCO final fica vivo no livro com reserva única.
    Executar após as provas que preparam CONTA-002 e antes das provas sem preparação."""
    conta = "CONTA-002"
    sem_pendencias(origem, conta)
    preparar_carteira(origem_mo, conta, "1000000.00", {t: 60000 if t == "ALFA3" else 1000 for t in ATIVOS}, prazo=30)
    tok = token("ordens", 300, conta)

    def enviar(corpo, caminho="/api/v1/ordens"):
        return http("POST", origem + caminho, tok, corpo, {"X-Idempotency-Key": "risco-" + uuid.uuid4().hex})

    def limit(ticker, lado, quantidade, preco, validade="DIA"):
        return {"ticker": ticker, "lado": lado, "tipo": "LIMIT", "validade": validade,
                "quantidade": quantidade, "precoLimite": preco}

    with concurrent.futures.ThreadPoolExecutor(2) as executor:
        ids = list(executor.map(lambda _: enviar(limit("ALFA3", "COMPRA", 60000, "10.00"))["id"], range(2)))
    decisoes = [aguardar_decisao(origem, tok, f"/api/v1/ordens/{i}")[0] for i in ids]
    rejeitadas = [o for o in decisoes if o["estado"] == "REJEITADA"]
    vencedoras = [o for o in decisoes if o["estado"] != "REJEITADA"]
    if len(rejeitadas) != 1 or rejeitadas[0]["motivoRejeicao"] != "SALDO_INSUFICIENTE" or len(vencedoras) != 1:
        raise SystemExit("Risco: duas compras concorrentes excedendo o saldo não produziram uma reserva e uma rejeição.")
    aguardar_campos(origem, tok, f"/api/v1/ordens/{vencedoras[0]['id']}", {"estado": "ABERTA"})
    aguardar_campos(origem_mo, token("risco", 120, conta), "/api/v1/risco/carteira",
                    {"saldoReservado": "600000.00", "saldoDisponivel": "400000.00"})
    for corpo, motivo in [(limit("GAMA3", "VENDA", 1001, "30.00"), "CUSTODIA_INSUFICIENTE"),
                          (limit("BETA3", "COMPRA", 100001, "20.00"), "QUANTIDADE_MAXIMA_EXCEDIDA")]:
        criada = enviar(corpo)
        aguardar_campos(origem, tok, f"/api/v1/ordens/{criada['id']}", {"estado": "REJEITADA", "motivoRejeicao": motivo})
    market = enviar({"ticker": "BETA3", "lado": "COMPRA", "tipo": "MARKET", "validade": "IOC", "quantidade": 10})
    aguardar_campos(origem, tok, f"/api/v1/ordens/{market['id']}",
                    {"estado": "CANCELADA", "quantidadeExecutada": 0, "quantidadeCancelada": 10})
    limpar = enviar(limit("ALFA3", "VENDA", 60000, "10.00", "IOC"))
    aguardar_campos(origem, tok, f"/api/v1/ordens/{limpar['id']}", {"estado": "EXECUTADA", "quantidadeExecutada": 60000})
    aguardar_campos(origem, tok, f"/api/v1/ordens/{vencedoras[0]['id']}", {"estado": "EXECUTADA"})
    aguardar_campos(origem_mo, token("risco", 120, conta), "/api/v1/risco/carteira",
                    {"saldoTotal": "400000.00", "saldoReservado": "0.00", "saldoDisponivel": "400000.00"})
    oco = enviar({"ticker": "DELTA3", "lado": "VENDA", "quantidade": 5, "validade": "DIA",
                   "takeProfit": {"precoLimite": "42.00"}, "stopLoss": {"tipo": "STOP", "precoGatilho": "38.00"}},
                  "/api/v1/ordens-oco")
    # RF05c.3b: o par é admitido inteiro; TP repousa e SL aguarda o gatilho. O verificar-robo negocia DELTA3 só a 40,05,
    # dentro de 38,00–42,00: nenhum negócio aciona o gatilho nem cruza o TP.
    limite = time.monotonic() + 20.0
    while True:
        par = aguardar_decisao(origem, tok, f"/api/v1/ordens-oco/{oco['grupoOco']}")
        estados = [o["estado"] for o in par]
        if estados == ["ABERTA", "AGUARDANDO_GATILHO"]:
            break
        if time.monotonic() > limite or "REJEITADA" in estados:
            raise SystemExit(f"Risco: OCO não admitiu as duas pernas juntas ({estados}).")
        time.sleep(0.2)
    final = carteira(origem_mo, conta)
    delta = next(c for c in final["custodias"] if c["ticker"] == "DELTA3")
    if final["saldoReservado"] != "0.00" or delta["reservado"] != 5:
        raise SystemExit("Risco: reserva única OCO divergente.")
    print("Risco: concorrência, rejeições, Market terminal, limpeza real e OCO admitido com reserva única verificados.")


RAIZ = Path(__file__).resolve().parent.parent


def compose(projeto):
    # --project-directory: o alvo é sempre este repositório, qualquer que seja o diretório de chamada.
    base = ["docker", "compose", "--project-directory", str(RAIZ), "-f", str(RAIZ / "docker-compose.yml")]
    if projeto:
        # Mesma lista de arquivos do ci-testes.sh (inclui o robô opt-in, sem ligar o perfil).
        return base + ["-p", projeto, "-f", str(RAIZ / "docker-compose.ci.yml"), "-f", str(RAIZ / "docker-compose.robo.yml")]
    return base


def sem_pendencias(origem, conta, prazo=30.0):
    """Espera a conta não ter ordens recentes RECEBIDA (comandos em trânsito) antes de preparar a carteira. Ordens
    com mais de 5 minutos são ignoradas: as anteriores à V5 do FO nunca tiveram comando e ficam RECEBIDA (ADR-012)."""
    tok = token("ordens", 60, conta)
    limite = time.monotonic() + prazo
    corte = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(minutes=5)

    def pendentes():
        return [o for o in http("GET", f"{origem}/api/v1/ordens?limite=100", tok)["ordens"]
                if o["estado"] == "RECEBIDA" and datetime.datetime.fromisoformat(o["recebidaEm"]) > corte]

    while pendentes():
        if time.monotonic() > limite:
            raise SystemExit(f"{conta} ainda tem ordens aguardando o risco após {prazo:.0f} s.")
        time.sleep(0.3)


def verificar_concorrencia(origem_mo, projeto):
    try:
        _verificar_concorrencia(origem_mo, projeto)
    finally:
        # Linhas de controle alteradas por SQL direto voltam ao estado semeado, mesmo se a prova falhar.
        preparar_carteira(origem_mo, "CONTA-003", "1000000.00", {t: 1000 for t in ATIVOS})


def _verificar_concorrencia(origem_mo, projeto):
    """Prova SQL com duas conexões reais no PostgreSQL do MO (HLD-003): ler-e-depois-gravar concede 140 sobre 100;
    o UPDATE condicional usado pelo MO concede só uma reserva de 70 e a segunda reavalia após o commit da primeira."""
    psql = compose(projeto) + ["exec", "-T", "postgres-middle-office", "psql", "-X", "-U", "prospero_mo", "-d",
                               "prospero_mo", "-v", "ON_ERROR_STOP=1", "-A", "-t"]

    class Sessao:
        def __init__(self):
            # stderr separado: avisos do Compose não se misturam às linhas de resultado do psql.
            self.p = subprocess.Popen(psql, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                      text=True, bufsize=1)
            self.linhas = queue.Queue()
            threading.Thread(target=lambda: [self.linhas.put(l.strip()) for l in self.p.stdout], daemon=True).start()

        def enviar(self, sql):
            self.p.stdin.write(sql + "\n")
            self.p.stdin.flush()

        def ler(self, prazo=10.0):
            return self.linhas.get(timeout=prazo)

        def fechar(self):
            self.p.stdin.close()
            self.p.wait(timeout=10)

    def unico(sql):
        return subprocess.check_output(psql + ["-c", sql], text=True, stderr=subprocess.DEVNULL).strip()

    conta = "CONTA-003"
    filtro = f"conta = '{conta}'"
    # RED: sem proteção, as duas sessões leem 100, ambas concedem 70 e gravam o valor calculado na leitura.
    preparar_carteira(origem_mo, conta, "100.00", {}, prazo=30)
    a, b = Sessao(), Sessao()
    concedidas = 0
    for s in (a, b):
        s.enviar(f"BEGIN; SELECT disponivel FROM conta_risco WHERE {filtro};")
    for s in (a, b):
        if s.ler() != "BEGIN" or s.ler() != "100.00":
            raise SystemExit("Concorrência SQL: leitura inicial inesperada.")
        concedidas += 1
    for s in (a, b):
        s.enviar(f"UPDATE conta_risco SET disponivel = 30.00, reservado = 70.00 WHERE {filtro}; COMMIT;")
        s.ler()
        s.ler()
        s.fechar()
    perdida = unico(f"SELECT disponivel || '|' || reservado FROM conta_risco WHERE {filtro}")
    if concedidas != 2 or perdida != "30.00|70.00":
        raise SystemExit("Concorrência SQL: anomalia de ler-e-depois-gravar não reproduzida.")
    # GREEN: UPDATE condicional (o mesmo de RepositorioRiscoJpa.reservarCaixa).
    preparar_carteira(origem_mo, conta, "100.00", {})
    reservar = (f"UPDATE conta_risco SET disponivel = disponivel - 70, reservado = reservado + 70 "
                f"WHERE {filtro} AND disponivel >= 70;")
    a, b = Sessao(), Sessao()
    a.enviar("BEGIN; " + reservar)
    if (a.ler(), a.ler()) != ("BEGIN", "UPDATE 1"):
        raise SystemExit("Concorrência SQL: primeira reserva não aplicada.")
    b.enviar("BEGIN; SET LOCAL lock_timeout = '15s'; SELECT pg_backend_pid();")
    b.ler()
    b.ler()
    pid_b = b.ler()
    if not pid_b.isdigit():
        raise SystemExit("Concorrência SQL: pid da segunda sessão inválido.")
    b.enviar(reservar)
    limite = time.monotonic() + 10
    while unico(f"SELECT wait_event_type FROM pg_stat_activity WHERE pid = {int(pid_b)}") != "Lock":
        if time.monotonic() > limite:
            raise SystemExit("Concorrência SQL: a segunda sessão não esperou o lock da linha de controle.")
        time.sleep(0.2)
    a.enviar("COMMIT;")
    a.ler()
    segunda = b.ler(15)
    b.enviar("COMMIT;")
    b.ler()
    a.fechar()
    b.fechar()
    estado = unico(f"SELECT disponivel || '|' || reservado FROM conta_risco WHERE {filtro}")
    if segunda != "UPDATE 0" or estado != "30.00|70.00":
        raise SystemExit(f"Concorrência SQL: esperado UPDATE 0 e 30.00|70.00, obtido {segunda} e {estado}.")
    print("Concorrência SQL: ler-e-depois-gravar concede 140 sobre 100; UPDATE condicional concede uma reserva de 70.")


def verificar_recuperacao(origem, origem_mo, projeto):
    """MO parado: ordem fica RECEBIDA e é decidida uma vez ao voltar; comando reentregue e mensagem inválida no tópico
    não criam reserva nem travam o consumo (ADR-012)."""
    base = compose(projeto)
    conta = "CONTA-003"
    sem_pendencias(origem, conta)
    preparar_carteira(origem_mo, conta, "1000.00", {"ALFA3": 15}, prazo=30)
    tok = token("ordens", 300, conta)
    ordem = {"ticker": "ALFA3", "lado": "COMPRA", "tipo": "LIMIT", "validade": "DIA", "quantidade": 10,
             "precoLimite": "10.00"}
    subprocess.run(base + ["stop", "middle-office"], check=True, capture_output=True)
    try:
        criada = http("POST", origem + "/api/v1/ordens", tok, ordem,
                      {"X-Idempotency-Key": "recup-" + uuid.uuid4().hex[:16]})
        time.sleep(3)
        if http("GET", f"{origem}/api/v1/ordens/{criada['id']}", tok)["estado"] != "RECEBIDA":
            raise SystemExit("Recuperação: ordem decidida com o MO parado.")
    finally:
        subprocess.run(base + ["start", "middle-office"], check=True, capture_output=True)
    if projeto:
        # Porta publicada dinâmica (CI) pode mudar quando o contêiner reinicia.
        origem_mo = "http://" + subprocess.check_output(base + ["port", "middle-office", "8080"], text=True).strip()
    limite = time.monotonic() + 180
    while True:
        try:
            if http("GET", origem_mo + "/q/health/ready", "x").get("status") == "UP":
                break
        except (urllib.error.URLError, ConnectionError, ValueError):
            pass
        if time.monotonic() > limite:
            raise SystemExit("Recuperação: MO não voltou a ficar pronto.")
        time.sleep(1)
    aguardar_campos(origem, tok, f"/api/v1/ordens/{criada['id']}", {"estado": "ABERTA"}, 60)
    aguardar_campos(origem_mo, token("risco", 120, conta), "/api/v1/risco/carteira", {"saldoReservado": "100.00"})
    # Reentrega do mesmo comando (cópia da outbox do FO) e mensagem inválida, seguidas de uma ordem nova.
    intencao = str(uuid.UUID(criada["id"]))
    linhas = subprocess.check_output(base + [
        "exec", "-T", "postgres-front-office", "psql", "-X", "-A", "-t", "-U", "prospero_fo", "-d", "prospero_fo",
        # Harness interno sobre a pequena outbox efêmera CI: igualdade exata, sem busca por substring.
        # O JSON legado não tem índice de correlação; esta leitura pontual não entra no caminho das APIs.
        "-c", f"SELECT payload FROM outbox WHERE topico = 'risco.comandos.v1' "
              f"AND payload::jsonb->>'correlacao' = '{intencao}' LIMIT 2"],
        text=True, stderr=subprocess.DEVNULL).strip().splitlines()
    if len(linhas) != 1 or not linhas[0].startswith("{"):
        raise SystemExit("Recuperação: comando original não encontrado na outbox do FO para a reentrega.")
    payload = linhas[0]

    def mo_sql(sql):
        return subprocess.check_output(base + ["exec", "-T", "postgres-middle-office", "psql", "-X", "-A", "-t", "-U",
                                               "prospero_mo", "-d", "prospero_mo", "-c", sql], text=True,
                                       stderr=subprocess.DEVNULL).strip()

    rejeitados_antes = int(mo_sql("SELECT count(*) FROM evento_rejeitado"))
    mensagens = f"{conta}|{payload}\n{conta}|{{nao-e-json\n"
    subprocess.run(base + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server",
                           "localhost:9092", "--topic", "risco.comandos.v1", "--property", "parse.key=true",
                           "--property", "key.separator=|"], input=mensagens, text=True, check=True,
                   capture_output=True)
    nova = http("POST", origem + "/api/v1/ordens", tok, {**ordem, "quantidade": 5},
                {"X-Idempotency-Key": "recup-" + uuid.uuid4().hex[:16]})
    aguardar_campos(origem, tok, f"/api/v1/ordens/{nova['id']}", {"estado": "ABERTA"})
    reservado = carteira(origem_mo, conta)["saldoReservado"]
    decisoes = mo_sql(f"SELECT count(*) FROM decisao_risco WHERE intencao = '{intencao}'")
    rejeitados = int(mo_sql("SELECT count(*) FROM evento_rejeitado")) - rejeitados_antes
    limpeza = http("POST", origem + "/api/v1/ordens", tok,
                   {**ordem, "lado": "VENDA", "quantidade": 15, "validade": "IOC"},
                   {"X-Idempotency-Key": "recup-limpeza-" + uuid.uuid4().hex})
    aguardar_campos(origem, tok, f"/api/v1/ordens/{limpeza['id']}", {"estado": "EXECUTADA"})
    aguardar_campos(origem_mo, token("risco", 120, conta), "/api/v1/risco/carteira", {"saldoReservado": "0.00"})
    preparar_carteira(origem_mo, conta, "1000000.00", {t: 1000 for t in ATIVOS}, prazo=30)
    if (reservado, decisoes, rejeitados) != ("150.00", "1", 1):
        raise SystemExit(f"Recuperação: esperado 150.00 reservado, 1 decisão e 1 rejeitada; obtido {reservado}, "
                         f"{decisoes} e {rejeitados}.")
    print("Recuperação: MO parado mantém RECEBIDA e decide uma vez; reentrega e mensagem inválida sem efeito.")


def aguardar_campos(origem, tok, caminho, esperado, prazo=30.0):
    """Polling de projeções assíncronas, incluindo liberação financeira após o terminal do FO."""
    limite = time.monotonic() + prazo
    while time.monotonic() < limite:
        try:
            corpo = http("GET", origem + caminho, tok)
        except (urllib.error.URLError, ConnectionError):
            if caminho != "/q/health/ready":
                raise
            time.sleep(0.5)
            continue
        if all(corpo.get(k) == v for k, v in esperado.items()):
            return corpo
        time.sleep(0.2)
    raise SystemExit(f"Matching: projeção não atingiu {esperado} em {prazo:.0f} s para {caminho}.")


def verificar_matching(origem, origem_mo, projeto):
    """Livro real após reinício do ME; reentrega de admissão sem segunda oferta/execução ou débito.
    Apenas a prova de reentrega lê outbox/Kafka; ordens e carteiras usam APIs e a limpeza é negócio real.
    Requer contas 002/003 sem reservas anteriores; preserva todos os fatos e nunca apaga livro/reservas."""
    base = compose(projeto)
    for conta in ("CONTA-002", "CONTA-003"):
        sem_pendencias(origem, conta)
        preparar_carteira(origem_mo, conta, "1000.00", {"ALFA3": 100}, prazo=30)
    tokens = {c: token("ordens", 600, c) for c in ("CONTA-002", "CONTA-003")}

    def enviar(conta, lado, quantidade, validade="DIA"):
        return http("POST", origem + "/api/v1/ordens", tokens[conta],
                    {"ticker": "ALFA3", "lado": lado, "tipo": "LIMIT", "validade": validade,
                     "quantidade": quantidade, "precoLimite": "10.00"},
                    {"X-Idempotency-Key": "matching-" + uuid.uuid4().hex})

    def ordem(conta, id, **esperado):
        return aguardar_campos(origem, tokens[conta], f"/api/v1/ordens/{id}", esperado)

    def saldo(conta, total):
        return aguardar_campos(origem_mo, token("risco", 120, conta), "/api/v1/risco/carteira",
                              {"saldoTotal": total, "saldoDisponivel": total, "saldoReservado": "0.00"})

    venda = enviar("CONTA-003", "VENDA", 10)
    ordem("CONTA-003", venda["id"], estado="ABERTA")
    compra = enviar("CONTA-002", "COMPRA", 4, "IOC")
    ordem("CONTA-002", compra["id"], estado="EXECUTADA", quantidadeExecutada=4, quantidadeCancelada=0)
    ordem("CONTA-003", venda["id"], estado="PARCIALMENTE_EXECUTADA", quantidadeExecutada=4)
    saldo("CONTA-002", "960.00")
    # Recria o motor a partir do log com uma oferta parcialmente consumida.
    subprocess.run(base + ["restart", "matching-engine"], check=True, capture_output=True)
    origem_me = "http://" + subprocess.check_output(base + ["port", "matching-engine", "8080"], text=True).strip()
    aguardar_campos(origem_me, "x", "/q/health/ready", {"status": "UP"}, 180)
    # Mesma admissão após restart precisa ser deduplicada: não pode restaurar quantidade já executada.
    intencao = str(uuid.UUID(venda["id"]))
    # Mesmo UUID v3 de UUID.nameUUIDFromBytes no MO; busca pela PK, sem varrer payloads.
    admissao = uuid.UUID(bytes=hashlib.md5(("admissao:" + intencao).encode(), usedforsecurity=False).digest(), version=3)
    consulta = f"SELECT payload FROM outbox WHERE id = '{admissao}'"
    linhas = subprocess.check_output(base + ["exec", "-T", "postgres-middle-office", "psql", "-X", "-A", "-t",
        "-U", "prospero_mo", "-d", "prospero_mo", "-c", consulta], text=True,
        stderr=subprocess.DEVNULL).strip().splitlines()
    if len(linhas) != 1:
        raise SystemExit("Matching: admissão única não encontrada na outbox do MO.")
    subprocess.run(base + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server",
        "localhost:9092", "--topic", "matching.comandos.v1", "--property", "parse.key=true", "--property",
        "key.separator=|"], input="ALFA3|" + linhas[0] + "\n", text=True, check=True, capture_output=True)
    restante = enviar("CONTA-002", "COMPRA", 6, "IOC")
    ordem("CONTA-002", restante["id"], estado="EXECUTADA", quantidadeExecutada=6, quantidadeCancelada=0)
    ordem("CONTA-003", venda["id"], estado="EXECUTADA", quantidadeExecutada=10, quantidadeCancelada=0)
    saldo("CONTA-002", "900.00")
    final_venda = saldo("CONTA-003", "1000.00")
    alfa = next(c for c in final_venda["custodias"] if c["ticker"] == "ALFA3")
    if (alfa["total"], alfa["disponivel"], alfa["reservado"]) != (90, 90, 0):
        raise SystemExit("Matching: custódia divergente após recuperação e reentrega.")
    # A sobra falsa de uma admissão repetida faria esta FOK executar em vez de cancelar.
    prova = enviar("CONTA-002", "COMPRA", 1, "FOK")
    ordem("CONTA-002", prova["id"], estado="CANCELADA", quantidadeExecutada=0, quantidadeCancelada=1,
          motivoEncerramento="FOK_SEM_LIQUIDEZ")
    saldo("CONTA-002", "900.00")
    print("Matching: execução parcial, reinício/replay com oferta viva, reentrega, ausência de débito duplicado e livro vazio verificados.")


def verificar_robo(origem, projeto):
    """RF05d.2: robô formador (CONTA-004) cota DELTA3, repõe só o nível consumido e não duplica após reinício.

    Verificação pelo FO (ordens da CONTA-004); /estado (via exec, sem porta publicada) só confirma o estado e os ciclos.
    DELTA3: OCOs vivas do risco.xlsx (TP 42,00/38,00, gatilhos 38,00/42,00) ficam fora da grade 39,94–40,06 e da
    agressão a 40,05, sem cruzar nem acionar. O robô para ao fim; as ofertas DIA ficam vivas (encerramento só de GAMA3)."""
    base = compose(projeto) + ([] if projeto else ["-f", str(RAIZ / "docker-compose.robo.yml")]) + ["--profile", "robo"]
    ambiente_robo = {**os.environ, "ROBO_ATIVOS": "DELTA3:40.00:0.01:10", "ROBO_NIVEIS": "2", "ROBO_INTERVALO_MS": "500"}
    robo_tok = token("ordens", 300, "CONTA-004")

    def ordens_robo():
        return [o for o in http("GET", origem + "/api/v1/ordens?limite=100", robo_tok)["ordens"] if o["ticker"] == "DELTA3"]

    def estado(ciclos_minimos, prazo=60.0):
        limite = time.monotonic() + prazo
        while time.monotonic() < limite:
            saida = subprocess.run(base + ["exec", "-T", "robo-formador", "/robo-formador", "--estado"],
                                   capture_output=True, text=True, env=ambiente_robo)
            try:
                corpo = json.loads(saida.stdout) if saida.returncode == 0 else {}
            except ValueError:
                corpo = {}
            if corpo.get("estado") == "ATIVO" and corpo.get("ciclos", 0) >= ciclos_minimos:
                return corpo
            time.sleep(0.5)
        raise SystemExit(f"Robô: /estado não ficou ATIVO com {ciclos_minimos} ciclos em {prazo:.0f} s.")

    def vivas(esperadas, total, prazo=30.0):
        limite = time.monotonic() + prazo
        ordens = []
        while time.monotonic() < limite:
            ordens = ordens_robo()
            if (sorted((o["lado"], o["precoLimite"]) for o in ordens if o["estado"] == "ABERTA") == esperadas
                    and len(ordens) == total):
                return ordens
            time.sleep(0.5)
        raise SystemExit(f"Robô: esperadas {total} ordens com abertas {esperadas}; obtidas {len(ordens)} em {prazo:.0f} s.")

    niveis = [("COMPRA", "39.94"), ("COMPRA", "39.95"), ("VENDA", "40.05"), ("VENDA", "40.06")]
    try:
        # Sem --no-deps: localmente o FO pode ser recriado para entrar na rede interna do robô.
        subprocess.run(base + ["up", "-d", "--wait", "--wait-timeout", "120", "robo-formador"], check=True,
                       env=ambiente_robo)
        estado(1)
        vivas(niveis, 4)
        demo = token("ordens", 120, "CONTA-001")
        compra = http("POST", origem + "/api/v1/ordens", demo, {"ticker": "DELTA3", "lado": "COMPRA", "tipo": "LIMIT",
                      "validade": "IOC", "quantidade": 10, "precoLimite": "40.05"}, {"X-Idempotency-Key": "robo-" + uuid.uuid4().hex})
        aguardar_campos(origem, demo, f"/api/v1/ordens/{compra['id']}", {"estado": "EXECUTADA", "quantidadeExecutada": 10})
        depois = vivas(niveis, 5)
        if sum(o["estado"] == "EXECUTADA" for o in depois) != 1:
            raise SystemExit("Robô: reposição deveria criar exatamente uma oferta nova no nível consumido.")
        subprocess.run(base + ["restart", "robo-formador"], check=True, env=ambiente_robo)
        # Ciclos completos após o reinício sem ordem nova provam que as chaves determinísticas não duplicaram.
        estado(3)
        if len(ordens_robo()) != 5:
            raise SystemExit("Robô: reinício duplicou ofertas.")
    finally:
        subprocess.run(base + ["stop", "robo-formador"], env=ambiente_robo)
    print("Robô formador: cotação, reposição do nível consumido e reinício sem duplicação verificados.")


def escrever_config(destino, conteudo):
    # 0644 para o UID do container ler via bind individual: o destino deve estar em diretório privado (0700).
    fd = os.open(destino, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o644)
    with os.fdopen(fd, "w") as stream:
        stream.write(conteudo)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="comando", required=True)
    t = sub.add_parser("token", help="Imprime token curto no stdout.")
    t.add_argument("--escopo", choices=sorted(ESCOPOS), default="leitura")
    t.add_argument("--validade", type=int, default=900)
    t.add_argument("--conta", choices=sorted(CONTAS))
    c = sub.add_parser("config-runner", help="Grava configuração do runner com token de preparação.")
    c.add_argument("destino")
    c.add_argument("--origem", default="http://front-office:8080")
    c.add_argument("--origem-bo", default="http://back-office:8080")
    c.add_argument("--origem-mo", default="http://middle-office:8080")
    c.add_argument("--validade", type=int, default=900)
    c.add_argument("--perfil", choices=["mercado", "ordens", "risco", "sessao", "posicoes"], default="mercado",
                   help="Menor privilégio: cada workbook recebe só os tokens de que precisa.")
    v = sub.add_parser("verificar-ordens", help="Verifica idempotência concorrente no PostgreSQL real (CONTA-003).")
    v.add_argument("--origem", default="http://127.0.0.1:8081")
    r = sub.add_parser("verificar-risco", help="Verifica reservas pela SAGA FO→MO (prepara CONTA-002).")
    r.add_argument("--origem", default="http://127.0.0.1:8081")
    r.add_argument("--origem-mo", default="http://127.0.0.1:8082")
    q = sub.add_parser("verificar-concorrencia", help="Prova SQL com duas conexões no PostgreSQL do MO (CONTA-003).")
    q.add_argument("--origem-mo", default="http://127.0.0.1:8082")
    q.add_argument("--projeto", help="Projeto Compose de CI (usa docker-compose.ci.yml).")
    e = sub.add_parser("verificar-recuperacao", help="Para o MO, verifica RECEBIDA, reentrega e mensagem inválida.")
    e.add_argument("--origem", default="http://127.0.0.1:8081")
    e.add_argument("--origem-mo", default="http://127.0.0.1:8082")
    e.add_argument("--projeto", help="Projeto Compose de CI (usa docker-compose.ci.yml).")
    m = sub.add_parser("verificar-matching", help="Verifica matching real, replay e reentrega após reinício do ME.")
    m.add_argument("--origem", default="http://127.0.0.1:8081")
    m.add_argument("--origem-mo", default="http://127.0.0.1:8082")
    m.add_argument("--projeto", help="Projeto Compose de CI (usa docker-compose.ci.yml).")
    b = sub.add_parser("verificar-robo", help="Sobe o robô formador (CONTA-004) em DELTA3 e verifica reposição e reinício.")
    b.add_argument("--origem", default="http://127.0.0.1:8081")
    b.add_argument("--projeto", help="Projeto Compose de CI (usa docker-compose.ci.yml).")
    d = sub.add_parser("preparar-demo", help="Prepara o UUID sintético da fixture histórica da tela; preserva o mercado real.")
    d.add_argument("--origem", default="http://127.0.0.1:8081")
    args = parser.parse_args()
    if args.comando in {"preparar-demo", "verificar-ordens", "verificar-risco", "verificar-concorrencia",
                        "verificar-recuperacao", "verificar-matching", "verificar-robo"}:
        for origem in (getattr(args, "origem", None), getattr(args, "origem_mo", None)):
            partes = urlsplit(origem) if origem else None
            if partes and (partes.scheme != "http" or partes.hostname not in {"127.0.0.1", "localhost"}
                           or partes.username or partes.password or partes.path not in {"", "/"} or partes.query
                           or partes.fragment):
                raise SystemExit("Origem deve ser http local (loopback), sem credenciais nem caminho.")
        if getattr(args, "projeto", None) and not re.fullmatch(r"prospero-ci-\d+-\d+", args.projeto):
            raise SystemExit("Projeto Compose de CI inválido.")
        match args.comando:
            case "preparar-demo":
                preparar_demo(args.origem)
            case "verificar-ordens":
                verificar_ordens(args.origem)
            case "verificar-risco":
                verificar_risco(args.origem, args.origem_mo)
            case "verificar-concorrencia":
                verificar_concorrencia(args.origem_mo, args.projeto)
            case "verificar-matching":
                verificar_matching(args.origem, args.origem_mo, args.projeto)
            case "verificar-recuperacao":
                verificar_recuperacao(args.origem, args.origem_mo, args.projeto)
            case "verificar-robo":
                verificar_robo(args.origem, args.projeto)
        return
    if args.comando == "token":
        print(token(args.escopo, args.validade, args.conta))
        return
    risco = lambda valor: {"origem": args.origem_mo, "headers": {"Authorization": "Bearer " + valor}}
    # ordens-a/ordens-b: mesma origem com contas distintas, para testar isolamento entre contas.
    bearer = lambda valor: {"origem": args.origem, "headers": {"Authorization": "Bearer " + valor}}
    if args.perfil == "mercado":
        config = {"servicos": {"front-office": bearer(token("preparacao", args.validade))}}
    elif args.perfil == "sessao":
        # RF05c.4: ordens da CONTA-002 e encerramento manual; executado por último (uma sessão por log).
        config = {"servicos": {"ordens-a": bearer(token("ordens", args.validade, "CONTA-002")),
                               "sessao": risco(token("sessao", args.validade))}}
    elif args.perfil == "ordens":
        config = {"servicos": {"ordens-a": bearer(token("ordens", args.validade, "CONTA-002")),
                               "ordens-b": bearer(token("ordens", args.validade, "CONTA-003"))}}
    else:
        # RF04b: ordens e carteira da CONTA-002, carteira da CONTA-003 (isolamento) e preparação de carteiras.
        config = {"servicos": {"ordens-a": bearer(token("ordens", args.validade, "CONTA-002")),
                               "ordens-b": bearer(token("ordens", args.validade, "CONTA-003")),
                               "risco-a": risco(token("risco", args.validade, "CONTA-002")),
                               "risco-b": risco(token("risco", args.validade, "CONTA-003")),
                               "preparacao-risco": risco(token("risco-preparacao", args.validade)),
                               "mercado": bearer(token("leitura", args.validade))}}
    if args.perfil == "posicoes":
        config["servicos"]["posicoes-anonimo"] = {"origem": args.origem_bo}
        config["servicos"]["posicoes-token-fo"] = {"origem": args.origem_bo, "headers": {
            "Authorization": "Bearer " + token("ordens", args.validade, "CONTA-002")}}
        config["servicos"]["posicoes-a"] = {"origem": args.origem_bo, "headers": {
            "Authorization": "Bearer " + token("posicoes", args.validade, "CONTA-002")}}
        config["servicos"]["posicoes-b"] = {"origem": args.origem_bo, "headers": {
            "Authorization": "Bearer " + token("posicoes", args.validade, "CONTA-003")}}
    escrever_config(args.destino, json.dumps(config, indent=2) + "\n")
    print("Configuração do runner gravada; token não exibido.")


if __name__ == "__main__":
    main()
