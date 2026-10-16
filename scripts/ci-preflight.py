#!/usr/bin/env python3
"""Recusa carga pesada quando o runner não possui orçamento de memória."""
from pathlib import Path
import os
import sys

info = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
available = int(info['MemAvailable'].strip().split()[0]) // 1024
minimum = int(os.environ.get('PROSPERO_CI_MIN_MEMORY_MIB', '4096'))
if available < minimum:
    sys.exit(f'Runner sem orçamento: {available} MiB disponíveis; exigidos {minimum} MiB. Nenhum projeto será parado automaticamente.')
print(f'Runner: {available} MiB disponíveis; orçamento mínimo {minimum} MiB.')
