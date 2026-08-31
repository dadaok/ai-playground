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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 3단계: 에이전트 루프를 SDK 헬퍼(BetaToolRunner) 없이 "손으로" 짠다.
 *
 * 2단계에서 BetaToolRunner 가 대신 해주던 일을 여기서 직접 구현한다:
 *   1. messages 를 통째로 보내 client.messages().create() 호출
 *   2. 응답의 stop_reason 확인
 *   3. tool_use 면 → content 안의 tool_use 블록을 꺼내 실제 실행
 *   4. tool_result 블록들을 user 메시지로 만들어 messages 에 append
 *   5. end_turn 이 나올 때까지 1~4 반복 (무한 루프 방지 상한도 직접 건다)
 *
 * 실행:  ./gradlew step3   (또는 IntelliJ 에서 main 실행)
 */
public class Step3ManualLoop {

    // 모델에게 넘길 도구 정의 (JSON 스키마를 직접 작성).
    private static final Tool CALCULATOR = Tool.builder()
            .name("calculator")
            .description("두 수의 사칙연산을 계산한다. 산수가 필요하면 반드시 이 도구를 사용할 것.")
            .inputSchema(Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("op", JsonValue.from(Map.of(
                                    "type", "string",
                                    "enum", List.of("+", "-", "*", "/"))))
                            .putAdditionalProperty("a", JsonValue.from(Map.of("type", "number")))
                            .putAdditionalProperty("b", JsonValue.from(Map.of("type", "number")))
                            .build())
                    .required(List.of("op", "a", "b"))
                    .build())
            .build();

    private static final int MAX_TURNS = 10;

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        // 우리가 직접 관리하는 대화 내역.
        List<MessageParam> conversation = new ArrayList<>();
        conversation.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content("(128 * 7) 에서 19 를 뺀 값이 뭐야? 계산은 반드시 도구를 써.")
                .build());

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model("claude-haiku-4-5")
                    .maxTokens(1024L)
                    .addTool(CALCULATOR)
                    .messages(conversation)      // 매번 전체 대화를 다시 보낸다 (stateless)
                    .build();

            Message response = client.messages().create(params);

            Optional<StopReason> stop = response.stopReason();
            System.out.println("=== turn " + turn + " (stop_reason=" + stop.map(Object::toString).orElse("?") + ") ===");

            // 모델이 낸 텍스트 출력
            response.content().stream()
                    .flatMap(b -> b.text().stream())
                    .forEach(t -> System.out.println("  [모델] " + t.text()));

            // 방금 받은 assistant 턴을 대화에 추가 (다음 요청에 포함되어야 함)
            conversation.add(response.toParam());

            // 도구 요청이 없으면 → 끝
            if (!stop.equals(Optional.of(StopReason.TOOL_USE))) {
                System.out.println("\n루프 종료 (turn " + turn + ").");
                return;
            }

            // 도구 요청 처리: content 안의 tool_use 블록마다 실행
            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.toolUse().isEmpty()) continue;
                ToolUseBlock call = block.toolUse().get();

                String output;
                try {
                    output = executeTool(call);
                } catch (Exception e) {
                    output = "도구 실행 오류: " + e.getMessage();
                }

                toolResults.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id())        // 어느 요청에 대한 응답인지 id 로 짝을 맞춘다
                        .content(output)
                        .build()));
            }

            // tool_result 들을 하나의 user 메시지로 묶어 대화에 추가
            conversation.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }

        System.out.println("MAX_TURNS 도달 — 루프 강제 종료.");
    }

    /** tool_use 블록 하나를 실제로 실행하고 결과 문자열을 돌려준다. */
    private static String executeTool(ToolUseBlock call) {
        System.out.println("  [도구 요청] name=" + call.name() + " input=" + call._input());

        if ("calculator".equals(call.name())) {
            // 모델이 채워 보낸 input JSON 을 POJO 로 변환
            CalcInput in = call._input().convert(CalcInput.class);
            double r = switch (in.op()) {
                case "+" -> in.a() + in.b();
                case "-" -> in.a() - in.b();
                case "*" -> in.a() * in.b();
                case "/" -> in.a() / in.b();
                default -> throw new IllegalArgumentException("알 수 없는 연산자: " + in.op());
            };
            System.out.println("  [도구 실행] " + in.a() + " " + in.op() + " " + in.b() + " = " + r);
            return String.valueOf(r);
        }
        throw new IllegalArgumentException("모르는 도구: " + call.name());
    }

    /** calculator 도구의 입력 스키마에 대응하는 레코드. */
    public record CalcInput(String op, double a, double b) {}
}
