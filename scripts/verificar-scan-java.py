#!/usr/bin/env python3
"""Recusa relatório sem os componentes Java centrais da fundação."""
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text())
blocking = []
for result in report.get("Results", []):
    for finding in result.get("Vulnerabilities", []):
        if finding.get("Severity") in {"HIGH", "CRITICAL"}:
            blocking.append(finding)
            print("Vulnerabilidade:", finding.get("VulnerabilityID"), finding.get("PkgName"),
                  finding.get("InstalledVersion"), "correção:", finding.get("FixedVersion", "indisponível"))
if blocking:
    sys.exit("Scan Java com vulnerabilidades HIGH/CRITICAL; consultar os achados acima.")
packages = {
    package["Name"]
    for result in report.get("Results", []) if result.get("Type") == "jar"
    for package in result.get("Packages", [])
}
required = {
    "io.quarkus:quarkus-core",
    "org.hibernate.orm:hibernate-core",
    "org.postgresql:postgresql",
    "org.apache.kafka:kafka-clients",
}
missing = required - packages
if missing:
    sys.exit("Cobertura Java incompleta: " + ", ".join(sorted(missing)))
print(f"Cobertura Java presente: {len(packages)} componentes identificados.")
