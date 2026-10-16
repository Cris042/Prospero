#!/usr/bin/env python3
"""Cria credenciais locais sem sobrescrever configuração existente."""
from pathlib import Path
import secrets
import os

root = Path(__file__).resolve().parents[1]
path = root / ".env"
if path.exists():
    print(".env já existe; conteúdo preservado.")
else:
    keys = ["FO_DB_PASSWORD", "MO_DB_PASSWORD", "ME_DB_PASSWORD", "BO_DB_PASSWORD", "GRAFANA_ADMIN_PASSWORD"]
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write("# Gerado localmente; não versionar nem compartilhar.\n")
        for key in keys:
            stream.write(f"{key}={secrets.token_urlsafe(24)}\n")
    path.chmod(0o600)
    print(".env criado com credenciais locais aleatórias; valores não exibidos.")
