#!/usr/bin/env bash
set -euo pipefail
prospero_memory_binary="${PROSPERO_MEMORY_BIN:-$HOME/.local/bin/ai-memory}"
prospero_memory_dir="${PROSPERO_MEMORY_DIR:-$HOME/.local/share/prospero-ai-memory}"
if [ ! -x "$prospero_memory_binary" ]; then
  printf '%s\n' 'ai-memory não instalado. Consulte docs/rules.md, seção Memória e retomada.' >&2
  exit 2
fi
exec "$prospero_memory_binary" --data-dir "$prospero_memory_dir" "$@"
