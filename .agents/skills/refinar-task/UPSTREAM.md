# Skill: refinar-task

**Autor:** Cristóvão Augusto

**Definição canônica e independente de ferramenta.** Adaptador de descoberta em `.claude/skills/refinar-task/SKILL.md`, sem conteúdo próprio.

## Para o futuro agente

Conduzir uma cerimônia multiagente de refinamento de um épico antes do primeiro PRD desse épico. O refinamento antecipa exigências de arquitetura, qualidade, segurança, operação e viabilidade, consolidadas por futura task, sem decidir regra de negócio, implementar código ou criar um novo estágio no fluxo canônico.

O refinamento é uma **técnica dentro da etapa de épico/planejamento** do fluxo `roadmap → épico → PRD → HLD → FDD → task → PR → auditoria → merge → deploy`. Seu registro alimenta os documentos posteriores, mas não substitui PRD, HLD, FDD, ADR nem task.

## Origem e adaptação

Adaptada da skill [`refinar-task`](https://github.com/mclovin137/TGM2/blob/abc15b18bdc3e814c1dffa5e4e1eb99de7a7e292/.claude/skills/refinar-task/SKILL.md) do projeto `mclovin137/TGM2`, snapshot do commit `abc15b18bdc3e814c1dffa5e4e1eb99de7a7e292`.

A origem consultada não declarou licença no arquivo da skill, e não foi encontrado arquivo `LICENSE` na raiz desse snapshot. Esta versão preserva a utilidade da cerimônia, mas foi reescrita para o contrato do projeto Minerva: remove dependências de `roles.md`, `state.md`, `plan.md`, `AskUserQuestion`, modelo específico, limite fixo de arquivos e caminhos próprios do TGM2.

## Responsabilidade e participantes

- **Oráculo:** orquestrar a cerimônia, preparar o mesmo brief para todos, despachar os pareceres, controlar no máximo uma tréplica e devolver decisões ao usuário. Não escrever arquivos nem emitir parecer especialista.
- **Yoda:** avaliar arquitetura, fronteiras, aderência a HLD/ADRs, decisões pendentes, domínio e risco de overengineering.
- **Patrick Jane:** avaliar critérios verificáveis, matriz de testes, invariantes, regressão, idempotência e evidências.
- **Neo:** avaliar superfície de ataque, dados sensíveis, permissões, input/output, dependências e TCs de segurança.
- **Jarvis:** avaliar impacto operacional, ambientes, capacidade, observabilidade, backup/restore, rollback, deploy e limites de custo zero.
- **Severino:** avaliar viabilidade de implementação, dependências técnicas, divisão coerente do trabalho e lacunas do FDD, sem decidir arquitetura.

Cada participante permanece dentro da própria definição em `docs/agentes/`. Parecer consultivo não transfere autoria nem aprovação. Ninguém aprova o próprio trabalho, e o futuro implementador não pode ser o auditor da entrega.

## Gatilhos

Usar antes do primeiro PRD de um épico quando ao menos uma condição se aplicar:

- o épico prevê mais de uma feature ou futura task com dependências entre elas;
- toca regra de negócio, endpoint, dado crítico, fronteira de contexto ou integração;
- atravessa arquitetura, segurança, QA e operação;
- envolve risco relevante, comportamento irreversível ou decisão ainda pendente;
- o usuário pede explicitamente refinamento multiagente.

Pular quando:

- já existe refinamento vigente para o mesmo escopo do épico;
- a demanda é trivial, como documentação isolada, chore mecânico ou correção pequena sem efeito em comportamento, arquitetura, segurança, operação ou estratégia de testes;
- a entrega é pequena e independente, e o planejamento disponível permite seguir diretamente para o PRD sem ganho material da cerimônia.

Não repetir por futura task. Fazer rodada complementar somente quando o escopo do épico mudar materialmente ou quando o usuário pedir foco adicional numa parte crítica. Registrar por que a rodada adicional foi necessária.

## Pré-requisitos

1. Ler `docs/rules.md`, `docs/continuidade.md` e `docs/lib.md`.
2. Identificar roadmap, épico, decisões do usuário, ADRs e documentos vigentes relacionados.
3. Confirmar que o épico tem objetivo e contorno suficientes para análise. Se nem o problema ou resultado esperado estiver definido, registrar `❓ LACUNA` e devolver ao usuário antes de mobilizar pareceres técnicos.
4. Procurar refinamento anterior e comparar seu escopo com o atual.

Aplicar somente Git, GitHub e Docker como fundações do template. Linguagem, framework, persistência, hospedagem, cache, testes e topologia não podem ser presumidos; pendência estrutural segue para ADR com Yoda e lacuna de negócio segue para o usuário.

## Procedimento

### 1. Decidir se refina

Aplicar os gatilhos e registrar uma das conclusões:

- `REFINAR`: informar o motivo e seguir;
- `PULAR`: informar a evidência de trivialidade ou o refinamento vigente e seguir para o próximo artefato canônico;
- `BLOQUEADO`: listar a lacuna, seu dono e o que ela impede.

### 2. Preparar um brief comum

Oráculo preparar um único brief, suficiente e proporcional ao épico, contendo:

- objetivo, escopo conhecido e exclusões;
- futuras tasks ou capacidades previstas, ainda sem fingir que são tasks canônicas;
- regras de negócio confirmadas e `❓ LACUNA` conhecidas;
- requisitos não funcionais e áreas críticas aplicáveis;
- HLDs e ADRs vigentes ou decisões pendentes;
- estado atual relevante do sistema e integrações tocadas;
- perguntas que cada especialidade deve responder.

Enviar o mesmo brief a todos. Cada agente pode ler fontes canônicas adicionais necessárias para fundamentar o próprio parecer. Não impor limite fixo de arquivos nem exigir releitura indiscriminada do repositório.

### 3. Obter pareceres independentes

Despachar Yoda, Patrick Jane, Neo, Jarvis e Severino. Executar pareceres em paralelo quando a ferramenta permitir; caso contrário, executar sequencialmente sem alterar o brief nem mostrar a um agente a conclusão dos anteriores antes de seu primeiro parecer.

Se a ferramenta não permitir instanciar agentes ou responsabilidades independentes, declarar a limitação e não apresentar uma simulação de voz única como consenso multiagente.

Cada parecer deve cobrir as futuras tasks aplicáveis e terminar com:

- posição: `SEGUIR`, `SEGUIR COM RESSALVAS` ou `REFORMULAR`;
- exigências para documentos e futuras tasks;
- riscos e evidências que sustentam o parecer;
- perguntas abertas e seu efeito bloqueante ou não bloqueante.

Não usar estimativa de tempo, modelo específico, quantidade fixa de arquivos ou escolha técnica ainda sem ADR como critério de viabilidade.

### 4. Confrontar divergências

Oráculo listar concordâncias e divergências sem escolher silenciosamente entre especialistas.

- Aplicar regras do projeto quando elas resolverem objetivamente o conflito.
- Permitir no máximo uma rodada de tréplica, somente com os agentes envolvidos e o ponto conflitante completo.
- Escalar ao usuário toda divergência de regra de negócio, escopo ou prioridade.
- Encaminhar a Yoda e a ADR toda divergência estrutural ou tecnológica; a ADR exige decisão do usuário antes de ser tratada como vigente.
- Manter como `❓ LACUNA` o que não tiver fonte ou decisão.

Consenso técnico não aprova PRD futuro, FDD, código ou PR. Cada artefato continua com seu dono e revisão previstos no projeto.

### 5. Consolidar por futura task

Para cada capacidade ou futura task, consolidar somente exigências rastreáveis:

- resultado esperado e limite de escopo;
- requisitos e invariantes conhecidos;
- restrições arquiteturais vigentes e ADRs necessários;
- critérios que o PRD deve tornar verificáveis;
- riscos de segurança e controles requeridos;
- estratégia de teste e evidências esperadas;
- requisitos operacionais e de observabilidade;
- dependências, ordem e lacunas.

Não criar identificador `T-NNN` nesta etapa. A task canônica só é criada depois de PRD e FDD aprovado, por `docs/skills/criar-task.md`.

### 6. Registrar quando houver épico real

Ao executar a skill para um épico real, seguir `docs/skills/atualizar-obsidian.md` e registrar o refinamento na nota do épico em `<BASE>/epicos/e-NNN-<slug>.md`, criando a pasta se necessário. Se uma rodada complementar alterar task canônica já existente, atualizar também a nota correspondente em `<BASE>/tasks/t-NNN-<slug>.md` e seu histórico. Como o Oráculo não escreve arquivos, ele delega essa escrita a um agente na responsabilidade de planejar/revisar e só consolida depois de receber e conferir os caminhos escritos.

Não criar nota de task antecipada para uma futura task ainda inexistente. A criação ou edição desta própria skill exige somente a nota `<BASE>/skills/refinar-task.md`; não criar nota fictícia de épico ou task.

## Molde do registro de refinamento

Adicionar ou atualizar na nota do épico:

```markdown
## Refinamento multiagente

Data: AAAA-MM-DD
Situação: vigente | substituído | bloqueado
Escopo analisado: <descrição>

### Pareceres
| Agente | Posição | Pontos-chave | Evidências |
| --- | --- | --- | --- |
| Yoda | <posição> | <síntese> | <fontes> |
| Patrick Jane | <posição> | <síntese> | <fontes> |
| Neo | <posição> | <síntese> | <fontes> |
| Jarvis | <posição> | <síntese> | <fontes> |
| Severino | <posição> | <síntese> | <fontes> |

### Divergências e resolução
1. <divergência>: <consenso fundamentado | escalada ao usuário | ADR necessária>

### Exigências por futura task
#### <capacidade ou nome provisório>
- Resultado e escopo: <itens>
- PRD deve cobrir: <itens>
- HLD/ADR: <itens>
- FDD deve cobrir: <itens>
- Testes e segurança: <itens>
- Operação e observabilidade: <itens>
- Dependências e lacunas: <itens>

### Decisões e lacunas
- Decisões confirmadas: <fontes>
- ADRs necessárias: <itens>
- ❓ LACUNA: <questão, dono e efeito>
```

## Checagens finais

- O refinamento permaneceu dentro da etapa de épico e não alterou o fluxo canônico.
- O mesmo brief sustentou todos os primeiros pareceres.
- Os cinco agentes canônicos participaram de forma independente ou a limitação foi declarada.
- Cada parecer respeitou sua responsabilidade e trouxe evidência.
- Houve no máximo uma tréplica por divergência.
- Divergência de negócio foi escalada ao usuário e decisão estrutural foi encaminhada para ADR.
- Exigências foram consolidadas por futura task sem criar task canônica antecipada.
- PRD, HLD, FDD, ADR e task não foram substituídos pela síntese.
- O refinamento foi pulado quando trivial ou já vigente.
- Na execução real, a nota de épico e eventuais tasks existentes foram atualizadas no Obsidian.

## Entrega

Informar a decisão `REFINAR`, `PULAR` ou `BLOQUEADO`; posições dos agentes; consensos; divergências; ADRs e lacunas; exigências por futura task; próximo artefato canônico; e caminhos completos das notas Obsidian realmente escritas.
