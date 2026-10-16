# Skill: tdd-workflow

**Autor:** Cristóvão Augusto

**Definição canônica e independente de ferramenta.** Adaptador de descoberta em `.claude/skills/tdd-workflow/SKILL.md`, sem conteúdo próprio.

## Para o futuro agente

Implementar features, correções e refatorações por ciclos RED, GREEN e REFACTOR, com testes derivados de comportamento, invariantes de domínio e contratos. A ferramenta de unitário e integração é definida pela ADR da aplicação; todo endpoint exige teste de integração idempotente.

## Origem e licença

Adaptada da skill `tdd-workflow` do [mclovin137/TGM2](https://github.com/mclovin137/TGM2/tree/abc15b18bdc3e814c1dffa5e4e1eb99de7a7e292/.claude/skills/tdd-workflow), vendorizada por esse projeto a partir de [affaan-m/ECC](https://github.com/affaan-m/ECC/tree/main/skills/tdd-workflow), licença MIT. Snapshot de origem: `abc15b18bdc3e814c1dffa5e4e1eb99de7a7e292`.

## Entradas e bloqueios

- Task pronta, PRD e FDD aprovado.
- HLD e ADRs quando aplicáveis.
- Runner e convenções definidos pela stack vigente.
- Critérios de aceite, invariantes e contratos identificados.

Não implementar requisito ausente. Marcar `❓ LACUNA` e devolver ao dono do FDD. Não escolher framework de teste, linguagem, banco ou CI.

## Ciclo obrigatório

1. Ler a cadeia documental e listar comportamentos observáveis.
2. Mapear cada critério de aceite e invariante para um ou mais testes.
3. Para cada endpoint, mapear sucesso, validação, autorização, erro, borda, estado inválido e concorrência aplicável.
4. Escrever o menor teste que expõe o comportamento ainda ausente.
5. Executar e registrar o **RED** pela razão esperada. Falha de ambiente não prova RED.
6. Implementar o mínimo para alcançar **GREEN**, mantendo domínio independente de framework.
7. Executar o teste focal e a suíte afetada.
8. Refatorar sem mudar comportamento e executar novamente.
9. Repetir até cobrir a task.
10. Executar a suíte completa e os dois pipelines quando disponíveis.
11. Produzir relatório factual com comandos, resultados e evidências.

## Regras de teste

- Organizar testes pela linguagem do domínio, agregado e invariante.
- Testar comportamento público, não detalhes internos.
- Cada teste cria o próprio dado, usa identificador único e limpa o que criou.
- Não depender de ordem, dado fixo compartilhado ou `sleep` fixo; aguardar condição observável.
- Cobrir caminho feliz, falha, borda, autorização e concorrência relevante.
- Todo endpoint tem teste de integração idempotente, mesmo sem interface gráfica.
- Toda execução gera as evidências definidas pela ADR de testes e as publica como artefato no pipeline de casos de teste.
- Não usar percentual de linhas como substituto de cobertura de fluxos. Meta numérica só vale se PRD, FDD ou ADR a definiu.
- Não desabilitar, pular ou afrouxar teste para obter verde.

## Pirâmide condicionada ao risco

- **Domínio:** testes rápidos para invariantes e regras puras.
- **Aplicação:** testes de casos de uso, transações e portas.
- **Infraestrutura:** integração real com adaptadores relevantes em ambiente descartável.
- **Contrato:** compatibilidade nas fronteiras de contexto e integrações.
- **Endpoint:** teste de integração ponta a ponta no nível exigido pelo projeto.
- **Segurança:** casos negativos compartilhados com Neo.

## Relatório de evidência

```markdown
# Evidência TDD: <task>

| Ciclo | Comportamento | RED | GREEN | REFACTOR | Evidência |
| --- | --- | --- | --- | --- | --- |
| 1 | <regra ou fluxo> | <comando e falha esperada> | <comando e sucesso> | <mudança e regressão> | <artefato> |

## Cobertura por requisito
- <critério ou invariante>: <testes>

## Endpoints
- <endpoint ou contrato>: <fluxos de integração e evidências>

## Suíte final
- <comandos executados e resultados reais>

## Lacunas e riscos residuais
- <item ou nenhum>
```
