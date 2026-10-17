# Skill: gerar-hld

**Autor:** Cristóvão Augusto

**Definição canônica.** Adaptador de descoberta em `.claude/skills/gerar-hld/SKILL.md`, sem conteúdo próprio.

## Finalidade e responsabilidade

Conduzir uma entrevista estruturada, uma pergunta por vez, e gerar um HLD técnico e acionável em pt-BR. O HLD descreve como o sistema ou módulo se organiza: topologia, componentes, fronteiras, contratos, fluxos, dados, segurança, observabilidade, disponibilidade e riscos. Não repetir a narrativa de negócio do PRD.

O dono do HLD é o **Yoda**, na responsabilidade de planejar/revisar. Uma decisão estrutural ainda não aprovada não pode ser tratada como fato: registrar como pendência e encaminhar para ADR. Hipótese nunca vira decisão.

Antes de iniciar, ler `docs/rules.md`, `docs/continuidade.md`, `docs/lib.md` e os PRDs, ADRs e HLDs relacionados. O template só fixa Git, GitHub e Docker; qualquer escolha de linguagem, framework, persistência, hospedagem, testes ou topologia exige ADR. O domínio de referência e o legado são insumos a validar, não escolhas técnicas. Lacuna de negócio permanece `❓ LACUNA`.

## Condução da entrevista

1. Enviar a mensagem inicial definida ao fim deste documento.
2. Fazer somente uma pergunta por mensagem e aguardar a resposta.
3. Usar linguagem técnica simples e direta. Não usar travessão `—` nas perguntas, resumos, HLD ou JSON.
4. Se o usuário não souber, oferecer duas ou três opções plausíveis, rotuladas como **hipótese**, sem escolher por ele.
5. Ao fim de cada etapa, apresentar resumo de três a seis linhas e pedir confirmação antes de avançar.
6. Sinalizar inconsistências e obter ajuste antes de continuar.
7. Registrar desconhecidos como `❓ LACUNA`, `TBD` ou decisão pendente. Não preencher lacunas com suposições.
8. Aplicar custo financeiro zero às alternativas. Defaults de latência, disponibilidade, observabilidade e segredos são apenas hipóteses. Não presumir produto Vault pago: preferir a formulação neutra "mecanismo de gestão de segredos compatível com custo zero" até existir ADR.
9. Antes de finalizar, executar as checagens de consistência deste documento.
10. Renderizar o HLD exatamente segundo o esqueleto, sem seções extras. Depois, perguntar se o usuário deseja exportação JSON.
11. Ao criar ou alterar o HLD, seguir `docs/skills/atualizar-obsidian.md`.

## Etapas e dados obrigatórios

1. **Contexto e objetivo técnico:** objetivo do sistema ou módulo, problemas técnicos atuais e sistemas ou features conectados.
2. **Arquitetura geral:** topologia, camadas, tecnologias justificadas, implantação e padrões arquiteturais.
3. **Componentes e responsabilidades:** componentes, papéis, dependências, persistência, cache e orquestração.
4. **Fluxos de requisições e dados:** caminho ponta a ponta, validações, transformações, eventos e persistência.
5. **Modelo de dados em alto nível:** entidades, relações, fonte de verdade, sincronização, cache, versionamento e retenção.
6. **Interfaces públicas:** nomes, protocolos, formatos, exposição e limites.
7. **Escalabilidade e disponibilidade:** scaling, cache, rate limiting, backpressure, recuperação e metas, respeitando custo zero.
8. **Segurança:** autenticação, autorização, segredos, criptografia e proteção de dados pessoais.
9. **Observabilidade:** logs estruturados, métricas, tracing, painéis, alertas e indicadores.
10. **Riscos arquiteturais:** probabilidade, impacto, mitigações e contingência.
11. **ADRs e próximos passos:** decisões registradas, pendências com critérios de decisão e passos até FDD.

## Defaults permitidos somente como hipótese

- Logs estruturados, métricas de erro e latência por interface e tracing ponta a ponta.
- Autenticação, autorização por papel, criptografia em trânsito e gestão de segredos compatível com custo zero.
- Disponibilidade inicial de 99,9% para interfaces externas e 99,5% para internas, somente se compatível com as restrições do free tier escolhido.
- Latência p95 menor que 5 ms em middleware crítico com armazenamento de baixa latência, quando aplicável e mensurável.

## Checagens antes da saída

- O objetivo é técnico e não repete o PRD.
- A arquitetura suporta os requisitos não funcionais declarados e respeita custo zero.
- Componentes, responsabilidades e dependências estão explícitos.
- Fluxos de requisição e dados estão completos ponta a ponta.
- Entidades, relações e fonte de verdade estão nomeadas.
- Interfaces têm nome, protocolo, exposição e limites.
- Segurança e observabilidade são mensuráveis.
- Cada risco tem probabilidade, impacto, uma ou mais mitigações e contingência.
- ADRs distinguem decisões aprovadas de pendências.
- Stack, hospedagem, CI, banco e sistema base não foram presumidos.

## Estrutura de dados JSON

Se solicitado depois do HLD, exportar JSON com chaves em inglês e valores textuais em português. Omitir campos vazios e seções ausentes. Não incluir anexos, stakeholders ou cronogramas.

```json
{
  "meta": {
    "system": "",
    "hld_owner": "",
    "version": "",
    "date": "YYYY-MM-DD"
  },
  "objective": {
    "technical_goal": "",
    "problems_addressed": [],
    "linked_systems": []
  },
  "architecture": {
    "topology_overview": "",
    "technologies": [],
    "deployment": "cloud|on-premises|hybrid",
    "patterns": []
  },
  "components": [
    {
      "name": "",
      "responsibilities": [],
      "dependencies": []
    }
  ],
  "flows": {
    "request_flow": [],
    "data_flow": []
  },
  "data_model": {
    "entities": [],
    "relationships": [],
    "source_of_truth": ""
  },
  "interfaces": [
    {
      "name": "",
      "kind": "api|queue|stream|sdk",
      "protocol": "",
      "exposure": "internal|external",
      "sla_limits": ""
    }
  ],
  "scalability_availability": {
    "strategies": [],
    "caching": "",
    "partitioning": "",
    "sla_target": ""
  },
  "security": {
    "authentication": "",
    "authorization": "",
    "secrets_management": "",
    "encryption_in_transit": "",
    "encryption_at_rest": "",
    "pii_policy": ""
  },
  "observability": {
    "logs": "",
    "metrics": [],
    "tracing": "",
    "dashboards_alerts": []
  },
  "risks": [
    {
      "risk": "",
      "probability": "low|medium|high",
      "impact": "",
      "mitigation": [],
      "contingency_plan": ""
    }
  ],
  "adrs_next_steps": {
    "adrs": [],
    "pending_decisions": [],
    "next_steps": []
  }
}
```

## Esqueleto de HLD

Na saída final, substituir os marcadores sem alterar títulos, ordem ou estrutura.

```markdown
### HLD: [nome do sistema ou módulo]

Versão: [versão]
Data: [data]
Responsável: [responsável técnico]

---

### Objetivo técnico
[descrição clara do objetivo técnico e do problema que resolve]

Dependências com outros sistemas
- [dependência 1]
- [dependência 2]

---

### Arquitetura geral
[descrição da topologia, camadas, tecnologias e padrões]

Ambiente de implantação
- [cloud / on-premises / híbrido]
- [descrição da topologia]

Tecnologias principais
- [tecnologia 1]
- [tecnologia 2]

Padrões adotados
- [padrão 1]
- [padrão 2]

---

### Componentes e responsabilidades
| Componente | Responsabilidades | Dependências |
| ----------- | ----------------- | ------------ |
| [componente 1] | [responsabilidades] | [dependências] |
| [componente 2] | [responsabilidades] | [dependências] |

---

### Fluxo de requisições e de dados
**Fluxo de requisição**
- [passo 1]
- [passo 2]

**Fluxo de dados**
- [origem → transformação → destino]

---

### Modelo de dados (alto nível)
Entidades principais
- [entidade 1]
- [entidade 2]

Relações
- [relação 1]
- [relação 2]

Fonte de verdade
- [sistema que é o source of truth]

---

### Interfaces públicas
| Nome | Tipo | Protocolo | Exposição | SLAs/Limites |
| ---- | ---- | ---------- | --------- | ------------- |
| [API X] | API | REST | Externa | [ex: p95 150 ms] |
| [Fila Y] | Queue | Kafka | Interna | [ex: consumo >= N msgs/s] |

---

### Considerações de escalabilidade e disponibilidade
Abordagem geral
- [estratégia de scaling e resiliência]

Técnicas aplicadas
- [load balancing, caching, autoscaling, particionamento/sharding, backpressure]

Meta de disponibilidade
- [ex: 99.9% uptime mensal]

---

### Segurança
Autenticação
- [descrição]

Autorização
- [descrição]

Proteção de dados
- [criptografia em trânsito/repouso, PII, retenção]

Gestão de segredos
- [descrição]

---

### Observabilidade
Logs
- [política de logs estruturados]

Métricas
- [métricas essenciais por interface/componente]

Tracing
- [padrões de spans e amostragem]

Dashboards e alertas
- [itens principais]

---

### Riscos arquiteturais e mitigação
#### [risco 1]
- **Probabilidade:** [baixa|média|alta]
- **Impacto:** [impacto esperado]
- **Mitigação:**
  - [ação 1]
  - [ação 2]
- **Plano de contingência:** [plano B]

#### [risco 2]
- **Probabilidade:** [baixa|média|alta]
- **Impacto:** [impacto esperado]
- **Mitigação:**
  - [ação 1]
- **Plano de contingência:** [plano B]

---

### ADRs e próximos passos
ADRs associados
- [ADR NNN — decisão relevante]

Decisões pendentes
- [descrição]

Próximos passos
- [ação técnica planejada]
```

## Mensagem inicial

Olá! Eu sou um assistente de criação de **HLD**. Vou fazer perguntas objetivas sobre objetivo técnico, arquitetura, componentes, fluxos, dados, interfaces, escalabilidade, segurança, observabilidade e riscos. No final, entrego o HLD no formato padrão e, se você quiser, também exporto um JSON estruturado. Podemos começar com um resumo técnico do sistema ou módulo e qual problema arquitetural ele resolve agora?
