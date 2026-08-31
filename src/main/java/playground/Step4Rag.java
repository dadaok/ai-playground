package playground;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 4단계: RAG (Retrieval-Augmented Generation).
 *
 * 아이디어는 한 문장이다:
 *   "모델에게 물어보기 전에, 관련 문서를 찾아서 프롬프트에 붙여넣는다."
 *
 * 왜 필요한가?
 *   - 모델은 우리 회사 내부 문서(docs/ 폴더)를 모른다. 학습 데이터에 없으니까.
 *   - 그냥 물으면 그럴듯하게 지어낸다(hallucination).
 *   - 그래서: 질문과 비슷한 문서 조각을 검색 → 그 조각만 근거로 답하게 시킨다.
 *
 * RAG 파이프라인 (아래 코드가 이 순서대로 한다):
 *   1. 문서 로드 & 청크(chunk) 분할        loadChunks()
 *   2. 질문으로 관련 청크 검색 (top-k)     retrieve()
 *   3. 검색된 청크를 프롬프트에 삽입해 LLM 호출   answerWithContext()
 *
 * 참고: 진짜 RAG 는 2번에서 "임베딩(embedding) 벡터 + 벡터DB" 를 쓴다.
 *       Anthropic 은 임베딩 API 가 없어서 보통 Voyage AI 를 쓴다.
 *       여기서는 개념에 집중하려고 2번을 간단한 키워드 유사도(TF-IDF 코사인)로 구현했다.
 *       구조는 완전히 동일하다 — retrieve() 내부만 임베딩 검색으로 갈아끼우면 된다.
 *
 * 실행:  ./gradlew step4
 */
public class Step4Rag {

    private static final Path DOCS_DIR = Path.of("docs");
    private static final int TOP_K = 3;

    record Chunk(String source, String text) {}

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        List<Chunk> chunks = loadChunks();
        System.out.println("문서 " + chunks.size() + " 청크 로드됨 (" + DOCS_DIR.toAbsolutePath() + ")\n");

        Index index = Index.build(chunks);

        List<String> questions = List.of(
                "구독 해지하면 환불은 언제 돼?",
                "디카페인 원두도 팔아?",
                "고객센터는 몇 시까지 해?",
                "매장에서 커피 마실 수 있어?"  // 자료에 명확한 답이 없는 질문
        );

        for (String q : questions) {
            System.out.println("############################################################");
            System.out.println("질문: " + q);
            System.out.println();

            // (A) RAG 없이 — 그냥 물어본다
            System.out.println("── RAG 없음 (모델의 순수 추측) ──");
            System.out.println(callModel(client, null, q));
            System.out.println();

            // (B) RAG 있음 — 검색 후 근거를 붙여서 물어본다
            List<Scored> hits = index.retrieve(q, TOP_K);
            System.out.println("── 검색된 청크 (top " + TOP_K + ") ──");
            for (Scored s : hits) {
                System.out.printf("  [%.3f] %s: %s%n", s.score(),
                        s.chunk().source(), preview(s.chunk().text()));
            }
            String context = buildContext(hits);
            System.out.println();
            System.out.println("── RAG 있음 (검색 결과를 근거로) ──");
            System.out.println(callModel(client, context, q));
            System.out.println();
        }
    }

    // ── 1. 문서 로드 & 청크 분할 ────────────────────────────────────────────

    private static List<Chunk> loadChunks() {
        try (Stream<Path> files = Files.list(DOCS_DIR)) {
            List<Chunk> out = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                // 빈 줄 기준으로 문단 = 청크. (진짜 RAG 도 보통 이렇게 문단/문장 단위로 쪼갠다)
                for (String para : content.split("\\n\\s*\\n")) {
                    String t = para.strip();
                    if (!t.isBlank()) {
                        out.add(new Chunk(p.getFileName().toString(), t));
                    }
                }
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("docs/ 폴더를 읽지 못했습니다. 프로젝트 루트에서 실행하세요.", e);
        }
    }

    // ── 2. 검색: TF-IDF 코사인 유사도 ──────────────────────────────────────

    record Scored(Chunk chunk, double score) {}

    /** 아주 작은 역색인. retrieve() 안쪽만 임베딩 검색으로 바꾸면 '진짜' RAG 가 된다. */
    static class Index {
        private final List<Chunk> chunks;
        private final List<Map<String, Integer>> termFreqs; // 청크별 단어 빈도
        private final Map<String, Double> idf;              // 단어별 희소성 가중치

        private Index(List<Chunk> chunks, List<Map<String, Integer>> tf, Map<String, Double> idf) {
            this.chunks = chunks;
            this.termFreqs = tf;
            this.idf = idf;
        }

        static Index build(List<Chunk> chunks) {
            List<Map<String, Integer>> tf = new ArrayList<>();
            Map<String, Integer> docFreq = new HashMap<>();
            for (Chunk c : chunks) {
                Map<String, Integer> counts = new HashMap<>();
                for (String tok : tokenize(c.text())) {
                    counts.merge(tok, 1, Integer::sum);
                }
                tf.add(counts);
                for (String term : counts.keySet()) {
                    docFreq.merge(term, 1, Integer::sum);
                }
            }
            int n = chunks.size();
            Map<String, Double> idf = new HashMap<>();
            docFreq.forEach((term, df) -> idf.put(term, Math.log(1.0 + (double) n / df)));
            return new Index(chunks, tf, idf);
        }

        List<Scored> retrieve(String query, int k) {
            Map<String, Integer> qCounts = new HashMap<>();
            for (String tok : tokenize(query)) qCounts.merge(tok, 1, Integer::sum);

            double qNorm = norm(qCounts);
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Integer> dCounts = termFreqs.get(i);
                double dot = 0;
                for (Map.Entry<String, Integer> e : qCounts.entrySet()) {
                    Integer dtf = dCounts.get(e.getKey());
                    if (dtf == null) continue;
                    double w = idf.getOrDefault(e.getKey(), 0.0);
                    dot += (e.getValue() * w) * (dtf * w);
                }
                double dNorm = norm(dCounts);
                double cos = (qNorm == 0 || dNorm == 0) ? 0 : dot / (qNorm * dNorm);
                scored.add(new Scored(chunks.get(i), cos));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            return scored.subList(0, Math.min(k, scored.size()));
        }

        private double norm(Map<String, Integer> counts) {
            double sum = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                double w = e.getValue() * idf.getOrDefault(e.getKey(), 0.0);
                sum += w * w;
            }
            return Math.sqrt(sum);
        }
    }

    /**
     * 토큰화. 한국어는 조사("환불은", "몇 시까지") 때문에 단어가 딱 안 맞는다.
     * 그래서 (1) 공백/문장부호로 자른 단어 + (2) 한글 단어의 2글자 조각(bigram) 을 함께 쓴다.
     * "환불은" -> [환불은, 환불, 불은] -> 문서의 "환불" 과 매칭됨.
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String raw : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (raw.isBlank()) continue;
            tokens.add(raw);
            boolean hangul = raw.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
            if (hangul && raw.length() >= 2) {
                for (int i = 0; i + 2 <= raw.length(); i++) {
                    tokens.add(raw.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    // ── 3. 검색 결과를 프롬프트에 넣어 LLM 호출 ────────────────────────────

    private static String buildContext(List<Scored> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Scored s : hits) {
            sb.append("[").append(i++).append("] (출처: ").append(s.chunk().source()).append(")\n");
            sb.append(s.chunk().text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String callModel(AnthropicClient client, String context, String question) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model("claude-haiku-4-5")
                .maxTokens(500L);

        if (context == null) {
            b.system("너는 달빛커피 고객센터 상담원이다. 한국어로 간결히 답해라.");
            b.addUserMessage(question);
        } else {
            b.system("너는 달빛커피 고객센터 상담원이다. 아래 <자료> 안의 내용만 근거로 답해라. "
                    + "자료에 없는 내용은 지어내지 말고 '자료에서 확인되지 않습니다'라고 답해라. 한국어로 간결히.");
            b.addUserMessage("<자료>\n" + context + "\n</자료>\n\n질문: " + question);
        }

        Message resp = client.messages().create(b.build());
        StringBuilder out = new StringBuilder();
        resp.content().stream().flatMap(cb -> cb.text().stream())
                .forEach(t -> out.append(t.text()));
        return out.toString().strip();
    }

    private static String preview(String s) {
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }
}
