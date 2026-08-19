import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lab01S02 (item 6 do enunciado) - tira uma "foto" do board GitHub Projects (v2) e salva
 * num CSV. O GitHub Projects nao guarda historico de mudanca de coluna consultavel via
 * API, entao esse script precisa ser rodado de novo em cada sprint/aula pra ir acumulando
 * a serie de snapshots (cada execucao gera um arquivo novo, com a data no nome).
 */
public class ExportadorKanban {

    private static final String OWNER = "Cardosoooo";
    private static final int PROJECT_NUMBER = 2;
    private static final String OUTPUT_DIR = "data/snapshots";

    public static void main(String[] args) {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Defina a variavel de ambiente GITHUB_TOKEN.");
            System.exit(1);
        }

        try {
            List<Map<String, Object>> items = fetchAllItems(token);
            System.out.println("Itens do board coletados: " + items.size());

            Files.createDirectories(Path.of(OUTPUT_DIR));
            String snapshotDate = LocalDate.now().toString();
            Path csvPath = Path.of(OUTPUT_DIR, "kanban_" + snapshotDate + ".csv");
            saveCsv(items, snapshotDate, csvPath);

            System.out.println("Snapshot salvo em " + csvPath);
        } catch (IOException | InterruptedException e) {
            System.err.println("Falha ao exportar o snapshot: " + e.getMessage());
            System.err.println("Se o erro for de permissao/scope, o GITHUB_TOKEN precisa do scope 'project' (leitura de Projects v2), que e diferente do scope usado no MineradorGitHub.");
            System.exit(1);
        }
    }

    // Projects v2 tambem pagina por cursor, igual a busca de repositorios do MineradorGitHub.
    // Hoje o board tem poucos itens (cabe numa pagina so), mas o board vai crescer junto com
    // o semestre, entao a paginacao fica pronta desde ja.
    private static List<Map<String, Object>> fetchAllItems(String token) throws IOException, InterruptedException {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;

        while (true) {
            Map<String, Object> json = GitHubGraphQL.query(buildQuery(cursor), token);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>) data.get("user");
            @SuppressWarnings("unchecked")
            Map<String, Object> projectV2 = (Map<String, Object>) user.get("projectV2");
            @SuppressWarnings("unchecked")
            Map<String, Object> items = (Map<String, Object>) projectV2.get("items");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) items.get("nodes");
            @SuppressWarnings("unchecked")
            Map<String, Object> pageInfo = (Map<String, Object>) items.get("pageInfo");

            all.addAll(nodes);

            boolean hasNextPage = Boolean.TRUE.equals(pageInfo.get("hasNextPage"));
            cursor = (String) pageInfo.get("endCursor");
            if (!hasNextPage) break;
        }

        return all;
    }

    private static String buildQuery(String cursor) {
        String afterClause = cursor == null ? "" : ", after: \"" + cursor + "\"";
        return """
            query {
              user(login: "%s") {
                projectV2(number: %d) {
                  items(first: 50%s) {
                    pageInfo { hasNextPage endCursor }
                    nodes {
                      fieldValueByName(name: "Status") {
                        ... on ProjectV2ItemFieldSingleSelectValue { name }
                      }
                      content {
                        ... on Issue {
                          number
                          title
                          state
                          assignees(first: 5) { nodes { login } }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.formatted(OWNER, PROJECT_NUMBER, afterClause);
    }

    private static void saveCsv(List<Map<String, Object>> items, String snapshotDate, Path csvPath) throws IOException {
        List<String> header = List.of("snapshotDate", "issueNumber", "title", "status", "issueState", "assignees");
        StringBuilder csv = new StringBuilder(String.join(",", header)).append("\n");

        for (Map<String, Object> item : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) item.get("content");
            if (content == null) continue; // item sem Issue vinculada (nao deveria existir, mas por seguranca)

            @SuppressWarnings("unchecked")
            Map<String, Object> statusField = (Map<String, Object>) item.get("fieldValueByName");
            String status = statusField != null ? GitHubGraphQL.str(statusField.get("name")) : "";

            csv.append(GitHubGraphQL.csvField(snapshotDate)).append(",")
                    .append(GitHubGraphQL.csvField(GitHubGraphQL.str(content.get("number")))).append(",")
                    .append(GitHubGraphQL.csvField(GitHubGraphQL.str(content.get("title")))).append(",")
                    .append(GitHubGraphQL.csvField(status)).append(",")
                    .append(GitHubGraphQL.csvField(GitHubGraphQL.str(content.get("state")))).append(",")
                    .append(GitHubGraphQL.csvField(assigneeLogins(content))).append("\n");
        }

        Files.writeString(csvPath, csv.toString(), StandardCharsets.UTF_8);
    }

    /** Package-private (sem "private") de proposito, pra dar pra chamar direto do Testes.java. */
    @SuppressWarnings("unchecked")
    static String assigneeLogins(Map<String, Object> content) {
        Map<String, Object> assignees = (Map<String, Object>) content.get("assignees");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) assignees.get("nodes");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> node : nodes) {
            if (sb.length() > 0) sb.append(";");
            sb.append(GitHubGraphQL.str(node.get("login")));
        }
        return sb.toString();
    }
}
