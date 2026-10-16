# ADR-004 — Quando criar testes por planilha

- Data: 2026-10-16
- Estado: aceita; implementação pendente
- Decisor: usuário
- Origem: decisão de criar runner `.xlsx` no projeto frontend, com todos os passos dos cenários via API.

## Contexto

O projeto precisa de cenários legíveis, reproduzíveis e independentes de preparação manual. Uma planilha não deve exigir acesso aos bancos para funcionar. Ao mesmo tempo, testes por HTTP não substituem verificações internas de invariantes, determinismo e falhas de infraestrutura.

## Elegibilidade

| Alteração | Obrigação |
| --- | --- |
| Novo comportamento de negócio exposto por API | Criar cenário de planilha com sucesso e rejeições relevantes. |
| Mudança de contrato, validação, resultado financeiro ou transição de estado | Criar ou atualizar os cenários afetados. |
| Fluxo entre serviços, compensação ou regressão reproduzível via API | Cobrir o resultado observável e a recuperação possível pela API. |
| Refatoração sem mudança observável | Executar os cenários existentes; não duplicar planilha. |
| Documentação, formatação ou atualização técnica sem mudança funcional | Validar o impacto; planilha nova não é obrigatória. |
| Invariante interna, arquitetura, carga ou falha não controlável via API | Usar teste interno/de infraestrutura adequado; complementar a planilha quando houver resultado de negócio observável. |

Todo endpoint de negócio terá teste por API. A planilha cobre comportamento, não exige um arquivo por endpoint: um cenário pode percorrer várias operações. Se a mudança exigir planilha, a tarefa só atende o aceite após executar e registrar o resultado. Dispensa deve citar a linha aplicável desta tabela no PR.

## Execução e independência

O runner será um módulo de testes dentro de `frontend/`, executável por linha de comando em container, sem navegador obrigatório e sem importação de Excel na interface nesta entrega. Não é um quinto microsserviço de negócio. Implementação e escolha da biblioteca XLSX pertencem à P03.

Cada workbook `.xlsx` deve declarar seus cenários e conter três abas:

- **Carga:** requisições de preparação de dados, com resultados capturáveis.
- **Passos:** ordem, identificador, método, caminho, headers e corpo; referências a valores capturados de passos anteriores.
- **Resultados:** status HTTP, headers e campos esperados, incluindo condições de conclusão assíncrona.

A P03 fixará e documentará colunas e sintaxe antes de criar o parser. A planilha precisa ser capaz de definir cenários sem alterações no código do runner. Configuração externa fornece somente ambiente, credenciais e limites operacionais, não dados de negócio ocultos.

Carga, execução, consultas e eventual limpeza usam exclusivamente APIs. Proibidos SQL, consultas a Redis ou publicação direta no Kafka pelo runner. Não usar endpoints de teste que burlem invariantes ou um endpoint genérico para executar código/SQL. APIs locais de preparação, se necessárias, devem ter finalidade explícita, validação, documentação e isolamento do perfil de teste.

Cada cenário cria seus dados e pode executar sozinho, em ordem diferente e novamente. Usar identificadores únicos por execução; não depender de IDs fixos nem do estado deixado por outro workbook. A estratégia para dados iniciais, formadores de mercado e controle de tempo deve ser definida antes dos cenários que a exigirem.

Em fluxos assíncronos, consultar uma API de acompanhamento até uma condição esperada, com intervalo e prazo máximo explícitos. Timeout ou resultado divergente falha o cenário. Relatar cenário, passo, esperado, observado e identificadores de correlação, sem credenciais; o processo termina com código diferente de zero se houver falha.

## Alternativas e trade-offs

CSV seria mais fácil de revisar no Git, mas foi descartado em favor das abas de Excel. Executar apenas no navegador dificultaria automação; o runner CLI permite execução local repetível. A planilha é binária e exige biblioteca de leitura: incluir resumo legível dos cenários e evidências no PR.

Testes unitários e de propriedades continuam permitidos e necessários para o núcleo financeiro. ArchUnit, Testcontainers e testes de carga complementam os cenários HTTP. Interromper containers em testes de resiliência é responsabilidade do harness de infraestrutura, não uma instrução escondida na planilha.

## Validação e revisão

Na P03, demonstrar carga por API, encadeamento de IDs, asserções de status/corpo/headers, falha diagnosticável e repetição sem preparação manual. O runner deve rejeitar arquivos inválidos antes de iniciar seus efeitos e não executar macros ou fórmulas como código. Rever quando o formato deixar de expressar os cenários sem alterações recorrentes no runner.

Relacionados: [ADR-003](ADR-003-contratos-api.md), [roadmap](../roadmap.md).
