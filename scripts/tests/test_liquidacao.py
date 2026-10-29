import copy
import importlib.util
from pathlib import Path
import unittest
import uuid
import json
import sys
from types import SimpleNamespace
from unittest.mock import patch

RAIZ = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("liquidacao", RAIZ / "scripts" / "liquidacao.py")
liquidacao = importlib.util.module_from_spec(spec)
spec.loader.exec_module(liquidacao)


def carteira(conta, caixa, quantidade):
    return {"conta": conta, "saldoTotal": caixa, "saldoDisponivel": caixa, "saldoReservado": "0.00",
            "custodias": [{"ticker": "ALFA3", "total": quantidade, "disponivel": quantidade, "reservado": 0}]}


def participacao(conta, lado, numero=1, quantidade="100", valor="2000.00"):
    return {"conta": conta, "negocio": f"ALFA3-2026-10-23-{numero}", "ticker": "ALFA3", "lado": lado,
            "ordem": str(uuid.uuid5(uuid.NAMESPACE_URL, f"ordem-{numero}-{lado}")), "preco": "20.00",
            "quantidade": quantidade, "valor": valor, "dataSessao": "2026-10-23",
            "dataLiquidacao": "2026-10-27", "liquidacao": "LIQUIDADO"}


def partidas(negocio):
    compra = negocio["lado"] == "COMPRADOR"
    conta = "CUSTODIA" if compra else "CAIXA"
    unidade = negocio["ticker"] if compra else "BRL"
    valor = negocio["quantidade"] if compra else negocio["valor"]
    transacao = str(uuid.uuid5(uuid.NAMESPACE_URL, f'{negocio["negocio"]}-{negocio["lado"]}'))
    base = {"conta": negocio["conta"], "transacao": transacao, "negocio": negocio["negocio"],
            "ticker": negocio["ticker"], "lado": negocio["lado"], "momento": "D2",
            "data": negocio["dataLiquidacao"], "unidade": unidade, "valor": valor}
    return [{**base, "contaContabil": conta, "natureza": "DEBITO"},
            {**base, "contaContabil": conta + "_A_RECEBER", "natureza": "CREDITO"}]


class ConciliacaoTest(unittest.TestCase):
    def setUp(self):
        self.negocios = [participacao("CONTA-001", "COMPRADOR"), participacao("CONTA-002", "VENDEDOR")]
        self.lancamentos = [p for n in self.negocios for p in partidas(n)]
        self.antes = {"CONTA-001": carteira("CONTA-001", "8000.00", 0),
                      "CONTA-002": carteira("CONTA-002", "5000.00", 100)}
        self.depois = {"CONTA-001": carteira("CONTA-001", "8000.00", 100),
                       "CONTA-002": carteira("CONTA-002", "7000.00", 100)}

    def conciliar(self):
        return liquidacao.conciliar(self.negocios, self.lancamentos, self.antes, self.depois)

    def test_recompra_que_cobre_venda_a_descoberto_libera_garantia_e_concilia_pela_custodia_efetiva(self):
        # ADR-021: CONTA-001 tinha 30 vendidas a descoberto e 330,00 de garantia; o crédito de 100 cobre as 30.
        self.antes["CONTA-001"] = {**carteira("CONTA-001", "8000.00", 0), "saldoDisponivel": "7670.00",
                                   "saldoReservado": "330.00"}
        self.antes["CONTA-001"]["custodias"][0]["descobertoExecutado"] = 30
        self.depois["CONTA-001"] = carteira("CONTA-001", "8000.00", 70)
        self.depois["CONTA-001"]["custodias"][0]["descobertoExecutado"] = 0
        self.assertEqual([], self.conciliar()["diferencas"])

    def test_reserva_que_cai_sem_cobertura_ou_que_sobe_continua_erro(self):
        self.antes["CONTA-001"] = {**carteira("CONTA-001", "8000.00", 0), "saldoDisponivel": "7670.00",
                                   "saldoReservado": "330.00"}
        with self.assertRaises(ValueError):
            self.conciliar()
        self.antes["CONTA-001"] = carteira("CONTA-001", "8000.00", 0)
        self.depois["CONTA-001"] = {**carteira("CONTA-001", "8000.00", 100), "saldoDisponivel": "7900.00",
                                    "saldoReservado": "100.00"}
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_creditos_de_caixa_e_custodia_conciliam_por_movimentos_sem_usar_saldos_absolutos(self):
        self.assertEqual([], self.conciliar()["diferencas"])
        self.antes["CONTA-002"] = carteira("CONTA-002", "9007199254740993.01", 100)
        self.depois["CONTA-002"] = carteira("CONTA-002", "9007199254742993.01", 100)
        self.assertEqual([], self.conciliar()["diferencas"], "caixa acima da precisão de float continua exato")

    def test_agrega_varios_negocios_e_preserva_centavos_exatos(self):
        novos = [participacao("CONTA-001", "COMPRADOR", 2, "3", "0.30"),
                 participacao("CONTA-002", "VENDEDOR", 2, "3", "0.30")]
        for negocio in novos:
            negocio["preco"] = "0.10"
            self.lancamentos.extend(partidas(negocio))
        self.negocios.extend(novos)
        self.depois["CONTA-001"] = carteira("CONTA-001", "8000.00", 103)
        self.depois["CONTA-002"] = carteira("CONTA-002", "7000.30", 100)
        self.assertEqual([], self.conciliar()["diferencas"])

    def test_autonegociacao_admite_dois_lados_distintos_para_mesma_conta(self):
        for negocio in self.negocios:
            negocio["conta"] = "CONTA-001"
        self.lancamentos = [p for n in self.negocios for p in partidas(n)]
        self.antes = {"CONTA-001": carteira("CONTA-001", "8000.00", 100)}
        self.depois = {"CONTA-001": carteira("CONTA-001", "10000.00", 200)}
        self.assertEqual([], self.conciliar()["diferencas"])

    def test_pendente_nao_e_credito_esperado(self):
        for negocio in self.negocios:
            negocio["liquidacao"] = "PENDENTE"
        self.lancamentos = []
        self.depois = copy.deepcopy(self.antes)
        self.assertEqual([], self.conciliar()["diferencas"])

    def test_um_centavo_ou_uma_acao_de_diferenca_nao_sao_arredondados(self):
        self.depois["CONTA-002"] = carteira("CONTA-002", "6999.99", 100)
        self.depois["CONTA-001"] = carteira("CONTA-001", "8000.00", 99)
        diferencas = self.conciliar()["diferencas"]
        self.assertEqual(2, len(diferencas))
        por_recurso = {(d["conta"], d["unidade"]): d for d in diferencas}
        self.assertEqual({"conta": "CONTA-002", "unidade": "BRL", "esperado": "2000.00", "observado": "1999.99"},
                         por_recurso[("CONTA-002", "BRL")])
        self.assertEqual({"conta": "CONTA-001", "unidade": "ALFA3", "esperado": "100", "observado": "99"},
                         por_recurso[("CONTA-001", "ALFA3")])

    def test_movimento_inesperado_de_recurso_sem_credito_tambem_e_diferenca(self):
        self.depois["CONTA-001"]["saldoTotal"] = "8000.01"
        self.depois["CONTA-001"]["saldoDisponivel"] = "8000.01"
        diferencas = self.conciliar()["diferencas"]
        self.assertEqual([{"conta": "CONTA-001", "unidade": "BRL", "esperado": "0.00", "observado": "0.01"}], diferencas)

    def test_participacao_duplicada_falha_mesmo_que_dados_sejam_identicos(self):
        self.negocios.append(copy.deepcopy(self.negocios[0]))
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_partidas_duplicadas_nao_passam_so_por_continuarem_balanceadas(self):
        self.lancamentos.extend(copy.deepcopy(self.lancamentos[:2]))
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_liquidado_sem_partidas_d2_correspondentes_falha_fechado(self):
        self.lancamentos = []
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_transacao_desbalanceada_nao_e_compensada_por_outra_transacao(self):
        self.lancamentos[1]["valor"] = "99"
        self.lancamentos[3]["valor"] = "2001.00"
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_mesma_transacao_nao_compensa_unidades_diferentes(self):
        self.lancamentos[1]["unidade"] = "BETA3"
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_d2_balanceado_com_valor_divergente_do_fato_e_recusado(self):
        for linha in self.lancamentos[:2]:
            linha["valor"] = "99"
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_credito_esperado_nao_desaparece_se_ativo_falta_nos_dois_snapshots(self):
        self.antes["CONTA-001"]["custodias"] = []
        self.depois["CONTA-001"]["custodias"] = []
        self.assertEqual([{"conta": "CONTA-001", "unidade": "ALFA3", "esperado": "100", "observado": "0"}],
                         self.conciliar()["diferencas"])

    def test_conta_da_partida_nao_pode_ser_trocada_para_compensar_outro_lado(self):
        self.lancamentos[1]["conta"] = "CONTA-002"
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_valores_nao_finitos_numeros_float_e_quantidade_fracionaria_sao_invalidos(self):
        original = copy.deepcopy(self.negocios)
        for campo, valor in [("valor", "NaN"), ("valor", "Infinity"), ("valor", 2000.0), ("valor", True),
                             ("quantidade", "1.5"), ("quantidade", "-1"), ("valor", "2000.001")]:
            with self.subTest(campo=campo, valor=valor):
                self.negocios = copy.deepcopy(original)
                self.negocios[1][campo] = valor
                with self.assertRaises(ValueError):
                    self.conciliar()

    def test_conta_ausente_em_snapshot_nao_vira_saldo_zero(self):
        del self.antes["CONTA-002"]
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_custodia_duplicada_no_snapshot_e_recusada(self):
        self.depois["CONTA-001"]["custodias"].append(copy.deepcopy(self.depois["CONTA-001"]["custodias"][0]))
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_reservas_de_caixa_ou_custodia_nao_podem_mudar_na_janela_de_conciliacao(self):
        original = copy.deepcopy(self.depois)
        self.depois["CONTA-002"]["saldoDisponivel"] = "6999.00"
        self.depois["CONTA-002"]["saldoReservado"] = "1.00"
        with self.assertRaises(ValueError):
            self.conciliar()
        self.depois = copy.deepcopy(original)
        self.depois["CONTA-001"]["custodias"][0].update(disponivel=99, reservado=1)
        with self.assertRaises(ValueError):
            self.conciliar()

    def test_dados_de_entrada_nao_sao_mutados(self):
        antes = copy.deepcopy((self.negocios, self.lancamentos, self.antes, self.depois))
        self.conciliar()
        self.assertEqual(antes, (self.negocios, self.lancamentos, self.antes, self.depois))


class RecuperacaoTest(unittest.TestCase):
    def setUp(self):
        self.projeto = 'prospero-ci-123-1'
        self.bo = SimpleNamespace(origem='http://127.0.0.1:8001')
        self.mo = SimpleNamespace(origem='http://127.0.0.1:8002')
        self.worker_suspenso = True
        self.ids = {'back-office': 'a' * 64, 'middle-office': 'b' * 64}
        self.compose = ['docker', 'compose', '-p', self.projeto]

    def saida(self, comando, **kwargs):
        if comando[:2] == ['docker', 'inspect']:
            servico = next(s for s, identificador in self.ids.items() if identificador == comando[2])
            return json.dumps([{'Config': {
                'Labels': {'com.docker.compose.project': self.projeto, 'com.docker.compose.service': servico},
                'Env': ['PROSPERO_LIQUIDACAO_WORKER_HABILITADO=false'] if self.worker_suspenso else [],
            }, 'State': {'Running': True}}])
        if 'port' in comando:
            return '127.0.0.1:8003'
        return self.ids[comando[-1]]

    def test_worker_nao_controlado_recusa_antes_de_interromper_servicos(self):
        self.worker_suspenso = False
        with patch.dict(sys.modules, {'mercado': SimpleNamespace(compose=lambda _: self.compose)}), \
                patch('subprocess.check_output', side_effect=self.saida), \
                patch('subprocess.run') as executar:
            with self.assertRaisesRegex(ValueError, 'worker BO suspenso'):
                liquidacao.recuperar(self.bo, self.mo, '2026-10-27', self.projeto)
            executar.assert_not_called()

    def test_falha_apos_parar_mo_restaura_servico_em_finally(self):
        with patch.dict(sys.modules, {'mercado': SimpleNamespace(compose=lambda _: self.compose)}), \
                patch('subprocess.check_output', side_effect=self.saida), \
                patch('subprocess.run') as executar, \
                patch.object(liquidacao, 'avancar', side_effect=RuntimeError('falha API')):
            with self.assertRaisesRegex(RuntimeError, 'falha API'):
                liquidacao.recuperar(self.bo, self.mo, '2026-10-27', self.projeto)
            comandos = [c.args[0] for c in executar.call_args_list]
            self.assertEqual([['docker', 'kill', self.ids['middle-office']],
                              self.compose + ['start', 'middle-office']], comandos)
            self.assertEqual('http://127.0.0.1:8003', self.mo.origem)

    def test_projeto_de_desenvolvimento_e_recusado_sem_operar_docker(self):
        with patch('subprocess.check_output') as consultar, patch('subprocess.run') as executar:
            with self.assertRaises(ValueError):
                liquidacao.recuperar(self.bo, self.mo, '2026-10-27', 'prospero')
            consultar.assert_not_called()
            executar.assert_not_called()

    def test_falha_ao_recriar_bo_tenta_restaurar_ambos_os_servicos(self):
        import subprocess
        tentativas_bo = 0

        def executar(comando, **kwargs):
            nonlocal tentativas_bo
            if '--force-recreate' in comando:
                tentativas_bo += 1
                self.assertEqual('true', kwargs['env']['PROSPERO_CI_WORKER_LIQUIDACAO'])
                if tentativas_bo == 1:
                    raise subprocess.CalledProcessError(1, comando)

        with patch.dict(sys.modules, {'mercado': SimpleNamespace(compose=lambda _: self.compose)}), \
                patch('subprocess.check_output', side_effect=self.saida), \
                patch('subprocess.run', side_effect=executar) as rodar, \
                patch.object(liquidacao, 'avancar'), \
                patch.object(liquidacao, 'participantes', return_value=[
                    {'liquidacao': 'LIQUIDADO'}, {'liquidacao': 'PENDENTE'}]):
            with self.assertRaises(subprocess.CalledProcessError):
                liquidacao.recuperar(self.bo, self.mo, '2026-10-27', self.projeto)
            self.assertEqual(2, tentativas_bo)
            self.assertIn(self.compose + ['start', 'middle-office'], [c.args[0] for c in rodar.call_args_list])


class ContaMedicaoTest(unittest.TestCase):
    """CONTA-005 (medição, V01-2): conta de negócio no BO, com liquidação D+2 consultável e conciliada como as demais."""

    def test_conjunto_de_contas_da_liquidacao_inclui_a_conta_005(self):
        self.assertEqual({f"CONTA-00{i}" for i in range(1, 6)}, liquidacao.CONTAS)

    def test_snapshot_aceita_a_conta_005_e_recusa_contas_fora_do_conjunto(self):
        liquidacao.snapshot("CONTA-005", carteira("CONTA-005", "8000.00", 0))
        with self.assertRaises(ValueError):
            liquidacao.snapshot("CONTA-999", carteira("CONTA-999", "8000.00", 0))

    def test_negocio_da_conta_005_concilia_com_a_contraparte_003(self):
        negocios = [participacao("CONTA-005", "COMPRADOR"), participacao("CONTA-003", "VENDEDOR")]
        lancamentos = [p for n in negocios for p in partidas(n)]
        antes = {"CONTA-005": carteira("CONTA-005", "8000.00", 0), "CONTA-003": carteira("CONTA-003", "5000.00", 100)}
        depois = {"CONTA-005": carteira("CONTA-005", "8000.00", 100), "CONTA-003": carteira("CONTA-003", "7000.00", 100)}
        self.assertEqual([], liquidacao.conciliar(negocios, lancamentos, antes, depois)["diferencas"])

    def test_negocio_da_conta_005_sem_credito_do_mo_aparece_como_diferenca(self):
        negocios = [participacao("CONTA-005", "COMPRADOR"), participacao("CONTA-003", "VENDEDOR")]
        lancamentos = [p for n in negocios for p in partidas(n)]
        antes = {"CONTA-005": carteira("CONTA-005", "8000.00", 0), "CONTA-003": carteira("CONTA-003", "5000.00", 100)}
        depois = {"CONTA-005": carteira("CONTA-005", "8000.00", 0), "CONTA-003": carteira("CONTA-003", "7000.00", 100)}
        diferencas = liquidacao.conciliar(negocios, lancamentos, antes, depois)["diferencas"]
        self.assertIn({"conta": "CONTA-005", "unidade": "ALFA3", "esperado": "100", "observado": "0"}, diferencas)

    def test_participantes_e_carteiras_consultam_a_conta_005(self):
        consultadas = []

        def chamar(caminho, escopo, conta=None, corpo=None, metodo=None):
            consultadas.append((caminho.split("?")[0], conta))
            if escopo == "risco":
                return {"conta": conta}
            return {"conta": conta, "negocios": [], "proximoCursor": None}

        servico = SimpleNamespace(chamar=chamar)
        liquidacao.participantes(servico)
        self.assertIn(("/api/v1/negocios", "CONTA-005"), consultadas)
        self.assertIn("CONTA-005", liquidacao.carteiras(servico))


if __name__ == "__main__":
    unittest.main()
