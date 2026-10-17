# Agentes do Próspero

| Perfil | Uso | Configuração |
| --- | --- | --- |
| [c4-diagram-generator](c4-diagram-generator.md) | Propostas C4 baseadas em fontes, sem escrita ou decisões novas | [.codex/config.toml](../../.codex/config.toml) e [papel](../../.codex/agents/c4-diagram-generator.toml) |

A definição Markdown é canônica e portátil. O adaptador Codex declara esforço medium e sandbox read-only, herdando modelo/provider; não modifica configurações pessoais. A descoberta de `.codex/config.toml` depende da confiança no projeto e do suporte da versão instalada. Não confundir arquivo configurado com agente já carregado na sessão atual.

Se o runtime não expuser papéis nomeados, delegar a um agente separado medium, fornecendo o caminho do perfil e fontes mínimas. Exemplo de tarefa: “Leia docs/agentes/c4-diagram-generator.md; proponha C2 do estado implementado usando PROJETO.md, ADR-001 e docker-compose.yml; devolva texto, fontes e lacunas, sem gravar.”

O orquestrador escreve as propostas revisadas. Revisão, testes e segurança mantêm agentes próprios conforme [regras](../rules.md). O perfil não substitui PRD/HLD/ADR nem autoriza implementação, merge ou renderização. Evidência de configuração e exercício ficam em [P07-testes](../evidencias/P07-testes.md).

Compatibilidade verificada: Codex CLI 0.163.0, configuração reconhecida por `app-server --strict-config` e `config/read`. Esta API da sessão não expõe `agent_type`: o exercício usou delegação com o perfil canônico. A leitura da configuração não comprova spawn especializado nem aplicação de sandbox por papel. Consulte as evidências antes de atualizar a versão/formato.
