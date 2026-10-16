# Memória entre sessões

## Instalação e estado real

ai-memory **3.1.0** foi instalado no usuário local a partir do release oficial Linux x86_64, com SHA-256 `10b2411ff6988e2a275e17734170a617341a51eec8162ba3166bdd4284cde651` conferido. O bundle de hooks do mesmo release acompanha o binário. Não há provedor de LLM/API paga configurado; busca e páginas funcionam localmente. O servidor pode usar embeddings locais gratuitos.

O servidor roda no Compose independente `prospero-memoria`, imagem fixada por versão/digest, porta `127.0.0.1:49374`, dados em `~/.local/share/prospero-ai-memory` fora do Git. Não é dependência dos quatro microsserviços e não será iniciado pela CI. O marcador `.ai-memory.toml` fixa o escopo `prospero/Prospero` e a estratégia por raiz do repositório.

O setup oficial configurou MCP, hooks e seis skills gerenciadas de memória no usuário. O modo de captura **allowlist** só captura projetos com marcador, preservando a opção de não capturar outros repositórios. Não versionar tokens, arquivos de configuração pessoal ou backups de memória.

## Ação necessária no Codex

O instalador informou que os sete hooks ainda precisam de confiança no Codex. Abra `/hooks`, confira os hooks ai-memory e confie neles; reinicie a sessão para carregar hooks/MCP. Não automatizar essa aprovação em nome do usuário. A instalação/configuração não prova captura automática antes dessa etapa.

Para iniciar a CLI Codex com o contexto e autenticação da memória carregados, use na raiz `bash scripts/memoria.sh run codex`. A consulta e gravação por CLI já funcionam nesta sessão, independentemente da recarga do MCP. O token permanece em arquivo privado; não copie seu valor para README ou terminal compartilhado.

## Operação

```bash
docker compose -f infra/memoria/compose.yml up -d
docker compose -f infra/memoria/compose.yml ps
bash scripts/memoria.sh search 'fundação' --workspace prospero --project Prospero
bash scripts/memoria.sh read-page --path working/fundacao-e-governanca.md --workspace prospero --project Prospero
```

`PROSPERO_MEMORY_BIN` e `PROSPERO_MEMORY_DIR` permitem outro caminho local. Para outra máquina, instalar o release e o bundle de hooks pelo [procedimento oficial](https://github.com/akitaonrails/ai-memory/blob/584ab792a2ac506445ca27629f040311ad8dc0d2/INSTALL_FOR_AGENTS.md), inicializar os dados antes de subir o Compose e executar o setup com `--agent codex --no-service`. Para UID/GID diferentes de1000, definir `PROSPERO_UID`/`PROSPERO_GID` no Compose da memória.

Use `ai-memory backup --help` e `restore --help` para arquivar/restaurar no diretório privado do usuário; backups podem conter credenciais e não pertencem ao repositório. Restauração deve ser validada em diretório/serviço separado, sem substituir a memória ativa.

## Uso no fluxo

Ao iniciar tarefa, `refinar-task` consulta a memória e confronta lembranças com rules/ADRs/roadmap. Ao encerrar, salvar progresso, decisões operacionais, tentativas, evidências e próximo passo. `docs/continuidade.md` foi removido por decisão do usuário; não recriá-lo. O roadmap registra status e links dos PRs; o ai-memory guarda contexto e handoffs.

A [evidência independente](evidencias/P04-testes.md) confirma consulta, reinício e recuperação de backup em serviço isolado. Não afirmar backup, restauração ou captura automática como verificados sem execução real.
