package playground.cafe;

import playground.rag.Corpus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 정책 문서(docs/) 위의 아주 단순한 키워드 검색.
 *
 * 4편(블로그)의 TF-IDF 를 더 단순화한 버전: 겹치는 토큰 개수만 센다.
 * 목적은 정교함이 아니라 "단어가 안 겹치면 못 찾는다"는 한계를 보여주는 것.
 */
final class PolicySearch {

    private static final List<Corpus.Chunk> CHUNKS = Corpus.load(Path.of("docs"));

    private PolicySearch() {}

    /** score > 0 인 것만, 점수 내림차순 top-k. 하나도 안 겹치면 빈 리스트. */
    static List<Corpus.Chunk> search(String query, int k) {
        Set<String> qTokens = tokenize(query);
        List<double[]> scored = new ArrayList<>(); // [index, score]
        for (int i = 0; i < CHUNKS.size(); i++) {
            Set<String> dTokens = tokenize(CHUNKS.get(i).content());
            long overlap = qTokens.stream().filter(dTokens::contains).count();
            if (overlap > 0) scored.add(new double[]{i, overlap});
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble((double[] a) -> a[1]).reversed())
                .limit(k)
                .map(a -> CHUNKS.get((int) a[0]))
                .toList();
    }

    /** 단어 + 한글 2글자 조각(bigram). 4편 블로그와 같은 방식. */
    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String raw : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (raw.isBlank()) continue;
            tokens.add(raw);
            boolean hangul = raw.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
            if (hangul && raw.length() >= 2) {
                for (int i = 0; i + 2 <= raw.length(); i++) tokens.add(raw.substring(i, i + 2));
            }
        }
        return tokens;
    }
}
