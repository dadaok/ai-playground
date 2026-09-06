package playground.rag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pgvector(Postgres) 위의 검색 계층.
 *
 * 핵심: 벡터 검색이 "읽을 수 있는 SQL" 이 된다.
 *   ORDER BY embedding <=> :query    (<=> 는 pgvector 가 추가한 코사인 거리 연산자)
 *
 * 4편의 손수 만든 TF-IDF 검색을, 진짜 임베딩 + 진짜 인덱스로 대체한 것.
 */
public class RagStore implements AutoCloseable {

    public record Hit(long id, String source, String content, double score) {}

    private static final String URL = "jdbc:postgresql://localhost:5433/rag";
    private final Connection conn;

    public RagStore() {
        try {
            this.conn = DriverManager.getConnection(URL, "rag", "rag");
        } catch (SQLException e) {
            throw new RuntimeException(
                    "pgvector 에 연결 실패. `colima start && docker-compose up -d` 했는지 확인하세요. " + e.getMessage(), e);
        }
    }

    // ── 적재 ────────────────────────────────────────────────────────────

    /** 코퍼스를 통째로 다시 적재한다: 전부 임베딩(document) → INSERT. */
    public void reload(List<Corpus.Chunk> chunks, VoyageClient voyage) {
        try {
            conn.createStatement().execute("TRUNCATE chunks RESTART IDENTITY");

            List<String> texts = chunks.stream().map(Corpus.Chunk::content).toList();
            List<float[]> vectors = voyage.embed(texts, "document");   // 배치로 한 번에

            String sql = "INSERT INTO chunks (source, content, embedding) VALUES (?, ?, ?::vector)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunks.size(); i++) {
                    ps.setString(1, chunks.get(i).source());
                    ps.setString(2, chunks.get(i).content());
                    ps.setString(3, vecLiteral(vectors.get(i)));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── 검색 3종 ────────────────────────────────────────────────────────

    /** (1) 의미 검색: 임베딩 코사인 거리 오름차순. */
    public List<Hit> vectorSearch(float[] queryVec, int k) {
        String sql = """
                SELECT id, source, content, 1 - (embedding <=> ?::vector) AS score
                FROM chunks
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            String v = vecLiteral(queryVec);
            ps.setString(1, v);
            ps.setString(2, v);
            ps.setInt(3, k);
            return read(ps);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** (2) 키워드 검색: Postgres 전문검색(tsvector) + ts_rank. */
    public List<Hit> keywordSearch(String queryText, int k) {
        String sql = """
                SELECT id, source, content, ts_rank(ts, plainto_tsquery('simple', ?)) AS score
                FROM chunks
                WHERE ts @@ plainto_tsquery('simple', ?)
                ORDER BY score DESC
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, queryText);
            ps.setString(2, queryText);
            ps.setInt(3, k);
            return read(ps);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * (3) 하이브리드: 벡터 top-N + 키워드 top-N 을 RRF(Reciprocal Rank Fusion)로 합침.
     *   score(doc) = Σ 1 / (K + rank_in_list)     (K=60 관례)
     * 순위만 쓰므로 서로 다른 점수 체계를 섞어도 안전하다.
     */
    public List<Hit> hybrid(String queryText, float[] queryVec, int k) {
        int pool = Math.max(k * 4, 20);
        List<Hit> vec = vectorSearch(queryVec, pool);
        List<Hit> kw = keywordSearch(queryText, pool);

        final int K = 60;
        Map<Long, Double> fused = new LinkedHashMap<>();
        Map<Long, Hit> byId = new LinkedHashMap<>();
        for (int r = 0; r < vec.size(); r++) {
            Hit h = vec.get(r);
            fused.merge(h.id(), 1.0 / (K + r + 1), Double::sum);
            byId.putIfAbsent(h.id(), h);
        }
        for (int r = 0; r < kw.size(); r++) {
            Hit h = kw.get(r);
            fused.merge(h.id(), 1.0 / (K + r + 1), Double::sum);
            byId.putIfAbsent(h.id(), h);
        }

        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(k)
                .map(e -> {
                    Hit h = byId.get(e.getKey());
                    return new Hit(h.id(), h.source(), h.content(), e.getValue());
                })
                .toList();
    }

    // ── 유틸 ────────────────────────────────────────────────────────────

    private static List<Hit> read(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Hit> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Hit(rs.getLong("id"), rs.getString("source"),
                        rs.getString("content"), rs.getDouble("score")));
            }
            return out;
        }
    }

    /** float[] -> pgvector 리터럴 문자열  "[0.1,0.2,...]" */
    static String vecLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
