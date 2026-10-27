---
name: refinar-task
description: Refinar obrigatoriamente cada nova tarefa do roadmap do Próspero — classifica via rápida ou fluxo completo, consulta memória e ADRs e colhe pareceres de Yoda, Patrick Jane e Neo (Jarvis com gatilho operacional) antes da implementação.
---

# Refinar tarefa do Próspero

Esta adaptação substitui os gatilhos por épico e as dispensas do [upstream](UPSTREAM.md). Invocar **sempre antes de iniciar uma nova tarefa do roadmap**, inclusive documental. Para tarefa já em curso, fazer rodada complementar somente se houver mudança material de escopo; não apresentar refinamento retroativo como realizado antes do código.

## Fontes obrigatórias

1. Ler [rules](../../../docs/rules.md), [roadmap](../../../docs/roadmap.md), tarefa do componente quando houver, [inventário](../../../docs/libs.md) e ADRs aplicáveis.
2. Aplicar o [guia documental](../../../docs/rules.md#documentação-e-aderência): revisar sistema, PRD/requisitos, HLD/desenho e ADRs, comparar com implementação e registrar aderência, divergências e necessidade proporcional de documentos antes do código.
3. Consultar ai-memory no escopo Prospero e recuperar decisões, handoff e tentativas relevantes. Confrontar o resultado com arquivos atuais. A memória não altera requisitos nem prevalece sobre ADRs e regras.
4. Registrar indisponibilidade real da memória e tentar recuperar a conexão; usar fontes documentais para trabalho independente. Não criar `continuidade.md`, não inventar consulta e não declarar a tarefa refinada com memória sem tê-la consultado.

## Classificação

Antes dos pareceres, classificar a mudança:

- **Via rápida:** documentação, governança, adaptador de ferramenta, automação mecânica ou manutenção **sem** comportamento de produto, endpoint, contrato público, persistência, integração, dependência ou decisão estrutural. Tarefa curta com objetivo, arquivos permitidos, validação determinística (sintaxe, links, estrutura, diff) e um revisor independente do tema dominante; Neo também quando tocar hook, permissão, segredo, dependência ou CI.
- **Fluxo completo:** qualquer outro caso, incluindo toda tarefa RF/P/V com efeito financeiro ou contrato. Na dúvida, fluxo completo; se a via rápida encontrar comportamento de produto, volta ao fluxo completo.

## Brief comum e pareceres

Preparar um mesmo brief com objetivo, requisito/RNF, limites, estado atual, decisões já aceitas, dependências, riscos e perguntas. Despachar pareceres em agentes separados, com esforço **medium**, sem revelar o parecer de outro agente antes da análise inicial. Os papéis seguem as definições globais em `~/.agents/agentes/`:

| Agente | Perspectiva | Quando |
| --- | --- | --- |
| Yoda | Arquitetura/domínio: fronteiras, invariantes, compatibilidade, ADRs, tamanho e sequência da tarefa | Sempre no fluxo completo |
| Patrick Jane | Testes/QA: critérios observáveis, casos negativos, planilha (ADR-004) e caminho de evidências | Sempre no fluxo completo |
| Neo | Segurança: entradas, dados, permissões, dependências e superfícies de ataque | Sempre no fluxo completo |
| Jarvis | Operação: recursos locais, containers, telemetria, persistência e recuperação | Quando houver Compose, ambiente, observabilidade, carga ou recuperação |

Ausência de gatilho para Jarvis é registrada no refinamento. O implementador (Severino ou a sessão principal) não substitui os pareceristas. Na validação da entrega, revisão = Yoda, testes = Patrick Jane e segurança = Neo, separados entre si e do implementador.

## Consolidação e início

Cada parecer informa `SEGUIR`, `SEGUIR COM RESSALVAS` ou `REFORMULAR`, evidências, critérios e lacunas. Consolidar concordâncias e divergências, com no máximo uma tréplica por ponto. Aplicar regras/ADRs para resolver o que já tem contrato; decisões novas de negócio/escopo ou mudanças arquiteturais materiais voltam ao usuário e a ADR quando pertinente. Não repetir perguntas já respondidas.

Registrar brief, agentes efetivos, posições, divergências, decisões, critérios de aceite, plano de testes e próxima ação na seção existente da tarefa no roadmap ou no corpo do PR. Salvar síntese/referência no ai-memory. Não criar arquivo/pasta de refinamento por rotina; documentação mínima conforme rules e instrução atual do usuário.

Somente iniciar a implementação dependente após resolver lacunas bloqueantes. Registrar o que ainda pode avançar de forma independente. Refinamento não aprova código, PR, merge ou release. Não exigir PRD/FDD/HLD por ritual nem Obsidian/personas do Minerva. Usar ferramentas de agentes realmente disponíveis; sem agentes, declarar limitação em vez de simular consenso.

## Proveniência

Fonte: [Cris042/Minerva, commit 76a991d7296c](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/skills/refinar-task.md). O original está preservado em `UPSTREAM.md`; gatilhos, caminhos, responsabilidades e uso de memória seguem esta adaptação e as instruções atuais do usuário.
