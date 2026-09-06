package playground.cafe;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.function.Supplier;

/**
 * 달빛커피 봇 (2) — 실시간 정보용 도구를 붙인다.
 *
 * 1단계 문제: "내 주문 어디쯤이야?" 같은 실시간 질문엔 답할 수 없었다.
 *            (그 정보는 학습 데이터에 없고, 애초에 매번 바뀐다)
 * 해결: 모델에게 함수를 쥐여준다. 모델이 필요하면 "이 함수를 이 인자로 불러줘"라고 요청하고,
 *      우리 코드가 Shop(운영 데이터)을 조회해 결과를 돌려준다.
 *
 * 실행:  ./gradlew cafe2
 */
public class Cafe02Tools {

    @JsonClassDescription("주문번호로 주문의 현재 상태와 예상 배송일을 조회한다.")
    static class OrderStatus implements Supplier<String> {
        @JsonPropertyDescription("주문번호. 예: 12345")
        public String orderId;

        @Override
        public String get() {
            Shop.Order o = Shop.order(orderId);
            System.out.println("  [도구] OrderStatus(" + orderId + ")");
            if (o == null) return "주문번호 " + orderId + " 를 찾을 수 없습니다.";
            return "주문 %s: 원두=%s, 상태=%s, 결제금액=%d원, 예상배송일=%s"
                    .formatted(o.id(), o.bean(), o.status(), o.paidWon(), o.shipDate());
        }
    }

    @JsonClassDescription("원두 이름으로 현재 재고(봉지 수)를 조회한다. 0이면 품절이다.")
    static class Inventory implements Supplier<String> {
        @JsonPropertyDescription("원두 이름. 예: 에티오피아 예가체프, 콜롬비아 수프리모, 과테말라 안티구아, 디카페인")
        public String bean;

        @Override
        public String get() {
            Integer n = Shop.stock(bean);
            System.out.println("  [도구] Inventory(" + bean + ")");
            if (n == null) return "'" + bean + "' 은(는) 취급하지 않는 원두입니다.";
            return n == 0 ? bean + " : 품절" : bean + " : " + n + "봉지 재고";
        }
    }

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        String[] questions = {
                "12345 주문 지금 어디쯤이야?",
                "에티오피아 예가체프 재고 있어?",
        };

        for (String q : questions) {
            System.out.println("Q. " + q);
            BetaToolRunner runner = client.beta().messages().toolRunner(
                    MessageCreateParams.builder()
                            .model("claude-haiku-4-5")
                            .maxTokens(500L)
                            .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13")
                            .system("너는 '달빛커피' 고객지원 상담원이다. 실시간 정보는 반드시 도구로 확인하고 한국어로 간결히 답해라.")
                            .addTool(OrderStatus.class)
                            .addTool(Inventory.class)
                            .addUserMessage(q)
                            .build());

            BetaMessage last = null;
            for (BetaMessage m : runner) last = m;
            if (last != null) {
                last.content().forEach(b -> b.text().ifPresent(t -> System.out.println("봇: " + t.text())));
            }
            System.out.println("─".repeat(60));
        }

        System.out.println("""

                → 이제 실시간 질문에 답한다. 하지만 도구를 '언제 어떤 순서로' 쓸지는
                  아직 단순하다. 다음(cafe3): 재고가 없으면 대안 원두를 스스로 찾는 등
                  여러 도구를 모델이 알아서 조합하게 한다 = 에이전트 루프.""");
    }
}
