package playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 최소 MCP 클라이언트 (SDK 없이).
 *
 * 5단계에서 만든 MCP '서버'의 반대편이다. 하는 일:
 *   1. 서버를 자식 프로세스로 실행 (ProcessBuilder)
 *   2. 그 프로세스의 stdin/stdout 파이프로 JSON-RPC 를 주고받음
 *   3. initialize 핸드셰이크 → tools/list → tools/call
 *
 * 6단계 harness 가 이 클래스를 써서 "외부 MCP 서버의 툴"을 자기 툴 목록에 편입한다.
 */
public class McpStdioClient implements AutoCloseable {

    public record McpTool(String name, String description, JsonNode inputSchema) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final Process process;
    private final BufferedWriter toServer;
    private final BufferedReader fromServer;
    private int nextId = 1;

    public McpStdioClient(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT); // 서버의 [mcp] 로그(stderr)를 그대로 콘솔에 보여줌
        this.process = pb.start();
        this.toServer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromServer = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** 핸드셰이크. 반드시 첫 호출. */
    public void initialize() throws Exception {
        ObjectNode params = M.createObjectNode();
        params.put("protocolVersion", "2025-06-18");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "step6-harness").put("version", "1.0.0");
        request("initialize", params);

        // 핸드셰이크 마무리: 알림(응답 없음)
        ObjectNode note = M.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/initialized");
        send(note);
    }

    /** 서버가 제공하는 툴 목록. */
    public List<McpTool> listTools() throws Exception {
        JsonNode result = request("tools/list", M.createObjectNode());
        List<McpTool> out = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            out.add(new McpTool(
                    t.path("name").asText(),
                    t.path("description").asText(""),
                    t.path("inputSchema")));
        }
        return out;
    }

    /** 툴 실행. content 안의 text 조각들을 이어붙여 돌려준다. */
    public String callTool(String name, Map<String, Object> arguments) throws Exception {
        ObjectNode params = M.createObjectNode();
        params.put("name", name);
        params.set("arguments", M.valueToTree(arguments));
        JsonNode result = request("tools/call", params);

        StringBuilder sb = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asText())) sb.append(c.path("text").asText());
        }
        if (result.path("isError").asBoolean(false)) return "[도구 오류] " + sb;
        return sb.toString();
    }

    // ── JSON-RPC 배관 ────────────────────────────────────────────────────

    private JsonNode request(String method, JsonNode params) throws Exception {
        int id = nextId++;
        ObjectNode req = M.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        send(req);

        // id 가 일치하는 응답이 올 때까지 읽는다 (서버 알림 등은 건너뜀)
        String line;
        while ((line = fromServer.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg = M.readTree(line);
            if (msg.path("id").isInt() && msg.path("id").asInt() == id) {
                if (msg.has("error")) {
                    throw new RuntimeException("MCP 오류: " + msg.get("error").toString());
                }
                return msg.path("result");
            }
        }
        throw new RuntimeException("MCP 서버가 응답 없이 종료됨 (method=" + method + ")");
    }

    private void send(JsonNode msg) throws Exception {
        toServer.write(M.writeValueAsString(msg));
        toServer.write("\n");
        toServer.flush();
    }

    @Override
    public void close() {
        try {
            toServer.close();
        } catch (Exception ignored) {
        }
        process.destroy();
    }
}
