# Mineração de Repositórios Populares do GitHub

Projeto da disciplina **Laboratório de Experimentação de Software** (Engenharia de Software, PUC Minas). Investiga, com dados, se os 1000 repositórios com mais estrelas do GitHub sustentam a fama de "maduros e bem cuidados" que costuma ser atribuída a projetos populares e acompanha, em paralelo, o processo do próprio laboratório num quadro Kanban (GitHub Projects v2).

## Integrantes

- Gabriel Cardoso ([@Cardosoooo](https://github.com/Cardosoooo))
- Guilherme Brina Ferreira ([@Gmbferreira](https://github.com/Gmbferreira))

## O que o projeto analisa

A coleta mede, para cada um dos 1000 repositórios, os dados necessários para responder a 7 Questões de Pesquisa (RQs):

| RQ | Pergunta |
|---|---|
| RQ01 | Sistemas populares são maduros/antigos? |
| RQ02 | Recebem muita contribuição externa? |
| RQ03 | Lançam releases com frequência? |
| RQ04 | São atualizados com frequência? |
| RQ05 | São escritos nas linguagens mais populares? |
| RQ06 | Possuem um alto percentual de issues fechadas? |
| RQ07 | Repositórios em linguagens populares recebem mais contribuição, releases e atualizações? |

A definição de métrica e o campo coletado para cada RQ estão na seção [Mapeamento das Questões de Pesquisa](#mapeamento-das-questões-de-pesquisa) abaixo.

## Estrutura do projeto

```
lab-experimentacao-software/
├── MineradorGitHub.java        # coleta os 1000 repositórios via GraphQL (RQ01-RQ07 + RQ08), com cache local
├── ExportadorKanban.java       # snapshot do board GitHub Projects (v2) em CSV
├── AnalisadorRQs.java          # calcula as métricas das RQ01-RQ08 a partir do CSV coletado
├── GitHubGraphQL.java          # utilitário compartilhado: HTTP/retry, parser JSON e helpers de CSV
├── Testes.java                 # testes automatizados desses utilitários (sem framework externo)
├── data/
│   ├── repositorios.csv        # dataset principal: 1000 repositórios, uma linha por repositório
│   ├── repositorios_raw.json   # resposta bruta da API (auditoria/debug)
│   ├── cache/                  # cache local por página da coleta (0.json, 1.json, ...)
│   ├── snapshots/              # um CSV por execução do ExportadorKanban (kanban_<data>.csv)
│   └── analise/                # saída do AnalisadorRQs + cache_stats.csv (métricas do cache)
├── relatorio/
│   └── introducao_rascunho.md  # rascunho da Introdução do relatório final
├── .gitignore
└── README.md
```

Não há build tool (Maven/Gradle) de propósito — o enunciado proíbe bibliotecas de terceiros para consultar a API do GitHub, e mantendo tudo em Java puro (`javac`/`java` direto) fica mais simples auditar que nenhuma dependência externa entra na consulta.

## Pré-requisitos

- JDK 17 ou superior
- Um [Personal Access Token do GitHub](https://github.com/settings/tokens) (classic):
  - escopo `public_repo` — usado pelo `MineradorGitHub`
  - escopo `project` — usado pelo `ExportadorKanban` (ler o GitHub Projects v2 exige esse escopo, `public_repo` não é suficiente)

## Configuração

O token **não** é commitado, ele é lido da variável de ambiente `GITHUB_TOKEN`.

PowerShell:

```powershell
$env:GITHUB_TOKEN = "seu_token_aqui"
```

Bash:

```bash
export GITHUB_TOKEN="seu_token_aqui"
```

## Executar

Compilar:

```bash
javac *.java
```

Coleta dos repositórios (RQ01–RQ07):

```bash
java MineradorGitHub            # reutiliza o cache local (rápido, sem gastar rate limit)
java MineradorGitHub --refresh  # força nova consulta da API e reescreve o cache
```

> **Cache local (inovação — frente de arquitetura/ferramenta de coleta):** cada página
> dos 1000 repositórios é salva em `data/cache/<página>.json` assim que é recebida. Numa
> re-execução **sem** `--refresh`, páginas já cacheadas são lidas do disco em vez de
> consultar a API de novo — ideal para re-rodar a análise, e **retoma** coletas
> interrompidas no meio (timeout, rate limit, queda de rede) em vez de reiniciar do zero.
> Por ser *staleness-by-design*, usar `--refresh` quando se quer dados realmente novos.
> Cada execução registra em `data/analise/cache_stats.csv` (páginas no cache vs. rede,
> % de hit, requests economizados e tempo de rede poupado), resultado discutido nas
> seções Resultados/Discussão e Conclusão do relatório.

Snapshot do Kanban (rodar de novo a cada semana/aula, conforme exigido no processo — gera um CSV novo por data):

```bash
java ExportadorKanban
```

Análise das RQ01–RQ08 (lê o `repositorios.csv` já coletado, não faz chamada de rede):

```bash
java AnalisadorRQs
```

Testes automatizados:

```bash
java Testes
```

Saída salva automaticamente em:

- `data/repositorios_raw.json` — resposta bruta da API
- `data/cache/<página>.json` — cache local por página da coleta (reuso/retomada)
- `data/repositorios.csv` — 1000 repositórios, com `collectedAt` (data/hora da coleta) em cada linha
- `data/snapshots/kanban_<data>.csv` — estado do board na data da execução; a série completa desses arquivos é a base de dados usada nos Labs 04/05
- `data/analise/resumo.csv`, `por_linguagem.csv`, `rq08_estrelas_engajamento.csv` — métricas calculadas para as RQ01–RQ08
- `data/analise/cache_stats.csv` — métricas do cache (modo, %hit, requests economizados, tempo poupado) por execução

## Mapeamento das Questões de Pesquisa

| RQ | Pergunta | Métrica | Campo(s) coletado(s) |
|---|---|---|---|
| RQ01 | Sistemas populares são maduros/antigos? | Idade do repositório | `createdAt` |
| RQ02 | Recebem muita contribuição externa? | Total de PRs aceitas | `pullRequests(states: MERGED)` |
| RQ03 | Lançam releases com frequência? | Total de releases | `releases.totalCount` |
| RQ04 | São atualizados com frequência? | Tempo até a última atualização | `updatedAt` |
| RQ05 | São escritos nas linguagens mais populares? | Linguagem primária | `primaryLanguage.name` |
| RQ06 | Alto percentual de issues fechadas? | Issues fechadas / total de issues | `totalIssues`, `closedIssues` |
| RQ07 | Linguagens populares recebem mais contribuição/releases/updates? | RQ02, RQ03 e RQ04 agrupadas por linguagem | derivado dos campos acima na análise |

RQ01 e RQ04 armazenam as datas brutas (`createdAt`/`updatedAt`); o cálculo de idade e de tempo desde a última atualização é feito na etapa de análise (Lab01S03), sempre a partir de `collectedAt` (gravado em toda linha do CSV) como data de referência — não da data em que a análise for rodada. Isso garante que o resultado não mude dependendo de quando o script de análise é executado.

**Fonte de referência para "linguagens mais populares" (RQ05/RQ07):** [GitHub Octoverse 2025](https://github.blog/news-insights/octoverse/octoverse-a-new-developer-joins-github-every-second-as-ai-leads-typescript-to-1/), ranking por número de contribuidores. Consideradas populares: TypeScript, Python, JavaScript, Java, C#, PHP, Shell, C++, HCL e Go. Referência fixa para o laboratório inteiro — não muda entre sprints.

## Inovações propostas pelo grupo (30% da nota — fora do enunciado)

O enunciado cobre 70% da disciplina; os 30% restantes vêm de contribuições originais. Adotamos **duas** frentes do item 3.6, ambas com resultado discutido nas seções de Resultados/Discussão e Conclusão:

### Frente (a)+(b): nova RQ + métrica/variável adicional — RQ08
**RQ08** — Dentro dos 1000 repositórios mais populares, quem tem mais estrelas recebe contribuição proporcionalmente maior, ou o engajamento cresce mais devagar que a popularidade?

- **Métrica:** PRs aceitas por mil estrelas e releases por mil estrelas, comparados entre quartis de estrelas dentro da amostra (`stargazerCount`, campo novo — antes só usávamos estrelas pra ordenar o ranking, nunca guardávamos o número).
- **Hipótese informal:** a relação não é proporcional — repositórios hiper-populares devem ter uma taxa de PRs/releases por estrela **menor**, porque dar estrela é um clique sem custo e contribuir exige trabalho de verdade.
- Detalhada em [relatorio/introducao_rascunho.md](relatorio/introducao_rascunho.md), calculada por `AnalisadorRQs.java` em `data/analise/rq08_estrelas_engajamento.csv`.

### Frente (c): mudança de arquitetura/ferramenta de coleta — cache local
A coleta dos 1000 repositórios (~100 chamadas GraphQL) agora usa um **cache local por página** (`data/cache/<página>.json`), com três efeitos mensuráveis:

1. **Retomada de coleta interrompida** — a página é salva assim que recebida; se a execução cair no meio (502/503/504, rate limit, queda de rede), só o que falta é buscado, nada é desperdiçado. Hoje, sem o cache, uma interrupção reinicia os ~100 requests do zero.
2. **Reuso sem re-consultar** — re-executar (análise, reprodução) sem `--refresh` lê do disco, não gasta rate limit nem tempo de rede.
3. **Custo/eficiência reportado** — cada execução grava `data/analise/cache_stats.csv` (modo, páginas do cache vs. rede, % hit, requests economizados e tempo de rede poupado, via latência média contra-factual). Esse número é citado na discussão.

**Troca documentada (ameaça à validade):** o cache é *staleness-by-design* — reutilizá-lo mantém dados da última coleta real. Por isso `--refresh` existe e deve ser usado quando se quer dados novos. Como os `endCursor` do GitHub apontam para posições estáveis do resultado ordenado por estrelas, a retomada é consistente, mas se o ranking mudar entre execuções uma re-coleta (`--refresh`) é o caminho correto para revalidar.

## Visualização gráfica

Um gráfico por RQ (RQ01–RQ08), com a pergunta correspondente e os valores-chave em destaque — HTML/SVG nativo, sem biblioteca de terceiros, gerado a partir dos dados de `data/analise/`:

- [visualizacao/relatorio_interativo.html](visualizacao/relatorio_interativo.html) — um gráfico por RQ, com tabela de valores
- [visualizacao/dashboard.html](visualizacao/dashboard.html) — painel resumido: KPIs, progresso das sprints, status do Kanban e achado principal de cada RQ

Abra qualquer um dos dois arquivos direto no navegador (não precisa de servidor).

## Testes automatizados

`Testes.java` cobre os utilitários de `GitHubGraphQL.java` (parser JSON, escape/parse de JSON e CSV) e os helpers específicos de cada script (`MineradorGitHub.totalCount`/`cacheFile`/`readCachedPage`, `ExportadorKanban.assigneeLogins`, `AnalisadorRQs.mediana`/`quartil`), sem depender de rede — usam dados de exemplo, não fazem chamada real à API. O teste do cache faz o *round-trip* de uma página (escreve e relê `data/cache/<n>.json`, conferindo nodes e `endCursor` preservados). Não usa JUnit nem nenhum framework: só `System.exit(1)` se algo falhar, o suficiente para o tamanho do projeto e consistente com a regra de não usar bibliotecas de terceiros.

## Restrições do enunciado

- Nenhuma biblioteca de terceiros consulta a API do GitHub — a query GraphQL e o parsing da resposta são implementados no próprio `GitHubGraphQL.java` (parser JSON mínimo incluso, sem dependências externas).
- Cada commit referencia o número da Issue correspondente (ex.: `#12 implementa consulta GraphQL`).

## Configuração do processo (GitHub Projects)

- **Repositório:** https://github.com/Cardosoooo/lab-experimentacao-software
- **GitHub Projects:** [Kanban Sprint 1](https://github.com/users/Cardosoooo/projects/2)
- **Colunas (Status):** `Backlog → To Do → Doing → Review → Done`
- **Limite de WIP (Doing): 2.** O grupo passou a ter 2 integrantes (Gabriel Cardoso e Guilherme Brina Ferreira) — WIP=2 segue a sugestão do processo da disciplina (1 cartão por pessoa ativa no board), evitando que mais de uma Issue por integrante fique em andamento ao mesmo tempo.

## Roadmap das sprints

- **Lab01S01** ✅: consulta para 100 repositórios + GitHub Projects criado.
- **Lab01S02** ✅: paginação para 1000 repositórios, dados em `.csv`, primeira versão do relatório com hipóteses informais, snapshot do board exportado.
- **Lab01S03** ✅: métricas calculadas para as 7 RQs + RQ08 (inovação), com visualização gráfica.
- **Relatório Final** ✅: documento consolidado com introdução, metodologia, resultados, discussão e seção de configuração do processo.
