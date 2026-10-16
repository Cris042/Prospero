# Skill: gerar-adr

**Autor:** Cristóvão Augusto

**Definição canônica.** Adaptador de descoberta em `.claude/skills/gerar-adr/SKILL.md`, sem conteúdo próprio.

## Para o futuro agente

Conduzir a análise e registrar uma decisão arquitetural do projeto Minerva sem confundir hipótese, proposta e decisão aceita. ADR é transversal ao fluxo do projeto, tem **Yoda** como dono e documenta contexto, forças, alternativas, trade-offs, decisão e consequências.

## Responsabilidade e limites

- Exercer a responsabilidade de **planejar/revisar** como Yoda ou apoiar Yoda sem usurpar sua autoria.
- Não implementar a decisão nem aprovar trabalho próprio.
- Não escolher pelo usuário stack, hospedagem, CI, banco ou qualquer solução que permaneça pendente.
- Não converter hipótese em decisão. Sem aceite explícito, usar status `proposta`.
- Não omitir alternativa viável, inclusive manter o estado atual.
- Rejeitar opção paga. Só aceitar free tier permanente ou open source self-hosted sem custo. Se nenhuma opção gratuita for viável, devolver a decisão ao usuário.
- Não registrar regra de negócio como ADR. Lacuna de negócio deve ser marcada `❓ LACUNA` e encaminhada ao responsável pelo PRD/FDD.

## Entradas mínimas

- Decisão que precisa ser tomada e motivo para tomá-la agora.
- Artefato ou etapa que originou a decisão: PRD, HLD, FDD, task, incidente ou auditoria.
- Restrições e critérios de decisão verificáveis.
- ADRs anteriores relacionadas, quando existirem.

Se a decisão estiver mal delimitada, continuar a entrevista. Se o usuário pedir decisão de tecnologia sem critérios suficientes, apresentar hipóteses e pedir escolha, sem decidir silenciosamente.

## Entrevista

Fazer **uma pergunta por vez** e aguardar a resposta. Ao fim de cada etapa, resumir em 3 a 6 linhas e pedir confirmação. Sinalizar contradições antes de avançar.

1. **Contexto:** qual decisão é necessária, qual problema a originou e qual é o prazo lógico para decidir.
2. **Escopo:** quais componentes, fluxos e artefatos são afetados e o que fica fora.
3. **Forças:** critérios funcionais, operacionais, segurança, dados, manutenção, reversibilidade e custo zero.
4. **Opções:** estado atual e alternativas plausíveis. Para cada uma, registrar benefícios, custos, riscos e evidências. Opção sugerida pelo agente permanece `hipótese`.
5. **Trade-offs:** explicitar o que se ganha e o que se aceita perder em cada opção.
6. **Decisão:** pedir ao usuário o aceite explícito da opção. Sem aceite, manter `proposta` e registrar a decisão como `TBD`.
7. **Consequências:** efeitos positivos, negativos, riscos residuais, impacto em artefatos e condições para revisitar.
8. **Status e relações:** definir `proposta`, `aceita`, `rejeitada`, `substituída` ou `obsoleta`; relacionar ADRs e documentos afetados.

## Procedimento

1. Ler `docs/rules.md`, `docs/continuidade.md`, `docs/lib.md` e os artefatos de origem antes da entrevista.
2. Procurar ADRs de tema semelhante na base para evitar duplicidade e definir o próximo `NNN`.
3. Conduzir a entrevista e executar as checagens abaixo.
4. Renderizar exatamente o molde Markdown.
5. Salvar em `<BASE>/adrs/adr-NNN-<slug>.md`, com nome ASCII minúsculo e hífens.
6. Atualizar documentos afetados somente quando isso fizer parte do escopo autorizado. Uma ADR não altera retroativamente HLD, FDD ou task por conta própria.
7. Se o status for `proposta`, solicitar revisão de Yoda e aceite do usuário. Se Yoda for o autor, solicitar aceite do usuário e revisão independente quando houver auditoria associada.

## Checagens de consistência

- O título expressa uma única decisão estrutural.
- Contexto e forças explicam por que a decisão existe agora.
- Há ao menos duas opções reais e o estado atual foi considerado.
- Cada opção contém benefícios, custos, riscos e trade-offs comparáveis.
- Custo financeiro do projeto é zero ou a opção foi rejeitada.
- Hipóteses estão rotuladas e decisão pendente continua `TBD`.
- A decisão aceita corresponde ao aceite explícito do usuário.
- Consequências positivas e negativas estão presentes.
- Relações, documentos afetados e critério de revisão estão explícitos.
- A nota foi criada ou atualizada na base Obsidian e o caminho foi conferido.

## Molde de saída

```markdown
---
type: minerva-adr
project: Minerva
date: AAAA-MM-DD
status: proposta|aceita|rejeitada|substituída|obsoleta
tags:
  - minerva
  - adr
ai-first: true
---

# ADR NNN: <título>

## Para o futuro agente
<O que foi decidido ou permanece pendente e quando esta ADR importa.>

## Contexto
<Problema, origem e razão para decidir agora.>

## Escopo
- Afetado: <itens>
- Fora de escopo: <itens>

## Forças e critérios
- <critério verificável>

## Opções consideradas

### Opção 1: <nome>
- Status na análise: candidata|hipótese|descartada|escolhida
- Benefícios: <itens>
- Custos e limitações: <itens>
- Riscos: <itens>
- Trade-off: <o que se ganha e o que se aceita perder>
- Evidências: <fontes ou TBD>

### Opção 2: <nome>
- Status na análise: candidata|hipótese|descartada|escolhida
- Benefícios: <itens>
- Custos e limitações: <itens>
- Riscos: <itens>
- Trade-off: <o que se ganha e o que se aceita perder>
- Evidências: <fontes ou TBD>

## Decisão
<Opção aceita explicitamente ou TBD.>

## Consequências

### Positivas
- <consequência>

### Negativas
- <consequência>

### Riscos residuais
- <risco e tratamento>

## Impacto nos artefatos
- <PRD, HLD, FDD, task, código, operação ou nenhum>

## Relações
- Origem: <link ou identificação>
- ADRs relacionadas: <links ou nenhuma>
- Substitui: <ADR ou nenhuma>
- Substituída por: <ADR ou nenhuma>

## Critério para revisitar
<condição objetiva>

## Aceite
- Responsável arquitetural: Yoda
- Decisor: <usuário ou TBD>
- Evidência do aceite: <registro ou TBD>

## Histórico
- AAAA-MM-DD: ADR criada com status <status>.
```

## Entrega

Informar status, pendências, caminho completo da nota e artefatos que precisarão ser atualizados. Nunca afirmar que a ADR foi aceita ou salva sem conferir a evidência correspondente.
