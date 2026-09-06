package playground.rag;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 5b단계 - 평가.
 *
 * "리랭킹이 정말 낫나?" 를 감이 아니라 숫자로 확인한다.
 * 골든 셋(질문 → 정답이 들어있는 파일)을 손으로 만들고, 검색 전략별로 지표를 잰다.
 *
 *   recall@k : 상위 k개 안에 정답 청크가 있으면 1
 *   MRR      : 정답이 r등이면 1/r  (1등=1.0, 못 찾으면 0)
 *
 * 실무에서는 이 골든 셋이 20~100개 이상이어야 하지만, 원리는 동일하다.
 *
 * 실행:  ./gradlew step4bEval
 */
public class Step4bEval {

    /** 질문 → 정답이 들어있어야 하는 파일명. */
    private static final Map<String, String> GOLDEN = Map.of(
            "구독을 중간에 그만두면 이미 낸 돈은 어떻게 되나요?", "배송정책.txt",
            "카페인 없는 원두도 파나요?", "원두정보.txt",
            "가장 인기 있는 요금제가 뭐예요?", "구독요금.txt",
            "상담원이랑 통화 가능한 시간대는?", "회사소개.txt",
            "주문한 커피는 언제 오나요?", "배송정책.txt",
            "첫 달 할인 있어요?", "구독요금.txt"
    );

    private static final int K = 3;

    public static void main(String[] args) {
        VoyageClient voyage = new VoyageClient();

        try (RagStore store = new RagStore()) {
            // 코퍼스가 이미 적재돼 있다고 가정 (없으면 먼저 step4b 실행)
            store.reload(Corpus.load(Path.of("docs")), voyage);

            Scorer vec = new Scorer("벡터만");
            Scorer hyb = new Scorer("하이브리드");
            Scorer rer = new Scorer("하이브리드+리랭킹");

            for (var entry : GOLDEN.entrySet()) {
                String q = entry.getKey();
                String gold = entry.getValue();
                float[] qVec = voyage.embedOne(q, "query");

                vec.add(sources(store.vectorSearch(qVec, K)), gold);

                List<RagStore.Hit> hybrid = store.hybrid(q, qVec, 8);
                hyb.add(sources(hybrid.subList(0, Math.min(K, hybrid.size()))), gold);

                List<String> cand = hybrid.stream().map(RagStore.Hit::content).toList();
                List<String> reranked = voyage.rerank(q, cand, K).stream()
                        .map(r -> hybrid.get(r.index()).source()).toList();
                rer.add(reranked, gold);
            }

            System.out.printf("%n골든 셋 %d개, k=%d%n", GOLDEN.size(), K);
            System.out.println("─".repeat(46));
            System.out.printf("%-22s %10s %8s%n", "전략", "recall@k", "MRR");
            System.out.println("─".repeat(46));
            for (Scorer s : List.of(vec, hyb, rer)) s.print();
            System.out.println("─".repeat(46));
        }
    }

    private static List<String> sources(List<RagStore.Hit> hits) {
        return hits.stream().map(RagStore.Hit::source).toList();
    }

    static class Scorer {
        final String name;
        int n;
        double recallSum;
        double mrrSum;

        Scorer(String name) { this.name = name; }

        void add(List<String> retrievedSources, String gold) {
            n++;
            int rank = retrievedSources.indexOf(gold);   // 0-based, 없으면 -1
            if (rank >= 0) {
                recallSum += 1;
                mrrSum += 1.0 / (rank + 1);
            }
        }

        void print() {
            System.out.printf("%-22s %10.3f %8.3f%n", name, recallSum / n, mrrSum / n);
        }
    }
}
