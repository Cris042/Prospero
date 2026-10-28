#!/usr/bin/env python3
"""Evidência integrada da carga (B3): junta leituras (B1), escrita (B2), cancelamento sob carga e snapshots de telemetria
de uma execução em `carga-consolidada.json`.

Regras: oferecidas = iniciadas = finalizadas + erros + descartes e em voo 0 em cada execução; duas execuções com
identificadores (rodadas) disjuntos; reconciliação financeira `conferida` ou `pendente` com a lista explícita das
pendências; ausência de fonte nunca vira zero; nenhum token, cabeçalho de autorização, corpo ou capacidade inferida
(throughput). O arquivo recebe o SHA avaliado e o contexto do runner, sem dados de conta."""
import argparse
import json
from pathlib import Path
import re
import sys

VERSAO = 1
CHAVES_TOPO = ('tipo', 'versao', 'sha', 'contexto', 'fontes', 'leituras', 'escrita', 'cancelamento', 'contagens',
               'conciliacaoFinanceira', 'telemetria', 'capacidade')
PROIBIDAS = ('token', 'authorization', 'corpo', 'body', 'bearer', 'senha', 'password', 'jwt')
PREFIXOS_PROIBIDOS = ('eyJ', 'Bearer ')
PRAZO_TELEMETRIA = ('coletado', 'indisponivel')


def _rodada_valida(valor):
    return isinstance(valor, str) and re.fullmatch(r'[0-9a-f]{32}', valor) is not None


def _conferir_contagens(contagens, rotulo):
    try:
        oferecidas = contagens['oferecidas']
        iniciadas = contagens['iniciadas']
        finalizadas = contagens['finalizadas']
        erros = contagens['errosHTTP']
        descartes = contagens['descartadas']
        em_voo = contagens['emVoo']
    except (KeyError, TypeError):
        raise ValueError(f'contagens incompletas em {rotulo}') from None
    if not (oferecidas == iniciadas == finalizadas + erros + descartes) or em_voo != 0:
        raise ValueError(f'contagens inconsistentes em {rotulo}')


def _somar(execucoes, campo):
    return sum(e['contagens'][campo] for e in execucoes)


def _conferir_telemetria(snapshots):
    for snapshot in snapshots:
        if not isinstance(snapshot, dict) or not isinstance(snapshot.get('fontes'), dict):
            raise ValueError('snapshot de telemetria invalido')
        for nome, fonte in snapshot['fontes'].items():
            if fonte.get('estado') not in PRAZO_TELEMETRIA:
                raise ValueError('estado de telemetria desconhecido')
            if fonte['estado'] == 'coletado' and fonte.get('valores') is None:
                raise ValueError('telemetria coletada sem valor')
            if fonte['estado'] == 'indisponivel' and not fonte.get('motivo'):
                raise ValueError('telemetria indisponivel sem motivo')


def _conciliacao(escrita, cancelamento):
    pendencias = []
    for indice, execucao in enumerate(escrita, start=1):
        if execucao.get('resultadoFinanceiro') != 'conferido':
            pendencias.append({'execucao': indice, 'motivo': execucao.get('resultadoFinanceiro') or 'ausente'})
    for indice, execucao in enumerate(cancelamento, start=1):
        if execucao.get('liberacoes') != 1 or execucao.get('saldoAposLiberacao', {}).get('saldoReservado') != '0.00':
            pendencias.append({'execucao': f'cancelamento-{indice}', 'motivo': 'liberacao divergente'})
    return {'estado': 'conferida' if not pendencias else 'pendente', 'pendencias': pendencias}


def consolidar(*, leituras, escrita, cancelamento, telemetria, sha, contexto, fontes):
    if not isinstance(sha, str) or not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('sha invalido')
    execucoes_escrita = escrita.get('execucoes')
    execucoes_cancelamento = cancelamento.get('execucoes')
    if not isinstance(execucoes_escrita, list) or len(execucoes_escrita) != 2:
        raise ValueError('a evidencia exige duas execucoes de escrita')
    if not isinstance(execucoes_cancelamento, list) or len(execucoes_cancelamento) != 2:
        raise ValueError('a evidencia exige duas execucoes de cancelamento')
    rodadas = [e.get('rodada') for e in execucoes_cancelamento]
    if not all(_rodada_valida(r) for r in rodadas) or len(set(rodadas)) != 2:
        raise ValueError('identificadores das execucoes nao sao disjuntos')
    for indice, execucao in enumerate(execucoes_escrita, start=1):
        _conferir_contagens(execucao.get('contagens'), f'escrita {indice}')
    for indice, resultado in enumerate(leituras.get('resultados', []), start=1):
        _conferir_contagens(resultado.get('contagens'), f'leitura {indice}')
    _conferir_telemetria(telemetria)
    total = {
        'oferecidas': _somar(execucoes_escrita, 'oferecidas'), 'iniciadas': _somar(execucoes_escrita, 'iniciadas'),
        'finalizadas': _somar(execucoes_escrita, 'finalizadas'), 'erros': _somar(execucoes_escrita, 'errosHTTP'),
        'descartes': _somar(execucoes_escrita, 'descartadas'), 'emVoo': _somar(execucoes_escrita, 'emVoo'),
    }
    doc = {
        'tipo': 'evidencia-carga-integrada', 'versao': VERSAO, 'sha': sha, 'contexto': contexto,
        'fontes': sorted(fontes),
        'leituras': {'getLogicosCatalogo': leituras.get('getLogicosCatalogo'),
                     'chamadasAdministrativas': leituras.get('chamadasAdministrativas'),
                     'resultados': leituras.get('resultados', [])},
        'escrita': {'execucoes': execucoes_escrita},
        'cancelamento': {'execucoes': execucoes_cancelamento},
        'contagens': total,
        'conciliacaoFinanceira': _conciliacao(execucoes_escrita, execucoes_cancelamento),
        'telemetria': telemetria,
        'capacidade': 'nao_inferida',
    }
    validar(doc)
    return doc


def _percorrer(valor, caminho='raiz'):
    if isinstance(valor, dict):
        for chave, filho in valor.items():
            if str(chave).lower() in PROIBIDAS:
                raise ValueError(f'chave proibida em {caminho}')
            if 'throughput' in str(chave).lower() or ('capacidade' in str(chave).lower() and caminho != 'raiz'):
                raise ValueError('capacidade nao pode ser inferida na evidencia')
            _percorrer(filho, f'{caminho}.{chave}')
    elif isinstance(valor, list):
        for indice, filho in enumerate(valor):
            _percorrer(filho, f'{caminho}[{indice}]')
    elif isinstance(valor, str) and valor.startswith(PREFIXOS_PROIBIDOS):
        raise ValueError(f'valor proibido em {caminho}')


def validar(doc):
    if not isinstance(doc, dict) or set(doc) != set(CHAVES_TOPO):
        raise ValueError('estrutura da evidencia invalida')
    if doc['tipo'] != 'evidencia-carga-integrada' or doc['versao'] != VERSAO:
        raise ValueError('tipo ou versao da evidencia invalidos')
    if doc['capacidade'] != 'nao_inferida':
        raise ValueError('capacidade nao pode ser inferida na evidencia')
    if not re.fullmatch(r'[0-9a-f]{40}', str(doc['sha'])):
        raise ValueError('sha invalido')
    _percorrer(doc)
    return True


class _Argumentos(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError('argumentos invalidos')


def _ler(caminho):
    return json.loads(Path(caminho).read_text(encoding='utf-8'))


def main(argv=None):
    try:
        parser = _Argumentos(description=__doc__)
        parser.add_argument('--leituras', required=True, type=Path)
        parser.add_argument('--escrita', required=True, type=Path)
        parser.add_argument('--cancelamento', required=True, type=Path)
        parser.add_argument('--telemetria', required=True, type=Path, action='append')
        parser.add_argument('--sha', required=True)
        parser.add_argument('--runner', required=True)
        parser.add_argument('--run', required=True)
        parser.add_argument('--saida', required=True, type=Path)
        args = parser.parse_args(argv)
        if args.runner != 'ci-hospedado' or not re.fullmatch(r'[0-9]+', args.run):
            raise ValueError('contexto do runner invalido')
        arquivos = [args.leituras, args.escrita, args.cancelamento, *args.telemetria]
        doc = consolidar(leituras=_ler(args.leituras), escrita=_ler(args.escrita),
                         cancelamento=_ler(args.cancelamento), telemetria=[_ler(a) for a in args.telemetria],
                         sha=args.sha, contexto={'runner': args.runner, 'run': args.run},
                         fontes=[a.name for a in arquivos])
        args.saida.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'tipo': 'evidencia-carga-integrada', 'conciliacao': doc['conciliacaoFinanceira']['estado'],
                          'contagens': doc['contagens']}, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        print('Evidencia integrada recusada; verificar entradas e contagens. Dados sensiveis omitidos.',
              file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
