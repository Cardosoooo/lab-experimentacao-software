import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    public static void main(String[] args) {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Defina a variavel de ambiente GITHUB_TOKEN com um Personal Access Token do GitHub (escopo 'public_repo').");
            System.exit(1);
        }

        try {
            List<Map<String, Object>> nodes = fetchAllRepositories(token);
            System.out.println("Repositorios recebidos: " + nodes.size());

            Files.createDirectories(Path.of(OUTPUT_DIR));
            Files.writeString(Path.of(OUTPUT_DIR, "repositorios_raw.json"), GitHubGraphQL.stringify(nodes), StandardCharsets.UTF_8);
            saveCsv(nodes);

            System.out.println("Dados salvos em " + OUTPUT_DIR + "/repositorios_raw.json e " + OUTPUT_DIR + "/repositorios.csv");
        } catch (IOException | InterruptedException e) {
            System.err.println("Falha ao executar a consulta: " + e.getMessage());
            System.exit(1);
        }
    }

    // A busca do GitHub (campo "search") pagina com cursor, tipo lista encadeada: cada
    // resposta devolve um "endCursor" que a gente manda de volta na proxima chamada pra
    // continuar de onde parou. hasNextPage=false quer dizer que acabaram os repositorios.
    private static List<Map<String, Object>> fetchAllRepositories(String token) throws IOException, InterruptedException {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;

        while (all.size() < REPO_COUNT) {
            int pageSize = Math.min(PAGE_SIZE, REPO_COUNT - all.size());
            Map<String, Object> json = GitHubGraphQL.query(buildQuery(pageSize, cursor), token);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> search = (Map<String, Object>) data.get("search");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) search.get("nodes");
            @SuppressWarnings("unchecked")
            Map<String, Object> pageInfo = (Map<String, Object>) search.get("pageInfo");

            all.addAll(nodes);
            System.out.println("Coletados " + all.size() + "/" + REPO_COUNT + " repositorios...");

            boolean hasNextPage = Boolean.TRUE.equals(pageInfo.get("hasNextPage"));
            cursor = (String) pageInfo.get("endCursor");
            if (!hasNextPage) break;
        }

        return all;
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
                  }
                }
              }
            }
            """.formatted(pageSize, afterClause);
        // nameWithOwner + createdAt -> RQ01 | pullRequests -> RQ02 | releases -> RQ03
        // updatedAt -> RQ04 | primaryLanguage -> RQ05 | totalIssues/closedIssues -> RQ06
        // RQ07 e derivada de RQ02+RQ03+RQ04 agrupadas por linguagem, sem campo proprio.
    }

    private static void saveCsv(List<Map<String, Object>> nodes) throws IOException {
        List<String> header = List.of(
                "nameWithOwner", "createdAt", "updatedAt", "primaryLanguage",
                "mergedPullRequests", "releases", "totalIssues", "closedIssues"
        );
        StringBuilder csv = new StringBuilder(String.join(",", header)).append("\n");

        for (Map<String, Object> node : nodes) {
            @SuppressWarnings("unchecked")
            Map<String, Object> language = (Map<String, Object>) node.get("primaryLanguage");

            csv.append(csvField(str(node.get("nameWithOwner")))).append(",")
                    .append(csvField(str(node.get("createdAt")))).append(",")
                    .append(csvField(str(node.get("updatedAt")))).append(",")
                    .append(csvField(language != null ? str(language.get("name")) : "")).append(",")
                    .append(totalCount(node.get("pullRequests"))).append(",")
                    .append(totalCount(node.get("releases"))).append(",")
                    .append(totalCount(node.get("totalIssues"))).append(",")
                    .append(totalCount(node.get("closedIssues"))).append("\n");
        }

        Files.writeString(Path.of(OUTPUT_DIR, "repositorios.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static long totalCount(Object field) {
        if (field == null) return 0;
        Object value = ((Map<String, Object>) field).get("totalCount");
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
