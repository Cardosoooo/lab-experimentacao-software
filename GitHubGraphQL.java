import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parte comum dos dois scripts do laboratorio: manda uma query GraphQL pra API do GitHub
 * (com retry se der erro de gateway) e faz o parse do JSON de volta, sem usar nenhuma lib
 * de terceiros. Foi extraido do MineradorGitHub (Sprint 1) pra o exportador do Kanban
 * (Sprint 2) poder reusar em vez de copiar o mesmo codigo.
 */
public final class GitHubGraphQL {

    private static final int MAX_RETRIES = 6;
    private static final long BASE_BACKOFF_MS = 2000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private GitHubGraphQL() {
    }

    /** Executa a query e ja devolve o JSON parseado, verificando o campo "errors" da resposta. */
    public static Map<String, Object> query(String query, String token) throws IOException, InterruptedException {
        String body = fetchWithRetry(query, token);

        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) MiniJson.parse(body);
        if (json.containsKey("errors")) {
            throw new IOException("A API do GitHub retornou erros: " + json.get("errors"));
        }
        return json;
    }

    // GitHub as vezes devolve 502/503/504 quando a query e pesada (ex.: contar issues de
    // repositorios gigantes). Nao e erro nosso, e questao de tentar de novo com um tempo
    // de espera crescente.
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
                long backoffMs = Math.min(BASE_BACKOFF_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
                System.out.println("HTTP " + e.statusCode + " (tentativa " + attempt + "/" + MAX_RETRIES + "), nova tentativa em " + (backoffMs / 1000) + "s...");
                Thread.sleep(backoffMs);
            }
        }
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

    /** Serializa de volta pra JSON os Map/List que vieram do MiniJson (usado pra salvar o raw). */
    public static String stringify(Object value) {
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

    /** Exposto pra dar pra testar o parser isolado, sem precisar de uma chamada de rede de verdade. */
    public static Object parseJson(String json) {
        return MiniJson.parse(json);
    }

    /** Usado pelos dois scripts pra montar CSV: envolve em aspas se tiver virgula, aspas ou quebra de linha. */
    public static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    /** Inverso de csvField: quebra uma linha de CSV em campos, respeitando aspas. */
    public static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    public static String jsonEscape(String raw) {
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

    /**
     * Parser JSON bem simples, so pra nao depender de biblioteca externa. Cobre os tipos que
     * aparecem numa resposta do GitHub (objeto, array, string, numero, bool, null) e nao tenta
     * ser 100% do spec JSON (ex.: nao valida entrada malformada).
     */
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
