import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lab01S01 - coleta via GraphQL dos dados/metricas necessarios para as RQ01-RQ07
 * de uma amostra de 100 repositorios (por estrelas). Sem bibliotecas de terceiros
 * para consultar a API do GitHub, conforme exigido no enunciado.
 */
public class MineradorGitHub {

    private static final int REPO_COUNT = 100;
    private static final int PAGE_SIZE = 10;
    private static final int MAX_RETRIES = 4;
    private static final long BASE_BACKOFF_MS = 2000;
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
            Files.writeString(Path.of(OUTPUT_DIR, "repositorios_raw.json"), stringify(nodes), StandardCharsets.UTF_8);
            saveCsv(nodes);

            System.out.println("Dados salvos em " + OUTPUT_DIR + "/repositorios_raw.json e " + OUTPUT_DIR + "/repositorios.csv");
        } catch (IOException | InterruptedException e) {
            System.err.println("Falha ao executar a consulta: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Coleta REPO_COUNT repositorios em lotes de PAGE_SIZE. Pedir issues.totalCount duas vezes
     * (total e fechadas) para 100 repositorios de uma vez e caro o suficiente para a API do
     * GitHub estourar timeout (502/504); lotes menores + retry evitam isso.
     */
    private static List<Map<String, Object>> fetchAllRepositories(String token) throws IOException, InterruptedException {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;

        while (all.size() < REPO_COUNT) {
            int pageSize = Math.min(PAGE_SIZE, REPO_COUNT - all.size());
            String body = fetchWithRetry(buildQuery(pageSize, cursor), token);

            @SuppressWarnings("unchecked")
            Map<String, Object> json = (Map<String, Object>) MiniJson.parse(body);
            if (json.containsKey("errors")) {
                throw new IOException("A API do GitHub retornou erros: " + json.get("errors"));
            }

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

    private static String fetchWithRetry(String query, String token) throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executeQuery(query, token);
            } catch (GraphQLHttpException e) {
                boolean retryable = e.statusCode == 502 || e.statusCode == 503 || e.statusCode == 504;
                if (!retryable || attempt >= MAX_RETRIES) {
                    throw e;
                }
                long backoffMs = BASE_BACKOFF_MS * (1L << (attempt - 1));
                System.out.println("HTTP " + e.statusCode + " (tentativa " + attempt + "/" + MAX_RETRIES + "), nova tentativa em " + (backoffMs / 1000) + "s...");
                Thread.sleep(backoffMs);
            }
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
                  }
                }
              }
            }
            """.formatted(pageSize, afterClause);
        // nameWithOwner + createdAt -> RQ01 | pullRequests -> RQ02 | releases -> RQ03
        // updatedAt -> RQ04 | primaryLanguage -> RQ05 | totalIssues/closedIssues -> RQ06
        // RQ07 e derivada de RQ02+RQ03+RQ04 agrupadas por linguagem, sem campo proprio.
    }

    private static String executeQuery(String query, String token) throws IOException, InterruptedException {
        String body = "{\"query\": \"" + jsonEscape(query) + "\"}";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/graphql"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new GraphQLHttpException(response.statusCode(), response.body());
        }
        return response.body();
    }

    private static final class GraphQLHttpException extends IOException {
        final int statusCode;

        GraphQLHttpException(int statusCode, String body) {
            super("HTTP " + statusCode + ": " + body);
            this.statusCode = statusCode;
        }
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

    private static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        stringify(value, sb);
        return sb.toString();
    }

    private static void stringify(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(jsonEscape(s)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(jsonEscape(String.valueOf(entry.getKey()))).append("\":");
                stringify(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                stringify(item, sb);
            }
            sb.append(']');
        }
    }

    private static String jsonEscape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Parser JSON minimo (sem dependencias externas) para o formato de resposta da API GraphQL do GitHub. */
    private static final class MiniJson {
        private final String src;
        private int pos;

        private MiniJson(String src) {
            this.src = src;
        }

        static Object parse(String json) {
            MiniJson parser = new MiniJson(json);
            parser.skipWhitespace();
            return parser.parseValue();
        }

        private Object parseValue() {
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++;
                skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                if (src.charAt(pos++) == '}') break;
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                if (src.charAt(pos++) == ']') break;
            }
            return list;
        }

        private String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = src.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            pos += 5;
            return Boolean.FALSE;
        }

        private Object parseNull() {
            pos += 4;
            return null;
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            String number = src.substring(start, pos);
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
