---
name: estado-roadmap
description: Use para saber onde o roadmap do Próspero está — resume em uma chamada o estado de cada tarefa (P, RF, V) do docs/roadmap.md e os PRs abertos, sem ler o roadmap inteiro.
---

# Estado do roadmap do Próspero

```bash
python3 .agents/skills/estado-roadmap/estado_roadmap.py
```

Mostra uma tabela com cada tarefa `P`, `RF` e `V` do [roadmap](../../../docs/roadmap.md), o estado declarado na primeira linha "Estado" da seção e o trecho dessa linha, seguida dos PRs abertos (via `gh`). Use no início de cada sessão, ao retomar o loop do roadmap e antes da `refinar-task`.

- Estado `?` significa que a seção não declara uma linha "Estado"; leia só aquela seção.
- O resumo reflete o que o roadmap **diz**. Divergência com o Git (PR integrado mas tarefa em validação, por exemplo) é `[DOC-MISMATCH]` a corrigir no roadmap, conferindo `gh pr view` antes.
- Para detalhes de uma tarefa, leia apenas a seção dela (`grep -n '^### RF10' docs/roadmap.md` e um intervalo de linhas), nunca o arquivo inteiro.
