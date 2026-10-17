# Guia de uso de documentação no Próspero

## Objetivo e regra de entrada

Antes de criar ou alterar uma solução, revisar o sistema e os documentos relacionados. Usar documentação proporcional ao impacto, evitando duplicação e decisões sem contexto. Este guia integra as [regras](rules.md); o [playbook](playbook-desenvolvimento.md) ajuda a aplicá-lo seletivamente.

```text
Necessidade → PRD (requisito) → HLD (estrutura) → ADRs (decisões)
           → Implementação → Revisão de aderência
```

A sequência orienta planejamento; não exige três documentos novos por tarefa. Referenciar e atualizar documentos existentes quando bastar. ADRs podem preceder um HLD novo; preservar sua história e não simular aprovação retroativa.

## Responsabilidades e conteúdo mínimo

| Documento | Pergunta e fonte de verdade | Conteúdo mínimo pertinente |
| --- | --- | --- |
| PRD | O que construir e por quê? Produto e comportamento esperado. | Contexto, problema, objetivo, usuários/atores, RFs, RNFs relevantes, regras de negócio, aceite, exclusões, métricas de sucesso, dependências e riscos. |
| HLD | Como organizar a solução em alto nível? Arquitetura e fluxos. | Contexto/link ao PRD ou requisitos existentes, objetivo técnico, estado atual/proposto, componentes, dados, integrações, APIs/eventos, persistência, segurança, observabilidade, escala/resiliência, implantação/migração, riscos/trade-offs e ADRs. |
| ADR | Por que escolher esta decisão técnica? Contexto e alternativas. | Título/ID, status, contexto, decisão, alternativas, motivos, consequências positivas/negativas e referências ao PRD/HLD/ADRs pertinentes. |

PRD não escolhe classes, stack ou detalhes de implementação. HLD não repete narrativa de produto nem inventa decisões. ADR registra uma decisão específica, sem substituir a descrição de toda a arquitetura. Código é evidência da implementação real; não prevalece automaticamente sobre requisito ou decisão vigente.

PRDs novos ficam em `docs/prds/PRD-<ID>-<slug>.md`; HLDs em `docs/hlds/HLD-<ID>-<slug>.md`. Criar essas pastas somente com documentos necessários. ADRs mantêm caminhos e status do [índice vigente](adrs/README.md). Usar Proposto, Aceito, Depreciado ou Substituído conforme decisão real, preservando ligações de substituição.

## Quando criar ou atualizar

| Situação | PRD | HLD | ADR |
| --- | --- | --- | --- |
| Funcionalidade simples / nova regra de negócio | Criar ou atualizar | Se desenho precisar | Se houver decisão relevante |
| Funcionalidade complexa / múltiplos contextos | Criar ou atualizar | Criar ou atualizar | Conforme decisões |
| Novo serviço ou módulo relevante | Conforme impacto de produto | Sim | Conforme decisões |
| Nova integração externa / fluxo distribuído | Conforme comportamento | Sim | Conforme decisões |
| Mudança de arquitetura ou padrão transversal | Conforme impacto | Sim | Sim |
| Tecnologia relevante / decisão difícil de reverter | Se produto mudar | Conforme impacto | Sim |
| Correção pequena / refatoração local / dependência sem impacto estrutural | Geralmente dispensado | Geralmente dispensado | Geralmente dispensado |

Decisões sobre segurança, consistência, escalabilidade ou disponibilidade exigem avaliar ADR; nome de variável, pacote local ou alteração trivial de endpoint não justificam ADR por si só. Mudança relevante pode atualizar um PRD/HLD existente, sem criar outro documento.

Usar as skills [gerar-prd](../.agents/skills/gerar-prd/SKILL.md), [gerar-hld](../.agents/skills/gerar-hld/SKILL.md) e [gerar-adr](../.agents/skills/gerar-adr/SKILL.md). Perguntar apenas sobre lacunas bloqueantes; não repetir decisões já fornecidas nem importar metas genéricas do projeto de referência.

## Relações e fontes de verdade

Ligar necessidade/RF → PRD ou seção de requisitos existente → HLD pertinente → ADRs → tarefa/PR → código/testes/evidências. Manter referências de retorno quando úteis, sem copiar listas inteiras. Preservar IDs RF01–RF10 e RNF01–RNF08.

O [PRD-001](prds/PRD-001-simulador-prospero.md) e o [HLD-001](hlds/HLD-001-arquitetura-prospero.md), com quatro HLDs de serviço, substituem os documentos anteriores removidos na P08. O [mapa de migração](evidencias/P08-aderencia.md) mantém rastreabilidade; a origem histórica continua no Git. Atualizar a fonte correspondente sem criar uma cópia paralela.

## Revisão obrigatória antes de implementar

- [ ] Entendi o problema e revisei sistema, código/configuração e documentos relacionados?
- [ ] Existe PRD ou referência de requisitos? Revisei comportamento, regras, aceite e lacunas?
- [ ] Existe HLD ou desenho pertinente? Revisei componentes, contratos, dados e estado atual/proposto?
- [ ] Revisei os ADRs aplicáveis e verifiquei decisões obsoletas ou substituídas?
- [ ] Comparei documentação com implementação, distinguindo requisitos planejados dos implementados?
- [ ] A mudança contradiz decisão aceita ou exige novo ADR, PRD ou HLD? Registrei a análise e eventuais dispensas?

A revisão entra no refinamento de toda tarefa, feature, refatoração ou mudança arquitetural. Registrar resultado proporcional no refinamento/PR, incluindo ausência de divergência no escopo efetivamente examinado; não afirmar auditoria integral a partir de leitura parcial.

## Divergências e encaminhamento

| Marcador | Significado | Exemplo e ação |
| --- | --- | --- |
| `[DOC-GAP]` | Documento/informação necessário ausente | Fluxo transversal novo sem desenho: completar HLD antes da implementação dependente. |
| `[DOC-MISMATCH]` | Documento diverge de outro documento ou do estado observado | HLD descreve réplicas, Compose mostra bancos independentes: investigar origem e corrigir a descrição se desatualizada. |
| `[ADR-VIOLATION]` | Implementação contraria ADR vigente | Chamada síncrona entre MS contra ADR-001: corrigir código ou propor substituição explícita, nunca alterar silenciosamente a regra. |
| `[PRD-MISMATCH]` | Comportamento diverge do requisito aplicável | Cancelamento libera reserva duas vezes apesar do aceite: corrigir implementação ou revisar requisito com o usuário. |

Cada achado informa fonte/trecho, evidência observada, impacto, ação, responsável e estado. Decidir entre corrigir código, atualizar documento desatualizado, criar/substituir ADR, revisar PRD com usuário ou atualizar HLD de evolução aprovada. Bloqueios relevantes ao trabalho devem ser resolvidos antes de novas decisões dependentes. Pendências externas ao escopo podem ser encaminhadas com responsável, sem desaparecer do registro.

Ausência de RF ainda planejado não é automaticamente violação. Hipótese não é decisão; container iniciado não comprova fluxo financeiro nem meta de desempenho. Não resolver conflito assumindo que o código está correto.

## Outros documentos e simplicidade

A LLM pode sugerir ao usuário outro tipo de documento quando houver necessidade concreta não atendida: por exemplo, runbook de recuperação, RFC para alternativas ainda abertas, desenho de segurança ou detalhe local de protocolo. Explicar o problema, benefício, tamanho mínimo e por que atualizar um documento existente não basta. A sugestão não torna o artefato obrigatório nem autoriza ampliação de escopo. Preferir seção/link existente; FDD, LLD e documentação de IA não são etapas fixas.

## Checklist de conclusão

- [ ] Implementação alinhada ao PRD/requisitos, HLD/desenho e ADRs aplicáveis?
- [ ] Mudou produto? Atualizei o PRD ou referência vigente?
- [ ] Mudou arquitetura/fluxo? Atualizei o HLD ou desenho vigente?
- [ ] Nova decisão relevante? Registrei ADR e status real; marquei corretamente os substituídos?
- [ ] Comparei novamente documentos e código e encaminhei divergências com evidências?
- [ ] Links, fontes e estado planejado/implementado/comprovado permanecem coerentes?

Mudança relevante sem a atualização documental necessária permanece incompleta. Documentação orienta decisões futuras; a cadeia necessidade → requisito → arquitetura → decisão → código deve ser rastreável e simples de manter.
