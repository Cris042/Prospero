# Repository Guidelines

## Orientações e continuidade

Leia [docs/rules.md](docs/rules.md), [roadmap](docs/roadmap.md), [ADRs](docs/adrs/README.md) e o estado real do Git. Responda e documente em pt-BR. Consulte ai-memory no escopo do projeto e confronte as lembranças com os arquivos atuais. Não recrie `continuidade.md` nem declare testes ou PRs sem evidência.

## Estrutura e arquitetura

`front-office/`, `middle-office/`, `matching-engine/` e `back-office/` são projetos Maven independentes com Quarkus e Java 26. Cada serviço possui PostgreSQL próprio. `frontend/` contém Angular, testes de componentes e E2E; `infra/` configura observabilidade e memória; `scripts/` reúne operação e verificações. O runner XLSX será implementado na P03.

Organize o backend por funcionalidade: REST → Actor, domínio e Helpers puros, com repositórios JPA separados. Siga DDD, SAGA e Object Calisthenics conforme os ADRs. Use DTOs, `/api/v1`, OpenAPI e códigos HTTP apropriados; não exponha entidades. Valores financeiros não usam `double`.

## Desenvolvimento e validação

Na raiz, `python3 scripts/preparar-ambiente.py` gera configuração local; `docker compose up -d --wait` inicia imagens já construídas. Consulte o README para build completo. Em cada serviço, `./mvnw verify` exige JDK 26 e executa JUnit, REST Assured e ArchUnit. Em `frontend/`, use `npm ci`, `npm test -- --watch=false`, `npm run build` e `npm run format:check`. Testes E2E usam Playwright. Ainda não há meta de cobertura aprovada.

Siga a formatação existente: Java com quatro espaços; TypeScript/HTML/CSS com Prettier. Classes Java usam PascalCase, métodos camelCase; arquivos Angular seguem os nomes existentes. Preserve Helpers sem infraestrutura. Consulte ADR-004 para decidir quando criar planilhas via API.

## Fluxo de contribuição

Invoque `refinar-task` antes de cada nova tarefa do roadmap, com memória e pareceres independentes. Revisão, testes e segurança rodam em três agentes separados do implementador, com esforço **medium**. Use o catálogo em `.agents/skills/README.md`; para operação, use `subir-sistema`. Diagramas C4 usam o perfil em `docs/agentes/`, com esforço medium, fontes explícitas e propostas em leitura.

O histórico ainda está em formação; use commits curtos que descrevam a mudança. Cada tarefa exige branch e PR com alterações, motivos, trade-offs, evidências e imagens quando houver mudança visual. Não faça merge ou release automaticamente. Atualize documentação e memória conforme o impacto; nunca versione `.env` ou credenciais.
