# Introdução (rascunho — Lab01S02)

## Contexto

Repositórios populares no GitHub costumam ser tratados como referência de "boa prática"
de engenharia de software, mas isso raramente é checado com dados. Este laboratório
investiga, nos 1000 repositórios com mais estrelas, se características básicas
(idade, contribuição externa, frequência de releases/atualizações, linguagem, gestão de
issues) realmente sustentam essa fama de "maduro e bem cuidado".

## Questões de Pesquisa e hipóteses informais

- **RQ01** — Sistemas populares são maduros/antigos?
  Sim, a maioria deve ter vários anos de existência, projeto não costuma
  ficar popular da noite pro dia, leva tempo até acumular estrelas.

- **RQ02** — Recebem muita contribuição externa?
  Sim, número alto de PRs aceitas, mais visibilidade tende a atrair mais
  gente disposta a contribuir.

- **RQ03** — Lançam releases com frequência?
  Sim, volume razoável de release, projeto popular geralmente tem processo
  de entrega ativo, não fica estagnado.

- **RQ04** — São atualizados com frequência?
  Sim, tempo curto desde o último update, repositório abandonado tende a
  perder relevância e cair no ranking de estrelas.

- **RQ05** — São escritos nas linguagens mais populares?
  Sim, a maior parte deve estar concentrada em poucas linguagens do topo do
  ranking do [GitHub Octoverse 2025](https://github.blog/news-insights/octoverse/octoverse-a-new-developer-joins-github-every-second-as-ai-leads-typescript-to-1/)
  (TypeScript, Python, JavaScript, Java, C#, PHP, Shell, C++, HCL, Go).

- **RQ06** — Alto percentual de issues fechadas?
  Sim, proporção alta de issues fechadas, mas com ressalva: isso pode
  refletir manutenção ativa real, ou só fechamento automático (bots de stale issue),
  sem necessariamente ter sido resolvido.

- **RQ07** — Linguagens populares recebem mais contribuição, releases e updates?
  Sim, linguagens no topo do ranking devem ter médias maiores nas três
  métricas (RQ02+RQ03+RQ04). Comunidade maior tende a significar mais gente
  contribuindo e mais infraestrutura de CI/CD madura pra sustentar releases frequentes.

## RQs / métricas de inovação (30%)

`[a definir]`
