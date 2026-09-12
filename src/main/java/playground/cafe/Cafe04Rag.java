package playground.cafe;

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
import playground.rag.Corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 달빛커피 봇 (4) — 정책 문서 검색(RAG)을 도구로 추가한다.
 *
 * 3단계까지 문제: 재고·주문은 답하지만, "환불 규정이 뭐야?" 같은 **정책 질문**엔
 *                여전히 모델이 아는 척 지어낸다. 정책은 docs/ 에 문서로만 있다.
 * 해결: search_policy 라는 도구를 추가한다. 검색된 문서 조각을 근거로만 답하게 한다.
 *
 * 그리고 이 단계의 진짜 목적 — **키워드 검색의 한계**를 실제로 재현한다.
 * 질문 1은 문서 단어("환불","해지")를 그대로 쓴다 → 잘 찾는다.
 * 질문 2는 같은 걸 다르게 표현한다("돈을 돌려받다") → 단어가 안 겹쳐 검색이 빈손이다.
 * 이 실패가 다음 단계(의미 기반 임베딩 검색)로 넘어가는 이유다.
 *
 * 실행:  ./gradlew cafe4
 */
public class Cafe04Rag {

    private static final int MAX_TURNS = 6;

    private static final List<Tool> TOOLS = List.of(
            tool("order_status", "주문번호로 주문 상태와 배송일을 조회한다.",
                    Map.of("order_id", "주문번호"), List.of("order_id")),
            tool("inventory", "원두 이름으로 현재 재고(봉지 수)를 조회한다.",
                    Map.of("bean", "원두 이름"), List.of("bean")),
            tool("search_policy", "배송·환불·구독·회사 정책 문서에서 질문과 관련된 조각을 검색한다. "
                    + "정책/규정 관련 질문엔 반드시 이 도구로 먼저 확인하고, 검색 결과에 있는 내용만 답하라.",
                    Map.of("query", "검색할 질문/키워드"), List.of("query"))
    );

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        ask(client, "구독을 해지하면 이미 낸 돈은 환불되나요?");
        System.out.println("\n" + "=".repeat(70) + "\n");
        ask(client, "다 쓰기 전에 그만두면 냈던 돈을 돌려받을 수 있나요?");

        System.out.println("""

                → 질문 1은 문서 단어를 그대로 써서 검색이 잘 됐다.
                  질문 2는 같은 질문을 다르게 표현했을 뿐인데 키워드가 안 겹쳐
                  search_policy 가 빈손이거나 엉뚱한 걸 찾았을 것이다.
                  다음 단계: 임베딩 기반 의미 검색으로 이 한계를 없앤다 (5b편, step4b).""");
    }

    private static void ask(AnthropicClient client, String question) {
        System.out.println("고객: " + question + "\n");
        List<MessageParam> conv = new ArrayList<>();
        conv.add(MessageParam.builder().role(MessageParam.Role.USER).content(question).build());

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            MessageCreateParams.Builder p = MessageCreateParams.builder()
                    .model("claude-haiku-4-5")
                    .maxTokens(600L)
                    .system("너는 '달빛커피' 고객지원 상담원이다. 정책/규정 질문은 반드시 search_policy 로 "
                            + "확인하고 그 결과에 있는 내용만 근거로 답하라. 검색 결과가 없거나 관련 없으면 "
                            + "'정확한 답을 찾지 못했습니다. 고객센터로 문의해주세요' 라고 답하라. 한국어로 간결히.")
                    .messages(conv);
            for (Tool t : TOOLS) p.addTool(t);

            Message resp = client.messages().create(p.build());
            Optional<StopReason> stop = resp.stopReason();
            resp.content().stream().flatMap(b -> b.text().stream())
                    .forEach(t -> System.out.println("  봇: " + t.text()));
            conv.add(resp.toParam());

            if (!stop.equals(Optional.of(StopReason.TOOL_USE))) return;

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock b : resp.content()) {
                if (b.toolUse().isEmpty()) continue;
                ToolUseBlock call = b.toolUse().get();
                String out = run(call);
                System.out.println("  [도구] " + call.name() + " " + call._input() + " → " + preview(out));
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id()).content(out).build()));
            }
            conv.add(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(results).build());
        }
    }

    private static String run(ToolUseBlock call) {
        JsonNode in = call._input().convert(JsonNode.class);
        return switch (call.name()) {
            case "order_status" -> {
                Shop.Order o = Shop.order(in.path("order_id").asText());
                yield o == null ? "주문을 찾을 수 없음" : "%s: 상태=%s".formatted(o.id(), o.status());
            }
            case "inventory" -> {
                Integer n = Shop.stock(in.path("bean").asText());
                yield n == null ? "취급하지 않음" : n + "봉지";
            }
            case "search_policy" -> {
                List<Corpus.Chunk> hits = PolicySearch.search(in.path("query").asText(), 2);
                if (hits.isEmpty()) yield "검색 결과 없음";
                StringBuilder sb = new StringBuilder();
                for (Corpus.Chunk c : hits) sb.append("[").append(c.source()).append("] ").append(c.content()).append("\n\n");
                yield sb.toString().strip();
            }
            default -> "알 수 없는 도구";
        };
    }

    private static Tool tool(String name, String desc, Map<String, String> props, List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        props.forEach((k, v) -> pb.putAdditionalProperty(k,
                JsonValue.from(Map.of("type", "string", "description", v))));
        return Tool.builder()
                .name(name).description(desc)
                .inputSchema(Tool.InputSchema.builder().properties(pb.build()).required(required).build())
                .build();
    }

    private static String preview(String s) {
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 50 ? oneLine : oneLine.substring(0, 50) + "…";
    }
}
