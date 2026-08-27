import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Lab01S03 - le data/repositorios.csv (ja coletado nas sprints anteriores) e calcula as
 * metricas das RQ01-RQ07: idade, PRs aceitas, releases, tempo desde a atualizacao,
 * distribuicao por linguagem, percentual de issues fechadas, e o cruzamento de RQ02/03/04
 * por linguagem. Nao faz nenhuma chamada de rede - so processa o CSV ja coletado.
 */
public class AnalisadorRQs {

    private static final String INPUT_CSV = "data/repositorios.csv";
    private static final String OUTPUT_DIR = "data/analise";

    // Mesma fonte definida no README para RQ05/RQ07: GitHub Octoverse 2025.
    private static final Set<String> LINGUAGENS_POPULARES = Set.of(
            "TypeScript", "Python", "JavaScript", "Java", "C#", "PHP", "Shell", "C++", "HCL", "Go"
    );

    record Repositorio(
            String nameWithOwner, double idadeDias, double diasDesdeAtualizacao,
            long mergedPRs, long releases, long totalIssues, long closedIssues, String linguagem,
            long stargazers
    ) {}

    public static void main(String[] args) {
        try {
            List<Repositorio> repos = lerCsv(Path.of(INPUT_CSV));
            System.out.println("Repositorios carregados: " + repos.size());

            Files.createDirectories(Path.of(OUTPUT_DIR));
            escreverResumo(repos);
            escreverDistribuicoes(repos);
            escreverPorLinguagem(repos);
            escreverRQ08(repos);

            System.out.println("Analise salva em " + OUTPUT_DIR + "/resumo.csv, " + OUTPUT_DIR
                    + "/distribuicoes.csv, " + OUTPUT_DIR + "/por_linguagem.csv e "
                    + OUTPUT_DIR + "/rq08_estrelas_engajamento.csv");
        } catch (IOException e) {
            System.err.println("Falha ao analisar os dados: " + e.getMessage());
            System.exit(1);
        }
    }

    private static List<Repositorio> lerCsv(Path path) throws IOException {
        List<String> linhas = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Repositorio> repos = new ArrayList<>();

        for (int i = 1; i < linhas.size(); i++) { // linha 0 e o header
            List<String> campos = GitHubGraphQL.parseCsvLine(linhas.get(i));
            if (campos.size() < 9) continue;

            Instant createdAt = Instant.parse(campos.get(1));
            Instant updatedAt = Instant.parse(campos.get(2));
            String linguagem = campos.get(3).isBlank() ? "Sem linguagem identificada" : campos.get(3);
            long mergedPRs = Long.parseLong(campos.get(4));
            long releases = Long.parseLong(campos.get(5));
            long totalIssues = Long.parseLong(campos.get(6));
            long closedIssues = Long.parseLong(campos.get(7));
            Instant collectedAt = Instant.parse(campos.get(8));
            // stargazerCount so existe em CSVs gerados apos a coluna ter sido adicionada
            // (RQ08); CSVs antigos ficam com 0 em vez de quebrar a leitura.
            long stargazers = campos.size() > 9 && !campos.get(9).isBlank() ? Long.parseLong(campos.get(9)) : 0;

            double idadeDias = Duration.between(createdAt, collectedAt).toDays();
            double diasDesdeAtualizacao = Duration.between(updatedAt, collectedAt).toDays();

            repos.add(new Repositorio(campos.get(0), idadeDias, diasDesdeAtualizacao,
                    mergedPRs, releases, totalIssues, closedIssues, linguagem, stargazers));
        }

        return repos;
    }

    private static void escreverResumo(List<Repositorio> repos) throws IOException {
        List<Double> idades = repos.stream().map(Repositorio::idadeDias).toList();
        List<Double> prs = repos.stream().map(r -> (double) r.mergedPRs()).toList();
        List<Double> releases = repos.stream().map(r -> (double) r.releases()).toList();
        List<Double> diasAtualizacao = repos.stream().map(Repositorio::diasDesdeAtualizacao).toList();

        // RQ06: repositorio sem nenhuma issue nao entra na mediana do percentual - "0 fechadas
        // de 0" nao e nem 0% nem 100% de fechamento, e apenas ausencia de dado.
        List<Double> percentuaisFechamento = new ArrayList<>();
        long semIssues = 0;
        for (Repositorio r : repos) {
            if (r.totalIssues() == 0) {
                semIssues++;
            } else {
                percentuaisFechamento.add(100.0 * r.closedIssues() / r.totalIssues());
            }
        }

        long populares = repos.stream().filter(r -> LINGUAGENS_POPULARES.contains(r.linguagem())).count();

        List<String> linhas = new ArrayList<>();
        linhas.add("rq,metrica,valor,n");
        linhas.add("RQ01,idade_mediana_dias," + fmt(mediana(idades)) + "," + repos.size());
        linhas.add("RQ01,idade_mediana_anos," + fmt(mediana(idades) / 365.25) + "," + repos.size());
        linhas.add("RQ02,pull_requests_aceitas_mediana," + fmt(mediana(prs)) + "," + repos.size());
        linhas.add("RQ03,releases_mediana," + fmt(mediana(releases)) + "," + repos.size());
        linhas.add("RQ04,dias_desde_atualizacao_mediana," + fmt(mediana(diasAtualizacao)) + "," + repos.size());
        linhas.add("RQ05,repositorios_em_linguagem_popular_pct," + fmt(100.0 * populares / repos.size()) + "," + repos.size());
        linhas.add("RQ06,pct_issues_fechadas_mediana," + fmt(mediana(percentuaisFechamento)) + "," + percentuaisFechamento.size());
        linhas.add("RQ06,repositorios_sem_issues," + semIssues + "," + repos.size());

        Files.writeString(Path.of(OUTPUT_DIR, "resumo.csv"), String.join("\n", linhas) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Cinco-numeros (minimo, Q1, mediana, Q3, maximo) das metricas numericas das RQ01-RQ04 -
     * a mediana sozinha (em resumo.csv) nao da pra desenhar um boxplot, precisa da dispersao
     * tambem.
     */
    private static void escreverDistribuicoes(List<Repositorio> repos) throws IOException {
        List<String> linhas = new ArrayList<>();
        linhas.add("metrica,minimo,p25,mediana,p75,maximo");
        linhas.add(linhaDistribuicao("idade_dias", repos.stream().map(Repositorio::idadeDias).toList()));
        linhas.add(linhaDistribuicao("pull_requests_aceitas", repos.stream().map(r -> (double) r.mergedPRs()).toList()));
        linhas.add(linhaDistribuicao("releases", repos.stream().map(r -> (double) r.releases()).toList()));
        linhas.add(linhaDistribuicao("dias_desde_atualizacao", repos.stream().map(Repositorio::diasDesdeAtualizacao).toList()));

        Files.writeString(Path.of(OUTPUT_DIR, "distribuicoes.csv"), String.join("\n", linhas) + "\n", StandardCharsets.UTF_8);
    }

    private static String linhaDistribuicao(String metrica, List<Double> valores) {
        double[] resumo = cincoNumeros(valores);
        return metrica + "," + fmt(resumo[0]) + "," + fmt(resumo[1]) + "," + fmt(resumo[2]) + "," + fmt(resumo[3]) + "," + fmt(resumo[4]);
    }

    /** Package-private de proposito, pra dar pra testar direto. Retorna {minimo, p25, mediana, p75, maximo}. */
    static double[] cincoNumeros(List<Double> valores) {
        List<Double> ordenado = new ArrayList<>(valores);
        Collections.sort(ordenado);
        int n = ordenado.size();
        return new double[]{
                ordenado.get(0),
                ordenado.get(n / 4),
                mediana(valores),
                ordenado.get(3 * n / 4),
                ordenado.get(n - 1)
        };
    }

    private static void escreverPorLinguagem(List<Repositorio> repos) throws IOException {
        Map<String, List<Repositorio>> porLinguagem = new LinkedHashMap<>();
        for (Repositorio r : repos) {
            porLinguagem.computeIfAbsent(r.linguagem(), k -> new ArrayList<>()).add(r);
        }

        List<Map.Entry<String, List<Repositorio>>> ordenado = new ArrayList<>(porLinguagem.entrySet());
        ordenado.sort((a, b) -> b.getValue().size() - a.getValue().size());

        List<String> linhas = new ArrayList<>();
        linhas.add("linguagem,popular,quantidade,pr_mediana,releases_mediana,dias_desde_atualizacao_mediana");

        for (Map.Entry<String, List<Repositorio>> entrada : ordenado) {
            List<Repositorio> grupo = entrada.getValue();
            List<Double> prs = grupo.stream().map(r -> (double) r.mergedPRs()).toList();
            List<Double> releases = grupo.stream().map(r -> (double) r.releases()).toList();
            List<Double> dias = grupo.stream().map(Repositorio::diasDesdeAtualizacao).toList();

            linhas.add(GitHubGraphQL.csvField(entrada.getKey()) + ","
                    + LINGUAGENS_POPULARES.contains(entrada.getKey()) + ","
                    + grupo.size() + ","
                    + fmt(mediana(prs)) + ","
                    + fmt(mediana(releases)) + ","
                    + fmt(mediana(dias)));
        }

        Files.writeString(Path.of(OUTPUT_DIR, "por_linguagem.csv"), String.join("\n", linhas) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * RQ08: dentro dos 1000 mais populares, quem tem mais estrelas recebe contribuicao
     * proporcionalmente maior, ou o engajamento cresce mais devagar que a popularidade?
     * Divide a amostra em quartis de estrelas e compara PRs/releases por mil estrelas entre
     * eles - se a taxa cair do Q1 pro Q4, popularidade nao "compra" engajamento proporcional.
     */
    private static void escreverRQ08(List<Repositorio> repos) throws IOException {
        List<Long> stargazersOrdenado = repos.stream().map(Repositorio::stargazers).sorted().toList();

        Map<Integer, List<Repositorio>> porQuartil = new TreeMap<>();
        for (Repositorio r : repos) {
            porQuartil.computeIfAbsent(quartil(r.stargazers(), stargazersOrdenado), k -> new ArrayList<>()).add(r);
        }

        List<String> linhas = new ArrayList<>();
        linhas.add("quartil_estrelas,quantidade,estrelas_mediana,prs_por_mil_estrelas_mediana,releases_por_mil_estrelas_mediana");

        for (Map.Entry<Integer, List<Repositorio>> entrada : porQuartil.entrySet()) {
            List<Repositorio> grupo = entrada.getValue();
            List<Double> estrelas = grupo.stream().map(r -> (double) r.stargazers()).toList();
            // repos com 0 estrelas nao deveriam existir na amostra (filtro stars:>1 na busca),
            // mas o filtro protege contra divisao por zero de qualquer forma.
            List<Double> prsPorMilEstrelas = grupo.stream()
                    .filter(r -> r.stargazers() > 0)
                    .map(r -> 1000.0 * r.mergedPRs() / r.stargazers())
                    .toList();
            List<Double> releasesPorMilEstrelas = grupo.stream()
                    .filter(r -> r.stargazers() > 0)
                    .map(r -> 1000.0 * r.releases() / r.stargazers())
                    .toList();

            linhas.add(entrada.getKey() + "," + grupo.size() + "," + fmt(mediana(estrelas)) + ","
                    + fmt(mediana(prsPorMilEstrelas)) + "," + fmt(mediana(releasesPorMilEstrelas)));
        }

        Files.writeString(Path.of(OUTPUT_DIR, "rq08_estrelas_engajamento.csv"), String.join("\n", linhas) + "\n", StandardCharsets.UTF_8);
    }

    /** Package-private de proposito, pra dar pra testar direto. Espera a lista ja ordenada. */
    static int quartil(long valor, List<Long> ordenadoAscendente) {
        int n = ordenadoAscendente.size();
        long p25 = ordenadoAscendente.get(n / 4);
        long p50 = ordenadoAscendente.get(n / 2);
        long p75 = ordenadoAscendente.get(3 * n / 4);
        if (valor <= p25) return 1;
        if (valor <= p50) return 2;
        if (valor <= p75) return 3;
        return 4;
    }

    /** Package-private (sem "private") de proposito, pra dar pra chamar direto do Testes.java. */
    static double mediana(List<Double> valores) {
        if (valores.isEmpty()) return 0;
        List<Double> ordenado = new ArrayList<>(valores);
        Collections.sort(ordenado);
        int n = ordenado.size();
        return n % 2 == 1 ? ordenado.get(n / 2) : (ordenado.get(n / 2 - 1) + ordenado.get(n / 2)) / 2.0;
    }

    private static String fmt(double valor) {
        // Locale.ROOT de proposito: "%.2f" com locale pt-BR usaria virgula decimal, que
        // quebraria o separador de campo do CSV (tambem virgula).
        return String.format(Locale.ROOT, "%.2f", valor);
    }
}
