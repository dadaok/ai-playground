package playground.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Voyage AI 클라이언트 (임베딩 + 리랭킹).
 *
 * 구조는 Claude API 호출과 똑같다: 키를 헤더에 넣고 HTTP POST, JSON 응답 파싱.
 * Anthropic 은 임베딩 API 가 없어서 RAG 에서는 보통 Voyage 를 쓴다.
 *
 * 키:  export VOYAGE_API_KEY=pa-...
 *      (https://dashboard.voyageai.com 에서 발급, 가입 시 무료 크레딧)
 */
public class VoyageClient {

    private static final String BASE = "https://api.voyageai.com/v1";
    public static final int DIM = 1024;                 // voyage-3.5 기본 차원

    private final ObjectMapper M = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;

    public VoyageClient() {
        this.apiKey = System.getenv("VOYAGE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("VOYAGE_API_KEY 환경변수가 없습니다. https://dashboard.voyageai.com");
        }
    }

    /**
     * 텍스트들을 벡터로.
     * @param inputType "document"(적재할 문서) 또는 "query"(검색 질의) — 모델이 둘을 다르게 인코딩한다.
     */
    public List<float[]> embed(List<String> texts, String inputType) {
        ObjectNode body = M.createObjectNode();
        body.put("model", "voyage-3.5");
        body.put("input_type", inputType);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);

        JsonNode resp = post("/embeddings", body);
        List<float[]> out = new ArrayList<>();
        for (JsonNode row : resp.path("data")) {
            JsonNode arr = row.path("embedding");
            float[] v = new float[arr.size()];
            for (int i = 0; i < v.length; i++) v[i] = (float) arr.get(i).asDouble();
            out.add(v);
        }
        return out;
    }

    public float[] embedOne(String text, String inputType) {
        return embed(List.of(text), inputType).get(0);
    }

    /** 리랭킹 결과 한 건: 원본 documents 리스트에서의 인덱스 + 관련도 점수. */
    public record Ranked(int index, double score) {}

    /**
     * 질의에 대해 후보 문서들을 다시 정렬한다.
     * 벡터 검색(양방향 임베딩의 코사인)보다 정확한 cross-encoder 방식. 대신 느리고 비싸서
     * "싸게 많이 뽑고 → 비싸게 조금 재정렬" 패턴으로 쓴다.
     */
    public List<Ranked> rerank(String query, List<String> documents, int topK) {
        ObjectNode body = M.createObjectNode();
        body.put("model", "rerank-2.5");
        body.put("query", query);
        body.put("top_k", topK);
        ArrayNode docs = body.putArray("documents");
        documents.forEach(docs::add);

        JsonNode resp = post("/rerank", body);
        List<Ranked> out = new ArrayList<>();
        for (JsonNode row : resp.path("data")) {
            out.add(new Ranked(row.path("index").asInt(), row.path("relevance_score").asDouble()));
        }
        return out;
    }

    private JsonNode post(String path, JsonNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new RuntimeException("Voyage " + path + " -> " + res.statusCode() + ": " + res.body());
            }
            return M.readTree(res.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
