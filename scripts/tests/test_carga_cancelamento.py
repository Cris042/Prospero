"""Fronteiras do cenário de cancelamento sob carga (B3): saldo em repouso, liberação única, corrida e falha; só com dublês."""
import importlib.util
from decimal import Decimal
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))


def carregar(nome, arquivo):
    spec = importlib.util.spec_from_file_location(nome, SCRIPTS / arquivo)
    modulo = importlib.util.module_from_spec(spec)
    sys.modules[nome] = modulo
    spec.loader.exec_module(modulo)
    return modulo


canc = carregar('carga_cancelamento_qa', 'testar-carga-cancelamento.py')


def saldo(total, disponivel, reservado):
    return {'saldoTotal': total, 'saldoDisponivel': disponivel, 'saldoReservado': reservado}


class Plano(unittest.TestCase):
    def test_plano_exige_contraparte_menor_que_agressores_e_dentro_do_teto(self):
        self.assertEqual(canc.plano(4, 2), {'agressores': 4, 'contraparte': 2})
        for n, m in ((4, 4), (4, 0), (1, 0), (7, 2), (4, -1), (True, 1)):
            with self.subTest(n=n, m=m), self.assertRaises(ValueError):
                canc.plano(n, m)


class Desfecho(unittest.TestCase):
    def test_os_dois_ramos_da_corrida_sao_aceitos(self):
        # Cancelamento aplicado antes da agressão: a ordem termina CANCELADA.
        self.assertEqual(canc.classificar_desfecho('CANCELADA', {'estado': 'CONFIRMADO', 'decisao': 'APLICADO',
                                                                'motivo': None}), 'CANCELADA')
        # Agressão chegou antes: o matching recusa com SEM_SALDO_VIVO e a ordem termina EXECUTADA.
        self.assertEqual(canc.classificar_desfecho('EXECUTADA', {'estado': 'RECUSADO', 'decisao': 'SEM_SALDO_VIVO',
                                                                'motivo': 'SEM_SALDO_VIVO'}), 'EXECUTADA')
        # Ordem já encerrada no MO: a regra local recusa sem consultar o ME, então decisao fica nula.
        self.assertEqual(canc.classificar_desfecho('EXECUTADA', {'estado': 'RECUSADO', 'decisao': None,
                                                                'motivo': 'SEM_SALDO_VIVO'}), 'EXECUTADA')

    def test_combinacoes_incoerentes_recusam(self):
        casos = (
            ('ABERTA', {'estado': 'CONFIRMADO', 'decisao': 'APLICADO', 'motivo': None}),
            ('CANCELADA', {'estado': 'RECUSADO', 'decisao': 'SEM_SALDO_VIVO', 'motivo': 'SEM_SALDO_VIVO'}),
            ('EXECUTADA', {'estado': 'CONFIRMADO', 'decisao': 'APLICADO', 'motivo': None}),
            ('CANCELADA', {'estado': 'SOLICITADO', 'decisao': None, 'motivo': None}),
            ('EXECUTADA', {'estado': 'RECUSADO', 'decisao': 'SEM_SALDO_VIVO', 'motivo': 'RISCO_REJEITADO'}),
            ('EXECUTADA', {'estado': 'RECUSADO', 'decisao': None, 'motivo': 'ADMISSAO_NAO_COMPROVADA'}),
            ('EXECUTADA', {'estado': 'RECUSADO', 'decisao': 'APLICADO', 'motivo': 'SEM_SALDO_VIVO'}),
        )
        for estado, operacao in casos:
            with self.subTest(estado=estado, operacao=operacao), self.assertRaises(ValueError):
                canc.classificar_desfecho(estado, operacao)


class Valores(unittest.TestCase):
    def test_valor_monetario_e_decimal(self):
        self.assertIsInstance(canc._valor('1.10'), Decimal)

    def test_numero_json_em_valor_monetario_e_recusado(self):
        # Float em _valor abriria a comparação binária; número JSON nunca entra como saldo.
        for valor in (1.1, 10, True, None, '1,10', ''):
            with self.subTest(valor=valor), self.assertRaises(ValueError):
                canc._valor(valor)

    def test_saldo_reservado_numerico_e_recusado_na_liberacao(self):
        for reservado in (0, 0.0, 20.0):
            with self.subTest(reservado=reservado), self.assertRaises(ValueError):
                canc.conferir_liberacao(saldo('999980.00', '999980.00', reservado), 2, '10.00', '1000000.00')


class Saldos(unittest.TestCase):
    def test_saldo_em_repouso_reserva_so_as_abertas_com_decimal(self):
        antes = saldo('999980.00', '999960.00', '20.00')
        canc.conferir_repouso(antes, executadas=2, abertas=2, preco='10.00', inicial='1000000.00')
        with self.assertRaises(ValueError):
            canc.conferir_repouso(saldo('999980.00', '999959.9999999', '20.0000001'), 2, 2, '10.00', '1000000.00')

    def test_aritmetica_em_decimal_fecha_centavos_que_o_float_erra(self):
        # 1000000.00 − 0.10 × 3 = 999999.70 exato em Decimal; em float não fecha a comparação.
        canc.conferir_liberacao(saldo('999999.70', '999999.70', '0.00'), 3, '0.10', '1000000.00')
        canc.conferir_repouso(saldo('999999.70', '999999.40', '0.30'), 3, 3, '0.10', '1000000.00')

    def test_liberacao_unica_zera_reserva_e_iguala_disponivel_ao_total(self):
        canc.conferir_liberacao(saldo('999980.00', '999980.00', '0.00'), 2, '10.00', '1000000.00')

    def test_liberacao_dupla_reprova_com_os_saldos_que_ela_produziria(self):
        # Dupla liberação: reserva negativa e disponível acima do total. Ambos os saldos precisam reprovar.
        dupla = (saldo('999980.00', '1000000.00', '-20.00'), saldo('999980.00', '1000000.00', '0.00'),
                 saldo('999980.00', '999980.00', '20.00'))
        for lido in dupla:
            with self.subTest(lido=lido), self.assertRaises(ValueError):
                canc.conferir_liberacao(lido, 2, '10.00', '1000000.00')

    def test_saldo_total_divergente_recusa(self):
        with self.assertRaises(ValueError):
            canc.conferir_liberacao(saldo('999979.99', '999979.99', '0.00'), 2, '10.00', '1000000.00')


class Quantidades(unittest.TestCase):
    @staticmethod
    def ordem(feita, cancelada, reduzida=0):
        return {'quantidade': 1, 'quantidadeExecutada': feita, 'quantidadeCancelada': cancelada,
                'quantidadeReduzida': reduzida}

    def test_leitura_da_api_fecha_o_original_e_devolve_os_valores_lidos(self):
        corpos = [self.ordem(1, 0), self.ordem(0, 1), self.ordem(1, 0), self.ordem(0, 1)]
        self.assertEqual(canc.conferir_quantidades(corpos, 4), (2, 2))

    def test_ordem_que_nao_fecha_ou_quantidade_nao_inteira_recusa(self):
        casos = (
            ([self.ordem(1, 1)], 1),
            ([self.ordem(1, 0), self.ordem(0, 0)], 2),
            ([self.ordem(1, 0)], 2),
            ([{'quantidade': 1, 'quantidadeExecutada': 1.0, 'quantidadeCancelada': 0, 'quantidadeReduzida': 0}], 1),
            ([{}], 1),
        )
        for corpos, n in casos:
            with self.subTest(corpos=corpos, n=n), self.assertRaises(ValueError):
                canc.conferir_quantidades(corpos, n)


class Custodia(unittest.TestCase):
    def test_custodia_da_compradora_e_lida_ou_ausente(self):
        carteira = {'custodias': [{'ticker': 'ALFA3', 'total': 2, 'disponivel': 2, 'reservado': 0}]}
        self.assertEqual(canc.custodia_compradora(carteira), {'total': 2, 'disponivel': 2, 'reservado': 0})
        self.assertIsNone(canc.custodia_compradora({'custodias': []}))
        with self.assertRaises(ValueError):
            canc.custodia_compradora({'custodias': [{'ticker': 'ALFA3', 'total': 2.0, 'disponivel': 2,
                                                     'reservado': 0}]})

    def test_cancelamento_nao_altera_a_custodia(self):
        canc.conferir_custodia(None, None)
        with self.assertRaises(ValueError):
            canc.conferir_custodia({'total': 2, 'disponivel': 2, 'reservado': 0},
                                   {'total': 1, 'disponivel': 1, 'reservado': 0})


class Invariante(unittest.TestCase):
    def test_executada_mais_cancelada_iguala_o_original_sem_pendencia(self):
        canc.conferir_invariante(['EXECUTADA', 'CANCELADA', 'CANCELADA', 'EXECUTADA'], 4)
        casos = ((['EXECUTADA', 'ABERTA'], 2), (['CANCELADA'], 2), (['EXECUTADA'] * 5, 4),
                 (['REJEITADA', 'CANCELADA'], 2))
        for estados, n in casos:
            with self.subTest(estados=estados, n=n), self.assertRaises(ValueError):
                canc.conferir_invariante(estados, n)


class Repeticao(unittest.TestCase):
    def test_cabecalho_de_repeticao_so_aceita_true_ou_false(self):
        self.assertFalse(canc.repetido({'X-Cancelamento-Repetido': 'false'}))
        self.assertTrue(canc.repetido({'X-Cancelamento-Repetido': 'true'}))
        for valor in (None, '', 'TRUE', '1'):
            with self.subTest(valor=valor), self.assertRaises(ValueError):
                canc.repetido({} if valor is None else {'X-Cancelamento-Repetido': valor})


class Compensacao(unittest.TestCase):
    TEXTO_PENDENTE = ('# TYPE prospero_cancelamento_pendente_segundos_max gauge\n'
                      'prospero_cancelamento_pendente_segundos_max{servico="front-office"} 37.0\n')
    TEXTO_LIMPO = TEXTO_PENDENTE.replace(' 37.0', ' 0.0')

    def test_falha_so_conta_quando_o_gauge_mostra_pendencia_e_depois_zera(self):
        self.assertEqual(canc.pendencia_observada(self.TEXTO_PENDENTE), 37.0)
        canc.conferir_recuperacao(self.TEXTO_LIMPO)
        with self.assertRaises(ValueError):
            canc.pendencia_observada(self.TEXTO_LIMPO)  # sem pendência não há falha ensaiada
        with self.assertRaises(ValueError):
            canc.conferir_recuperacao(self.TEXTO_PENDENTE)  # ainda pendente após religar recusa


class Publico(unittest.TestCase):
    def test_relatorio_publico_nao_carrega_token_corpo_nem_ids(self):
        relatorio = canc.relatorio_publico(
            [{'digestsOrdens': ['a' * 64], 'ordens': 4, 'executadas': 2, 'canceladas': 2, 'falhaEnsaiada': False,
              'repeticaoConfirmada': True, 'negativos': {'ordemAlheia': 404, 'corpoInvalido': 400},
              'saldoEmRepouso': {'saldoTotal': '999980.00', 'saldoDisponivel': '999960.00',
                                 'saldoReservado': '20.00', 'custodiaCompradora': None},
              'saldoAposLiberacao': {'saldoTotal': '999980.00', 'saldoDisponivel': '999980.00',
                                     'saldoReservado': '0.00', 'custodiaCompradora': None}}])
        texto = repr(relatorio)
        for proibido in ('token', 'Bearer', 'eyJ', 'corpo_bruto', 'ordemId', 'rodada'):
            self.assertNotIn(proibido, texto)
        self.assertEqual(relatorio['tipo'], 'smoke-cancelamento-carga')
        self.assertNotIn('liberacoes', relatorio['execucoes'][0])


if __name__ == '__main__':
    unittest.main()
