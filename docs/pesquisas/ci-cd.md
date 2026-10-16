# Pesquisa — CI/CD local do Próspero

Pesquisa independente pelo agente `pesquisa_ci`, esforço medium, em 2026-10-16, usando a skill deep-research e fontes primárias. O pedido explícito foi verificar padrões de CI/CD; não houve entrevista redundante nem reestruturação fictícia de pesquisa fornecida.

## Conclusões aplicadas

1. Separar revisão, testes e segurança em workflows distintos, com nomes de checks únicos e falha explícita. A separação organiza responsabilidades; auditoria por agentes medium na sessão permanece um processo distinto. [Sintaxe de workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax).
2. Usar `pull_request` e negar forks em condição do job, antes de reservar runner. Fixar actions por commit, checkout sem credenciais e token somente leitura nos checks. [Uso seguro do Actions](https://docs.github.com/en/actions/reference/security/secure-use).
3. Comentar em job separado, sem checkout nem execução do código do PR. JavaScript constante, dados por ambiente, resultado validado por enum, marcador por workflow, autor do bot e checagem do SHA atual antes do upsert. [actions/github-script](https://github.com/actions/github-script).
4. Runner self-hosted persistente pode carregar comprometimento de um job a outro. Uma VM/daemon dedicados sem acesso ao Docker/HOME do Horus protegem a separação; mudar apenas usuário/grupo/projeto não oferece essa barreira. [Docker Engine security](https://docs.docker.com/engine/security/) e [OWASP: Poisoned Pipeline Execution](https://owasp.github.io/www-project-top-10-ci-cd-security-risks/CICD-SEC-04-Poisoned-Pipeline-Execution).
5. Serializar jobs pesados, builds e módulos, com orçamento de memória e preflight. Uma instância de runner executa um job por vez; isso não controla automaticamente o stack de desenvolvimento. [Concorrência do Actions](https://docs.github.com/en/actions/concepts/workflows-and-actions/concurrency).
6. `-p` isola recursos Compose, mas imagens explícitas e portas precisam de identidade própria. Usar tags por run/attempt, portas loopback dinâmicas e `ports: !override`; requer Compose2.24.4+. Descobrir portas por `docker compose port`. [Nome de projeto](https://docs.docker.com/compose/how-tos/project-name/) e [merge](https://docs.docker.com/reference/compose-file/merge/).
7. Limpeza com os mesmos arquivos/projeto da criação, em finalização e sinais, removendo apenas recursos da execução. Nunca usar prune global. [Compose down](https://docs.docker.com/reference/cli/docker/compose/down/).
8. Quarkus e Angular exigem comandos compatíveis com as versões adotadas; testes por módulo comprovam autonomia. [Quarkus testing](https://quarkus.io/version/3.40/guides/getting-started-testing/) e [Angular testing](https://angular.dev/guide/testing).

## Custo e limites de ativação

Self-hosted não cobra minutos de execução; artefatos/cache hospedados têm contabilização própria. Começar com logs e summaries, sem upload obrigatório de artefatos. [Cobrança Actions](https://docs.github.com/en/billing/concepts/product-billing/github-actions).

Repositório privado em GitHub Free não oferece proteção de branch como em Pro/Team/Enterprise. Não prometer enforcement antes de verificar o plano; sem ele, revisar os checks manualmente antes do merge, sem contratar upgrade. [Proteção de branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).

A pesquisa não executou workflows nem comprovou runner/VM ou orçamento operacional. As evidências locais e remotas serão registradas separadamente. Não foram ativadas chamadas a modelos ou APIs pagas.
