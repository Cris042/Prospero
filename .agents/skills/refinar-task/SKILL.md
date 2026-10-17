---
name: refinar-task
description: Refinar obrigatoriamente cada nova tarefa do roadmap do Próspero com memória, ADRs e pareceres de agentes separados antes da implementação.
---

# Refinar tarefa do Próspero

Esta adaptação substitui os gatilhos por épico e as dispensas do [upstream](UPSTREAM.md). Invocar **sempre antes de iniciar uma nova tarefa do roadmap**, inclusive documental. Para tarefa já em curso, fazer rodada complementar somente se houver mudança material de escopo; não apresentar refinamento retroativo como realizado antes do código.

## Fontes obrigatórias

1. Ler [rules](../../../docs/rules.md), [roadmap](../../../docs/roadmap.md), tarefa do componente quando houver, [inventário](../../../docs/libs.md) e ADRs aplicáveis.
2. Aplicar o [guia documental](../../../docs/documentacao.md): revisar sistema, PRD/requisitos, HLD/desenho e ADRs, comparar com implementação e registrar aderência, divergências e necessidade proporcional de documentos antes do código.
3. Consultar ai-memory no escopo Prospero e recuperar decisões, handoff e tentativas relevantes. Confrontar o resultado com arquivos atuais. A memória não altera requisitos nem prevalece sobre ADRs e regras.
4. Registrar indisponibilidade real da memória e tentar recuperar a conexão; usar fontes documentais para trabalho independente. Não criar `continuidade.md`, não inventar consulta e não declarar a tarefa refinada com memória sem tê-la consultado.

## Brief comum e pontos de vista

Preparar um mesmo brief com objetivo, requisito/RNF, limites, estado atual, decisões já aceitas, dependências, riscos e perguntas. Despachar pareceres em agentes separados, todos com esforço **medium**, sem revelar o primeiro parecer de outro agente antes da análise inicial:

- Arquitetura/domínio: fronteiras, invariantes, compatibilidade e ADRs.
- Testes/QA: critérios observáveis, casos negativos, planilha e caminho de evidências.
- Segurança: entradas, dados, permissões, dependências e superfícies de ataque.
- Operação: recursos locais, containers, telemetria, persistência e recuperação.
- Viabilidade: sequência de implementação, dependências e tamanho da tarefa.

São cinco perspectivas independentes; não exigir execução simultânea. Respeitar slots disponíveis e executar em lotes, preservando o brief. O implementador não substitui os pareceristas. Agentes de revisão, testes e segurança permanecem separados entre si e do implementador durante a validação da entrega.

## Consolidação e início

Cada parecer informa `SEGUIR`, `SEGUIR COM RESSALVAS` ou `REFORMULAR`, evidências, critérios e lacunas. Consolidar concordâncias e divergências, com no máximo uma tréplica por ponto. Aplicar regras/ADRs para resolver o que já tem contrato; decisões novas de negócio/escopo ou mudanças arquiteturais materiais voltam ao usuário e a ADR quando pertinente. Não repetir perguntas já respondidas.

Registrar o refinamento em `docs/refinamentos/<ID>-<slug>.md`, com o brief, agentes efetivos, posições, divergências, decisões, critérios de aceite, plano de testes, dependências e próxima ação. Salvar síntese e referência desse documento no ai-memory. Vincular o registro à tarefa do roadmap.

Somente iniciar a implementação dependente após resolver lacunas bloqueantes. Registrar o que ainda pode avançar de forma independente. Refinamento não aprova código, PR, merge ou release. Não exigir PRD/FDD/HLD por ritual nem Obsidian/personas do Minerva. Usar ferramentas de agentes realmente disponíveis; sem agentes, declarar limitação em vez de simular consenso.

## Proveniência

Fonte: [Cris042/Minerva, commit 76a991d7296c](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/skills/refinar-task.md). O original está preservado em `UPSTREAM.md`; gatilhos, caminhos, responsabilidades e uso de memória seguem esta adaptação e as instruções atuais do usuário.
