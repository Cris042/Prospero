#!/usr/bin/env python3
"""Verifica fronteiras dos quatro serviços sem depender do build agregador."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CONTEXTS = {"front-office": "fo", "middle-office": "mo", "matching-engine": "me", "back-office": "bo"}
FORBIDDEN_PURE = ("io.quarkus.", "jakarta.persistence.", "jakarta.ws.rs.", "jakarta.enterprise.",
                  "jakarta.inject.", "org.hibernate.", "org.apache.kafka.", "java.sql.",
                  "javax.sql.", "java.net.", "io.smallrye.")
errors = []
for module, context in CONTEXTS.items():
    root = ROOT / module
    pom = ET.parse(root / "pom.xml")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for dependency in pom.findall(".//m:dependency", ns):
        if dependency.findtext("m:groupId", namespaces=ns) == "br.prospero":
            errors.append(f"{module}: dependência da implementação de outro serviço")
    for path in (root / "src/main/java").rglob("*.java"):
        source = path.read_text()
        imports = re.findall(r"^import\s+(?:static\s+)?([^;]+);", source, re.M)
        for imported in imports:
            if imported.startswith("br.prospero.") and not imported.startswith(f"br.prospero.{context}."):
                errors.append(f"{path.relative_to(ROOT)}: import entre contextos: {imported}")
            if "rest" in path.parts and (".jpa." in imported or imported.startswith("jakarta.persistence.")):
                errors.append(f"{path.relative_to(ROOT)}: REST acessa persistência")
            if {"helper", "dominio"}.intersection(path.parts) and imported.startswith(FORBIDDEN_PURE):
                errors.append(f"{path.relative_to(ROOT)}: domínio/helper depende de infraestrutura: {imported}")
        if "dominio" in path.parts and re.search(r"\b(?:double|Double)\b", source):
            errors.append(f"{path.relative_to(ROOT)}: double no domínio financeiro")
for error in errors:
    print("ERRO:", error)
if errors:
    sys.exit(1)
print("OK: serviços sem dependências de implementação entre si; REST isolado de JPA; domínio/Helpers puros.")
