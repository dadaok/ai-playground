package playground.cafe;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.util.List;

/**
 * 달빛커피 봇 (1) — 아무 장치 없이 그냥 물어본다.
 *
 * 이 단계의 목적은 "잘 되는 것"이 아니라 "문제를 보는 것"이다.
 * 모델은 달빛커피라는 회사를 모른다. 그런데도 물으면 아는 척 지어낸다(hallucination).
 *
 * 실행:  ./gradlew cafe1
 */
public class Cafe01Naive {

    private static final String SYSTEM = "너는 '달빛커피' 고객지원 상담원이다. 한국어로 친절하고 간결하게 답해라.";

    // 실제 정답 (docs/ 문서 기준). 봇 답변과 대조해보기 위해 적어둔다.
    private static final List<String[]> QUESTIONS = List.of(
            new String[]{"구독을 중간에 해지하면 이미 낸 돈은 환불되나요?",
                    "발송 전이면 고객센터 요청 시 전액 환불, 영업일 5일 내 처리. 이미 발송된 회차는 환불 불가."},
            new String[]{"디카페인 원두도 파나요?",
                    "판매함. 콜롬비아 원두를 스위스 워터 방식으로 처리, 카페인 0.1% 미만, 상시 판매."},
            new String[]{"가장 인기 있는 요금제가 뭐예요?",
                    "스탠다드 플랜(월 33,000원, 매주 200g)."}
    );

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        for (String[] qa : QUESTIONS) {
            String question = qa[0], truth = qa[1];

            Message resp = client.messages().create(MessageCreateParams.builder()
                    .model("claude-haiku-4-5")
                    .maxTokens(400L)
                    .system(SYSTEM)
                    .addUserMessage(question)
                    .build());

            String botAnswer = resp.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .reduce("", String::concat)
                    .strip();

            System.out.println("Q. " + question);
            System.out.println("봇: " + botAnswer);
            System.out.println("(실제: " + truth + ")");
            System.out.println("─".repeat(60));
        }

        System.out.println("""

                → 봇은 달빛커피 정책을 모르면서 그럴듯하게 답한다.
                  다음 단계(cafe2): 실시간 정보용 '도구'를 붙인다.
                  그 다음(cafe4): 정책 문서를 검색해 근거로 답하게 한다(RAG).""");
    }
}
