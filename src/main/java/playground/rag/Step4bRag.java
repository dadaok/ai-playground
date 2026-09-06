package playground.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.nio.file.Path;
import java.util.List;

/**
 * 5b단계: 실무형 RAG.
 *
 * 4편(장난감: 키워드 TF-IDF)을 실제 스택으로 교체한다.
 *   - 임베딩:    Voyage voyage-3.5 (hosted, Claude API 와 같은 패턴)
 *   - 벡터 DB:   pgvector (Postgres) — 벡터 검색이 SQL 이 됨
 *   - 검색:      의미 검색 → 하이브리드(+키워드) → 리랭킹
 *
 * 파이프라인을 단계별로 출력한다. 각 단계가 무엇을 바꾸는지 눈으로 본다.
 *
 * 선행:
 *   colima start && docker-compose up -d
 *   export ANTHROPIC_API_KEY=sk-ant-...
 *   export VOYAGE_API_KEY=pa-...
 * 실행:  ./gradlew step4b
 */
public class Step4bRag {

    private static final Path DOCS = Path.of("docs");

    public static void main(String[] args) {
        VoyageClient voyage = new VoyageClient();
        AnthropicClient claude = AnthropicOkHttpClient.fromEnv();

        try (RagStore store = new RagStore()) {

            // ── 1. 텍스트 → 벡터 ────────────────────────────────────────
            System.out.println("### 1. 임베딩이란: 텍스트 → 고정 길이 숫자 배열");
            float[] demo = voyage.embedOne("구독 해지하면 환불되나요?", "query");
            System.out.printf("  \"구독 해지하면 환불되나요?\" → %d차원 벡터%n", demo.length);
            System.out.printf("  앞 8개: [%.4f, %.4f, %.4f, %.4f, %.4f, %.4f, %.4f, %.4f, ...]%n%n",
                    demo[0], demo[1], demo[2], demo[3], demo[4], demo[5], demo[6], demo[7]);

            // ── 2. 적재: 코퍼스 전체 임베딩 → pgvector ──────────────────
            System.out.println("### 2. 적재: docs/ 를 청크로 쪼개 전부 임베딩 후 INSERT");
            List<Corpus.Chunk> chunks = Corpus.load(DOCS);
            store.reload(chunks, voyage);
            System.out.printf("  %d개 청크를 chunks 테이블에 저장 (각 행에 vector(1024) + tsvector)%n%n", chunks.size());

            String question = "구독을 중간에 그만두면 이미 낸 돈은 어떻게 되나요?";
            System.out.println("질문: " + question + "\n");
            float[] qVec = voyage.embedOne(question, "query");

            // ── 3. 의미 검색 (벡터) ────────────────────────────────────
            System.out.println("### 3. 의미 검색 — ORDER BY embedding <=> :query");
            System.out.println("  질문에 '환불'이라는 단어가 없어도 의미로 찾는다.");
            print(store.vectorSearch(qVec, 3));

            // ── 4. 하이브리드 (벡터 + 키워드, RRF) ─────────────────────
            System.out.println("### 4. 하이브리드 — 벡터 + 키워드(tsvector) 를 RRF 로 합침");
            System.out.println("  키워드 단독:");
            print(store.keywordSearch(question, 3));
            System.out.println("  하이브리드:");
            List<RagStore.Hit> hybrid = store.hybrid(question, qVec, 8);
            print(hybrid.subList(0, Math.min(3, hybrid.size())));

            // ── 5. 리랭킹 ─────────────────────────────────────────────
            System.out.println("### 5. 리랭킹 — 하이브리드 top-8 을 cross-encoder 로 재정렬");
            List<String> candidates = hybrid.stream().map(RagStore.Hit::content).toList();
            List<VoyageClient.Ranked> ranked = voyage.rerank(question, candidates, 3);
            List<RagStore.Hit> finalHits = ranked.stream().map(r -> {
                RagStore.Hit h = hybrid.get(r.index());
                return new RagStore.Hit(h.id(), h.source(), h.content(), r.score());
            }).toList();
            System.out.println("  리랭킹 후 top-3 (점수는 rerank relevance):");
            print(finalHits);

            // ── 6. 생성 ───────────────────────────────────────────────
            System.out.println("### 6. 생성 — 최종 top-3 을 근거로 Claude 답변");
            System.out.println(answer(claude, finalHits, question));
        }
    }

    private static void print(List<RagStore.Hit> hits) {
        for (RagStore.Hit h : hits) {
            String preview = h.content().replaceAll("\\s+", " ");
            if (preview.length() > 70) preview = preview.substring(0, 70) + "…";
            System.out.printf("  [%.4f] %-14s %s%n", h.score(), h.source(), preview);
        }
        System.out.println();
    }

    private static String answer(AnthropicClient claude, List<RagStore.Hit> hits, String question) {
        StringBuilder ctx = new StringBuilder();
        int i = 1;
        for (RagStore.Hit h : hits) {
            ctx.append("[").append(i++).append("] (출처: ").append(h.source()).append(")\n")
               .append(h.content()).append("\n\n");
        }
        Message resp = claude.messages().create(MessageCreateParams.builder()
                .model("claude-haiku-4-5")
                .maxTokens(500L)
                .system("아래 <자료> 안의 내용만 근거로 한국어로 간결히 답하라. 자료에 없으면 '자료에서 확인되지 않습니다'.")
                .addUserMessage("<자료>\n" + ctx.toString().strip() + "\n</자료>\n\n질문: " + question)
                .build());
        return resp.content().stream().flatMap(b -> b.text().stream())
                .map(t -> t.text()).reduce("", String::concat).strip();
    }
}
