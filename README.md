# Lab01 — Laboratório de Experimentação de Software

Coleta e análise de características de repositórios populares no GitHub (LAB01), acompanhada do uso do GitHub Projects como quadro Kanban do grupo.

## Sprint atual: Lab01S02

Paginação para 1000 repositórios + dados em `.csv` + script de snapshot do Kanban.

### Pré-requisitos

- JDK 17 ou superior
- Um [Personal Access Token do GitHub](https://github.com/settings/tokens) (classic):
  - escopo `public_repo` — usado pelo `MineradorGitHub`
  - escopo `project` — usado pelo `ExportadorKanban` (ler o GitHub Projects v2 exige esse escopo, `public_repo` não é suficiente)

### Configuração

O token **não** é commitado — ele é lido da variável de ambiente `GITHUB_TOKEN`.

PowerShell:

```powershell
$env:GITHUB_TOKEN = "seu_token_aqui"
```

Bash:

```bash
export GITHUB_TOKEN="seu_token_aqui"
```

### Executar

Coleta dos repositórios (RQ01–RQ07):

```bash
javac *.java
java MineradorGitHub
```

Snapshot do Kanban (roda de novo em cada sprint/aula, gera um CSV novo por data):

```bash
java ExportadorKanban
```

Saída salva automaticamente em:

- `data/repositorios_raw.json` — resposta bruta da API (auditoria/debug)
- `data/repositorios.csv` — 1000 repositórios, uma linha por repositório
- `data/snapshots/kanban_<data>.csv` — estado do board na data da execução; a série completa desses arquivos é a base de dados usada nos Labs 04/05

O parsing e o envio da query GraphQL ficam em `GitHubGraphQL.java`, compartilhado pelos dois scripts.

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

RQ01 e RQ04 armazenam as datas brutas (`createdAt`/`updatedAt`); o cálculo de idade e de tempo desde a última atualização é feito na etapa de análise (Lab01S03), não na coleta — usando sempre `collectedAt` (gravado em toda linha do CSV) como data de referência, não a data em que a análise for rodada. Isso garante que o resultado não mude dependendo de quando o script de análise é executado.

**Fonte de referência para "linguagens mais populares" (RQ05/RQ07):** [GitHub Octoverse 2025](https://github.blog/news-insights/octoverse/octoverse-a-new-developer-joins-github-every-second-as-ai-leads-typescript-to-1/), ranking por número de contribuidores. Consideradas populares: TypeScript, Python, JavaScript, Java, C#, PHP, Shell, C++, HCL e Go. Essa referência é fixa para o laboratório inteiro — não muda entre sprints.

## Restrições do enunciado

- Nenhuma biblioteca de terceiros consulta a API do GitHub — a query GraphQL e o parsing da resposta são implementados no próprio `MineradorGitHub.java` (parser JSON mínimo incluso, sem dependências externas).
- Cada commit referencia o número da Issue correspondente (ex.: `#12 implementa consulta GraphQL`).

## Configuração do processo (GitHub Projects)

- **Repositório:** https://github.com/Cardosoooo/lab-experimentacao-software
- **GitHub Projects:** [Kanban Sprint 1](https://github.com/users/Cardosoooo/projects/2)
- **Colunas (Status):** `Backlog → To Do → Doing → Review → Done`
- **Limite de WIP (Doing): 1.** O grupo é composto por 1 integrante (Gabriel Cardoso), então só é possível codificar uma Issue por vez — WIP=1 força fechar (ou mover para Review) o item atual antes de iniciar outro, evitando trabalho pela metade acumulado na coluna.

## Roadmap das sprints

- **Lab01S01** ✅: consulta para 100 repositórios + GitHub Projects criado.
- **Lab01S02** (atual): paginação para 1000 repositórios, dados em `.csv`, primeira versão do relatório com hipóteses informais, snapshot do board exportado.
- **Lab01S03**: análise e visualização de dados para as 7 RQs.
- **Relatório Final**: documento consolidado com introdução, metodologia, resultados, discussão e seção de configuração do processo.
