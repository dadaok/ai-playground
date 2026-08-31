package playground;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * 1단계: LLM API를 "날것으로" 이해하기.
 *
 * 핵심 감(感):
 *  - 이건 상태 없는(stateless) HTTP 호출이다. 서버는 우리를 기억하지 않는다.
 *  - 대화는 messages 배열이다. role = user / assistant 가 번갈아 쌓인다.
 *  - 다음 턴에도 이전 대화를 계속 이어가려면 "내가" 매번 전체 messages 를 다시 보낸다.
 *
 * 실행:
 *  export ANTHROPIC_API_KEY=sk-ant-...
 *  ./gradlew run
 */
public class Step1Basic {

    public static void main(String[] args) {
        // ANTHROPIC_API_KEY 환경변수를 자동으로 읽는다.
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-haiku-4-5")
                // 가장 저렴한 모델. 더 똑똑한 답이 필요하면 "claude-sonnet-5" 나 "claude-opus-5" 로.
                .maxTokens(1024L)
                // system 프롬프트: 모델의 역할/태도를 정하는 지시문
                .system("너는 자바 개발자에게 AI 개념을 설명하는 친절한 튜터야. 한국어로 답해.")
                // user 메시지: 이번 턴의 질문
                .addUserMessage("에이전트(agent)와 그냥 LLM 한 번 호출하는 것의 차이를 3문장으로 설명해줘.")
                .build();

        Message response = client.messages().create(params);

        // 응답 content 는 여러 개의 block 으로 나뉠 수 있다(텍스트, 생각, 도구호출 등).
        // 여기서는 text block 만 뽑아서 출력한다.
        response.content().stream()
                .flatMap(block -> block.text().stream())
                .forEach(textBlock -> System.out.println(textBlock.text()));

        // 토큰 사용량 = 요금의 단위. 매 호출마다 확인하는 습관을 들이자.
        System.out.println();
        System.out.println("---");
        System.out.println("input tokens : " + response.usage().inputTokens());
        System.out.println("output tokens: " + response.usage().outputTokens());
    }
}
