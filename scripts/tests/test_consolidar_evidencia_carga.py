"""Evidência integrada da carga (B3): consolidação, invariantes, IDs disjuntos e vedações; só com dublês."""
import importlib.util
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
_spec = importlib.util.spec_from_file_location('consolidar_evidencia_qa', SCRIPTS / 'consolidar-evidencia-carga.py')
cons = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cons)

SHA = 'c' * 40
CONTAGENS_ESCRITA = ('oferecidas', 'iniciadas', 'naoIniciadas', 'descartadas', 'interrompidas', 'finalizadas',
                     'emVoo', 'respostasHTTP', 'errosHTTP', 'timeouts', 'falhasTransporte', 'canceladasEmVoo',
                     'limitesResposta', 'bytesLidos')


def contagens(n, **extras):
    c = {nome: 0 for nome in CONTAGENS_ESCRITA}
    for nome in ('oferecidas', 'iniciadas', 'finalizadas', 'respostasHTTP'):
        c[nome] = n
    c.update(extras)
    return c


def escrita(n, **extras):
    return {'cenario': 'ORDEM_AGRESSOR', 'estado': 'CONCLUIDA', 'falhaEnsaiada': False,
            'contagens': contagens(n, **extras), 'resultadoFinanceiro': 'conferido',
            'reconciliacao': {'ids': n, 'terminais': {'EXECUTADA': n}, 'pendentes': 0, 'ausentes': 0}}


def cancelamento(rodada, falha=False):
    return {'rodada': rodada, 'ordens': 4, 'executadas': 2, 'canceladas': 2, 'falhaEnsaiada': falha,
            'pendentesDuranteFalha': 2 if falha else None, 'liberacoes': 1, 'repeticaoConfirmada': True,
            'recusaExecutada': 'SEM_SALDO_VIVO', 'negativos': {'ordemAlheia': 404, 'corpoInvalido': 400},
            'saldoAposLiberacao': {'saldoTotal': '999980.00', 'saldoReservado': '0.00'}}


def snapshot(rotulo):
    return {'rotulo': rotulo, 'instante': '2026-10-28T12:00:00+00:00', 'fontes': {
        'deadlocks:postgres-front-office': {'estado': 'coletado', 'origem': 'pg', 'instante': 'x', 'valores': 0},
        'latenciaFsync': {'estado': 'indisponivel', 'origem': 'nao coletado', 'instante': 'x', 'motivo': 'fora'}}}


def leituras():
    return {'getLogicosCatalogo': 12, 'chamadasAdministrativas': 9,
            'resultados': [{'cenario': 'ATIVOS', 'estado': 'CONCLUIDA', 'amostras': 2,
                            'contagens': contagens(2)}]}


def montar(**alteracoes):
    base = {
        'leituras': leituras(),
        'escrita': {'execucoes': [escrita(4), escrita(12)]},
        'cancelamento': {'execucoes': [cancelamento('a' * 32), cancelamento('b' * 32, falha=True)]},
        'telemetria': [snapshot('antes-escrita'), snapshot('depois-escrita')],
        'sha': SHA, 'contexto': {'runner': 'ci-hospedado', 'run': '42'}, 'fontes': ['carga-leituras.json'],
    }
    base.update(alteracoes)
    return base


class Consolidacao(unittest.TestCase):
    def test_execucao_integral_gera_evidencia_conferida(self):
        doc = cons.consolidar(**montar())
        self.assertEqual(doc['tipo'], 'evidencia-carga-integrada')
        self.assertEqual(doc['sha'], SHA)
        self.assertEqual(doc['conciliacaoFinanceira']['estado'], 'conferida')
        self.assertEqual(doc['capacidade'], 'nao_inferida')
        self.assertEqual(doc['contagens']['oferecidas'], 16)
        self.assertEqual(doc['contagens']['emVoo'], 0)
        cons.validar(doc)

    def test_reconciliacao_financeira_pendente_e_explicita_nao_some(self):
        execucoes = [escrita(4), escrita(12, errosHTTP=0)]
        execucoes[1]['resultadoFinanceiro'] = 'pendente'
        doc = cons.consolidar(**montar(escrita={'execucoes': execucoes}))
        self.assertEqual(doc['conciliacaoFinanceira']['estado'], 'pendente')
        self.assertEqual(doc['conciliacaoFinanceira']['pendencias'], [{'execucao': 2, 'motivo': 'pendente'}])
        cons.validar(doc)


class Invariantes(unittest.TestCase):
    def test_oferecidas_iniciadas_finalizadas_erros_descartes_e_em_voo(self):
        ruins = (
            contagens(4, oferecidas=5),
            contagens(4, finalizadas=3),
            contagens(4, errosHTTP=1),
            contagens(4, descartadas=1),
            contagens(4, emVoo=1),
        )
        for ruim in ruins:
            execucao = escrita(4)
            execucao['contagens'] = ruim
            with self.subTest(contagens=ruim), self.assertRaises(ValueError):
                cons.consolidar(**montar(escrita={'execucoes': [execucao, escrita(12)]}))

    def test_duas_execucoes_com_ids_disjuntos_sao_obrigatorias(self):
        with self.assertRaises(ValueError):
            cons.consolidar(**montar(cancelamento={'execucoes': [cancelamento('a' * 32)]}))
        with self.assertRaises(ValueError):
            cons.consolidar(**montar(cancelamento={'execucoes': [cancelamento('a' * 32), cancelamento('a' * 32)]}))
        with self.assertRaises(ValueError):
            cons.consolidar(**montar(escrita={'execucoes': [escrita(4)]}))

    def test_sha_e_telemetria_sao_validados(self):
        with self.assertRaises(ValueError):
            cons.consolidar(**montar(sha='XYZ'))
        telemetria = snapshot('antes-escrita')
        telemetria['fontes']['deadlocks:postgres-front-office']['valores'] = None
        with self.assertRaises(ValueError):
            cons.consolidar(**montar(telemetria=[telemetria, snapshot('depois-escrita')]))


class Vedacoes(unittest.TestCase):
    def test_token_corpo_e_jwt_nunca_entram_na_evidencia(self):
        proibidos = ({'token': 'x'}, {'corpo': 'x'}, {'authorization': 'x'}, {'Bearer': 'x'})
        for extra in proibidos:
            leitura = leituras()
            leitura['resultados'][0].update(extra)
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                cons.consolidar(**montar(leituras=leitura))
        leitura = leituras()
        leitura['resultados'][0]['observacao'] = 'eyJhbGciOiJIUzI1NiJ9.payload.assinatura'
        with self.assertRaises(ValueError):
            cons.consolidar(**montar(leituras=leitura))

    def test_capacidade_nunca_e_inferida_por_throughput(self):
        doc = cons.consolidar(**montar())
        doc['throughputMaximo'] = 300
        with self.assertRaises(ValueError):
            cons.validar(doc)


if __name__ == '__main__':
    unittest.main()
