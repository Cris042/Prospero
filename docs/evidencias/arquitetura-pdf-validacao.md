# Validação da revisão documental, C4 e playbook

Data: 2026-10-17. Branch: `docs/aderencia-pdf-c4`. Escopo documental: comparação do PDF, ajustes no PRD/roadmap/HLD, diagramas e ampliação do playbook solicitada durante o trabalho; sem código ou configuração operacional alterados. Não há nova tarefa de implementação do roadmap: revisão e manutenção autorizadas diretamente pelo usuário, sem novo refinamento de RF.

## Plano proporcional e resultados do autor

- Extrair o PDF local, conferir páginas/figuras e registrar hash e referências: executado, 14 páginas físicas; figuras de arquitetura/MER/DER inspecionadas.
- Confrontar PRD, cinco HLDs, ADRs, roadmaps, Compose, configuração de telemetria e código técnico: executado, preservando atual/planejado.
- Renderizar quatro fontes PlantUML locais em SVG: executado com PlantUML 1.2025.2, layout Smetana e saída zero, sem renderizador externo.
- Verificar links locais, proveniência de skills, integridade SVG e diff: resultados independentes registrados abaixo após execução.

Dispensa de nova planilha: ADR-004, linha de documentação sem mudança funcional. Não foram executados testes Java/Angular, negociação, carga, stack Compose ou CI remota nesta revisão; não validariam mudanças exclusivamente documentais. Não houve nova decisão arquitetural: PRD/HLD existentes bastam e nenhum ADR novo é necessário.

## Pareceres independentes

Três agentes separados entre si e do autor, com esforço medium:

| Papel | Escopo e resultado | Limites |
| --- | --- | --- |
| Revisão | RF03/RF06, ADRs, C2/C3 e 8 estruturas/51 campos coerentes; sem bloqueios. Solicitou declarar recorte dos atores C1, corrigido no catálogo. Complemento aprovou divisão C2 e 60 tópicos do playbook. | Leitura estática; PDF pela extração textual, sem inspeção independente das figuras. |
| Testes | Verificador documental, diff sem erros, 18 requisitos e 51 campos/tipos preservados. Quatro fontes renderizadas independentemente, XML válido e bytes iguais aos SVG versionados; inspeção visual legível. | Mermaid revisado manualmente, ferramenta local indisponível; sem execução funcional. |
| Segurança | Quatro SVG sem scripts, eventos, links/includes externos ou conteúdo ativo. Comentários SRC correspondem às fontes. Sem segredo/padrão sensível no escopo e sem achados sustentados. | Auditoria estática documental; sem auditoria do JAR/renderizador ou histórico completo. |

O C2 inicial tinha sobreposição de rótulo com cabeçalho de observabilidade, identificada em inspeção visual. Foi substituído pelos recortes de fundação e telemetria; QA confirmou a correção. Os recortes representam os mesmos containers, sem duplicar implantação.

## Complemento solicitado: cinco playbooks e 60 tópicos

O usuário pediu um agente para analisar os cinco playbooks do Minerva e enriquecer o guia com foco em segurança e SQL. O agente `revisao`, em etapa própria de pesquisa somente em leitura, leu integralmente 1.579 linhas e confrontou as cópias locais com `git show` do commit `76a991d7296cddba5e595a07e9b3b6f8da581b78`. Confirmou igualdade byte a byte; não alterou arquivos. A edição coube ao autor, seguida de revisão independente do texto.

| Fonte em docs/playbooks/ do Minerva | Linhas | SHA-256 conferido pelo agente |
| --- | --- | --- |
| playbook-backend.md | 411 | `da9ad20062ce6b5907cf4fbd0cb9f00c56b8927a5db6824a8f3e128531e69d7f` |
| playbook-security.md | 237 | `9c838c04df5c292760d920eec0002a49a94a019073318f476faa857a87647838` |
| playbook-database.md | 216 | `8e15e95ac3d7c6abb6b0251beb21018c50a0e032ce9353ec4e925801da814771` |
| playbook-sistemas-redes.md | 665 | `6c8e161edb9b5a93f24892b0b0029c0a8043c779deb77381327e1a4735a218cf` |
| playbook-documentacao.md | 50 | `8a95dca341799297374e5ef5b114d891f5a31e16b55c527e77b3b9e64eae7af4` |

Os links fixados constam no [playbook](../playbook-desenvolvimento.md). Síntese autoral a partir dos tópicos do usuário: A01–A21, S01–S21 e D01–D18, cada um com gatilho, conduta e trade-off. A18 reforça A09 por referência, sem duplicar política de cache. Sistemas/redes e documentação preservam suas seções seletivas existentes. Fontes primárias PostgreSQL 16, Kafka 4.1 e OWASP foram consultadas para conferir limites técnicos; estão ligadas nas seções correspondentes.

Adaptações essenciais: proibição de LIKE/ILIKE em vez da receita do doador; evitar scans desnecessários sem forçar índice; não importar “preço sempre do banco” para a boleta, TTL automático de reserva, SKIP LOCKED universal, comandos para matar processos, réplicas ou cadeia FDD/LLD. A regra de SQL foi registrada em rules por instrução explícita do usuário. É convenção de desenvolvimento, sem mudança de stack/topologia ou necessidade de ADR arquitetural.

Casos conferidos na revisão: mesma chave com outra intenção conflita; transação Kafka não cobre PostgreSQL; autorização por recurso não depende da imprevisibilidade do ID; preço limite/gatilho é entrada legítima, saldo/execução são autoridade do servidor; LIMIT não garante poucas linhas examinadas; Seq Scan justificado não é defeito automático; consulta parametrizada não autoriza recurso por si só.


Pareceres complementares: revisão sem pendências nos 60 tópicos; QA confirmou programaticamente IDs únicos/contíguos e quatro colunas preenchidas (75 documentos, 377 links e 16 skills naquele momento); segurança sem achados nas orientações de SQL, autorização, CSRF, SSRF e valores financeiros. Após acrescentar links de proveniência/relato, o autor repetiu o verificador: 75 documentos, 378 links e 16 skills, sem erros, e diff limpo. Números de links variam com as referências acrescentadas; não representam execução funcional.
