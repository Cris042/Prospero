#!/usr/bin/env python3
"""RF08: conciliação de movimentos exatos e verificação de liquidação somente por APIs locais."""
from collections import defaultdict
from decimal import Decimal, localcontext
import re

ATIVOS = {"ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3"}
CONTAS = {f"CONTA-00{i}" for i in range(1, 5)}


def numero(valor, financeiro=False):
    if not isinstance(valor, str) or not re.fullmatch(r"[0-9]{1,24}\.[0-9]{2}" if financeiro else r"[0-9]{1,24}", valor):
        raise ValueError("Valor fora do contrato textual exato.")
    return Decimal(valor)


def snapshot(conta, carteira):
    if carteira.get("conta") != conta or conta not in CONTAS:
        raise ValueError("Snapshot sem conta válida.")
    total = numero(carteira["saldoTotal"], True)
    if numero(carteira["saldoDisponivel"], True) + numero(carteira["saldoReservado"], True) != total:
        raise ValueError("Snapshot financeiro incoerente.")
    valores = {"BRL": total}
    for custodia in carteira["custodias"]:
        ticker = custodia["ticker"]
        if ticker not in ATIVOS or ticker in valores:
            raise ValueError("Custódia duplicada ou inválida.")
        for campo in ("total", "disponivel", "reservado"):
            if type(custodia[campo]) is not int or custodia[campo] < 0:
                raise ValueError("Custódia fora do contrato inteiro.")
        if custodia["disponivel"] + custodia["reservado"] != custodia["total"]:
            raise ValueError("Snapshot de custódia incoerente.")
        valores[ticker] = Decimal(custodia["total"])
    return valores


def conciliar(negocios, lancamentos, antes, depois):
    """Compara somente créditos dos lados selecionados; o snapshot já deve incluir todos os débitos D0."""
    try:
        with localcontext() as contexto:
            contexto.prec = 60
            return _conciliar(negocios, lancamentos, antes, depois)
    except (KeyError, TypeError, ArithmeticError) as erro:
        raise ValueError("Dados incompletos ou incoerentes para conciliação.") from erro


def _conciliar(negocios, lancamentos, antes, depois):
    if not antes or set(antes) != set(depois):
        raise ValueError("Snapshots incompletos.")
    esperados = defaultdict(Decimal)
    participacoes = {}
    for n in negocios:
        chave = (n["conta"], n["negocio"], n["lado"])
        if chave in participacoes or n["conta"] not in antes or n["ticker"] not in ATIVOS:
            raise ValueError("Participação duplicada ou sem referência.")
        if n["lado"] not in {"COMPRADOR", "VENDEDOR"} or n["liquidacao"] not in {"PENDENTE", "LIQUIDADO"}:
            raise ValueError("Estado ou lado inválido.")
        quantidade, valor = numero(n["quantidade"]), numero(n["valor"], True)
        if quantidade <= 0 or valor <= 0 or numero(n["preco"], True) * quantidade != valor:
            raise ValueError("Fato econômico incoerente.")
        participacoes[chave] = n
        if n["liquidacao"] == "LIQUIDADO":
            unidade, credito = (n["ticker"], quantidade) if n["lado"] == "COMPRADOR" else ("BRL", valor)
            esperados[(n["conta"], unidade)] += credito
    grupos = defaultdict(list)
    transacoes = {}
    for linha in lancamentos:
        if linha["momento"] == "D0":
            continue
        chave = (linha["conta"], linha["negocio"], linha["lado"])
        if linha["momento"] != "D2" or chave not in participacoes:
            raise ValueError("Partida sem negócio selecionado.")
        transacao = linha["transacao"]
        if transacao in transacoes and transacoes[transacao] != chave:
            raise ValueError("Transação compartilhada por lados diferentes.")
        transacoes[transacao] = chave
        grupos[chave].append(linha)
    for chave, negocio in participacoes.items():
        linhas = grupos[chave]
        if negocio["liquidacao"] == "PENDENTE":
            if linhas:
                raise ValueError("Pendente já possui D2.")
            continue
        unidade = negocio["ticker"] if negocio["lado"] == "COMPRADOR" else "BRL"
        valor = numero(negocio["quantidade"]) if unidade != "BRL" else numero(negocio["valor"], True)
        conta = "CUSTODIA" if unidade != "BRL" else "CAIXA"
        partidas = {(r["contaContabil"], r["natureza"], r["unidade"], numero(r["valor"], unidade == "BRL")) for r in linhas}
        if len(linhas) != 2 or len({r["transacao"] for r in linhas}) != 1 or partidas != {
                (conta, "DEBITO", unidade, valor), (conta + "_A_RECEBER", "CREDITO", unidade, valor)}:
            raise ValueError("D2 ausente, duplicado ou desbalanceado.")
        if any(r["data"] != negocio["dataLiquidacao"] for r in linhas):
            raise ValueError("Data D2 incoerente.")
    diferencas = []
    for conta in sorted(antes):
        inicio, fim = snapshot(conta, antes[conta]), snapshot(conta, depois[conta])
        if antes[conta]["saldoReservado"] != depois[conta]["saldoReservado"]:
            raise ValueError("Reservas mudaram durante a conciliação.")
        reservas_antes = {c["ticker"]: c["reservado"] for c in antes[conta]["custodias"]}
        reservas_depois = {c["ticker"]: c["reservado"] for c in depois[conta]["custodias"]}
        if any(reservas_antes.get(t, 0) != reservas_depois.get(t, 0) for t in ATIVOS):
            raise ValueError("Reservas de custódia mudaram durante a conciliação.")
        unidades = set(inicio) | set(fim) | {u for c, u in esperados if c == conta}
        for unidade in sorted(unidades):
            esperado = esperados[(conta, unidade)]
            observado = fim.get(unidade, Decimal(0)) - inicio.get(unidade, Decimal(0))
            if observado != esperado:
                formato = ".2f" if unidade == "BRL" else ".0f"
                diferencas.append(dict(conta=conta, unidade=unidade, esperado=format(esperado, formato),
                                       observado=format(observado, formato)))
    return {"diferencas": diferencas}


class ApiRecusada(RuntimeError):
    def __init__(self, status, codigo):
        super().__init__("API local recusou a operação.")
        self.status, self.codigo = status, codigo


class Cliente:
    def __init__(self, origem):
        from urllib.parse import urlsplit
        partes = urlsplit(origem)
        if (partes.scheme != 'http' or partes.hostname not in {'localhost', '127.0.0.1'}
                or partes.username or partes.password or partes.path not in {'', '/'} or partes.query or partes.fragment):
            raise ValueError('Origem deve ser HTTP loopback sem credenciais nem caminho.')
        self.origem = origem.rstrip('/')

    def chamar(self, caminho, escopo, conta=None, corpo=None, metodo=None):
        import json
        import urllib.request
        import urllib.error
        from mercado import token

        class SemRedirecionamento(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None

        headers = {'Authorization': 'Bearer ' + token(escopo, 300, conta)}
        dados = None if corpo is None else json.dumps(corpo).encode()
        if dados is not None:
            headers['Content-Type'] = 'application/json'
        pedido = urllib.request.Request(self.origem + caminho, data=dados, headers=headers,
                                        method=metodo or ('POST' if corpo is not None else 'GET'))
        try:
            with urllib.request.build_opener(SemRedirecionamento).open(pedido, timeout=10) as resposta:
                limite = resposta.read(1048577)
                if len(limite) > 1048576:
                    raise ValueError('Resposta excede limite da verificação.')
                return json.loads(limite)
        except urllib.error.HTTPError as erro:
            try:
                codigo = json.loads(erro.read(8192)).get('codigo', 'ERRO_HTTP')
            except (ValueError, AttributeError):
                codigo = 'ERRO_HTTP'
            raise ApiRecusada(erro.code, codigo) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise RuntimeError('API local indisponível.') from None


def aguardar(condicao, prazo=180):
    import time
    limite = time.monotonic() + prazo
    while True:
        try:
            resultado = condicao()
            if resultado:
                return resultado
        except ApiRecusada as erro:
            if erro.status != 503:
                raise
        except RuntimeError:
            pass
        if time.monotonic() >= limite:
            raise RuntimeError('Condição financeira não confirmada dentro do prazo.')
        time.sleep(0.3)


def paginas(cliente, rota, conta, campo):
    from urllib.parse import urlencode
    resultado, cursor, vistos = [], None, set()
    for _ in range(100):
        query = {'limite': '100'}
        if cursor is not None:
            query['cursor'] = cursor
        pagina = cliente.chamar(rota + '?' + urlencode(query), 'razao', conta)
        if pagina['conta'] != conta or len(pagina[campo]) > 100:
            raise ValueError('Página fora do contrato.')
        resultado.extend({**linha, 'conta': conta} for linha in pagina[campo])
        cursor = pagina['proximoCursor']
        if cursor is None:
            return resultado
        if cursor in vistos:
            raise ValueError('Cursor repetido.')
        vistos.add(cursor)
    raise ValueError('Histórico excede orçamento de cem páginas por conta.')


def participantes(bo):
    return [n for conta in sorted(CONTAS) for n in paginas(bo, '/api/v1/negocios', conta, 'negocios')]


def credito_mo(mo, negocio, creditado):
    from urllib.parse import quote
    pagina = mo.chamar('/api/v1/risco/liquidacoes/' + quote(negocio['negocio'], safe=''), 'risco', negocio['conta'])
    return any(c['lado'] == negocio['lado'] and c['negocio'] == negocio['negocio']
               and c['creditado'] is creditado and c['ticker'] == negocio['ticker']
               and c['valor'] == (negocio['quantidade'] if negocio['lado'] == 'COMPRADOR' else negocio['valor'])
               for c in pagina['creditos'])


def carteiras(mo):
    return {conta: mo.chamar('/api/v1/risco/carteira', 'risco', conta) for conta in sorted(CONTAS)}


def avancar(bo, data):
    try:
        return bo.chamar('/api/v1/data-movimento', 'liquidacao', corpo={'dataAlvo': data})
    except ApiRecusada as erro:
        if erro.status != 409 or erro.codigo != 'DATA_RETROATIVA':
            raise
        # Data já à frente em repetição: o worker deve completar as pendências, sem retroceder.
        return None


def recuperar(bo, mo, alvo, projeto):
    """Somente execução CI descartável: confirma labels antes de interromper BO/MO e restaura em finally."""
    import json
    import subprocess
    if not projeto or not re.fullmatch(r'prospero-ci-[0-9]+-[0-9]+', projeto):
        raise ValueError('Falhas exigem projeto CI descartável identificado.')
    from mercado import compose
    base = compose(projeto)
    ids = {}
    for servico in ('back-office', 'middle-office'):
        recipiente = subprocess.check_output(base + ['ps', '-q', servico], text=True).strip()
        if not re.fullmatch(r'[a-f0-9]{12,64}', recipiente):
            raise ValueError('Container único obrigatório.')
        estado = json.loads(subprocess.check_output(['docker', 'inspect', recipiente], text=True))[0]
        labels = estado['Config']['Labels']
        if (labels.get('com.docker.compose.project') != projeto or labels.get('com.docker.compose.service') != servico
                or not estado['State']['Running']):
            raise ValueError('Container não pertence à execução ativa.')
        if servico == 'back-office' and 'PROSPERO_LIQUIDACAO_WORKER_HABILITADO=false' not in estado['Config'].get('Env', []):
            raise ValueError('Prova de queda exige worker BO suspenso na configuração CI inicial.')
        ids[servico] = recipiente
    parados = set()

    def iniciar(servico, cliente):
        if servico == 'back-office':
            import os
            # Mesmo banco/log e imagem; só worker/relay voltam ativos para drenar a fila após a queda.
            ambiente = {**os.environ, 'PROSPERO_CI_WORKER_LIQUIDACAO': 'true'}
            subprocess.run(base + ['up', '-d', '--no-deps', '--force-recreate', servico],
                           env=ambiente, check=True, stdout=subprocess.DEVNULL)
        else:
            subprocess.run(base + ['start', servico], check=True, stdout=subprocess.DEVNULL)
        porta = subprocess.check_output(base + ['port', servico, '8080'], text=True).strip()
        novo = Cliente('http://' + porta)
        cliente.origem = novo.origem
        parados.discard(servico)

    try:
        parados.add('middle-office')
        subprocess.run(['docker', 'kill', ids['middle-office']], check=True, stdout=subprocess.DEVNULL)
        # Worker e relay estão suspensos pela configuração CI: só o lote HTTP pode confirmar antes do kill.
        avancar(bo, alvo)
        em_curso = participantes(bo)
        if not any(n['liquidacao'] == 'LIQUIDADO' for n in em_curso) or not any(n['liquidacao'] == 'PENDENTE' for n in em_curso):
            raise ValueError('Cenário exige mais de um lote ainda em processamento para testar a queda do BO.')
        parados.add('back-office')
        subprocess.run(['docker', 'kill', ids['back-office']], check=True, stdout=subprocess.DEVNULL)
        iniciar('back-office', bo)
        aguardar(lambda: bool(participantes(bo)))
        # BO confirma seus efeitos enquanto MO permanece parado: isso não equivale a crédito.
        aguardar(lambda: all(n['liquidacao'] == 'LIQUIDADO' for n in participantes(bo)))
        iniciar('middle-office', mo)
    finally:
        falhas_retorno = []
        for servico, cliente in [('back-office', bo), ('middle-office', mo)]:
            if servico in parados:
                try:
                    iniciar(servico, cliente)
                except (RuntimeError, ValueError, subprocess.SubprocessError):
                    falhas_retorno.append(servico)
        if falhas_retorno:
            raise RuntimeError('Não foi possível restaurar todos os serviços da execução CI.') from None


def verificar(bo, mo, projeto=None, falhas=False):
    negocios = aguardar(lambda: participantes(bo))
    pendentes = [n for n in negocios if n['liquidacao'] == 'PENDENTE']
    # Prova causal: a expectativa só existe após o débito D0 na mesma transação MO.
    for n in pendentes:
        aguardar(lambda n=n: credito_mo(mo, n, False))
    antes = carteiras(mo)
    if pendentes:
        # Testar a guarda com os mesmos valores evita redefinir patrimônio se a configuração estiver incorreta.
        conta = next((n['conta'] for n in pendentes if n['conta'] in {'CONTA-002', 'CONTA-003'}), None)
        if conta is None:
            raise ValueError('Cenário deve incluir conta preparável com crédito pendente.')
        atual = antes[conta]
        pedido = {'saldo': atual['saldoTotal'], 'custodia': {c['ticker']: c['total'] for c in atual['custodias']}}
        try:
            mo.chamar('/api/v1/preparacoes-risco/contas/' + conta, 'risco-preparacao', corpo=pedido, metodo='PUT')
        except ApiRecusada as erro:
            if (erro.status, erro.codigo) != (409, 'LIQUIDACAO_PENDENTE'):
                raise
        else:
            raise ValueError('Guarda de pendência não está ativa.')
        if carteiras(mo) != antes:
            raise ValueError('Preparação alterou carteira apesar da pendência.')
    alvo = max(n['dataLiquidacao'] for n in negocios)
    if falhas and pendentes:
        recuperar(bo, mo, alvo, projeto)
    else:
        avancar(bo, alvo)
    selecionados = {(n['conta'], n['negocio'], n['lado']) for n in pendentes}
    def concluir():
        atuais = participantes(bo)
        lados = [n for n in atuais if (n['conta'], n['negocio'], n['lado']) in selecionados]
        if len(lados) != len(pendentes) or any(n['liquidacao'] != 'LIQUIDADO' for n in lados):
            return None
        if any(not credito_mo(mo, n, True) for n in lados):
            return None
        return {'lados': lados}
    lados = aguardar(concluir)['lados']
    lancamentos = [l for conta in sorted(CONTAS) for l in paginas(bo, '/api/v1/razao', conta, 'lancamentos')
                   if l['momento'] == 'D2' and (conta, l['negocio'], l['lado']) in selecionados]
    resultado = conciliar(lados, lancamentos, antes, carteiras(mo))
    if resultado['diferencas']:
        raise ValueError('Conciliação encontrou diferenças financeiras.')
    # Repetição do avanço e confirmação BO/MO não podem criar outro crédito.
    depois = carteiras(mo)
    avancar(bo, alvo)
    for n in lados:
        aguardar(lambda n=n: credito_mo(mo, n, True))
    if carteiras(mo) != depois:
        raise ValueError('Avanço repetido alterou carteira.')
    return {'ladosConciliados': len(lados), 'roboCreditado': any(n['conta'] == 'CONTA-004' for n in lados),
            'diferencas': [], 'dataAlvo': alvo}


def main():
    import argparse
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origem-bo', default='http://127.0.0.1:8084')
    parser.add_argument('--origem-mo', default='http://127.0.0.1:8082')
    parser.add_argument('--projeto')
    parser.add_argument('--falhas', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(verificar(Cliente(args.origem_bo), Cliente(args.origem_mo), args.projeto, args.falhas)))
    except (ValueError, RuntimeError) as erro:
        # Diagnóstico sem payloads, credenciais, conta ou valores financeiros.
        raise SystemExit('Verificação de liquidação falhou: ' + str(erro)) from None


if __name__ == '__main__':
    main()
