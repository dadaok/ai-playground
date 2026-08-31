# 5단계: MCP 계산기 서버 연결하기

`McpCalculatorServer.java` 는 3단계의 `calculator` 도구를 **독립 MCP 서버**로 빼낸 것이다.
이제 이 서버 하나를 여러 호스트가 각자 꽂아 쓴다.

## 0. 빌드

```bash
./gradlew mcpJar
# -> build/libs/mcp-calc-server.jar
```

## 1. 손으로 프로토콜 보기 (SDK·호스트 없이)

JSON-RPC 메시지를 직접 파이프로 넣어본다. stdout 에는 순수 JSON-RPC 만 나온다:

```bash
printf '%s\n' \
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}' \
'{"jsonrpc":"2.0","method":"notifications/initialized"}' \
'{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
'{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"calculator","arguments":{"op":"*","a":128,"b":7}}}' \
| java -jar build/libs/mcp-calc-server.jar
```

`2>/dev/null` 을 붙이면 `[mcp]` 로그(stderr)가 사라지고 프로토콜만 보인다.
이게 MCP 의 전부다: **파이프 + 줄 단위 JSON-RPC**.

## 2. Claude Code 에 연결

이 폴더에 `.mcp.json` 이 이미 있다. Claude Code 를 **이 디렉터리에서** 실행하면
`calc-java` 서버를 자동 인식한다. (처음엔 신뢰 여부를 물어봄)

```bash
cd /Users/hip-jilly/IdeaProjects/ai-playground
claude
```

확인:
- `/mcp` 입력 → `calc-java` 가 connected 로 뜨는지
- "calculator 도구로 128 곱하기 7 해줘" → 도구 호출되는지
- 다른 창에서 `tail -f` 할 순 없지만, Claude Code 의 `/mcp` 에서 상태 확인 가능

수동 등록도 가능:
```bash
claude mcp add calc-java -- java -jar /Users/hip-jilly/IdeaProjects/ai-playground/build/libs/mcp-calc-server.jar
```

## 3. Claude Desktop 에 연결

`~/Library/Application Support/Claude/claude_desktop_config.json` 을 열어(없으면 생성):

```json
{
  "mcpServers": {
    "calc-java": {
      "command": "java",
      "args": [
        "-jar",
        "/Users/hip-jilly/IdeaProjects/ai-playground/build/libs/mcp-calc-server.jar"
      ]
    }
  }
}
```

저장 후 Claude Desktop 재시작 → 입력창 아래 도구(🔨) 아이콘에 `calculator` 가 보이면 성공.

## 4. 핵심 정리

| | 3단계 (`Step3ManualLoop`) | 5단계 (MCP) |
|---|---|---|
| calculator 도구 위치 | 그 프로그램 안에 하드코딩 | 독립 프로세스(서버) |
| 누가 쓸 수 있나 | 그 프로그램만 | Claude Code + Desktop + 내 에이전트 (동시에) |
| 연결 방식 | 자바 메서드 호출 | stdio 파이프 위 JSON-RPC |
| 도구 추가 시 | 각 앱을 수정 | 서버 한 곳만 수정 → 모든 앱에 반영 |

MCP = "도구/데이터 소스를 LLM 앱에 꽂는 **표준 규격**". JDBC 드라이버처럼.
전송(stdio/HTTP)과 메시지(JSON-RPC)만 표준으로 맞추면 아무 호스트나 붙는다.
