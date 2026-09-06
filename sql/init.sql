-- 5b단계: 벡터 검색 스키마
-- 이 파일은 컨테이너 최초 생성 시 자동 실행된다.

CREATE EXTENSION IF NOT EXISTS vector;

-- 청크 한 개 = 한 행.
--  embedding : Voyage voyage-3.5 (1024차원) 임베딩 벡터
--  ts        : 키워드(전문) 검색용. 'simple' = 형태소 분석 없이 공백/구두점 분리
--              (한국어 형태소 사전이 기본 탑재 안 되어 있어 데모는 simple 로 충분)
CREATE TABLE IF NOT EXISTS chunks (
    id        bigserial PRIMARY KEY,
    source    text NOT NULL,
    content   text NOT NULL,
    embedding vector(1024),
    ts        tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED
);

-- 벡터 검색용 근사 최근접(ANN) 인덱스. 코사인 거리(<=>) 기준.
CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw
    ON chunks USING hnsw (embedding vector_cosine_ops);

-- 키워드 검색용 역색인
CREATE INDEX IF NOT EXISTS chunks_ts_gin
    ON chunks USING gin (ts);
