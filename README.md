# Mineração de Repositórios Populares do GitHub

Projeto da disciplina **Laboratório de Experimentação de Software** (Engenharia de Software, PUC Minas). Investiga, com dados, se os 1000 repositórios com mais estrelas do GitHub sustentam a fama de "maduros e bem cuidados" que costuma ser atribuída a projetos populares e acompanha, em paralelo, o processo do próprio laboratório num quadro Kanban (GitHub Projects v2).

## Integrante

- Gabriel Cardoso ([@Cardosoooo](https://github.com/Cardosoooo))

## Estrutura do projeto

```
lab-experimentacao-software/
├── MineradorGitHub.java        # coleta os 1000 repositórios via GraphQL (RQ01-RQ07)
├── ExportadorKanban.java       # snapshot do board GitHub Projects (v2) em CSV
├── GitHubGraphQL.java          # utilitário compartilhado: HTTP/retry, parser JSON e helpers de CSV
├── Testes.java                 # testes automatizados desses utilitários (sem framework externo)
├── data/
│   ├── repositorios.csv        # dataset principal: 1000 repositórios, uma linha por repositório
│   ├── repositorios_raw.json   # resposta bruta da API (auditoria/debug)
│   └── snapshots/              # um CSV por execução do ExportadorKanban (kanban_<data>.csv)
├── relatorio/
│   └── introducao_rascunho.md  # rascunho da Introdução do relatório final
├── .gitignore
└── README.md
```

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
java MineradorGitHub
```

Snapshot do Kanban (rodar de novo a cada semana/aula, conforme exigido no processo — gera um CSV novo por data):

```bash
java ExportadorKanban
```

Testes automatizados:

```bash
java Testes
```

Saída salva automaticamente em:

- `data/repositorios_raw.json` — resposta bruta da API
- `data/repositorios.csv` — 1000 repositórios, com `collectedAt` (data/hora da coleta) em cada linha
- `data/snapshots/kanban_<data>.csv` — estado do board na data da execução; a série completa desses arquivos é a base de dados usada nos Labs 04/05

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

## Testes automatizados

`Testes.java` cobre os utilitários de `GitHubGraphQL.java` (parser JSON, escape de JSON/CSV) e os helpers específicos de cada script (`MineradorGitHub.totalCount`, `ExportadorKanban.assigneeLogins`), sem depender de rede — os testes usam JSON de exemplo, não fazem chamada real à API. Não usa JUnit nem nenhum framework: só `System.exit(1)` se algo falhar, o suficiente para o tamanho do projeto e consistente com a regra de não usar bibliotecas de terceiros.

## Restrições do enunciado

- Nenhuma biblioteca de terceiros consulta a API do GitHub — a query GraphQL e o parsing da resposta são implementados no próprio `GitHubGraphQL.java` (parser JSON mínimo incluso, sem dependências externas).
- Cada commit referencia o número da Issue correspondente (ex.: `#12 implementa consulta GraphQL`).

## Configuração do processo (GitHub Projects)

- **Repositório:** https://github.com/Cardosoooo/lab-experimentacao-software
- **GitHub Projects:** [Kanban Sprint 1](https://github.com/users/Cardosoooo/projects/2)
- **Colunas (Status):** `Backlog → To Do → Doing → Review → Done`
- **Limite de WIP (Doing): 1.** O grupo é composto por 1 integrante (Gabriel Cardoso), então só é possível codificar uma Issue por vez — WIP=1 força fechar (ou mover para Review) o item atual antes de iniciar outro, evitando trabalho pela metade acumulado na coluna.

## Roadmap das sprints

- **Lab01S01** ✅: consulta para 100 repositórios + GitHub Projects criado.
- **Lab01S02** ✅: paginação para 1000 repositórios, dados em `.csv`, primeira versão do relatório com hipóteses informais, snapshot do board exportado.
- **Lab01S03** (próxima): análise e visualização de dados para as 7 RQs.
- **Relatório Final**: documento consolidado com introdução, metodologia, resultados, discussão e seção de configuração do processo.
