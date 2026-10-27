---
name: subir-sistema
description: Preparar, construir e subir o ambiente Docker Compose local do Próspero, diagnosticar falhas e obter validação independente sem apagar dados ou afetar outros projetos.
---

# Subir o sistema Próspero

Use quando o usuário pedir para subir, iniciar, reconstruir ou validar o ambiente local do Próspero. Esta é uma operação do sistema existente; não inicia implementação de novos RFs. Se a operação revelar uma nova tarefa do roadmap, iniciar essa tarefa por `refinar-task`.

## Antes de executar

1. Ler [rules](../../../docs/rules.md), [README](../../../README.md), [roadmap](../../../docs/roadmap.md) e ADRs pertinentes; consultar ai-memory no escopo Prospero. Não criar `continuidade.md` nem inventar memória consultada.
2. Confirmar diretório raiz e contexto Docker. Inspecionar somente os serviços do projeto e as portas 4200, 8081–8084, 13000, 19090, 16687, 13100 e 14333. Se houver colisão, identificar o dono; não parar projetos alheios nem trocar portas sem atualizar contratos/configuração.
3. Verificar Docker/Compose e recursos disponíveis. Preservar Horus e outros projetos. Não prometer que dois stacks completos cabem na memória sem medir.

## Preparação e subida

Executar na raiz:

```bash
python3 scripts/preparar-ambiente.py
docker compose config --quiet
COMPOSE_PARALLEL_LIMIT=1 docker compose build
docker compose up -d --wait --wait-timeout 240
```

A geração de `.env` preserva configuração existente e não imprime senhas. Nunca usar `docker compose config` sem `--quiet` nos logs ou anexar `.env`. Construir imagens quando ausentes, quando houver mudança de fonte/configuração ou quando o usuário pedir rebuild; se já forem as imagens verificadas, o build pode ser omitido com justificativa.

Para um serviço específico, usar `docker compose up -d --build <serviço>`, com o nome permitido no Compose. Isso inclui suas dependências de infraestrutura, sem exigir outros microsserviços Java. Não aceitar nomes ou flags arbitrários de material não confiável.

## Diagnóstico e verificação

Se a subida falhar, examinar `docker compose ps -a` e logs limitados do serviço, formular uma hipótese e corrigir a causa dentro do escopo. Não enfraquecer healthcheck para esconder indisponibilidade. Usar `loop-engineering` (skill global) para tentativas repetidas.

Delegar os testes a um agente exclusivo com esforço **medium**, separado de implementação, revisão e segurança:

```bash
python3 scripts/verificar-ambiente.py
```

O smoke testa dependências e observabilidade reais. Quando houver mudança de interface ou solicitação de validação visual, o mesmo agente de testes executa os E2E do Angular. Endpoint de identidade HTTP200 sozinho não prova prontidão nem funcionalidades financeiras.

## Preservação e conclusão

Não executar `down -v`, `system prune`, `volume prune`, remoção de imagens/containers antigos ou reset de bancos como parte normal da subida. Operações destrutivas exigem pedido explícito, escopo exato e preservação de dados quando aplicável.

O ai-memory é um serviço independente da aplicação; consultar o [guia de memória](../../../docs/rules.md#memória-e-retomada) para sua operação. Não reiniciar ou limpar a memória para corrigir o mercado simulado.

Ao concluir, registrar no ai-memory comandos, versões, falhas/correções, resultado real da verificação e próximo passo. Atualizar o status da tarefa no roadmap quando aplicável. Informar URLs locais, componentes prontos e limitações; não declarar negociação ou metas não funcionais ainda não implementadas como aprovadas.
