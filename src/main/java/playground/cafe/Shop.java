package playground.cafe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「달빛커피」의 가짜 운영 데이터. 실무라면 DB/외부 API 겠지만, 봇 시나리오에서는 이걸로 충분하다.
 * Cafe02 부터 도구(tool)가 이 클래스를 호출한다.
 */
public final class Shop {

    private Shop() {}

    public record Order(String id, String bean, String status, int paidWon, String shipDate) {}

    /** 주문번호 → 주문 */
    public static final Map<String, Order> ORDERS = new LinkedHashMap<>(Map.of(
            "12345", new Order("12345", "에티오피아 예가체프", "부산 창고 출고 완료", 33_000, "2026-09-04"),
            "12346", new Order("12346", "콜롬비아 수프리모", "로스팅 대기", 19_000, "2026-09-11"),
            "12347", new Order("12347", "패밀리 세트", "결제 완료", 59_000, "2026-09-11")
    ));

    /** 원두명 → 현재 재고(봉지). 0이면 품절. */
    public static final Map<String, Integer> INVENTORY = new LinkedHashMap<>(Map.of(
            "에티오피아 예가체프", 0,
            "콜롬비아 수프리모", 24,
            "과테말라 안티구아", 12,
            "디카페인", 50
    ));

    public static Order order(String id) {
        return ORDERS.get(id == null ? "" : id.trim());
    }

    public static Integer stock(String bean) {
        return INVENTORY.get(bean == null ? "" : bean.trim());
    }
}
