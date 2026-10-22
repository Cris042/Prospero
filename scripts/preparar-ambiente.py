#!/usr/bin/env python3
"""Cria credenciais locais sem sobrescrever valores existentes; acrescenta somente chaves ausentes."""
from pathlib import Path
import base64
import hashlib
import hmac
import json
import os
import secrets
import stat
import time

root = Path(__file__).resolve().parents[1]
path = root / ".env"
SENHAS = ["FO_DB_PASSWORD", "MO_DB_PASSWORD", "ME_DB_PASSWORD", "BO_DB_PASSWORD", "GRAFANA_ADMIN_PASSWORD"]
# Tokens injetados pelo proxy Nginx; o navegador nunca recebe a credencial.
VALIDADE_LEITURA = 365 * 24 * 3600
# Escrita (RF02c, ADR-010): validade curta, regenerada quando faltar menos de um dia.
VALIDADE_ORDENS = 7 * 24 * 3600
RENOVAR_ANTES = 24 * 3600


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


def expira_em(token):
    try:
        corpo = token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(corpo + "=" * (-len(corpo) % 4)))["exp"]
    except (IndexError, KeyError, ValueError):
        return 0


def ler(arquivo):
    valores = {}
    for linha in arquivo.read_text().splitlines():
        if "=" in linha and not linha.startswith("#"):
            chave, valor = linha.split("=", 1)
            valores[chave] = valor
    return valores


def exportar_token_robo(token):
    """Bind individual do secret do robô (ADR-013): diretório privado 0700 e arquivo 0444 legível pelo UID 65532."""
    privado = path.parent / ".robo-local"
    privado.mkdir(mode=0o700, exist_ok=True)
    privado.chmod(0o700)
    arquivo = privado / "token"
    if arquivo.exists() and arquivo.read_text() == token and stat.S_IMODE(arquivo.stat().st_mode) == 0o444:
        return
    temporario = privado / "token.tmp"
    temporario.unlink(missing_ok=True)
    descriptor = os.open(temporario, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(token)
    temporario.chmod(0o444)
    os.replace(temporario, arquivo)


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
    agora = int(time.time())
    if "FO_JWT_CHAVE" in novos or expira_em(existentes.get("FO_TOKEN_ORDENS", "")) < agora + RENOVAR_ANTES:
        novos["FO_TOKEN_ORDENS"] = jwt({"iss": "prospero-local", "aud": "prospero-front-office", "sub": "CONTA-001",
                                        "iat": agora, "exp": agora + VALIDADE_ORDENS,
                                        "groups": ["ordens:enviar", "ordens:leitura"]}, chave)
    # RF05d (ADR-013): token próprio do robô formador (CONTA-004), entregue só ao container opt-in, nunca ao Nginx.
    if "FO_JWT_CHAVE" in novos or expira_em(existentes.get("FO_TOKEN_ROBO", "")) < agora + RENOVAR_ANTES:
        novos["FO_TOKEN_ROBO"] = jwt({"iss": "prospero-local", "aud": "prospero-front-office", "sub": "CONTA-004",
                                      "iat": agora, "exp": agora + VALIDADE_ORDENS,
                                      "groups": ["ordens:enviar", "ordens:leitura"]}, chave)
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
    exportar_token_robo(ler(path)["FO_TOKEN_ROBO"])
    if novo:
        print(".env criado com credenciais locais aleatórias; valores não exibidos.")
    elif novos:
        print(f".env preservado; {len(novos)} chave(s) ausente(s) acrescentada(s); valores não exibidos.")
    else:
        print(".env já existe; conteúdo preservado.")


if __name__ == "__main__":
    main()
