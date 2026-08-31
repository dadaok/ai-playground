package playground;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 6단계: HARNESS.
 *
 * 3단계는 "맨몸 에이전트 루프"였다. harness 는 그 루프에 실전용 부가장치를 두른 것이다.
 * 이 파일은 미니 harness 를 직접 만들어서, harness 가 정확히 뭘 더 해주는지 보여준다:
 *
 *   1. 툴 레지스트리   — 로컬 툴 + 외부 MCP 서버(5단계)의 툴을 한 목록으로 통합
 *   2. 권한 게이트     — 위험한 툴은 실행 전에 사용자에게 y/n 확인
 *   3. 컨텍스트 관리   — 대화가 임계치를 넘으면 과거를 요약(compaction)해서 토큰 절약
 *   4. 턴 예산        — 무한 루프 방지 상한
 *   5. 로깅          — 각 단계가 언제 작동하는지 [harness] 로그로 표시
 *
 * Claude Code 가 바로 이런 harness 다 (규모만 훨씬 큼). 아래 항목들을 Claude Code 에서
 * 직접 확인해볼 수 있다: /context (컨텍스트 관리), 권한 프롬프트, 서브에이전트, /compact.
 *
 * 실행:  ./gradlew step6
 * 선행:  ./gradlew mcpJar   (5단계 MCP 서버 jar 필요)
 */
public class Step6Harness {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String MODEL = "claude-haiku-4-5";
    private static final int MAX_TURNS = 12;
    private static final int COMPACT_THRESHOLD_CHARS = 4000; // 데모용으로 낮게 (실무는 토큰 15만 등)
    private static final Path MCP_JAR = Path.of("build/libs/mcp-calc-server.jar");
    private static final Path NOTES_DIR = Path.of("notes");

    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    private final BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
    private final Map<String, RegisteredTool> registry = new LinkedHashMap<>();
    private final List<MessageParam> conversation = new ArrayList<>();

    /** 레지스트리 항목: 스키마 + 실행함수 + 승인 필요 여부. */
    record RegisteredTool(String name, String description, JsonNode inputSchema,
                          boolean requiresApproval, Function<JsonNode, String> execute) {}

    public static void main(String[] args) throws Exception {
        new Step6Harness().run("128 곱하기 7을 계산하고, 그 결과를 'answer.txt' 라는 메모로 저장해줘.");
    }

    void run(String userGoal) throws Exception {
        try (McpStdioClient mcp = connectMcp()) {
            registerLocalTools();
            registerMcpTools(mcp);
            System.out.println("[harness] 툴 " + registry.size() + "개 등록: " + registry.keySet());
            System.out.println("[harness] 목표: " + userGoal + "\n");

            conversation.add(userMessage(userGoal));

            for (int turn = 1; turn <= MAX_TURNS; turn++) {
                maybeCompact();  // (3) 컨텍스트 관리

                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(MODEL)
                        .maxTokens(1024L)
                        .system("너는 자동화 비서다. 필요한 툴을 사용해 목표를 완수하라. 한국어로 간결히.")
                        .messages(conversation);
                for (Tool t : anthropicTools()) params.addTool(t);

                Message response = client.messages().create(params.build());

                Optional<StopReason> stop = response.stopReason();
                System.out.println("── turn " + turn + " (stop=" + stop.map(Object::toString).orElse("?")
                        + ", 대화길이 ≈ " + estimateChars() + "자) ──");
                response.content().stream().flatMap(b -> b.text().stream())
                        .forEach(t -> System.out.println("  [모델] " + t.text()));
                conversation.add(response.toParam());

                if (!stop.equals(Optional.of(StopReason.TOOL_USE))) {
                    System.out.println("\n[harness] 목표 완료 (turn " + turn + ").");
                    return;
                }

                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.toolUse().isEmpty()) continue;
                    ToolUseBlock call = block.toolUse().get();
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(call.id())
                            .content(dispatch(call))   // (1)+(2) 레지스트리 조회 + 권한 게이트
                            .build()));
                }
                conversation.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build());
            }
            System.out.println("[harness] MAX_TURNS 도달 — 강제 종료 (턴 예산 초과).");
        }
    }

    // ── (1) 툴 레지스트리 ─────────────────────────────────────────────────

    private void registerLocalTools() {
        JsonNode saveNoteSchema = parseJson("""
                {
                  "type": "object",
                  "properties": {
                    "filename": { "type": "string" },
                    "text":     { "type": "string" }
                  },
                  "required": ["filename", "text"]
                }
                """);

        register(new RegisteredTool("save_note",
                "메모를 notes/ 폴더에 파일로 저장한다.",
                saveNoteSchema,
                true,   // ← 파일을 쓰는 작업이라 승인 필요
                args -> {
                    try {
                        Files.createDirectories(NOTES_DIR);
                        Path p = NOTES_DIR.resolve(args.path("filename").asText("note.txt"));
                        Files.writeString(p, args.path("text").asText(""));
                        return "저장됨: " + p;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
    }

    private void registerMcpTools(McpStdioClient mcp) throws Exception {
        for (McpStdioClient.McpTool t : mcp.listTools()) {
            register(new RegisteredTool(t.name(), t.description(), t.inputSchema(),
                    false,  // 계산기는 안전 → 자동 실행
                    args -> {
                        try {
                            Map<String, Object> m = M.convertValue(args, Map.class);
                            return mcp.callTool(t.name(), m);
                        } catch (Exception e) {
                            return "[MCP 호출 실패] " + e.getMessage();
                        }
                    }));
        }
    }

    private void register(RegisteredTool t) {
        registry.put(t.name(), t);
    }

    // ── (2) 권한 게이트 ──────────────────────────────────────────────────

    private String dispatch(ToolUseBlock call) {
        RegisteredTool tool = registry.get(call.name());
        if (tool == null) return "알 수 없는 툴: " + call.name();

        JsonNode args = call._input().convert(JsonNode.class);

        if (tool.requiresApproval()) {
            System.out.println("\n  ⚠️  [harness] 승인 요청 — 툴 '" + call.name() + "' 실행");
            System.out.println("      인자: " + args);
            System.out.print("      실행할까요? [y/N] ");
            String answer = readLine();
            if (!"y".equalsIgnoreCase(answer.trim())) {
                System.out.println("      → 거부됨\n");
                return "사용자가 실행을 거부했습니다.";
            }
            System.out.println("      → 승인됨\n");
        }

        System.out.println("  [harness] 툴 실행: " + call.name() + " " + args);
        String out = tool.execute().apply(args);
        System.out.println("  [harness] 결과: " + out);
        return out;
    }

    // ── (3) 컨텍스트 관리 (compaction) ───────────────────────────────────

    private void maybeCompact() {
        if (estimateChars() < COMPACT_THRESHOLD_CHARS || conversation.size() <= 3) return;

        int keepFromEnd = 2; // 최근 대화는 원본 유지
        List<MessageParam> old = conversation.subList(0, conversation.size() - keepFromEnd);

        StringBuilder dump = new StringBuilder();
        for (MessageParam mp : old) dump.append(mp.toString()).append('\n');

        Message summary = client.messages().create(MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(400L)
                .system("아래 대화 로그를 이후 작업에 필요한 사실만 남겨 5줄 이내로 요약하라.")
                .addUserMessage(dump.toString())
                .build());
        String summaryText = summary.content().stream().flatMap(b -> b.text().stream())
                .map(t -> t.text()).reduce("", String::concat);

        int before = estimateChars();
        List<MessageParam> rebuilt = new ArrayList<>();
        rebuilt.add(userMessage("[이전 대화 요약]\n" + summaryText));
        rebuilt.addAll(conversation.subList(conversation.size() - keepFromEnd, conversation.size()));
        conversation.clear();
        conversation.addAll(rebuilt);

        System.out.println("[harness] 🗜  컨텍스트 압축: 약 " + before + "자 → " + estimateChars()
                + "자 (과거 " + old.size() + "개 메시지 → 요약 1개)\n");
    }

    private int estimateChars() {
        int n = 0;
        for (MessageParam mp : conversation) n += mp.toString().length();
        return n;
    }

    // ── Anthropic 툴 목록 빌드 ───────────────────────────────────────────

    private List<Tool> anthropicTools() {
        List<Tool> out = new ArrayList<>();
        for (RegisteredTool rt : registry.values()) {
            Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
            JsonNode p = rt.inputSchema().path("properties");
            p.fieldNames().forEachRemaining(name ->
                    props.putAdditionalProperty(name, JsonValue.from(M.convertValue(p.get(name), Map.class))));

            List<String> required = new ArrayList<>();
            rt.inputSchema().path("required").forEach(r -> required.add(r.asText()));

            out.add(Tool.builder()
                    .name(rt.name())
                    .description(rt.description())
                    .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build())
                    .build());
        }
        return out;
    }

    // ── 잡동사니 ─────────────────────────────────────────────────────────

    private McpStdioClient connectMcp() throws Exception {
        if (!Files.exists(MCP_JAR)) {
            throw new IllegalStateException("먼저 `./gradlew mcpJar` 로 " + MCP_JAR + " 를 만드세요.");
        }
        System.out.println("[harness] MCP 서버 연결: java -jar " + MCP_JAR);
        McpStdioClient c = new McpStdioClient(List.of("java", "-jar", MCP_JAR.toString()));
        c.initialize();
        return c;
    }

    private static MessageParam userMessage(String text) {
        return MessageParam.builder().role(MessageParam.Role.USER).content(text).build();
    }

    private static JsonNode parseJson(String s) {
        try {
            return M.readTree(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readLine() {
        try {
            String l = console.readLine();
            return l == null ? "" : l;
        } catch (IOException e) {
            return "";
        }
    }
}
