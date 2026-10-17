# P07 — segurança independente

Data: 2026-10-17. Agente exclusivo de segurança, esforço medium, separado do implementador, revisão e QA. Aplicados `security-audit` local e checklist; consulta de configuração apoiada por OpenAI Docs. Parecer: **seguir**, sem achados de segurança substanciados no escopo, com limites de aplicação do papel registrados abaixo. Não autoriza merge.

## Escopo e fronteiras

Base `a694cd5b0eb3ceec8390f0075c4212be360efc80`, branch `task/p07-agente-c4`, candidato ainda sem commit. Lidos `docs/agentes/c4-diagram-generator.md`, catálogo de agentes, `.codex/config.toml`, `.codex/agents/c4-diagram-generator.toml` e alterações em AGENTS, regras e README. Nenhum desses arquivos foi alterado por este agente.

Ativos: fontes e segredos locais, integridade da arquitetura e configurações pessoais, capacidade de executar ferramentas e acesso a provedores. Entrada não confiável: documentos, comentários, memória e instruções embutidas nas fontes. Saída autorizada: proposta textual ao orquestrador, sem gravação ou execução pelo gerador.

- O perfil manda tratar arquivos externos, comentários e memória como dados, sem obedecer comandos embutidos. Exige lacunas explícitas e separação de planejado/implementado, evitando transformar conteúdo recuperado em decisão arquitetural.
- Perfil e role proíbem escrita, execução de comandos do projeto, instalação/renderização, busca externa, leitura de segredos e uso de provedores adicionais. Fontes pertinentes são explícitas; `.env`, tokens, configurações pessoais e backups estão excluídos. Não existem URLs de ferramentas, hooks, comandos de instalação ou configurações de autenticação novas.
- A proposta PlantUML deve ser autocontida, sem includes remotos ou macros indefinidas. O agente não renderiza nem busca includes. Se uma etapa futura renderizar texto recebido, deverá revisar diretivas e acesso a arquivos/rede antes da execução; esta entrega não implementa esse sink.
- Não há `model`, `model_provider`, definição de provider ou endpoint nos dois TOMLs. O papel declara somente esforço `medium`, `sandbox_mode = "read-only"` e instruções. Portanto o candidato não seleciona fornecedor/modelo adicional. Essa constatação estática não comprova qual configuração foi carregada numa sessão.
- O perfil declara corretamente que read-only não garante isolamento de leitura/rede e que limites comportamentais não são bloqueios técnicos universais. O catálogo condiciona descoberta ao suporte/confiança e oferece delegação portátil quando papéis nomeados não estiverem disponíveis. Um papel continua recebendo capacidades do runtime; não deve receber fontes privadas desnecessárias.
- Proveniência aponta commit fixado Minerva `76a991d7296cddba5e595a07e9b3b6f8da581b78`; adaptação retira modelo fixo, persona e FDD obrigatório. Não instala PlantUML nem importa executáveis.

## Evidência executada

`git diff --check`: exit 0. Python `tomllib` leu ambos os TOMLs; asserções confirmaram ausência de seleção de modelo/provider, esforço medium e sandbox declarados e existência do arquivo de papel relativo à `.codex/config.toml`. Isso comprova sintaxe TOML e intenção do candidato, não aceitação pelo parser Codex.

SHA-256 dos arquivos examinados:

| Arquivo | SHA-256 |
| --- | --- |
| `.codex/config.toml` | `7ce0155fed8a2d56c6a174f804b50808cc72009fc87e5b0e0edc84fc84a1d75c` |
| `.codex/agents/c4-diagram-generator.toml` | `ae74a491752871b96439c920882530042364ee9a6ad085d057ef48fab66d4120` |
| `docs/agentes/c4-diagram-generator.md` | `3344754a0fd7a90250dfaf88ca922ce98f13ade0845e7f26058fa4b81f5372a0` |
| `docs/agentes/README.md` | `2e08e4b677ecbf1ac1d1e0645326bd1413eb5a980de3e8ae8735f53da924c5c5` |

Inspeção manual do conteúdo/diff não identificou credenciais ou dados privados novos. Não houve mudança de dependências, imagem, build ou endpoint; scanners de dependências não foram repetidos. Nenhum renderizador, instalação ou comando das fontes auditadas foi executado.

## Validação necessária e limites

A [documentação oficial de subagentes](https://learn.chatgpt.com/docs/agent-configuration/subagents), consultada em 2026-10-17, descreve formato standalone e permissões herdadas do pai. O candidato usa camada `agents.<nome>.config_file`. QA comunicou, via orquestrador, validação dos TOMLs contra schema JSON e carregamento pelo Codex CLI 0.163.0: `app-server --strict-config`, `initialize` e `config/read` com cwd do repositório retornaram `agents.c4_diagram_generator.config_file` absoluto esperado. Essa evidência de versão/parser resolve a dúvida de compatibilidade da declaração para o CLI instalado; não comprova spawn especializado nem aplicação de sandbox próprio. Não foi demonstrado bypass ou vazamento.

Na hora desta revisão o relatório `P07-testes.md` ainda não existia; a evidência de carregamento acima foi recebida do orquestrador e não reexecutada por segurança. A sessão atual não expõe `agent_type`; não se presume execução especializada por nome ou aplicação técnica do sandbox do papel. O exercício comportamental cabe à prova independente de QA. Não se exige provocar acesso real a segredos para testar recusas: usar canários sintéticos sem dados privados.

Sem auditoria de configurações pessoais, hooks/MCP preexistentes, isolamento do SO, scanners de segredos/histórico ou alertas GitHub nesta rodada. As proibições do papel reduzem risco comportamental, mas não estabelecem uma fronteira técnica contra leitura ou rede no runtime atual. Não há garantia de imunidade a prompt injection ou de segurança global. Mudanças posteriores exigem reavaliar o impacto.
