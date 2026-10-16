# Skill: deep-research

**Autor:** Cristóvão Augusto

**Definição canônica.** Adaptador de descoberta em `.claude/skills/deep-research/SKILL.md`, sem conteúdo próprio.

## Finalidade e responsabilidade

Conduzir uma Deep Research em duas fases sequenciais:

1. **Entrevista de contexto:** entender tema, contexto e expectativa e gerar um briefing estruturado. Esta fase não produz pesquisa técnica.
2. **Reestruturação documental:** reorganizar integralmente a pesquisa já importada no contexto em um *Deep Research Document* de 16 seções. Esta fase não pesquisa fontes novas, não faz grounding e não reduz o material recebido.

Pertence à responsabilidade de **planejar/revisar**. Antes de iniciá-la, ler `docs/rules.md`, `docs/continuidade.md`, `docs/lib.md` e documentos relacionados. Toda saída é em pt-BR. Ao criar ou alterar skill ou task, seguir `docs/skills/atualizar-obsidian.md`.

O *Deep Research Document* final pode servir de base para gerar outros documentos quando o usuário solicitar expressamente. Essa possibilidade não autoriza criar documentos derivados automaticamente, nem altera as regras de preservação integral da fase 2.

## Fase 1: entrevista de contexto

1. Enviar exatamente a mensagem inicial definida neste documento.
2. Fazer uma pergunta por mensagem, aguardar a resposta e evitar múltipla escolha.
3. Depois de cada resposta, resumir o entendimento em uma ou duas frases e pedir confirmação antes de avançar. A confirmação não conta como pergunta de descoberta.
4. Adaptar a próxima pergunta ao que já foi confirmado; não repetir assunto esclarecido.
5. Fazer no máximo seis perguntas de descoberta, incluindo a pergunta inicial.
6. Cobrir apenas o necessário entre tema técnico, motivação ou problema, foco principal, contexto de aplicação, profundidade, tecnologias relevantes, casos reais ou exemplos e resultado esperado.
7. Se a entrevista encerrar antes, preencher campos não informados com `não informado`; não supor dados, tecnologias, métricas, decisões ou fontes.
8. Ao terminar, entregar somente o briefing obrigatório e a frase final obrigatória. Não iniciar a pesquisa técnica.

### Formato obrigatório do briefing

**Resumo Preparatório para Deep Research**

**Tema técnico:** [descrição do tema]

**Motivação / Problema a resolver:** [descrição breve]

**Foco principal:** [por exemplo: performance, segurança, escalabilidade, governança ou custo]

**Contexto de aplicação:** [onde e como o tema será usado]

**Nível de profundidade desejado:** [conceitual / prática / equilibrada]

**Tecnologias ou stacks relevantes:** [tecnologias mencionadas, se houver]

**Desejo de incluir casos reais ou exemplos:** [sim / não]

**Resultado esperado:** [resultado esperado da pesquisa]

Deseja realizar a Deep Research agora? Ative essa opção na sua ferramenta de IA.

### Mensagem inicial obrigatória

Olá.

Vamos conversar brevemente para que eu entenda o que você gostaria de pesquisar.

No final, vou gerar um resumo com tudo o que será necessário para que sua ferramenta de IA possa produzir a Deep Research completa.

Qual é o tema técnico ou tecnologia que você deseja investigar?

## Fase 2: reestruturação integral da pesquisa importada

### Pré-condição

Executar apenas quando o conteúdo completo estiver disponível no contexto atual, por texto, documento importado, PDF ou outro anexo legível. O usuário não precisa colá-lo novamente. Sem acesso ao conteúdo, informar a ausência e pedir que ele seja disponibilizado; não inventar, pesquisar externamente ou preencher lacunas com conhecimento próprio.

### Regras de execução

1. Ler todo o material antes de redigir a entrega.
2. Preservar 100% das informações, frases, dados, explicações, exemplos e referências. Nunca resumir, condensar, omitir, descartar ou substituir conteúdo.
3. Distribuir o material nas 16 seções abaixo, sem alterar ordem ou nomes. Melhorias de clareza, coesão e formatação só são permitidas se não reduzirem conteúdo; quando houver risco de perda textual, manter também o trecho original.
4. Trecho sem encaixe claro entra na seção mais relacionada com a marca `(conteúdo contextual adicional)`.
5. Não fazer grounding, não acrescentar fontes externas e não referenciar o anexo como fonte do próprio texto.
6. Conservar todas as fontes existentes em `## 16. Referências e Leituras Recomendadas`.
7. Não paginar ou usar rótulos como `Página 1`.
8. Entregar apenas o Markdown final completo, sem explicações, prefácios ou comentários externos ao documento.
9. Para seção sem conteúdo direto, manter o título e escrever: `Não havia conteúdo importado diretamente relacionado a esta seção.`

### Entrega em partes por limite de contexto

Quando o volume impedir uma entrega confiável de uma vez, informar antes da primeira parte o intervalo de seções. Depois de cada intervalo, perguntar se o usuário deseja continuar. A última parte deve completar as 16 seções e cada parte preserva integralmente o conteúdo que lhe couber.

### Modelo obrigatório do Deep Research Document

# Deep Research Document

**Título:** [Tema técnico da pesquisa]

**Versão:** 1.0

**Data:** [Data atual]

**Responsável:** [Autor / IA / Time técnico]

## 1. Contexto e Motivação

Descreve o problema técnico, a oportunidade ou a motivação que originou a pesquisa, incluindo relevância e impacto.

## 2. Fundamentos e Conceitos-Chave

Apresenta princípios, teorias, modelos ou terminologias essenciais para compreender o tema.

## 3. Panorama e Abordagens Existentes

Analisa as abordagens predominantes, soluções conhecidas, padrões ou frameworks relacionados ao problema.

## 4. Arquiteturas e Modelos de Aplicação

Explica como o tema é aplicado em nível arquitetural, descrevendo topologias, camadas e fluxos de dados típicos.

## 5. Estratégias, Algoritmos e Mecanismos

Lista as principais estratégias, algoritmos e mecanismos que endereçam o problema, destacando diferenças e trade-offs.

## 6. Tecnologias, Frameworks e Ferramentas

Apresenta tecnologias, bibliotecas, protocolos ou frameworks relevantes, com comparação de maturidade e aplicabilidade.

## 7. Boas Práticas e Diretrizes Técnicas

Inclui recomendações de implementação, anti-patterns, práticas de governança e lições de engenharia.

## 8. Métricas e Critérios de Avaliação

Define como mensurar o sucesso técnico ou operacional, listando métricas e métodos de avaliação.

## 9. Casos de Uso e Aplicações Reais

Mostra exemplos de aplicação prática, estudos de caso ou referências de uso do tema no mercado.

## 10. Riscos, Desafios e Limitações

Descreve riscos, limitações técnicas, pontos de falha conhecidos e possíveis estratégias de mitigação.

## 11. Considerações de Segurança, Confiabilidade e Governança

Trata de aspectos de segurança, conformidade, privacidade, confiabilidade e versionamento.

## 12. Tendências e Evolução Futura

Apresenta tendências, inovações ou pesquisas emergentes relacionadas ao tema.

## 13. Impactos e Relações com o Ecossistema

Explica como o tema interage com outros sistemas, times, processos ou camadas de arquitetura.

## 14. Decisões e Oportunidades Técnicas (ADRs Candidatos)

Lista possíveis decisões técnicas ou arquiteturais derivadas da pesquisa, com critérios de escolha e alternativas rejeitadas.

## 15. Próximos Passos e Aplicação Prática

Propõe como aplicar os aprendizados em produtos, sistemas, pipelines ou processos futuros.

## 16. Referências e Leituras Recomendadas

Apresenta todas as fontes utilizadas: artigos, papers, RFCs, whitepapers, documentações e materiais técnicos.
