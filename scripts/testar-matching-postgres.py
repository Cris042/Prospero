#!/usr/bin/env python3
"""Executa os ITs de matching em schemas próprios dos bancos efêmeros de uma execução CI."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--projeto", required=True)
    parser.add_argument("--cache", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"prospero-ci-[0-9]+-[0-9]+", args.projeto):
        parser.error("Somente projeto efêmero da execução CI é permitido.")
    raiz = Path(__file__).resolve().parents[1]
    cache = args.cache.resolve(strict=True)
    spec = importlib.util.spec_from_file_location("ambiente", raiz / "scripts/preparar-ambiente.py")
    ambiente = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ambiente)
    credenciais = ambiente.ler(raiz / ".env")
    for servico, sigla, banco, classe in [
        ("back-office", "BO", "bo", "PosicoesPostgresIT"),
        ("back-office", "BO", "bo", "RacionalPostgresIT"),
        ("back-office", "BO", "bo", "RazaoPostgresIT"),
        ("back-office", "BO", "bo", "LiquidacaoPostgresIT"),
        ("matching-engine", "ME", "me", "CheckpointPostgresIT"),
        ("middle-office", "MO", "mo", "AvisoOutboxPostgresIT"),
        ("middle-office", "MO", "mo", "PublicadorOutboxPostgresIT"),
        ("middle-office", "MO", "mo", "PublicacaoAdmissaoPostgresIT"),
        ("middle-office", "MO", "mo", "PublicacaoAdmissaoMigracaoPostgresIT"),
        ("middle-office", "MO", "mo", "MatchingPostgresIT"),
        ("middle-office", "MO", "mo", "CancelamentoSemCoordenacaoPostgresIT"),
        ("middle-office", "MO", "mo", "CancelamentoMigracaoPostgresIT"),
        ("middle-office", "MO", "mo", "CancelamentoCoordenacaoPostgresIT"),
        ("middle-office", "MO", "mo", "CancelamentoCorridaPostgresIT"),
        ("middle-office", "MO", "mo", "OcoPostgresIT"),
        ("middle-office", "MO", "mo", "OcoQaPostgresIT"),
        ("middle-office", "MO", "mo", "OcoAdmissaoQaPostgresIT"),
        ("middle-office", "MO", "mo", "EncerramentoPostgresIT"),
        ("middle-office", "MO", "mo", "EncerramentoSessaoPostgresIT"),
        ("middle-office", "MO", "mo", "EncerramentoQaPostgresIT"),
        ("middle-office", "MO", "mo", "EncerramentoSessaoQaPostgresIT"),
        ("middle-office", "MO", "mo", "LiquidacaoPostgresIT"),
        ("front-office", "FO", "fo", "AvisoOutboxPostgresIT"),
        ("front-office", "FO", "fo", "PublicadorOutboxPostgresIT"),
        ("front-office", "FO", "fo", "TrajetoriaPostgresIT"),
        ("front-office", "FO", "fo", "MatchingPostgresIT"),
        ("front-office", "FO", "fo", "CotacaoPostgresIT"),
        ("front-office", "FO", "fo", "OcoQaPostgresIT"),
        ("front-office", "FO", "fo", "EncerramentoQaPostgresIT"),
        ("front-office", "FO", "fo", "CancelamentoManualPostgresIT"),
        ("front-office", "FO", "fo", "CancelamentoOperacaoPostgresIT"),
    ]:
        # Nome de variável em --env: o segredo viaja só no ambiente, sem entrar no argumento/log.
        temporario = "matching_it_" + uuid.uuid4().hex
        base = ["docker", "compose", "-p", args.projeto, "-f", str(raiz / "docker-compose.yml"),
                "-f", str(raiz / "docker-compose.ci.yml")]
        recipiente = subprocess.check_output(base + ["ps", "-q", f"postgres-{servico}"], text=True).strip()
        if not re.fullmatch(r"[0-9a-f]{12,64}", recipiente):
            raise SystemExit("Container único do banco CI não encontrado.")
        psql = ["docker", "exec", recipiente, "psql", "-X", "-v", "ON_ERROR_STOP=1",
                "-U", f"prospero_{banco}", "-d", "postgres", "-c"]
        contexto = {
            **os.environ,
            "MATCHING_TEST_DB_URL": f"jdbc:postgresql://postgres-{servico}:5432/{temporario}",
            "MATCHING_TEST_DB_USER": f"prospero_{banco}",
            "MATCHING_TEST_DB_PASSWORD": credenciais[f"{sigla}_DB_PASSWORD"],
        }
        comando = [
            "docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
            "--memory=1g", "--cpus=2", "--network", f"{args.projeto}_dados-{banco}",
            "-e", "MATCHING_TEST_DB_URL", "-e", "MATCHING_TEST_DB_USER", "-e", "MATCHING_TEST_DB_PASSWORD",
            "-e", "MAVEN_USER_HOME=/maven",
            "-e", "MAVEN_OPTS=-Xmx384m -XX:ActiveProcessorCount=2 -Duser.home=/tmp/prospero -Dmaven.repo.local=/maven/repository",
            "-v", f"{raiz}:/workspace", "-v", f"{cache}:/maven", "-w", f"/workspace/{servico}",
            "eclipse-temurin:26.0.2.1_1-jdk-noble", "sh", "./mvnw", "-B", "-ntp", "-o",
            f"-Dtest={classe}", "test",
        ]
        print(f"PostgreSQL real: {servico}/{classe}, banco e schema descartáveis próprios.", flush=True)
        # Advisory locks pertencem ao banco: só um schema distinto disputaria a posse com o ME ativo.
        # Nome aleatório gerado aqui, sem identificador SQL recebido de usuário/arquivo/memória.
        subprocess.run(psql + [f"CREATE DATABASE {temporario} OWNER prospero_{banco}"], check=True)
        try:
            subprocess.run(comando, env=contexto, check=True)
        finally:
            subprocess.run(psql + [f"DROP DATABASE {temporario} WITH (FORCE)"], check=True)


if __name__ == "__main__":
    main()
