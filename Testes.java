import java.util.List;
import java.util.Map;

/**
 * Testes automatizados sem framework externo (o projeto nao tem build tool configurado
 * pra puxar JUnit, e a regra do laboratorio ja restringe dependencia de terceiros pra API
 * do GitHub, entao mantivemos tudo em Java puro pra nao complicar o setup).
 *
 * Rodar com:
 *   javac *.java
 *   java Testes
 *
 * Sai com codigo 1 se algum teste falhar.
 */
public class Testes {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testJsonEscape();
        testCsvField();
        testStr();
        testParseJson();
        testTotalCount();
        testAssigneeLogins();

        System.out.println();
        System.out.println(passed + " passaram, " + failed + " falharam");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testJsonEscape() {
        check("jsonEscape escapa aspas", GitHubGraphQL.jsonEscape("a\"b").equals("a\\\"b"));
        check("jsonEscape escapa barra invertida", GitHubGraphQL.jsonEscape("a\\b").equals("a\\\\b"));
        check("jsonEscape escapa quebra de linha", GitHubGraphQL.jsonEscape("a\nb").equals("a\\nb"));
        check("jsonEscape nao mexe em string sem caractere especial", GitHubGraphQL.jsonEscape("abc").equals("abc"));
    }

    private static void testCsvField() {
        check("csvField nao mexe em campo simples", GitHubGraphQL.csvField("java").equals("java"));
        check("csvField envolve em aspas quando tem virgula", GitHubGraphQL.csvField("a,b").equals("\"a,b\""));
        check("csvField duplica aspas internas", GitHubGraphQL.csvField("a\"b").equals("\"a\"\"b\""));
    }

    private static void testStr() {
        check("str de null vira string vazia", GitHubGraphQL.str(null).equals(""));
        check("str de numero vira o texto do numero", GitHubGraphQL.str(42).equals("42"));
    }

    @SuppressWarnings("unchecked")
    private static void testParseJson() {
        String sample = "{\"data\":{\"search\":{\"nodes\":["
                + "{\"nameWithOwner\":\"foo/bar\",\"primaryLanguage\":null,"
                + "\"pullRequests\":{\"totalCount\":123}}"
                + "]}}}";

        Map<String, Object> json = (Map<String, Object>) GitHubGraphQL.parseJson(sample);
        Map<String, Object> data = (Map<String, Object>) json.get("data");
        Map<String, Object> search = (Map<String, Object>) data.get("search");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) search.get("nodes");

        check("parseJson le a quantidade certa de nodes", nodes.size() == 1);
        check("parseJson le string aninhada", "foo/bar".equals(nodes.get(0).get("nameWithOwner")));
        check("parseJson trata campo null da API", nodes.get(0).get("primaryLanguage") == null);

        String comErro = "{\"errors\":[{\"message\":\"rate limited\"}]}";
        Map<String, Object> jsonErro = (Map<String, Object>) GitHubGraphQL.parseJson(comErro);
        check("parseJson expoe o campo errors da API", jsonErro.containsKey("errors"));
    }

    private static void testTotalCount() {
        Map<String, Object> campo = Map.of("totalCount", 42);
        check("totalCount le o valor de dentro do Map", MineradorGitHub.totalCount(campo) == 42);
        check("totalCount de campo nulo vira 0", MineradorGitHub.totalCount(null) == 0);
    }

    private static void testAssigneeLogins() {
        Map<String, Object> assignee1 = Map.of("login", "gabriel");
        Map<String, Object> assignee2 = Map.of("login", "outra-pessoa");
        Map<String, Object> assignees = Map.of("nodes", List.of(assignee1, assignee2));
        Map<String, Object> content = Map.of("assignees", assignees);

        String resultado = ExportadorKanban.assigneeLogins(content);
        check("assigneeLogins junta os logins com ;",
                resultado.equals("gabriel;outra-pessoa") || resultado.equals("outra-pessoa;gabriel"));
    }

    private static void check(String descricao, boolean condicao) {
        if (condicao) {
            passed++;
            System.out.println("OK     - " + descricao);
        } else {
            failed++;
            System.out.println("FALHOU - " + descricao);
        }
    }
}
