# ADR-008 — Políticas de risco alteráveis sem deploy

- Data: 2026-10-17.
- Estado: **proposta**, sem DSL, biblioteca ou administração de políticas implementadas/adotadas.
- Origem: usuário solicitou avaliar compilador de regras no MO e explicar motivo/aplicação no README; RF03/RF04 e RNF01/RNF05/RNF06.
- Decisor: usuário; a avaliação não presume aceite de linguagem, novas regras financeiras ou nova dependência.

## Contexto e alternativas

Analista de risco configura limites/túnel no [PRD](../prds/PRD-001-simulador-prospero.md). Alterar valores não precisa exigir recompilar fórmulas estáveis. Hoje MO tem identidade e decimal técnico P05; não há risco/reservas/DSL. O [HLD-003](../hlds/HLD-003-middle-office.md) orienta estratégias puras específicas, sem engine genérica. Esta proposta avalia uma evolução dessa orientação, sem revogá-la ou antecipar RF04 antes de RF03.

| Alternativa | Uso e benefício | Custo/limite |
| --- | --- | --- |
| Parâmetros tipados/versionados + estratégias Java | Recomendação inicial se mudam limites/coeficientes; atualiza dados sem deploy, com menor superfície | Fórmulas novas continuam exigindo código; armazenamento/administração ainda serão implementados |
| CEL Java restrito | Alternativa existente com parsing, checagem e programa reutilizável | Comprovar aritmética financeira exata, funções/custos e semântica de erros antes de adotar; não usar double financeiro |
| DSL autoral tipada + VM de pilha | Permite condições/combinações próprias e estudo de compiladores | Projeto passa a manter gramática, tipos, otimização, verificador, VM e compatibilidade; justificável se necessidade concreta ou objetivo didático for aceito |

[CEL Java oficial](https://github.com/cel-expr/cel-java) separa compilação/checagem e avaliação; não significa geração de bytecode JVM ou atendimento automático ao domínio. Sua [especificação](https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md) define numéricos básicos int/uint/double, `%` como módulo inteiro e operadores lógicos com semântica de erros que não equivale necessariamente ao curto-circuito tradicional da esquerda para a direita. Portanto `2%` e o comportamento proposto abaixo exigem desenho/adaptação, não adoção direta por similaridade.

## Proposta de aplicação caso a DSL seja escolhida

Separar administração/publicação da avaliação por ordem. Compilar uma vez ao publicar versão; avaliar programa imutável sobre fatos permitidos a cada decisão. A VM proposta interpreta instruções próprias, não carrega classes/bytecode JVM. Sem reflexão, I/O, acesso JPA, relógio livre, aleatoriedade, loops, recursão, imports ou funções arbitrárias. Não aceitar bytecode enviado por cliente.

| Fase | Contrato proposto |
| --- | --- |
| Léxico | Tokens com posição/lexema. `2%` pode virar token percentual com valor racional/decimal exato `0.02`; normalizar aqui é escolha da linguagem, não obrigação do lexer. Nunca passar por double |
| Sintático | Descida recursiva/precedência: `ou` < `e` < comparação < soma < produto; unários/parênteses e associatividade explícitos. Comparações encadeadas serão recusadas salvo contrato próprio. Rejeitar entrada residual |
| Semântico | Catálogo finito de variáveis com tipos/unidades: booleano, dinheiro por moeda, quantidade por ativo, preço e razão. `e`/`ou` exigem booleanos; operações financeiras precisam combinações explicitamente permitidas |
| Otimização | Dobrar somente constantes puras/exatas dentro dos limites, preservando tipo, resultado, overflow e comportamento de erro/curto-circuito. Número de constantes dobradas é diagnóstico do compilador, não melhoria de desempenho medida |
| Geração/verificação | Instruções tipadas e saltos condicionais para `e`/`ou`, da esquerda para a direita. Verificar opcodes, operandos, saltos válidos sem ciclos e altura/tipos da pilha em todos os caminhos |
| Execução | Capturar uma versão completa por decisão; pilha/fatos limitados e orçamento determinístico de passos. Retornar decisão/motivo ou erro explícito; nunca aprovar por falha |

Exemplos **ilustrativos**, sem adotar parâmetros ou fonte de VaR no produto:

```text
bloquear se patrimonio e var_95
```

Deve falhar na análise semântica se ambas forem monetárias: dinheiro não é booleano. Uma comparação ilustrativa `var_95 > patrimonio * 2%` só é válida se as duas variáveis forem monetárias da mesma moeda e `dinheiro × razão` estiver definido. O parser produz `maior(var_95, multiplicar(patrimonio, razao(0.02)))`; multiplicação fica mais profunda pela precedência. VaR95 e patrimônio ainda não têm fonte/contrato no Próspero.

Para `A e B`, se A for falso, não avaliar B; para `A ou B`, se A for verdadeiro, não avaliar B. A otimização não pode antecipar erro de um ramo dispensado; diagnósticos estáticos de tipos e aritmética constante precisam de regra explícita antes da implementação. Dados obrigatórios e controles de reserva não podem ser contornados por uma expressão curta. Nulos/ausência/defasagem não viram zero. Precisão, escala, arredondamento, divisão por zero e overflow devem ser fechados nas RFs; `BigDecimal` ou inteiros escalados não resolvem unidades/semântica por si. [BigDecimal Java 26](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/math/BigDecimal.html).

## Publicação, consistência e recuperação

Autoria, validação/simulação, publicação e ativação são ações distintas, autenticadas/autorizadas e auditadas. Versões imutáveis incluem fonte, hash, versão da linguagem/compilador/catálogo e programa verificável. Artefato inválido não substitui o ativo. Limitar texto, tokens, profundidade AST, precisão, constantes, instruções, pilha, duração/memória de compilação e avaliações concorrentes antes de expor administração.

Ativação troca o conjunto inteiro atomicamente, com política de vigência definida. Cada operação registra versão aplicada; em múltiplas instâncias, referência em memória isolada não dá ativação global. A transação deve conferir a versão elegível ou registrar explicitamente qual versão capturada vale para a ordem. Rollback escolhe política para novas decisões; não reinterpreta reservas nem desfaz negócio. Preservar versões e semântica necessárias à recuperação.

**Decidir não é reservar.** Avaliar expressão não altera saldo, não publica evento e não substitui verificação atômica dos limites concorrentes. Actor coordena fatos locais consistentes, política e transação que protege disponível/reserva, deduplica, registra decisão/versionamento e outbox. Se fatos mudarem entre avaliação e commit, revalidar sob proteção adequada ou repetir conforme contrato; duas ordens aprovadas sobre snapshot não podem gastar o mesmo recurso.

Reentrega recupera decisão/efeito persistidos da mesma intenção; não cria outra reserva sob política atual. Ausência de política necessária, erro de avaliação e orçamento excedido impedem autorização ao matching, com estado/diagnóstico explícito e tratamento de recuperação a definir. Não liberar reserva por inferência. Logs de resultado/motivo/versão são limitados, sem saldos, posição, token ou expressão integral sensível.

## Validação, consequências e decisão pendente

Antes de adotar: corpus de regras válidas/inválidas; precedência/unários/parênteses; tipos/unidades/limites; curto-circuito com ramo que falharia; equivalência AST de referência versus VM versus otimizado; fuzzing do parser/verificador; overflow/divisão; troca de versão concorrente; reinício/replay e duas ordens concorrentes sem duplicar/estourar reserva. Testes financeiros via API/XLSX complementam invariantes internas conforme ADR-004.

Medir separadamente compilação, avaliação com fatos já carregados e transação completa, incluindo alocação/GC e caudas no orçamento real. **Microssegundos é hipótese, não resultado.** Compilar fora do caminho quente reduz trabalho repetido, mas não prova latência, nem dispensa custo de carregar fatos/persistir reserva.

Recomendação: começar pelo contrato de parâmetros e versões; realizar experimento restrito comparando expressão tipada existente versus DSL somente se combinações dinâmicas forem necessárias. Benefício esperado é mudar política controladamente sem deploy; custo é superfície administrativa, governança, compatibilidade e verificação adicional. Escolha e adoção seguem pendentes; nenhuma dependência/migração/endpoint foi criada.

Relacionados: [ADR-002](ADR-002-padroes-dominio.md), [ADR-004](ADR-004-testes-planilha.md), [HLD-003](../hlds/HLD-003-middle-office.md), [roadmap RF04](../roadmap.md#rf04--risco-reservas-e-saga). Revisitar quando as regras/fatos RF03/RF04 e a necessidade de expressões alteráveis estiverem definidos. Fontes de técnica: [lexer](https://llvm.org/docs/tutorial/MyFirstLanguageFrontend/LangImpl01.html) e [parser/AST](https://llvm.org/docs/tutorial/MyFirstLanguageFrontend/LangImpl02.html) do tutorial oficial LLVM; usados para conceitos, sem adotar LLVM no MO.
