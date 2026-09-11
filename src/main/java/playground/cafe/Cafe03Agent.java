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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 달빛커피 봇 (3) — 에이전트 루프. 모델이 여러 도구를 스스로 조합한다.
 *
 * 2단계 문제: 도구는 있지만 "재고 없으면 대안을 찾아라" 같은 다단계 판단은
 *            우리가 코드로 순서를 짜줘야 했다.
 * 해결: 루프를 돌리고 판단을 모델에 맡긴다. 모델이 알아서
 *      재고확인 → 품절 → 카탈로그 조회 → 비슷한 원두 고르기 → 그 원두 재고확인 → 추천
 *      순서로 도구를 부른다. 우리 코드는 stop_reason 만 보고 루프를 돈다.
 *
 * 실행:  ./gradlew cafe3
 */
public class Cafe03Agent {

    private static final int MAX_TURNS = 8;

    private static final List<Tool> TOOLS = List.of(
            tool("order_status", "주문번호로 주문 상태와 배송일을 조회한다.",
                    Map.of("order_id", "주문번호. 예: 12345"), List.of("order_id")),
            tool("inventory", "원두 이름으로 현재 재고(봉지 수)를 조회한다. 0이면 품절.",
                    Map.of("bean", "원두 이름"), List.of("bean")),
            tool("bean_catalog", "판매 중인 모든 원두의 맛/로스팅 노트를 돌려준다. 인자 없음(dummy 무시).",
                    Map.of("dummy", "사용 안 함"), List.of())
    );

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        List<MessageParam> conv = new ArrayList<>();
        conv.add(user("에티오피아 예가체프 주문하고 싶은데 재고 있어? 없으면 맛이 비슷한 걸로 추천해줘."));
        System.out.println("고객: " + textOf(conv.get(0)) + "\n");

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            MessageCreateParams.Builder p = MessageCreateParams.builder()
                    .model("claude-haiku-4-5")
                    .maxTokens(700L)
                    .system("너는 '달빛커피' 고객지원 상담원이다. 재고·주문은 반드시 도구로 확인하고, "
                            + "품절이면 카탈로그를 보고 맛이 비슷한 대체 원두를 스스로 찾아 재고까지 확인한 뒤 추천하라. 한국어로.")
                    .messages(conv);
            for (Tool t : TOOLS) p.addTool(t);

            Message resp = client.messages().create(p.build());
            Optional<StopReason> stop = resp.stopReason();
            System.out.println("── turn " + turn + " (" + stop.map(Object::toString).orElse("?") + ") ──");
            resp.content().stream().flatMap(b -> b.text().stream())
                    .forEach(t -> System.out.println("  봇 생각/답: " + t.text()));
            conv.add(resp.toParam());

            if (!stop.equals(Optional.of(StopReason.TOOL_USE))) {
                System.out.println("\n[대화 종료] turn " + turn);
                return;
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock b : resp.content()) {
                if (b.toolUse().isEmpty()) continue;
                ToolUseBlock call = b.toolUse().get();
                String out = run(call);
                System.out.println("  → 결과: " + out);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id()).content(out).build()));
            }
            conv.add(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(results).build());
        }
        System.out.println("[MAX_TURNS 초과]");
    }

    private static String run(ToolUseBlock call) {
        JsonNode in = call._input().convert(JsonNode.class);
        System.out.println("  [도구] " + call.name() + " " + in);
        return switch (call.name()) {
            case "order_status" -> {
                Shop.Order o = Shop.order(in.path("order_id").asText());
                yield o == null ? "주문을 찾을 수 없음"
                        : "%s: %s, 상태=%s, 배송예정=%s".formatted(o.id(), o.bean(), o.status(), o.shipDate());
            }
            case "inventory" -> {
                String bean = in.path("bean").asText();
                Integer n = Shop.stock(bean);
                yield n == null ? "'" + bean + "' 는 취급하지 않음"
                        : n == 0 ? bean + ": 품절" : bean + ": " + n + "봉지";
            }
            case "bean_catalog" -> {
                StringBuilder sb = new StringBuilder();
                Shop.CATALOG.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
                yield sb.toString().strip();
            }
            default -> "알 수 없는 도구: " + call.name();
        };
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private static Tool tool(String name, String desc, Map<String, String> props, List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        props.forEach((k, v) -> pb.putAdditionalProperty(k,
                JsonValue.from(Map.of("type", "string", "description", v))));
        return Tool.builder()
                .name(name).description(desc)
                .inputSchema(Tool.InputSchema.builder().properties(pb.build()).required(required).build())
                .build();
    }

    private static MessageParam user(String s) {
        return MessageParam.builder().role(MessageParam.Role.USER).content(s).build();
    }

    private static String textOf(MessageParam m) {
        return m.content().string().orElse("");
    }
}
