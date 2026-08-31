# ai-playground

자바 개발자를 위한 AI 개념 연습 프로젝트. Agent / Tool use / RAG / MCP 를 직접 만들면서 익힌다.

## 0. API 키 발급 (Claude 계정과 다른 것)

**claude.ai 구독(Pro/Max)** 과 **API 키** 는 완전히 별개의 결제·시스템이다.

| | claude.ai 웹/앱 | Anthropic API |
|---|---|---|
| 무엇 | 사람이 브라우저에서 채팅하는 제품 | 내 코드가 HTTP 로 모델을 호출 |
| 로그인 | 계정 이메일/비번 | `x-api-key` 헤더에 API 키 |
| 결제 | 월 구독 (정액) | 사용한 토큰만큼 과금 (선불 크레딧) |
| 어디서 | claude.ai | **console.anthropic.com** |

Pro 구독이 있어도 API 크레딧은 따로 충전해야 한다. 반대로 API 키만 있고 구독은 없어도 된다.

### 발급 순서
1. https://console.anthropic.com 접속 → 가입 (claude.ai 계정과 같은 이메일로 로그인 가능하지만, 조직/결제는 별도)
2. 왼쪽 **Billing** → 결제수단 등록 → 크레딧 충전 (연습용이면 $5 로 충분)
3. **API keys** → `Create Key` → 키 복사 (`sk-ant-...`). **이때 한 번만 보여준다.**
4. 터미널에서 환경변수로 등록:

```bash
# 이번 셸에서만
export ANTHROPIC_API_KEY=sk-ant-...

# 매번 자동으로 하려면 ~/.zshrc 에 위 줄을 추가
```

IntelliJ 에서 실행할 때는 Run/Debug Configuration → Environment variables 에
`ANTHROPIC_API_KEY=sk-ant-...` 를 넣어도 된다.

> 키는 절대 git 에 커밋하지 말 것. (`.gitignore` 에 `.env` 를 넣어뒀다)

## 1. 프로젝트 열기

IntelliJ 에서 `ai-playground` 폴더를 **Open** 하면 Gradle 프로젝트로 자동 인식하고
의존성(Anthropic SDK)을 내려받는다. Gradle wrapper 도 IntelliJ 가 알아서 만든다.

CLI 로 실행하고 싶으면 IntelliJ 가 wrapper 를 만든 뒤 `./gradlew` 를 쓸 수 있다.

## 2. 단계별 실습

| 단계 | 파일 | 실행 | 배우는 것 |
|---|---|---|---|
| 1 | `Step1Basic.java` | `./gradlew run` | stateless API 호출, messages/role, system 프롬프트, 토큰 |
| 2 | `Step2ToolLoop.java` | `./gradlew step2` | tool use = 에이전트의 루프 (SDK 헬퍼가 루프를 돌림) |
| 3 | `Step3ManualLoop.java` | `./gradlew step3` | 그 루프를 헬퍼 없이 직접 구현 (create → stop_reason → tool_result → 반복) |
| 4 | `Step4Rag.java` + `docs/` | `./gradlew step4` | RAG: 문서 청크 분할 → 유사도 검색(top-k) → 프롬프트에 삽입. RAG 유/무 답변 비교 |
| 5 | `McpCalculatorServer.java` | `./gradlew mcpJar` | calculator 도구를 독립 MCP 서버로 분리. JSON-RPC 직접 구현. 연결법은 `MCP_SERVER_TEST.md` |
| 6 | `Step6Harness.java` + `McpStdioClient.java` | `./gradlew mcpJar && ./gradlew step6` | 미니 harness: 툴 레지스트리 + 권한 게이트 + 컨텍스트 압축 + MCP 통합. 3단계 루프에 실전 장치를 두름 |

### 더 볼 것
- **Claude Code 자체가 harness다.** 6단계에서 만든 것들을 실제로 확인: `/context`(컨텍스트 관리),
  파일 수정 시 권한 프롬프트, 서브에이전트, `/compact`(수동 압축), `/mcp`(MCP 서버 상태).
- Claude **Agent SDK** = Claude Code 를 라이브러리로 쓰는 것 (현재 자바 미지원, Python/TS).

## 참고
- 모델 ID 는 `claude-haiku-4-5` (가장 저렴) 로 넣어뒀다. 더 똑똑한 답이 필요하면
  `claude-sonnet-5` 나 `claude-opus-5` 로 바꾸면 된다.
- 토큰 요금: 콘솔의 Usage 페이지에서 실시간 확인.
