# Relatório — Laboratório de Experimentação de Software (Lab 01)

**Título:** Características de repositórios populares do GitHub + Setup do Kanban
**Disciplina:** Laboratório de Experimentação de Software 
**Integrante:** Gabriel Cardoso ([@Cardosoooo](https://github.com/Cardosoooo)) Guilherme Martini 
**Repositório:** https://github.com/Cardosoooo/lab-experimentacao-software

---

## 1. Introdução

Repositórios populares no GitHub costumam ser tratados como referência de boa prática de engenharia de software, mas essa fama raramente é checada com dados. Este laboratório investiga, nos **1.000 repositórios com maior número de estrelas** do GitHub, se características básicas — idade, contribuição externa, frequência de releases e atualizações, linguagem e gestão de issues — realmente sustentam a imagem de projeto "maduro e bem cuidado". Em paralelo, o próprio processo do laboratório foi conduzido em um quadro Kanban (GitHub Projects v2), usado como base de dados das sprints e para avaliar o fluxo de trabalho do grupo.

As **hipóteses informais** levantadas antes da coleta são apresentadas junto de cada RQ na seção Discussão. Resumidamente, esperávamos que os repositórios mais populares fossem majoritariamente maduros, ativamente contribuídos, com releases e atualizações frequentes, concentrados em poucas linguagens do topo do ranking e com alta proporção de issues fechadas — isto é, confirmando a "fama de bem cuidado" que a popularidade sugere.

Além das 7 RQs do enunciado, o grupo propôs como inovação: uma nova Questão de Pesquisa com métrica adicional (**RQ08**, engajamento × estrelas) e uma mudança de arquitetura da coleta (**cache local**, frente do enunciado). Ambas são detalhadas na Seção 3 e discutidas nas seções de Resultados e Conclusão.

---

## 2. Metodologia de Coleta

* **Tamanho da Amostra:** 1.000 repositórios com maior número de estrelas no GitHub.
* **Mecanismo de Extração:** API GraphQL do GitHub (v4), com requisições paginadas em lotes (*batch size* de 10), via consulta `search(query: "stars:>1 sort:stars-desc", type: REPOSITORY)`. A paginação usa cursor (`endCursor`), lote a lote, até acumular os 1.000 repositórios.
* **Implementação:** script próprio `MineradorGitHub.java` em Java puro (sem biblioteca de terceiros para consulta à API — requisito do enunciado), com parser JSON mínimo próprio (`GitHubGraphQL.java`), retry com *backoff* exponencial em erros de gateway (502/503/504) e testes automatizados sem framework (`Testes.java`).
* **Campos coletados por RQ:**

| RQ | Métrica | Campo(s) |
|---|---|---|
| RQ01 | Idade do repositório | `createdAt` |
| RQ02 | Total de PRs aceitas | `pullRequests(states: MERGED).totalCount` |
| RQ03 | Total de releases | `releases.totalCount` |
| RQ04 | Tempo até a última atualização | `updatedAt` |
| RQ05 | Linguagem primária | `primaryLanguage.name` |
| RQ06 | % de issues fechadas | `issues.totalCount` e `issues(states: CLOSED).totalCount` |
| RQ07 | RQ02/RQ03/RQ04 agrupadas por linguagem | derivado na análise |
| RQ08 | Engajamento por estrela | `stargazerCount` |

* **Data de referência:** todas as linhas gravam `collectedAt` (fixado no início da execução). Idade (RQ01) e tempo desde a última atualização (RQ04) são calculados a partir dessa data, não da data em que a análise roda — garantindo reprodutibilidade.
* **Fonte de "linguagens mais populares" (RQ05/RQ07):** GitHub **Octoverse 2025**, ranking por número de contribuidores. Consideradas populares: TypeScript, Python, JavaScript, Java, C#, PHP, Shell, C++, HCL e Go. Referência fixa para todo o laboratório.
* **Dataset gerado:** `data/repositorios.csv` (1.000 repositórios, uma linha por repositório) e `data/repositorios_raw.json` (resposta bruta, para auditoria).

---

## 3. Inovações Propostas pelo Grupo (item 3.6 — 30% da nota)

*O enunciado corresponde a 70% da exigência da disciplina; os outros 30% vêm das contribuições originais abaixo, devidamente identificadas e com resultado discutido.*

### 3.1 Frente (a)+(b): nova RQ e métrica adicional — RQ08 (estrelas × engajamento)

**RQ08 —** Dentro dos 1.000 repositórios mais populares, quem tem mais estrelas recebe contribuição proporcionalmente maior, ou o engajamento cresce mais devagar que a popularidade?

* **Métrica adicionada:** `stargazerCount` (o número de estrelas nunca era guardado — antes só servia para ordenar o ranking). A análise calcula **PRs aceitas por mil estrelas** e **releases por mil estrelas**, comparados entre **quartis de estrelas** dentro da própria amostra.
* **Por que relevante:** verificar se a popularidade (estrelas) se traduz em contribuição real de forma proporcional, ou se estrela funciona mais como "reconhecimento passivo".
* **Onde aparece:** dataset (campo novo), `data/analise/rq08_estrelas_engajamento.csv`, seções Resultados/Discussão e Conclusão.

### 3.2 Frente (c): mudança de arquitetura/ferramenta de coleta — cache local

**O que foi feito:** `MineradorGitHub.java` agora salva cada página da busca (≈100 requisições GraphQL para os 1.000 repositórios) em um arquivo local `data/cache/<página>.json`, imediatamente após recebê-la.

* **Retomada de coleta interrompida:** como cada página é persistida na hora, se a execução cair no meio (502/503/504, *rate limit*, queda de rede), na próxima execução **só o que falta é buscado**, em vez de reiniciar os ≈100 requests do zero.
* **Reuso sem re-consultar:** ao rodar `java MineradorGitHub` (sem flag), as páginas já cacheadas são lidas do disco — re-rodar a análise não gasta *rate limit* nem tempo de rede.
* **Flag `--refresh`:** `java MineradorGitHub --refresh` consulta a API do zero e reescreve o cache. Necessária quando se quer **dados realmente novos**, pois o cache é *staleness-by-design*.
* **Resultado mensurável:** cada execução grava `data/analise/cache_stats.csv` (modo, páginas servidas do cache vs. da rede, % de hit, requests economizados e tempo de rede poupado, estimado por latência média contra-factual).
* **Por que relevante:** robustez e reprodutibilidade da coleta — um experimento não deveria quebrar (nem custar rate limit) por causa de uma falha de rede no meio da mineração.
* **Onde aparece:** `MineradorGitHub.java`, `data/cache/`, `data/analise/cache_stats.csv`, seções Resultados/Discussão e Conclusão.

> **Série real de execuções (`data/analise/cache_stats.csv`, acumulada por execução):**
>
> | Execução | Modo | Páginas | Cache | Rede | % hit | Requests economizados |
> |---|---|---|---|---|---|---|
> | 18:22 (população) | refresh | 100 | 0 | 100 | 0% | 0 |
> | 18:41 (reuso) | cache | 100 | 100 | 0 | 100% | 100 |
>
> Na primeira execução (`--refresh`), o cache estava vazio: as 100 páginas vieram da rede (0% hit) e ficaram gravadas em `data/cache/`. Na execução seguinte (`java MineradorGitHub`, sem flag), **todas as 100 páginas foram servidas do cache** — **100% de hit** e **100 chamadas à API economizadas**, sem gastar *rate limit* nem tempo de rede. Isso materializa, com números medidos, os três efeitos do cache: retomada, reuso e economia de chamadas.
>
> *Nota sobre o campo `estimatedTimeSavedSec`: ficou 0,0 nas linhas porque a estimativa usa a latência média das chamadas de rede **da própria execução**; nas execuções 100% em cache não há chamada de rede que sirva de base. A métrica efetiva de economia é o nº de **requests economizados** (100 na linha de reuso).*

**Ameaça à validade do cache (controle):** o cache é *staleness-by-design* — reutilizá-lo mantém os dados da última coleta real. O `--refresh` é o mecanismo de controle para revalidar quando se quer dados novos (ex.: antes da correção). Como os `endCursor` do GitHub são estáveis dentro do resultado ordenado por estrelas, a retomada é consistente; se o ranking mudar entre execuções, uma re-coleta com `--refresh` resolve.

---

## 4. Resultados por RQ

### 4.0 Tabela Resumo das Estatísticas Descritivas (N = 1.000)

| Métrica Estatística | RQ01: Idade (anos) | RQ02: PRs Aceitas | RQ03: Releases | RQ04: Dias desde atualização |
| :--- | :---: | :---: | :---: | :---: |
| **Mediana** | **7,72** | **773,5** | **41** | **0** |
| Mínimo | 0,04 (14 dias) | 0 | 0 | 0 |
| 1º Quartil (Q1) | 3,46 (1.265 dias) | 175 | 0 | 0 |
| 3º Quartil (Q3) | 11,34 (4.143 dias) | 3.439 | 150 | 0 |
| Máximo | 18,38 (6.712 dias) | 103.737 | 1.000 | 3 |

> Conversões de dias para anos: idade em anos = dias / 365,25.

---

### 4.1 RQ 01: Sistemas populares são maduros/antigos?

**Hipótese informal:** Sim — a maioria deve ter vários anos de existência; um projeto raramente fica popular da noite para o dia, leva tempo para acumular estrelas.

**Metodologia e definição da métrica:** idade do repositório a partir de `createdAt`, em relação a `collectedAt`.

**Resultados (N = 1.000):**
* **Mediana: 7,72 anos** (≈2.818,5 dias); Q1 ≈ 3,46 anos; Q3 ≈ 11,34 anos.
* Intervalo: de 14 dias (repositório mais novo) a 6.712 dias (≈18,4 anos).

**Discussão (hipótese vs. resultado):** a hipótese foi **confirmada**. A mediana de quase 8 anos mostra que o topo do GitHub é dominado por projetos veteranos. Há uma cauda inferior de projetos recentes (menos de 1 ano), tipicamente impulsionados por picos de interesse (ex.: ferramentas de IA generativa), mas ela não desloca a concentração em projetos maduros.

---

### 4.2 RQ 02: Sistemas populares recebem muita contribuição externa?

**Hipótese informal:** Sim — número alto de PRs aceitas; mais visibilidade tende a atrair mais gente disposta a contribuir.

**Metodologia e definição da métrica:** total de pull requests aceitas (`states: MERGED`).

**Resultados (N = 1.000):**
* **Mediana: 773,5 PRs aceitas;** Q1 = 175; Q3 = 3.439.
* Intervalo: de 0 a **103.737** PRs (máximo).

**Discussão (hipótese vs. resultado):** a hipótese foi **confirmada**. A mediana de ~774 PRs comprova alta abertura comunitária na maioria dos projetos populares. Assim como observado na amostra de 100 (referência `Respostas.md`), valores 0 ou baixos aparecem principalmente em casos de **governança fora do GitHub** (ex.: fluxo por mailing lists / Gerrit em projetos como Linux e Go), em que o repositório no GitHub funciona como *mirror* — e não significam ausência de contribuição.

---

### 4.3 RQ 03: Sistemas populares lançam releases com frequência?

**Hipótese informal:** Sim — volume razoável de releases; projeto popular tende a ter entrega ativa, não fica estagnado.

**Metodologia e definição da métrica:** total de releases (`releases.totalCount`).

**Resultados (N = 1.000):**
* **Mediana: 41 releases;** Q1 = 0; Q3 = 150; máximo = 1.000.
* Uma parcela relevante dos repositórios tem **0 releases**.

**Discussão (hipótese vs. resultado):** a hipótese foi **parcialmente confirmada**, com comportamento bimodal:
1. **Repositórios de conteúdo/listas** (manuais, "*awesome*", coleções de recursos) apresentam 0 releases: atualizam o conteúdo direto no branch principal, sem mecanismo de versionamento formal.
2. **Bibliotecas, ferramentas e frameworks** usam ativamente o mecanismo de releases, com dezenas a centenas de lançamentos — ciclos contínuos de entrega de versão.

---

### 4.4 RQ 04: Sistemas populares são atualizados com frequência?

**Hipótese informal:** Sim — tempo curto desde o último update; repositório abandonado tende a perder relevância e cair no ranking.

**Metodologia e definição da métrica:** tempo (em dias) de `updatedAt` até `collectedAt`.

**Resultados (N = 1.000):**
* **Mediana: 0 dias** desde a última atualização; máximo de 3 dias em toda a amostra.

**Discussão (hipótese vs. resultado):** a hipótese foi **fortemente confirmada**. Todos os 1.000 repositórios foram atualizados em até 3 dias da coleta (maioria no mesmo dia). Popularidade e atualização frequente andam juntas — é praticamente impossível se manter no topo de estrelas estando desatualizado.

---

### 4.5 RQ 05: Sistemas populares são escritos nas linguagens mais populares?

**Hipótese informal:** Sim — a maior parte deve estar concentrada em poucas linguagens do topo do ranking (Octoverse 2025).

**Metodologia e definição da métrica:** linguagem primária (`primaryLanguage.name`) de cada repositório, comparada ao conjunto do Octoverse 2025 (TypeScript, Python, JavaScript, Java, C#, PHP, Shell, C++, HCL, Go).

**Resultados (N = 1.000):**
* **70,00%** dos repositórios usam uma linguagem considerada popular.
* Ranking das linguagens mais presentes: **Python 228**, TypeScript 171, JavaScript 109, Go 77, C++ 42, Java 41, Shell 20, C# 8, PHP 4. (88 repositórios sem linguagem identificada, ex.: listas/Markdown.)

**Discussão (hipótese vs. resultado):** a hipótese foi **confirmada**. Mais de 2/3 da amostra está concentrado em linguagens do topo do ranking global, com Python, TypeScript e JavaScript dominando — coerente com a preferência da comunidade e com a forte presença de projetos de web/IA. O restante (≈30%) usa linguagens fora do "topo" (ex.: Rust, Ruby, Kotlin, C), que ainda assim podem ser muito relevantes em nichos.

---

### 4.6 RQ 06: Sistemas populares possuem um alto percentual de issues fechadas?

**Hipótese informal:** Sim — proporção alta de issues fechadas, com ressalva: pode refletir manutenção ativa real ou apenas *bots* de stale issue.

**Metodologia e definição da métrica:** razão `issues fechadas / total de issues` por repositório (repositórios sem issues excluídos).

**Resultados (N = 1.000; 956 com issues):**
* **% de issues fechadas — mediana: 87,51%.**
* 44 repositórios não possuem issues (excluídos do cálculo).

**Discussão (hipótese vs. resultado):** a hipótese foi **confirmada**. A mediana de ~88% de issues fechadas indica boa capacidade de triagem encerrando demandas. A ressalva da hipótese permanece como **limitação**: uma parte desse fechamento pode vir de automação (*stale bots*) e não representar resolução real — uma direção a aprofundar em trabalhos futuros.

---

### 4.7 RQ 07: Linguagens populares recebem mais contribuição, releases e atualizações?

**Hipótese informal:** Sim — linguagens no topo do ranking tendem a ter médias maiores nas três métricas (RQ02+RQ03+RQ04), pois comunidade maior leva a mais contribuições e mais infraestrutura de CI/CD.

**Metodologia e definição da métrica:** PRs aceitas, releases e dias desde a última atualização, **agrupados por linguagem** (`data/analise/por_linguagem.csv`).

**Resultados (seleção, valores medianos por linguagem):**

| Linguagem | Popular | N | PRs (mediana) | Releases (mediana) |
| :--- | :---: | :---: | :---: | :---: |
| Python | sim | 228 | 535,5 | 21 |
| TypeScript | sim | 171 | 1.980 | 138 |
| JavaScript | sim | 109 | 617 | 39 |
| Go | sim | 77 | 1.961 | 142 |
| C++ | sim | 42 | 1.200,5 | 59 |
| Java | sim | 41 | 948 | 55 |
| PHP | sim | 4 | 10.687 | 580,5 |
| Rust | não | 58 | 2.399 | 97 |
| Ruby | não | 13 | 6.288 | 28 |

* Atualizações: mediana **0 dias** em praticamente todas as linguagens — todos os repositórios populares, independentemente da linguagem, estão sendo atualizados.

**Discussão (hipótese vs. resultado):** a hipótese foi **parcialmente confirmada**. Linguagens populares como TypeScript e Go realmente apresentam altas medianas de PRs e releases (comunidade grande + muitos releases). Contudo, linguagens **não** classificadas como populares também alcançam valores altos (ex.: Rust e Ruby com PRs medianas acima de 2.000 e 6.000). Ou seja: **linguagem popular não é condição para muito engajamento**; a relação existe, mas não é determinística, e a variância dentro de cada linguagem é grande.

---

### 4.8 RQ 08 (inovação): quem tem mais estrelas recebe contribuição proporcionalmente maior?

**Hipótese informal:** Não — a relação não é proporcional. Repositórios hiper-populares (mais estrelas) devem ter uma taxa de PRs/releases por estrela **menor**, porque dar estrela é um clique sem custo, enquanto contribuir exige trabalho real.

**Resultados (N = 1.000, por quartil de estrelas):**

| Quartil | N | Estrelas (mediana) | PRs / 1.000 estrelas (mediana) | Releases / 1.000 estrelas (mediana) |
| :---: | :---: | :---: | :---: | :---: |
| Q1 (menos estrelas) | 251 | 35.693 | **14,12** | 1,15 |
| Q2 | 250 | 43.282,5 | **18,73** | 1,19 |
| Q3 | 250 | 58.775,5 | **15,01** | 0,75 |
| Q4 (mais estrelas) | 249 | 101.005 | **8,35** | 0,25 |

**Discussão (hipótese vs. resultado):** a hipótese foi **confirmada no topo**. O quartil mais estrelado (Q4, mediana ≈ 100 mil estrelas) tem a **menor** taxa de PRs por mil estrelas (8,35) e a **menor** taxa de releases por mil estrelas (0,25) — o engajamento cresce mais devagar que a popularidade. O padrão não é monotônico (Q2 é o pico), mas a queda acentuada entre Q2/Q3 e Q4 sustenta a ideia de que, no extremo da popularidade, as estrelas crescem mais rápido do que a contribuição efetiva.

**Limitação:** a comparação é relativa à própria amostra (já são os repositórios mais populares do GitHub inteiro) — mesmo o Q1, "menos estrelado", ainda é extremamente popular.

---

## 5. Configuração do Processo (GitHub Projects)

O quadro Kanban acompanhou todo o laboratório e serviu de base de dados para o processo de desenvolvimento (sprints Lab01S01→S03).

* **Repositório:** https://github.com/Cardosoooo/lab-experimentacao-software
* **GitHub Projects (v2):** [Kanban Sprint 1](https://github.com/users/Cardosoooo/projects/2)
* **Colunas do board (campo Status):** `Backlog → To Do → Doing → Review → Done`.
* **Cartões = Issues do repositório**, adicionadas ao Project e **atribuídas a um responsável** (campo Assignee), rastreáveis pela API.
* **Limite de WIP (Doing): 1.** O grupo é composto por 1 integrante, portanto só é possível codificar uma Issue por vez; WIP=1 força fechar (ou mover para Review) o item atual antes de iniciar outro, evitando trabalho pela metade acumulado na coluna.
* **Vínculo commit ↔ Issue:** todo commit referencia o número da Issue (ex.: `#6 pagina consulta para 1000 repositorios`), garantindo vínculo automático no histórico e contabilização correta na avaliação.
* **Snapshots de sprint:** ao fim de cada sprint, o `ExportadorKanban.java` exporta o estado do board (Status, Assignee) para um CSV em `data/snapshots/kanban_<data>.csv`. Como o GitHub Projects não guarda histórico consultável de mudança de coluna, essa série de snapshots faz esse papel e será a base dos Labs 04/05.

---

## 6. Conclusão

Os dados dos 1.000 repositórios mais populares do GitHub **confirmam, em sua maior parte, a fama de "projetos maduros e bem cuidados"**: são antigos (mediana ≈ 7,7 anos), muito contribuídos (mediana ≈ 774 PRs), atualizados quase que diariamente (mediana de 0 dias) e com alta proporção de issues fechadas (mediana ≈ 88%). A concentração em linguagens populares (70%) é real, porém a relação entre linguagem e engajamento não é determinística. Releases apresentam comportamento bimodal (repositórios de conteúdo não versionam; frameworks versionam bastante).

As **inovações** cumpriram seu papel de contribuição original medindo algo fora do enunciado:
* **RQ08** mostrou, de forma quantificável, que **engajamento não cresce proporcionalmente à popularidade** — no quartil mais estrelado, a taxa de PRs/releases por mil estrelas cai de forma acentuada, dando suporte à ideia de que estrela é, em grande parte, "reconhecimento passivo".
* **Cache local** trouxe robustez e reprodutibilidade à coleta: retomada de execuções interrompidas e economia de *rate limit* — medida em execução real, com **100% de hit e 100 chamadas à API economizadas** no reuso (série em `cache_stats.csv`) — com a devida ressalva de *staleness* controlada pela flag `--refresh`.

Em conjunto, o laboratório cumpriu as 7 RQs do enunciado, agregou duas frentes de inovação com resultado discutido e registrou o processo completo no GitHub Projects.

---

*Documento gerado a partir dos dados em `data/analise/` e das coletas em `data/repositorios.csv`. A série de métricas do cache está em `data/analise/cache_stats.csv` (acumulada por execução).*
