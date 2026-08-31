package playground;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * 2단계: 도구 사용(tool use) = 에이전트의 심장.
 *
 * LLM 은 코드를 직접 실행하지 못한다. 대신 이렇게 동작한다:
 *   1. 우리가 "이런 도구들이 있다"고 스키마(이름 + 설명 + 파라미터)를 넘긴다.
 *   2. 모델이 필요하면 "calc 를 이 인자로 불러줘"라고 JSON 으로 요청한다 (stop_reason = "tool_use").
 *   3. 우리 코드가 그 함수를 실제로 실행한다.
 *   4. 결과를 다시 모델에게 넘긴다.
 *   5. 모델이 답을 마무리하거나(=end_turn), 또 다른 도구를 부른다. 2~4 를 반복.
 *
 * 이 2~4 반복 루프가 바로 "에이전트"다. 여기서는 SDK 의 BetaToolRunner 가
 * 그 루프를 대신 돌려준다. (3단계에서 이 루프를 손으로 직접 짜볼 것)
 *
 * 실행:
 *   ./gradlew step2
 */
public class Step2ToolLoop {

    /**
     * 도구 1: 계산기.
     * - 클래스 = 도구 하나. 필드 = 파라미터. Supplier<String> 의 get() = 실제 실행 로직.
     * - 애노테이션의 설명문을 모델이 읽고 "언제 이 도구를 쓸지" 판단한다. 설명이 곧 프롬프트다.
     */
    @JsonClassDescription("두 수의 사칙연산을 계산한다. 산수가 필요하면 반드시 이 도구를 사용할 것.")
    static class Calculator implements Supplier<String> {
        @JsonPropertyDescription("연산자: +, -, *, / 중 하나")
        public String op;
        @JsonPropertyDescription("첫 번째 피연산자")
        public double a;
        @JsonPropertyDescription("두 번째 피연산자")
        public double b;

        @Override
        public String get() {
            System.out.println("  [도구 실행] Calculator: " + a + " " + op + " " + b);
            double result = switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> a / b;
                default -> throw new IllegalArgumentException("알 수 없는 연산자: " + op);
            };
            return String.valueOf(result);
        }
    }

    /**
     * 도구 2: 현재 시각.
     * (도구는 파라미터가 최소 1개 있어야 한다 — Anthropic SDK 의 스키마 검증 규칙.
     *  그래서 timezone 을 받는다. "지금 시간" 처럼 인자 없이 물어도 모델이 알아서 채워준다.)
     */
    @JsonClassDescription("주어진 시간대의 현재 날짜와 시각을 ISO-8601 형식으로 돌려준다.")
    static class CurrentTime implements Supplier<String> {
        @JsonPropertyDescription("IANA 시간대 이름. 예: Asia/Seoul, UTC")
        public String timezone;

        @Override
        public String get() {
            System.out.println("  [도구 실행] CurrentTime: " + timezone);
            return ZonedDateTime.now(ZoneId.of(timezone)).toString();
        }
    }

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        BetaToolRunner runner = client.beta().messages().toolRunner(
                MessageCreateParams.builder()
                        .model("claude-haiku-4-5")
                        .maxTokens(1024L)
                        .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13")
                        .addTool(Calculator.class)
                        .addTool(CurrentTime.class)
                        .addUserMessage(
                                "지금 서울은 몇 시야? 그리고 (128 * 7) 에서 19 를 뺀 값도 알려줘. "
                                        + "계산은 반드시 도구를 써서 해.")
                        .build());

        // runner 를 순회하면 한 턴씩 진행된다. 모델 응답 -> 도구 실행 -> 다시 모델 ...
        int turn = 1;
        for (BetaMessage message : runner) {
            System.out.println("=== turn " + turn++ + " (stop_reason=" + message.stopReason() + ") ===");
            System.out.println(message);
            System.out.println();
        }
    }
}
