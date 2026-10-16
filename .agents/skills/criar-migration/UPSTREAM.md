# Skill: criar-migration

**Autor:** Cristóvão Augusto

**Definição canônica.** Adaptador de descoberta em `.claude/skills/criar-migration/SKILL.md`, sem conteúdo próprio.

## Para o futuro agente

Planejar e gerar uma migration SQL de ida e volta a partir de task e FDD aprovados, obedecendo à ADR de banco e à ferramenta de migration escolhida. O objetivo é alterar esquema ou dados com compatibilidade, validação, rollback testável e documentação por objeto, sem inventar dialeto.

## Responsabilidade e bloqueios

- Exercer a responsabilidade de **implementar**, normalmente como Severino.
- Não decidir banco, ORM, ferramenta de migration, convenção de arquivos ou dialeto SQL.
- Bloquear enquanto banco, versão, driver/ORM ou ferramenta necessária estiverem `TBD`.
- Exigir task e FDD aprovados. Exigir HLD e ADR quando a mudança afetar estrutura, persistência ou decisão técnica.
- Toda migration deve ter `UP` e `DOWN` capazes de restaurar esquema e dados ao estado anterior.
- Não apagar, truncar ou sobrescrever dado sem decisão explícita registrada. Mesmo com decisão, exigir cópia recuperável, validação e plano de restauração.
- Não executar a migration em ambiente compartilhado ou produção sem autorização e procedimento operacional correspondente.
- Cada tabela, índice, trigger ou função criada, alterada ou removida exige nota própria no Obsidian.

Se uma volta exata for tecnicamente impossível, a migration não é reversível e deve ser bloqueada. Propor estratégia reversível, como expandir e contrair em etapas, preservar coluna antiga, arquivar dados ou restaurar backup, e encaminhar qualquer trade-off estrutural a Yoda via ADR.

## Entradas obrigatórias

- Task identificada e pronta para implementação.
- FDD aprovado e critérios de aceite relacionados.
- ADR de banco aceita, com tecnologia e versão.
- Ferramenta de migration, ORM/driver e convenção de nomes decididos.
- Esquema atual e volume aproximado dos objetos afetados.
- Requisitos de compatibilidade entre versões da aplicação.

## Coleta

Ler primeiro os artefatos. Para dados ausentes, fazer **uma pergunta por vez**, aguardar a resposta e marcar desconhecido como `TBD`. Ao fim de cada etapa, resumir em 3 a 6 linhas e pedir confirmação.

1. Confirmar task, FDD, HLD, ADR de banco, dialeto, versão e ferramenta.
2. Inventariar objetos afetados: tabelas, colunas, constraints, índices, triggers, funções e dados.
3. Classificar cada mudança como aditiva, compatível, incompatível, transformação de dados ou destrutiva.
4. Definir compatibilidade durante rollout, incluindo versões antiga e nova da aplicação.
5. Definir estratégia de rollout: expandir, backfill idempotente e em lotes, alternar leituras/escritas, validar e só então contrair.
6. Avaliar transação, duração, locking, timeout, espaço, concorrência e janela operacional conforme capacidades reais do banco.
7. Definir rollback de esquema e dados, gatilhos objetivos e backup/restore quando aplicável.
8. Definir validações antes, durante e depois, incluindo contagens, constraints, amostras e invariantes de domínio.
9. Mapear notas Obsidian, uma por objeto DDL.

## Procedimento

1. Bloquear se qualquer decisão de tecnologia necessária estiver `TBD`.
2. Produzir o plano determinístico abaixo antes do SQL.
3. Gerar arquivos no formato exigido pela ferramenta decidida. Não criar convenção própria.
4. Usar apenas sintaxe confirmada para banco e versão escolhidos. Placeholders do molde não são SQL executável.
5. Tornar backfill reiniciável e idempotente, com progresso observável e sem `sleep` fixo.
6. Testar `UP`, validar estado, testar `DOWN` e comparar o estado restaurado em ambiente descartável.
7. Executar os testes da aplicação afetados e anexar evidências à entrega.
8. Criar ou atualizar `<BASE>/banco-de-dados/<tipo>-<nome>.md` para cada objeto DDL.

## Checagens de consistência

- Task, FDD e ADR de banco existem e estão aprovados.
- Banco, versão, dialeto, ORM/driver e ferramenta não estão `TBD`.
- Inventário cobre esquema e dados, inclusive dependências entre objetos.
- Rollout mantém compatibilidade entre versões coexistentes da aplicação.
- `UP` e `DOWN` restauram esquema e dados, não apenas compilam.
- Transformação de dados tem backfill idempotente, validável e reiniciável.
- Operações destrutivas têm decisão explícita, cópia recuperável e restauração testada.
- Transação e locking foram avaliados conforme a tecnologia real.
- Critérios de sucesso, falha e acionamento do rollback são objetivos.
- Backup foi restaurado em teste quando ele participa do rollback.
- Há uma nota Obsidian conferida por objeto DDL.

## Molde do plano

```markdown
# Migration: <identificador e título>

## Origem e decisões
- Task: <identificador>
- FDD aprovado: <identificador>
- HLD: <identificador ou não aplicável>
- ADR de banco: <identificador e status aceita>
- Banco e versão: <valor decidido>
- ORM/driver: <valor decidido ou não aplicável>
- Ferramenta de migration: <valor decidido>

## Inventário
| Objeto | Estado atual | Mudança | Classificação | Dependências | Nota Obsidian |
| --- | --- | --- | --- | --- | --- |
| <tipo e nome> | <estado> | <alteração> | aditiva|compatível|incompatível|dados|destrutiva | <itens> | <caminho> |

## Compatibilidade e rollout
1. <expansão compatível>
2. <backfill idempotente e em lotes>
3. <validação>
4. <alternância da aplicação>
5. <contração somente após condição objetiva>

## Transação, locking e capacidade
- Fronteira transacional: <descrição>
- Locks esperados e duração: <descrição medida ou TBD bloqueante>
- Timeout e concorrência: <descrição>
- Espaço e volume: <estimativa e fonte>

## Backup e recuperação
- Necessário: sim|não
- Motivo: <descrição>
- Local e retenção: <conforme decisão vigente>
- Evidência de restore: <referência ou pendente bloqueante>

## Validação

### Antes
- [ ] <checagem>

### Depois do UP
- [ ] <checagem de esquema, dados e invariantes>

### Depois do DOWN
- [ ] <comparação com o estado anterior>

## Critérios de rollback
- <sinal objetivo, limiar e responsável>

## Riscos
- <risco, mitigação e contingência>
```

## Moldes SQL

Substituir todos os placeholders somente depois de confirmar o dialeto. Incluir cabeçalho equivalente nos arquivos exigidos pela ferramenta escolhida.

```sql
-- MIGRATION: <identificador>
-- DIRECTION: UP
-- DATABASE: <produto e versão decididos>
-- SOURCE: <task, FDD e ADR>
-- PRECONDITIONS: <estado exigido>
-- TRANSACTION: <estratégia suportada pelo banco>

<SQL DE EXPANSÃO COMPATÍVEL>

<SQL OU CHAMADA DE BACKFILL IDEMPOTENTE, SE APLICÁVEL>

<VALIDAÇÕES EXECUTÁVEIS SEM ALTERAR DADOS>
```

```sql
-- MIGRATION: <identificador>
-- DIRECTION: DOWN
-- DATABASE: <produto e versão decididos>
-- SOURCE: <task, FDD e ADR>
-- PRECONDITIONS: <estado produzido pelo UP>
-- TRANSACTION: <estratégia suportada pelo banco>

<SQL QUE RESTAURA OS DADOS AO ESTADO ANTERIOR>

<SQL QUE RESTAURA O ESQUEMA AO ESTADO ANTERIOR>

<VALIDAÇÕES EXECUTÁVEIS DO ESTADO RESTAURADO>
```

## Entrega

Informar arquivos gerados, testes de `UP` e `DOWN`, evidências, riscos residuais e caminhos completos das notas por objeto. Se bloqueada, listar exatamente as decisões ou entradas ausentes sem gerar SQL especulativo.
