import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lab01S02 - coleta via GraphQL dos dados/metricas das RQ01-RQ07 para os 1000
 * repositorios com mais estrelas do GitHub. A consulta e sempre por lotes (PAGE_SIZE),
 * nunca os 1000 de uma vez, porque pedir issues.totalCount duas vezes por repositorio
 * (RQ06) fica pesado pra API e comecava a dar timeout com lotes grandes.
 */
public class MineradorGitHub {

    private static final int REPO_COUNT = 1000;
    private static final int PAGE_SIZE = 10;
    private static final String OUTPUT_DIR = "data";

    // Flag opcional: sem ela (padrao) a coleta reutiliza o cache local pagina a pagina
    // (rapido, sem gastar rate limit), com retomada de coletas interrompidas. Com ela, a
    // API e consultada de novo do zero e o cache e reescrito — necessario pra obter dados
    // realmente novos, ja que o cache e staleness-by-design.
    private static final String FLAG_REFRESH = "--refresh";
    private String mode = "cache";

    public static void main(String[] args) {
        MineradorGitHub minerador = new MineradorGitHub();
        minerador.run(args);
    }

    private void run(String[] args) {
        boolean refresh = readRefreshFlag(args);
        if (refresh) {
            mode = "refresh";
        }

        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Defina a variavel de ambiente GITHUB_TOKEN com um Personal Access Token do GitHub (escopo 'public_repo').");
            System.exit(1);
        }

        // Fixado uma unica vez no inicio da execucao: e o "hoje" usado depois pra calcular
        // idade (RQ01) e tempo desde a ultima atualizacao (RQ04) na analise (S03). Sem isso
        // gravado junto aos dados, analisar o CSV em outro dia daria um resultado diferente
        // do que a coleta original mediu.
        String collectedAt = Instant.now().toString();

        try {
            CollectionResult result = fetchAllRepositories(token, refresh);
            List<Map<String, Object>> nodes = result.nodes;
            System.out.println("Repositorios recebidos: " + nodes.size());

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("collectedAt", collectedAt);
            raw.put("repositories", nodes);

            Files.createDirectories(Path.of(OUTPUT_DIR));
            Files.writeString(Path.of(OUTPUT_DIR, "repositorios_raw.json"), GitHubGraphQL.stringify(raw), StandardCharsets.UTF_8);
            saveCsv(nodes, collectedAt);

            saveCacheStats(result.stats, collectedAt);
            printCacheSummary(result.stats);

            System.out.println("Dados salvos em " + OUTPUT_DIR + "/repositorios_raw.json e " + OUTPUT_DIR + "/repositorios.csv (collectedAt=" + collectedAt + ")");
        } catch (IOException | InterruptedException e) {
            System.err.println("Falha ao executar a consulta: " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean readRefreshFlag(String[] args) {
        for (String arg : args) {
            if (FLAG_REFRESH.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    // A busca do GitHub (campo "search") pagina com cursor, tipo lista encadeada: cada
    // resposta devolve um "endCursor" que a gente manda de volta na proxima chamada pra
    // continuar de onde parou. hasNextPage=false quer dizer que acabaram os repositorios.
    //
    // INOVACAO - cache local por pagina: cada pagina e salva em data/cache/<offset>.json
    // assim que e recebida. Numa re-execucao (modo "cache", sem --refresh), paginas que ja
    // existem sao lidas do disco em vez de consultar a API de novo; se a coleta for
    // interrompida no meio (timeout, rate limit, queda de rede), as paginas ja salvas ficam
    // e so o que falta e buscado - nenhum request e desperdicado. Com --refresh, todas as
    // paginas sao re-consultadas e o cache reescrito.
    private CollectionResult fetchAllRepositories(String token, boolean refresh) throws IOException, InterruptedException {
        List<Map<String, Object>> all = new ArrayList<>();
        CacheStats stats = new CacheStats();
        Path cacheDir = Path.of(OUTPUT_DIR, "cache");
        Files.createDirectories(cacheDir);

        int pageIndex = 0;
        String cursor = null;

        while (all.size() < REPO_COUNT) {
            int pageSize = Math.min(PAGE_SIZE, REPO_COUNT - all.size());
            Path cacheFile = cacheFile(cacheDir, pageIndex);

            List<Map<String, Object>> nodes;
            Map<String, Object> pageInfo;

            if (!refresh && Files.exists(cacheFile)) {
                PageData cached = readCachedPage(cacheFile);
                nodes = cached.nodes;
                pageInfo = cached.pageInfo;
                stats.cacheHits++;
                System.out.println("Cache    #" + (pageIndex + 1) + " (paga. " + all.size() + "/" + REPO_COUNT + ")");
            } else {
                long startNs = System.nanoTime();
                Map<String, Object> json = GitHubGraphQL.query(buildQuery(pageSize, cursor), token);

                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) json.get("data");
                @SuppressWarnings("unchecked")
                Map<String, Object> search = (Map<String, Object>) data.get("search");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fetched = (List<Map<String, Object>>) search.get("nodes");
                @SuppressWarnings("unchecked")
                Map<String, Object> fetchedPageInfo = (Map<String, Object>) search.get("pageInfo");

                nodes = fetched;
                pageInfo = fetchedPageInfo;
                stats.cacheMisses++;
                stats.missTimeMs += (System.nanoTime() - startNs) / 1_000_000L;

                // Guarda a resposta bruta inteira (nodes + pageInfo) no cache, no mesmo
                // formato da API — o proprio json. A pagina fica persistida imediatamente:
                // se o processo morrer depois, so essa pagina se perde, o resto fica.
                Files.writeString(cacheFile, GitHubGraphQL.stringify(json), StandardCharsets.UTF_8);
                System.out.println("Rede     #" + (pageIndex + 1) + " (paga. " + all.size() + "/" + REPO_COUNT + ")");
            }

            all.addAll(nodes);
            System.out.println("Coletados " + all.size() + "/" + REPO_COUNT + " repositorios...");

            boolean hasNextPage = pageInfo == null || Boolean.TRUE.equals(pageInfo.get("hasNextPage"));
            String nextCursor = pageInfo == null ? null : (String) pageInfo.get("endCursor");
            if (!hasNextPage || nextCursor == null || nextCursor.isEmpty()) {
                break;
            }
            cursor = nextCursor;
            pageIndex++;
        }

        return new CollectionResult(all, stats);
    }

    /** Package-private (sem "private") de proposito, pra dar pra chamar direto do Testes.java. */
    static Path cacheFile(Path cacheDir, int pageIndex) {
        return cacheDir.resolve(pageIndex + ".json");
    }

    // Cada arquivo de cache guarda a resposta bruta da pagina, no mesmo formato da API
    // ({ data: { search: { nodes, pageInfo } } }). Isso preserva o endCursor, essencial
    // pra retomada: continuar a busca pela rede exatamente de onde o cache parou.
    static PageData readCachedPage(Path file) throws IOException {
        String body = Files.readString(file, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) GitHubGraphQL.parseJson(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) json.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> search = (Map<String, Object>) data.get("search");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) search.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) search.get("pageInfo");
        return new PageData(nodes, pageInfo);
    }

    private void printCacheSummary(CacheStats stats) {
        System.out.println();
        System.out.println("--- Cache (modo=" + mode + ") ---");
        System.out.println("paginas servidas do cache: " + stats.cacheHits);
        System.out.println("paginas vindas da rede   : " + stats.cacheMisses);
        System.out.println("chamadas a API economizadas: " + stats.cacheHits);
        System.out.printf("taxa de hit: %.1f%%\n", stats.hitPct());
        System.out.printf("tempo de rede economizado (s): ~%.1f\n", stats.estimatedTimeSavedSeconds());
    }

    private void saveCacheStats(CacheStats stats, String collectedAt) throws IOException {
        Path dir = Path.of(OUTPUT_DIR, "analise");
        Files.createDirectories(dir);
        Path file = dir.resolve("cache_stats.csv");

        boolean exists = Files.exists(file);
        List<String> header = List.of(
                "executedAt", "mode", "pagesTotal", "cacheHits", "cacheMisses",
                "hitPct", "requestsSaved", "estimatedTimeSavedSec"
        );

        StringBuilder sb = new StringBuilder();
        if (!exists) {
            sb.append(String.join(",", header)).append("\n");
        }
        sb.append(GitHubGraphQL.csvField(collectedAt)).append(",")
                .append(mode).append(",")
                .append(stats.cacheHits + stats.cacheMisses).append(",")
                .append(stats.cacheHits).append(",")
                .append(stats.cacheMisses).append(",")
                .append(String.format(java.util.Locale.ROOT, "%.1f", stats.hitPct())).append(",")
                .append(stats.cacheHits).append(",")
                .append(String.format(java.util.Locale.ROOT, "%.1f", stats.estimatedTimeSavedSeconds())).append("\n");

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static final class CacheStats {
        int cacheHits = 0;
        int cacheMisses = 0;
        long missTimeMs = 0;

        double hitPct() {
            int total = cacheHits + cacheMisses;
            return total == 0 ? 0.0 : 100.0 * cacheHits / total;
        }

        // Contra-factual: assume que as chamadas salvas (hits) teriam custado a mesma
        // latencia media das chamadas de rede reais desta execucao. Se nao houve rede nesta
        // execucao (100% hit), usa 0 para nao inventar numero.
        double estimatedTimeSavedSeconds() {
            if (cacheMisses == 0) {
                return 0.0;
            }
            double avgMs = (double) missTimeMs / cacheMisses;
            return cacheHits * avgMs / 1000.0;
        }
    }

    private static final class CollectionResult {
        final List<Map<String, Object>> nodes;
        final CacheStats stats;

        CollectionResult(List<Map<String, Object>> nodes, CacheStats stats) {
            this.nodes = nodes;
            this.stats = stats;
        }
    }

    static final class PageData {
        final List<Map<String, Object>> nodes;
        final Map<String, Object> pageInfo;

        PageData(List<Map<String, Object>> nodes, Map<String, Object> pageInfo) {
            this.nodes = nodes;
            this.pageInfo = pageInfo;
        }
    }

    private static String buildQuery(int pageSize, String cursor) {
        String afterClause = cursor == null ? "" : ", after: \"" + cursor + "\"";
        return """
            query {
              search(query: "stars:>1 sort:stars-desc", type: REPOSITORY, first: %d%s) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  ... on Repository {
                    nameWithOwner
                    createdAt
                    updatedAt
                    primaryLanguage { name }
                    pullRequests(states: MERGED) { totalCount }
                    releases { totalCount }
                    totalIssues: issues { totalCount }
                    closedIssues: issues(states: CLOSED) { totalCount }
                    stargazerCount
                  }
                }
              }
            }
            """.formatted(pageSize, afterClause);
        // nameWithOwner + createdAt -> RQ01 | pullRequests -> RQ02 | releases -> RQ03
        // updatedAt -> RQ04 | primaryLanguage -> RQ05 | totalIssues/closedIssues -> RQ06
        // RQ07 e derivada de RQ02+RQ03+RQ04 agrupadas por linguagem, sem campo proprio.
        // stargazerCount -> RQ08: so usavamos estrelas pra ordenar o ranking, nunca
        // guardavamos o numero em si.
    }

    private static void saveCsv(List<Map<String, Object>> nodes, String collectedAt) throws IOException {
        List<String> header = List.of(
                "nameWithOwner", "createdAt", "updatedAt", "primaryLanguage",
                "mergedPullRequests", "releases", "totalIssues", "closedIssues", "collectedAt",
                "stargazerCount"
        );
        StringBuilder csv = new StringBuilder(String.join(",", header)).append("\n");

        for (Map<String, Object> node : nodes) {
            @SuppressWarnings("unchecked")
            Map<String, Object> language = (Map<String, Object>) node.get("primaryLanguage");

            csv.append(GitHubGraphQL.csvField(GitHubGraphQL.str(node.get("nameWithOwner")))).append(",")
                    .append(GitHubGraphQL.csvField(GitHubGraphQL.str(node.get("createdAt")))).append(",")
                    .append(GitHubGraphQL.csvField(GitHubGraphQL.str(node.get("updatedAt")))).append(",")
                    .append(GitHubGraphQL.csvField(language != null ? GitHubGraphQL.str(language.get("name")) : "")).append(",")
                    .append(totalCount(node.get("pullRequests"))).append(",")
                    .append(totalCount(node.get("releases"))).append(",")
                    .append(totalCount(node.get("totalIssues"))).append(",")
                    .append(totalCount(node.get("closedIssues"))).append(",")
                    .append(GitHubGraphQL.csvField(collectedAt)).append(",")
                    .append(GitHubGraphQL.str(node.get("stargazerCount"))).append("\n");
        }

        Files.writeString(Path.of(OUTPUT_DIR, "repositorios.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    /** Package-private (sem "private") de proposito, pra dar pra chamar direto do Testes.java. */
    @SuppressWarnings("unchecked")
    static long totalCount(Object field) {
        if (field == null) return 0;
        Object value = ((Map<String, Object>) field).get("totalCount");
        return value instanceof Number number ? number.longValue() : 0;
    }
}
