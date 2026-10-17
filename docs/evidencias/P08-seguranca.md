# P08 — segurança independente

Data: 2026-10-17. Agente exclusivo de segurança, esforço medium, separado do implementador, QA e revisão. Aplicados adaptador local, upstream e checklist de `security-audit`. Base Git `c84aea222a17c28422e0050df9f477b487980470`; branch `task/p08-fluxo-documental`, candidato ainda sem commit. Parecer: **seguir no escopo examinado**, sem achados de segurança substanciados. Não autoriza merge.

## Escopo e ameaça

Foram examinados guia documental, duas skills novas e snapshots, adaptação refinar-task, regras/AGENTS/catálogos/inventário de hashes, script de verificação, playbook, PRD-001, cinco HLDs, mapa de aderência, ajustes do ADR-001/índice e links dos perfis C4. A migração remove os dois documentos anteriores por autorização do usuário e aponta a origem histórica ao commit acima; não altera a aplicação ou suas dependências.

Ativos: instruções dos agentes, integridade de requisitos/decisões e histórico, segredos locais e capacidade de execução/publicação. Entrada externa: conteúdo Minerva e suas instruções. Fronteira principal: dados de referência não podem adquirir autoridade para instalar, executar, publicar ou modificar permissões. Não há novo endpoint, parser de entrada financeira, renderizador, processo ou ferramenta externa nesta alteração.

## Fronteiras revisadas

- **Autoridade das fontes:** gerar-prd e gerar-hld substituem expressamente seus upstreams, que são snapshots fixados. Não exigem Obsidian, personas, continuidade, ferramentas, JSON ou confirmações repetidas. Não adotam latência/disponibilidade/free tier externos. Nova decisão de negócio volta ao usuário; gerar documentação não aprova aceite ou merge.
- **Proveniência:** ambos os snapshots são idênticos aos arquivos de referência disponíveis em `/tmp/prospero-minerva-docs`, com origem declarada no commit Minerva `76a991d7296cddba5e595a07e9b3b6f8da581b78`. `gerar-prd/UPSTREAM.md`: SHA-256 `28ed2fbfa5ec2e3fea7a8e244fca54df5d86e43d14c9e43c58e85a227c97ba41`; `gerar-hld/UPSTREAM.md`: `bccaeb89cb7998b42bcd6fb892b36927c504f8d6c43ae0129b39ac88423181ed`. O playbook seleciona conceitos de cinco fontes fixadas, sem promover seus textos integrais a instruções locais. Esta rodada não rebaixou arquivos remotos nem executou conteúdo do doador.
- **Dados e segurança financeira:** modelos de dados são explicitamente preliminares, sem schema aprovado, tabelas financeiras existentes ou FK entre serviços. HLDs preservam falta de autenticação/autorização de negócio, limites de entrada e proibição de logs sensíveis. Recuperação, idempotência, reservas concorrentes, publicação abortada no ME e participantes enviados ao BO continuam decisões/testes futuros. Não se apresenta infraestrutura configurada como SAGA/RBAC implementados.
- **Continuidade e decisões:** remoção dos dois documentos mantém referências históricas imutáveis no Git e mapa de migração. ADR-001 mantém contexto/alternativas/decisão, com troca do link de origem e nota documental; não há aprovação retroativa ou mudança financeira implícita. HMAC/mTLS/Argon2 permanecem alternativas sem adoção.
- **Playbook:** preserva SQL parametrizado, autorização por recurso, proteção contra mass assignment/SSRF, entradas limitadas, configurações privadas fora de Git/logs/memória e limpeza não destrutiva. Ressalta que `EXPLAIN ANALYZE` executa a instrução e não autoriza operar em terceiros. Nenhum comando passa a ser autorizado somente por constar no documento.
- **Verificador:** alteração remove exceção para link do documento excluído e substitui total fixo de 14 pelo confronto real entre diretórios/inventário, incluindo duplicatas. Continua verificando commit, arquivos/hashes e symlinks. Executa somente leitura local e validações; não avalia conteúdo como código nem acessa rede. O inventário é evidência de integridade do candidato, não assinatura criptográfica de confiança do fornecedor.

## Evidências executadas

`git diff --check`: exit 0. Comparação binária dos dois novos snapshots com referências locais: sucesso. Script de documentação revisado antes da execução.

Gitleaks **8.30.1**, configuração local com regras padrão e sem exclusões adicionais: scan `dir` sobre cópia temporária de **29 arquivos novos/alterados não ignorados**, criada a partir de `git diff --name-only` e `git ls-files --others --exclude-standard`. Arquivos removidos foram omitidos, symlinks e arquivo de nome `.env` recusados antes da cópia. Executado com `--redact --no-banner`: **178.964 bytes examinados, exit 0, nenhum segredo encontrado**. O diretório temporário foi removido automaticamente. Isso cobre o conteúdo daquele snapshot, não todo histórico ou arquivos adicionados depois.

Execução inicial de `python3 scripts/verificar-documentacao.py` apontou somente dois links para `ESTRUTURA.md`, ainda em elaboração naquele momento. O usuário depois definiu o índice no README, sem documento separado. Na verificação final os links foram atualizados e o comando passou: **71 documentos, 538 links locais e 16 skills com hashes conferidos**, exit 0. `git diff --check` final também passou.

O índice final do README foi examinado: lista pastas e arquivos mantidos com finalidade, sem conteúdo de `.env`, dados privados de memória, credenciais, backups ou caminhos privados. `.env.example` aparece como modelo público; o parágrafo final distingue arquivos locais/gerados de fontes mantidas. Não amplia permissões ou introduz execução. SHA-256 do README examinado: `5e17855f16103561762095abaabf29a81caf8fbb025e49030a2c4853bad24ef7`.

Gitleaks foi repetido somente para cobrir o conteúdo final acrescentado: mesmo método/configuração e redação, **32 arquivos novos/alterados, 245.805 bytes, nenhum segredo encontrado, exit 0**. O snapshot inclui o README final e relatórios disponíveis naquele momento, inclusive este relatório antes desta atualização de evidências. A atualização presente registra somente resultados e hashes, sem novas fontes privadas. Os números anteriores ficam como evidência da cobertura inicial, sem substituírem o scan final.

## Limites

A pendência transitória do índice foi resolvida e conferida no README; `ESTRUTURA.md` não foi criado. Não há achado de vulnerabilidade aguardando validação. Não foram repetidos scanners de dependências, builds Java/Angular, stack, testes financeiros ou stress, pois não houve mudança pertinente. Sem consulta de alertas GitHub ou scan do histórico completo nesta rodada. Fontes e instruções foram inspecionadas estaticamente; não se afirma imunidade a prompt injection ou segurança global. Mudanças posteriores exigem reavaliar o impacto e identificar a cobertura adicional.
