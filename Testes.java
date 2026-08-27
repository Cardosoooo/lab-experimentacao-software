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
        testParseCsvLine();
        testMediana();
        testQuartil();
        testCincoNumeros();

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

    private static void testParseCsvLine() {
        List<String> simples = GitHubGraphQL.parseCsvLine("a,b,c");
        check("parseCsvLine separa campos simples", simples.equals(List.of("a", "b", "c")));

        List<String> comVirgulaEntreAspas = GitHubGraphQL.parseCsvLine("foo/bar,\"a,b\",2026");
        check("parseCsvLine nao quebra campo entre aspas com virgula",
                comVirgulaEntreAspas.equals(List.of("foo/bar", "a,b", "2026")));

        List<String> comAspasEscapadas = GitHubGraphQL.parseCsvLine("\"a\"\"b\",c");
        check("parseCsvLine desfaz aspas duplicadas", comAspasEscapadas.equals(List.of("a\"b", "c")));

        check("parseCsvLine e o inverso de csvField",
                GitHubGraphQL.parseCsvLine(GitHubGraphQL.csvField("x,\"y\"")).get(0).equals("x,\"y\""));
    }

    private static void testMediana() {
        check("mediana de lista com quantidade impar", AnalisadorRQs.mediana(List.of(1.0, 3.0, 2.0)) == 2.0);
        check("mediana de lista com quantidade par", AnalisadorRQs.mediana(List.of(1.0, 2.0, 3.0, 4.0)) == 2.5);
        check("mediana de lista com 1 elemento", AnalisadorRQs.mediana(List.of(5.0)) == 5.0);
        check("mediana de lista vazia nao quebra", AnalisadorRQs.mediana(List.of()) == 0.0);
    }

    private static void testQuartil() {
        List<Long> estrelas = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L);
        check("quartil classifica o menor valor como Q1", AnalisadorRQs.quartil(10L, estrelas) == 1);
        check("quartil classifica o maior valor como Q4", AnalisadorRQs.quartil(80L, estrelas) == 4);
        check("quartil classifica valor mediano como Q2 ou Q3",
                AnalisadorRQs.quartil(40L, estrelas) == 2 || AnalisadorRQs.quartil(40L, estrelas) == 3);
    }

    private static void testCincoNumeros() {
        double[] resumo = AnalisadorRQs.cincoNumeros(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0));
        check("cincoNumeros identifica o minimo", resumo[0] == 1.0);
        check("cincoNumeros identifica a mediana", resumo[2] == 4.5);
        check("cincoNumeros identifica o maximo", resumo[4] == 8.0);
        check("cincoNumeros mantem p25 <= mediana <= p75", resumo[1] <= resumo[2] && resumo[2] <= resumo[3]);
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
