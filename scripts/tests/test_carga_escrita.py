"""Fronteiras do smoke B2: reconciliação, falha ensaiada e relatório público, só com dublês (sem stack)."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def carregar(nome, arquivo):
    spec = importlib.util.spec_from_file_location(nome, SCRIPTS / arquivo)
    modulo = importlib.util.module_from_spec(spec)
    sys.modules[nome] = modulo
    spec.loader.exec_module(modulo)
    return modulo


esc = carregar('carga_escrita_qa', 'testar-carga-escrita.py')
ci = carregar('carga_integrada_qa_b2', 'testar-carga-integrada.py')
INSTANCIA = 'b' * 32
IDENTIDADE = 'a' * 32
SEGREDO = 'sentinela-privada-qa-nao-publicar'
IDS = ['00000000-0000-4000-8000-00000000000%d' % i for i in range(1, 5)]


def plano_esperado(n, rps):
    return {'cenario': 'ORDEM_AGRESSOR', 'servico': 'front-office', 'modo': 'ABERTO',
            'estagios': [{'duracaoMs': n * 1000 // rps, 'rps': rps, 'vus': 0}], 'aquecimentoMs': 0,
            'concorrencia': 1, 'maxChamadas': n, 'timeoutMs': 2000, 'maxRespostaBytes': 65536}


def relatorio(n=4, rps=2, **extras):
    contagens = {nome: 0 for nome in ci.CONTAGENS}
    for nome in ('oferecidas', 'iniciadas', 'finalizadas', 'respostasHTTP'):
        contagens[nome] = n
    contagens['bytesLidos'] = 100 * n
    contagens.update(extras)
    return {'id': IDENTIDADE, 'instancia': INSTANCIA, 'estado': 'CONCLUIDA', 'plano': plano_esperado(n, rps),
            'ofertasPrevistas': n, 'contagens': contagens, 'medicao': dict(contagens)}


class Plano(unittest.TestCase):
    def test_plano_fixo_e_limitado(self):
        self.assertEqual(esc.plano(4, 2), plano_esperado(4, 2))
        for n, rps in ((0, 2), (21, 2), (4, 0), (4, 6)):
            with self.subTest(n=n, rps=rps), self.assertRaises(ValueError):
                esc.plano(n, rps)

    def test_projeto_e_composicao_seguem_o_smoke_b1(self):
        for projeto in ('horus-dev', 'prospero-ci-a-2', 'prospero-ci-1-2;id', ''):
            with self.subTest(projeto=projeto), self.assertRaises(ValueError):
                esc.validar_projeto(projeto)
        comando = esc.comando_compose('prospero-ci-1-2')
        self.assertEqual(comando, ci.comando_compose('prospero-ci-1-2'))
        self.assertEqual([comando[i + 1] for i, x in enumerate(comando) if x == '-f'], list(ci.ARQUIVOS_COMPOSE))


class Relatorio(unittest.TestCase):
    def test_nominal_aceito_e_qualquer_desvio_recusado(self):
        esc.validar_relatorio(relatorio(), INSTANCIA, 4, 2)
        for campo in ('descartadas', 'interrompidas', 'timeouts', 'falhasTransporte', 'canceladasEmVoo',
                      'limitesResposta', 'emVoo', 'errosHTTP', 'naoIniciadas'):
            r = relatorio(**{campo: 1})
            with self.subTest(campo=campo), self.assertRaises(ValueError):
                esc.validar_relatorio(r, INSTANCIA, 4, 2)
        for alterar in (lambda r: r.update(estado='CANCELADA'), lambda r: r.update(instancia='c' * 32),
                        lambda r: r['plano'].update(maxChamadas=5), lambda r: r.update(ofertasPrevistas=5)):
            r = relatorio(); alterar(r)
            with self.subTest(alteracao=alterar), self.assertRaises(ValueError):
                esc.validar_relatorio(r, INSTANCIA, 4, 2)

    def test_ids_do_fo_conferem_com_aceitas_do_gerador(self):
        esc.conferir_ids(relatorio(), set(IDS))
        for ids in (set(IDS[:3]), set(IDS) | {'00000000-0000-4000-8000-000000000009'}, set()):
            with self.subTest(n=len(ids)), self.assertRaises(ValueError):
                esc.conferir_ids(relatorio(), ids)


class Reconciliacao(unittest.TestCase):
    def relogio(self):
        self.agora = 0
        return lambda: self.agora

    def test_classifica_terminal_pendente_e_ausente(self):
        c = esc.classificar({IDS[0]: 'EXECUTADA', IDS[1]: 'CANCELADA', IDS[2]: 'ABERTA', IDS[3]: None})
        self.assertEqual(c['terminais'], {'EXECUTADA': 1, 'CANCELADA': 1})
        self.assertEqual((c['pendentes'], c['ausentes']), (1, 1))
        with self.assertRaises(ValueError):
            esc.classificar({IDS[0]: 'ESTADO_NOVO'})

    def test_pendente_vira_terminal_dentro_do_prazo_sem_sobreposicao(self):
        relogio = self.relogio()
        leituras = {i: 0 for i in IDS}
        def consultar(i):
            leituras[i] += 1
            return 'EXECUTADA' if leituras[i] >= 3 else 'RECEBIDA'
        def pausa(s):
            self.agora += s
        r = esc.reconciliar(IDS, consultar, prazo=30, pausa=pausa, relogio=relogio)
        self.assertEqual(r, {'ids': 4, 'terminais': {'EXECUTADA': 4}, 'pendentes': 0, 'ausentes': 0})
        self.assertTrue(all(n == 3 for n in leituras.values()), 'terminal nao deve ser consultado de novo')

    def test_pendente_apos_o_prazo_e_ausente_falham(self):
        relogio = self.relogio()
        def pausa(s):
            self.agora += s
        with self.assertRaises(ValueError):
            esc.reconciliar(IDS, lambda i: 'RECEBIDA', prazo=5, pausa=pausa, relogio=relogio)
        self.assertGreaterEqual(self.agora, 5)
        with self.assertRaises(ValueError):
            esc.reconciliar(IDS, lambda i: None if i == IDS[0] else 'EXECUTADA', prazo=2, pausa=pausa, relogio=relogio)

    def test_conjunto_vazio_nao_reconcilia(self):
        with self.assertRaises(ValueError):
            esc.reconciliar([], lambda i: 'EXECUTADA', prazo=1)


class Falha(unittest.TestCase):
    def test_servico_religa_mesmo_com_erro_na_acao(self):
        eventos = []
        with self.assertRaises(RuntimeError):
            esc.com_falha(lambda: eventos.append('parar'), lambda: eventos.append('religar'),
                          lambda: (_ for _ in ()).throw(RuntimeError('x')))
        self.assertEqual(eventos, ['parar', 'religar'])

    def test_servico_religa_no_caminho_feliz_e_se_parar_falhar(self):
        eventos = []
        self.assertEqual(esc.com_falha(lambda: eventos.append('parar'), lambda: eventos.append('religar'),
                                       lambda: 7), 7)
        self.assertEqual(eventos, ['parar', 'religar'])
        eventos.clear()
        def parar():
            eventos.append('parar'); raise OSError('parou pela metade')
        with self.assertRaises(OSError):
            esc.com_falha(parar, lambda: eventos.append('religar'), lambda: eventos.append('acao'))
        self.assertEqual(eventos, ['parar', 'religar'], 'parada parcial tambem religa e nao executa a acao')

    def test_falha_ao_religar_nao_mascara_erro_da_acao(self):
        def religar():
            raise OSError('nao religou')
        with self.assertRaises(RuntimeError):
            esc.com_falha(lambda: None, religar, lambda: (_ for _ in ()).throw(RuntimeError('acao')))


class RelatorioPublico(unittest.TestCase):
    def test_validade_dos_tokens_da_ci_cobre_leitura_e_escrita(self):
        self.assertEqual(ci.VALIDADE_TOKENS, 1200)

    def test_publica_so_contagens_sem_token_corpo_ou_ids(self):
        execucao = esc.resumo_execucao(relatorio(), True, {'ids': 4, 'terminais': {'EXECUTADA': 4},
                                                          'pendentes': 0, 'ausentes': 0}, 'conferido', 2)
        saida = json.dumps(esc.relatorio_publico([execucao], 37), ensure_ascii=False)
        publico = json.loads(saida)
        self.assertEqual(publico['tipo'], 'smoke-integrado-escrita')
        self.assertEqual(publico['chamadasAdministrativas'], 37)
        self.assertEqual(publico['execucoes'][0]['reconciliacao']['ids'], 4)
        self.assertTrue(publico['execucoes'][0]['falhaEnsaiada'])
        self.assertEqual(publico['execucoes'][0]['resultadoFinanceiro'], 'conferido')
        self.assertEqual(publico['execucoes'][0]['pendentesDuranteFalha'], 2)
        for fonte in ('wal', 'locks', 'lag', 'disco', 'rede'):
            self.assertEqual(publico['recursosNaoColetados'][fonte], 'indisponivel')
        for proibido in (SEGREDO, 'Bearer', 'autorizacao', 'precoLimite', 'ALFA3', 'CONTA-', IDENTIDADE, IDS[0], 'X-Idempotency'):
            self.assertNotIn(proibido, saida)


if __name__ == '__main__':
    unittest.main()
