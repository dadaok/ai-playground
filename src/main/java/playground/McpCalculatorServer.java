package playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 5단계: MCP 서버를 "SDK 없이" 직접 구현해서 MCP 의 정체를 본다.
 *
 * MCP (Model Context Protocol) 는 대단한 게 아니다:
 *   - 전송(transport): stdio = 그냥 표준입출력 파이프. (다른 옵션: HTTP)
 *   - 메시지: JSON-RPC 2.0 을 한 줄에 하나씩 (newline-delimited).
 *   - 그게 전부다. 이 파일이 그 프로토콜을 손으로 처리한다.
 *
 * 왜 MCP 인가? (JDBC 비유)
 *   3단계에선 calculator 도구를 그 프로그램 안에 박아넣었다. 재사용 불가.
 *   이 도구를 MCP 서버로 빼두면 → Claude Desktop, Claude Code, 내 3단계 에이전트가
 *   "똑같은 서버"를 각자 꽂아 쓴다. M개 앱 × N개 도구 통합이 M+N 으로 준다.
 *
 * 핸드셰이크 순서 (호스트가 이 서버를 프로세스로 띄운 뒤):
 *   호스트 → initialize                     서버 → 프로토콜 버전 + 능력(capabilities)
 *   호스트 → notifications/initialized       (응답 없음)
 *   호스트 → tools/list                      서버 → 도구 목록 + JSON 스키마
 *   호스트 → tools/call {name, arguments}    서버 → 실행 결과(content)
 *
 * ⚠️ stdio 서버의 절대 규칙: stdout(System.out)에는 JSON-RPC 만 쓴다.
 *    로그/디버그는 전부 stderr(System.err)로. stdout 에 딴 걸 쓰면 프로토콜이 깨진다.
 *
 * 빌드:  ./gradlew mcpJar   →  build/libs/mcp-calc-server.jar
 * 수동 테스트: MCP_SERVER_TEST.md 참고
 */
public class McpCalculatorServer {

    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        log("calculator MCP 서버 시작 (stdio, JSON-RPC)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try {
                msg = M.readTree(line);
            } catch (Exception e) {
                log("JSON 파싱 실패, 무시: " + e.getMessage());
                continue;
            }
            handle(msg);
        }
        log("stdin 종료 — 서버 내려감");
    }

    private static void handle(JsonNode msg) {
        String method = msg.path("method").asText(null);
        JsonNode id = msg.get("id");                 // 있으면 요청(request), 없으면 알림(notification)
        if (method == null) return;                  // 응답 메시지는 서버가 받을 일 없음
        log("← " + method + (id != null ? " (id=" + id + ")" : " (notification)"));

        switch (method) {
            case "initialize" -> {
                String protocolVersion = msg.path("params").path("protocolVersion").asText("2025-06-18");
                ObjectNode result = M.createObjectNode();
                result.put("protocolVersion", protocolVersion);   // 호스트가 요청한 버전을 그대로 수용
                result.putObject("capabilities").putObject("tools"); // "우리는 tools 기능을 제공한다"
                result.putObject("serverInfo").put("name", "calc-java").put("version", "1.0.0");
                reply(id, result);
            }
            case "notifications/initialized", "notifications/cancelled" -> {
                // 알림 — 응답하지 않는다
            }
            case "ping" -> reply(id, M.createObjectNode());
            case "tools/list" -> reply(id, toolsList());
            case "tools/call" -> reply(id, callTool(msg.path("params")));
            default -> {
                if (id != null) replyError(id, -32601, "지원하지 않는 method: " + method);
            }
        }
    }

    /** tools/list 응답: 도구 이름 + 설명 + 입력 JSON 스키마. (3단계의 Tool.builder 와 같은 내용) */
    private static ObjectNode toolsList() {
        ObjectNode result = M.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode calc = tools.addObject();
        calc.put("name", "calculator");
        calc.put("description", "두 수의 사칙연산을 계산한다. 산수가 필요하면 이 도구를 사용할 것.");
        ObjectNode schema = calc.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("op").put("type", "string").putArray("enum").add("+").add("-").add("*").add("/");
        props.putObject("a").put("type", "number");
        props.putObject("b").put("type", "number");
        schema.putArray("required").add("op").add("a").add("b");
        return result;
    }

    /** tools/call 처리: 실제 계산 후 content 배열로 결과를 돌려준다. */
    private static ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText();
        JsonNode a = params.path("arguments");
        ObjectNode result = M.createObjectNode();
        ArrayNode content = result.putArray("content");

        try {
            if (!"calculator".equals(name)) throw new IllegalArgumentException("알 수 없는 도구: " + name);
            String op = a.path("op").asText();
            double x = a.path("a").asDouble();
            double y = a.path("b").asDouble();
            if ("/".equals(op) && y == 0) throw new IllegalArgumentException("0 으로 나눌 수 없음");
            double r = switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> x / y;
                default -> throw new IllegalArgumentException("알 수 없는 연산자: " + op);
            };
            log("  계산: " + x + " " + op + " " + y + " = " + r);
            content.addObject().put("type", "text").put("text", String.valueOf(r));
        } catch (Exception e) {
            result.put("isError", true);
            content.addObject().put("type", "text").put("text", "오류: " + e.getMessage());
        }
        return result;
    }

    // ── JSON-RPC 전송 헬퍼 ────────────────────────────────────────────────

    private static void reply(JsonNode id, JsonNode result) {
        if (id == null) return;                      // 알림에는 응답하지 않는다
        ObjectNode resp = M.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("result", result);
        send(resp);
    }

    private static void replyError(JsonNode id, int code, String message) {
        ObjectNode resp = M.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.putObject("error").put("code", code).put("message", message);
        send(resp);
    }

    private static void send(JsonNode resp) {
        try {
            String s = M.writeValueAsString(resp);
            System.out.write((s + "\n").getBytes(StandardCharsets.UTF_8));  // stdout = 프로토콜 전용
            System.out.flush();
            log("→ " + s);
        } catch (Exception e) {
            log("응답 직렬화 실패: " + e);
        }
    }

    private static void log(String s) {
        System.err.println("[mcp] " + s);            // 로그 = stderr
    }
}
