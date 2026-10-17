#!/usr/bin/env python3
"""Pré-instalação do runner: consulta requisitos, sem registrar ou instalar nada."""
import argparse
import ctypes.util
import json
import os
from pathlib import Path
import platform
import pwd
import re
import shutil
import subprocess
import tempfile


REQUISITOS = {
    "usuario": "Executar como usuário não root, fora de container.",
    "plataforma": "Exige host Linux x64 com pelo menos duas CPUs.",
    "home": "Exige HOME próprio do usuário, sem escrita por grupo/outros.",
    "credenciais": "Remover credenciais pessoais e agentes herdados do usuário dedicado.",
    "ferramentas": "Exige Bash, Git, Python 3, Docker, curl, tar e sha256sum no PATH.",
    "node": "Exige Node.js 24 e npm no PATH.",
    "compose": "Exige Docker Compose com suporte real a !override.",
    "memoria": "Exige pelo menos 4096 MiB de RAM disponível no host.",
    "disco": "Exige pelo menos 20 GiB livres no HOME e no checkout.",
    "docker_local": "Exige contexto Docker com socket Unix local, sem overrides remotos.",
    "docker_servidor": "Daemon deve ser Linux x64, com duas CPUs e pelo menos 4096 MiB.",
    "docker_containers": "Daemon contém containers sem identificação exclusiva da CI Próspero.",
    "chromium": "Bibliotecas básicas do Chromium ausentes; preparar dependências na VM.",
}


def validar(fatos, atestado=False):
    erros = [mensagem for nome, mensagem in REQUISITOS.items() if not fatos.get(nome)]
    if not atestado:
        erros.append("Falta --atestar-isolamento: operador deve conferir VM/daemon/HOME/credenciais exclusivos, sem Horus ou host pessoal.")
    return erros


def memoria_suficiente(disponivel_mib):
    return disponivel_mib >= 4096


def containers_permitidos(containers):
    for container in containers:
        labels = dict(item.split("=", 1) for item in container.get("Labels", "").split(",") if "=" in item)
        if not re.fullmatch(r"prospero-ci-[0-9]+-[0-9]+", labels.get("com.docker.compose.project", "")):
            return False
    return True


def sem_credenciais(home, ambiente):
    # Só existência/estrutura: nunca ler ou imprimir valores de credenciais.
    caminhos = (
        ".aws", ".azure", ".kube", ".gnupg", ".config/gcloud", ".config/gh",
        ".git-credentials", ".netrc", ".npmrc", ".pypirc", ".m2/settings.xml",
    )
    variaveis = (
        "GH_TOKEN", "GITHUB_TOKEN", "SSH_AUTH_SOCK", "AWS_ACCESS_KEY_ID",
        "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "GOOGLE_APPLICATION_CREDENTIALS",
        "AZURE_CLIENT_SECRET", "NPM_TOKEN", "NODE_AUTH_TOKEN",
    )
    if any((home / path).exists() for path in caminhos) or any(ambiente.get(key) for key in variaveis):
        return False
    ssh = home / ".ssh"
    if ssh.exists() and any(path.name.startswith("id_") and not path.name.endswith(".pub") for path in ssh.iterdir()):
        return False
    config = Path(ambiente.get("DOCKER_CONFIG", home / ".docker"))
    if config.resolve() != (home / ".docker").resolve():
        return False
    arquivo = config / "config.json"
    if arquivo.exists():
        try:
            dados = json.loads(arquivo.read_text())
            if not isinstance(dados, dict) or any(dados.get(key) for key in ("auths", "credsStore", "credHelpers")):
                return False
        except (OSError, ValueError):
            return False
    return True


def consultar(*comando, entrada=None):
    # Sem shell, sem eco de stderr/ambiente e sem comandos de mutação.
    try:
        resultado = subprocess.run(comando, input=entrada, text=True, capture_output=True,
                                   timeout=15, check=False)
        return resultado.stdout.strip() if resultado.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def coletar():
    home = Path.home()
    fatos = {}
    fatos["usuario"] = os.getuid() != 0 and not Path("/.dockerenv").exists()
    fatos["plataforma"] = platform.system() == "Linux" and platform.machine() in ("x86_64", "amd64") and (os.cpu_count() or 0) >= 2
    try:
        estado = home.stat()
        fatos["home"] = home.resolve() == Path(pwd.getpwuid(os.getuid()).pw_dir).resolve() and estado.st_uid == os.getuid() and not estado.st_mode & 0o022
        fatos["credenciais"] = sem_credenciais(home, os.environ)
        fatos["disco"] = all(shutil.disk_usage(path).free >= 20 * 1024 ** 3 for path in (home, Path.cwd()))
    except (OSError, KeyError):
        fatos.update(home=False, credenciais=False, disco=False)
    fatos["ferramentas"] = all(shutil.which(name) for name in ("bash", "git", "python3", "docker", "curl", "tar", "sha256sum"))
    versao = consultar("node", "--version")
    fatos["node"] = bool(versao and re.fullmatch(r"v24\.[0-9]+\.[0-9]+", versao) and shutil.which("npm"))
    try:
        memoria = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        fatos["memoria"] = memoria_suficiente(int(memoria["MemAvailable"].split()[0]) // 1024)
    except (OSError, ValueError, KeyError):
        fatos["memoria"] = False
    bibliotecas = ("nss3", "atk-1.0", "atk-bridge-2.0", "cups", "xkbcommon", "Xcomposite", "Xdamage", "Xfixes", "Xrandr", "gbm", "asound")
    fatos["chromium"] = all(ctypes.util.find_library(name) for name in bibliotecas)
    # Parser temporário apenas; não cria rede, imagem, container ou volume.
    with tempfile.TemporaryDirectory(prefix="prospero-runner-preflight-") as directory:
        manifesto = Path(directory) / "compose.yml"
        manifesto.write_text("services:\n  probe:\n    image: scratch\n    ports: !override []\n")
        fatos["compose"] = consultar("docker", "compose", "-p", "prospero-preflight", "-f", str(manifesto), "config", "--quiet") is not None
    try:
        contexto = json.loads(consultar("docker", "context", "inspect") or "null")
        endpoint = contexto[0]["Endpoints"]["docker"]["Host"]
        fatos["docker_local"] = endpoint.startswith("unix:///") and not any(os.environ.get(name) for name in ("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"))
    except (ValueError, KeyError, TypeError, IndexError, AttributeError):
        fatos["docker_local"] = False
    # Não consultar daemon remoto nem mesmo para inventário.
    if fatos["docker_local"]:
        try:
            servidor = json.loads(consultar("docker", "info", "--format", "{{json .}}") or "null")
            fatos["docker_servidor"] = servidor["OSType"] == "linux" and servidor["Architecture"] in ("x86_64", "amd64") and servidor["NCPU"] >= 2 and memoria_suficiente(servidor["MemTotal"] // 1024 ** 2)
            saida = consultar("docker", "ps", "-a", "--format", "{{json .}}")
            fatos["docker_containers"] = saida is not None and containers_permitidos([json.loads(line) for line in saida.splitlines()])
        except (ValueError, KeyError, TypeError, AttributeError):
            fatos.update(docker_servidor=False, docker_containers=False)
    return fatos


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--atestar-isolamento", action="store_true", help="Operador conferiu host/VM, daemon/socket, HOME e credenciais exclusivos do Próspero, sem compartilhar Horus/host pessoal. Inventário não prova isolamento.")
    argumentos = parser.parse_args()
    try:
        erros = validar(coletar(), argumentos.atestar_isolamento)
    except OSError:
        erros = ["Não foi possível consultar requisitos locais; recusa sem expor dados do host."]
    for erro in erros:
        print("RECUSADO:", erro)
    if erros:
        return 1
    print("OK: requisitos locais atendidos e isolamento atestado pelo operador. Registro e execução remota continuam pendentes.")
    print("Limites: inventário não prova isolamento; conferir disco Docker, rede e gates reais na VM.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
