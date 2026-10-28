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

    def test_combinacoes_incoerentes_recusam(self):
        casos = (
            ('ABERTA', {'estado': 'CONFIRMADO', 'decisao': 'APLICADO', 'motivo': None}),
            ('CANCELADA', {'estado': 'RECUSADO', 'decisao': 'SEM_SALDO_VIVO', 'motivo': 'SEM_SALDO_VIVO'}),
            ('EXECUTADA', {'estado': 'CONFIRMADO', 'decisao': 'APLICADO', 'motivo': None}),
            ('CANCELADA', {'estado': 'SOLICITADO', 'decisao': None, 'motivo': None}),
            ('EXECUTADA', {'estado': 'RECUSADO', 'decisao': 'SEM_SALDO_VIVO', 'motivo': 'RISCO_REJEITADO'}),
        )
        for estado, operacao in casos:
            with self.subTest(estado=estado, operacao=operacao), self.assertRaises(ValueError):
                canc.classificar_desfecho(estado, operacao)


class Saldos(unittest.TestCase):
    def test_saldo_em_repouso_reserva_so_as_abertas_com_decimal(self):
        antes = {'saldoTotal': '999980.00', 'saldoReservado': '20.00'}
        canc.conferir_repouso(antes, executadas=2, abertas=2, preco='10.00', inicial='1000000.00')
        with self.assertRaises(ValueError):
            canc.conferir_repouso({'saldoTotal': '999980.00', 'saldoReservado': '20.0000001'}, 2, 2, '10.00',
                                  '1000000.00')

    def test_liberacao_unica_zera_reserva_sem_mexer_no_debito_da_execucao(self):
        depois = {'saldoTotal': '999980.00', 'saldoReservado': '0.00'}
        canc.conferir_liberacao(depois, executadas=2, preco='10.00', inicial='1000000.00')
        # Liberação dupla deixaria saldo reservado negativo; liberação ausente deixa reserva viva. Ambas recusam.
        for reservado in ('-20.00', '20.00'):
            with self.subTest(reservado=reservado), self.assertRaises(ValueError):
                canc.conferir_liberacao({'saldoTotal': '999980.00', 'saldoReservado': reservado}, 2, '10.00',
                                        '1000000.00')

    def test_saldo_total_divergente_recusa(self):
        with self.assertRaises(ValueError):
            canc.conferir_liberacao({'saldoTotal': '999979.99', 'saldoReservado': '0.00'}, 2, '10.00', '1000000.00')


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
            [{'rodada': 'a' * 32, 'ordens': 4, 'executadas': 2, 'canceladas': 2, 'falhaEnsaiada': False,
              'liberacoes': 1, 'repeticaoConfirmada': True, 'negativos': {'ordemAlheia': 404, 'corpoInvalido': 400},
              'saldoAposLiberacao': {'saldoTotal': '999980.00', 'saldoReservado': '0.00'}}])
        texto = repr(relatorio)
        for proibido in ('token', 'Bearer', 'eyJ', 'corpo_bruto', 'ordemId'):
            self.assertNotIn(proibido, texto)
        self.assertEqual(relatorio['tipo'], 'smoke-cancelamento-carga')
        self.assertEqual(relatorio['execucoes'][0]['liberacoes'], 1)


if __name__ == '__main__':
    unittest.main()
