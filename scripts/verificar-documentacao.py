#!/usr/bin/env python3
"""Verifica links mantidos pelo projeto e proveniência das skills locais."""
from pathlib import Path
import hashlib
import json
import re
import sys

root = Path(__file__).resolve().parents[1]
files = list(root.glob('*.md')) + list((root / 'docs').rglob('*.md'))
for component in ['front-office', 'middle-office', 'matching-engine', 'back-office', 'frontend']:
    files += list((root / component).glob('*.md'))
files += [root / '.agents/skills/README.md'] + list((root / '.agents/skills').glob('*/SKILL.md'))
errors = []
links = 0
for path in files:
    text = path.read_text()
    if not text.endswith('\n'):
        errors.append(f'{path.relative_to(root)}: falta newline final')
    for target in re.findall(r'\[[^\]]+\]\(([^)]+)\)', text):
        if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target) or target.startswith('#'):
            continue
        target = target.split('#')[0]
        if path.name == 'prospero-arquitetura.md' and target == 'prospero-arquitetura.pdf':
            continue  # Referência histórica ausente desde a proposta original.
        links += 1
        if not (path.parent / target).exists():
            errors.append(f'{path.relative_to(root)}: link ausente {target}')
manifest = json.loads((root / 'docs/skills/origens.json').read_text())
actual = {path.parent.name for path in (root / '.agents/skills').glob('*/SKILL.md')}
declared = {item['nome'] for item in manifest['skills']}
if actual != declared or len(actual) != 14:
    errors.append('Catálogo de skills difere do inventário de 14 entradas.')
for item in manifest['skills']:
    if item.get('tipo') != 'local' and not re.fullmatch('[0-9a-f]{40}', item.get('commit', '')):
        errors.append(f"Origem não fixada: {item['nome']}")
    skill_root = root / '.agents/skills' / item['nome']
    found = {str(p.relative_to(root)) for p in skill_root.rglob('*') if p.is_file()}
    if found != set(item['arquivos_sha256']):
        errors.append(f"Arquivos não inventariados ou ausentes: {item['nome']}")
    if any(p.is_symlink() for p in skill_root.rglob('*')):
        errors.append(f"Symlink não permitido na skill: {item['nome']}")
    for name, expected in item['arquivos_sha256'].items():
        path = root / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            errors.append(f'Proveniência divergente: {name}')
    path = root / '.agents/skills' / item['nome'] / 'SKILL.md'
    text = path.read_text()
    if not text.startswith('---\n') or f"name: {item['nome']}\n" not in text:
        errors.append(f'Metadados da skill inválidos: {path}')
if (root / 'docs/continuidade.md').exists():
    errors.append('continuidade.md foi removido por decisão do usuário; não recriar.')
for error in errors:
    print('ERRO:', error)
if errors:
    sys.exit(1)
print(f'OK: {len(files)} documentos, {links} links locais e 14 skills com hashes conferidos.')
