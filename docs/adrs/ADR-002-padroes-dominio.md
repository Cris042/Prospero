# ADR-002 — Padrões de domínio e coordenação distribuída

- Data: 2026-10-16
- Estado: aceita; implementação pendente
- Decisor: usuário
- Origem: requisitos RNF01–RNF06 e decisões de DDD, SAGA e Object Calisthenics.

## Contexto

Reservas, execução e contabilidade pertencem a serviços diferentes. Uma transação de banco não abrange esse fluxo. O núcleo financeiro precisa ser verificável sem infraestrutura, preservando precisão, ordenação e determinismo.

## Decisão

Aplicar DDD nos contextos definidos no [ADR-001](ADR-001-arquitetura-backend.md). Objetos de valor representam dinheiro, quantidade e preço sem `double`; agregados protegem invariantes locais. Actors coordenam casos de uso, mas as regras permanecem no domínio. Antes de implementar cálculos, definir escalas, arredondamento e overflow.

A SAGA será orquestrada pelo front office, com estado e próximos passos persistidos, correlação por ordem e tratamento explícito de timeout. Reservas podem ser liberadas ou ajustadas somente após conhecer o resultado efetivo da operação; timeout sozinho não prova ausência de execução. Depois de negócio efetivado, recuperar para frente sem apagar ou desfazer a execução original.

Usar outbox para efeitos que exigem persistir estado e garantir posterior publicação. Consumidores deduplicam eventos e persistem os efeitos locais atomicamente. Não prometer entrega global exatamente uma vez: reentregas e falhas são esperadas e os efeitos devem ser idempotentes.

O matching processa sequencialmente cada ativo, com prioridade preço-tempo, e produz o mesmo resultado para a mesma sequência. Tempo, IDs e aleatoriedade precisam de origem controlada. Snapshots, offsets e recuperação após publicação abortada serão definidos antes da entrega RF05; uma transação Kafka não reverte automaticamente o livro em memória.

### Object Calisthenics

Aplicar estas nove orientações ao código autoral, com exceções explícitas:

1. Um nível de indentação por método.
2. Evitar `else`, preferindo retornos antecipados e responsabilidades menores.
3. Encapsular primitivos e strings com significado de negócio em objetos de valor.
4. Encapsular coleções de domínio em tipos próprios.
5. Evitar cadeias de navegação entre objetos; encapsular o comportamento no responsável.
6. Não abreviar nomes de domínio.
7. Manter classes e métodos pequenos e coesos; revisão avalia responsabilidade, sem limite numérico arbitrário.
8. Limitar classes a duas variáveis de instância.
9. Preferir comportamento a getters/setters que exponham estado interno.

DTOs e entidades JPA ficam isentos dos itens 8 e 9 quando exigidos por representação e persistência. Acessores de records usados como DTOs são permitidos. Primitivos de protocolo, anotações, código gerado e contratos de bibliotecas não precisam de wrappers artificiais. Outras exceções, inclusive dependências injetadas em Actors, exigem justificativa no PR; a restrição não deve produzir contêineres de dependências apenas para esconder campos.

## Alternativas e trade-offs

Transação distribuída e banco compartilhado enfraqueceriam a autonomia; não serão usados. Coreografia pura reduziria o papel central do FO, mas dificultaria visualizar o estado do fluxo para estudo. SAGA orquestrada favorece explicação e investigação, com custo de máquina de estados, compensações e consistência eventual.

Objetos de valor e Object Calisthenics aumentam tipos e mapeamentos, em troca de invariantes explícitas. Factory, Strategy e outros padrões só entram quando houver necessidade demonstrável; não criar abstrações decorativas.

## Validação e revisão

RF04, RF05 e RF08 devem demonstrar conservação de recursos, deduplicação e recuperação. RF05 inclui propriedades e replay; RF10 inclui concorrência entre cancelamento e execução. Testes unitários não dependem de HTTP ou banco. Revisar o ADR quando uma invariante exigir mudança de fronteira ou uma exceção recorrente mostrar que a regra precisa de ajuste.

Relacionados: [roadmap](../roadmap.md), [ADR-004](ADR-004-testes-planilha.md).
