#!/usr/bin/env python3
"""Cria credenciais locais sem sobrescrever valores existentes; acrescenta somente chaves ausentes."""
from pathlib import Path
import base64
import hashlib
import hmac
import json
import os
import secrets
import time

root = Path(__file__).resolve().parents[1]
path = root / ".env"
SENHAS = ["FO_DB_PASSWORD", "MO_DB_PASSWORD", "ME_DB_PASSWORD", "BO_DB_PASSWORD", "GRAFANA_ADMIN_PASSWORD"]
# Token só de leitura usado pelo proxy Nginx; o navegador nunca recebe a credencial.
VALIDADE_LEITURA = 365 * 24 * 3600


def jwt(payload, chave):
    codificar = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
    cabecalho = codificar(b'{"alg":"HS256","typ":"JWT"}')
    corpo = codificar(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
    assinado = cabecalho + "." + corpo
    return assinado + "." + codificar(hmac.new(chave.encode(), assinado.encode(), hashlib.sha256).digest())


def jwk(chave):
    # Quarkus/SmallRye lê a chave simétrica como JWK oct codificado em base64url (JWK_BASE64URL).
    codificar = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
    conjunto = {"keys": [{"kty": "oct", "kid": "prospero-local", "alg": "HS256", "k": codificar(chave.encode())}]}
    return codificar(json.dumps(conjunto, separators=(",", ":")).encode())


def ler(arquivo):
    valores = {}
    for linha in arquivo.read_text().splitlines():
        if "=" in linha and not linha.startswith("#"):
            chave, valor = linha.split("=", 1)
            valores[chave] = valor
    return valores


def main():
    novo = not path.exists()
    if novo:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write("# Gerado localmente; não versionar nem compartilhar.\n")
    existentes = ler(path)
    novos = {k: secrets.token_urlsafe(24) for k in SENHAS if k not in existentes}
    chave = existentes.get("FO_JWT_CHAVE")
    if chave is None:
        chave = novos["FO_JWT_CHAVE"] = secrets.token_urlsafe(48)
    if "FO_JWT_JWK" not in existentes or "FO_JWT_CHAVE" in novos:
        novos["FO_JWT_JWK"] = jwk(chave)
    if "FO_TOKEN_LEITURA" not in existentes or "FO_JWT_CHAVE" in novos:
        agora = int(time.time())
        novos["FO_TOKEN_LEITURA"] = jwt({"iss": "prospero-local", "aud": "prospero-front-office", "iat": agora,
                                         "exp": agora + VALIDADE_LEITURA, "groups": ["mercado:leitura"]}, chave)
    if novos:
        # Reescreve atomicamente preservando valores existentes; nunca exibe segredos.
        linhas = [l for l in path.read_text().splitlines() if not any(l.startswith(k + "=") for k in novos)]
        linhas += [f"{k}={v}" for k, v in novos.items()]
        temporario = path.with_suffix(".tmp")
        descriptor = os.open(temporario, os.O_CREAT | os.O_TRUNC | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write("\n".join(linhas) + "\n")
        os.replace(temporario, path)
    path.chmod(0o600)
    if novo:
        print(".env criado com credenciais locais aleatórias; valores não exibidos.")
    elif novos:
        print(f".env preservado; {len(novos)} chave(s) ausente(s) acrescentada(s); valores não exibidos.")
    else:
        print(".env já existe; conteúdo preservado.")


if __name__ == "__main__":
    main()
