plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // 공식 Anthropic 자바 SDK
    implementation("com.anthropic:anthropic-java:2.34.0")

    // 2단계 도구 정의에 쓰는 Jackson 애노테이션 (@JsonClassDescription 등)
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
    // 5단계 MCP 서버에서 JSON-RPC 를 직접 다루는 데 쓰는 Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // `./gradlew run` 이 실행하는 클래스
    mainClass = "playground.Step1Basic"
}

// `./gradlew step2` 로 2단계 실행
tasks.register<JavaExec>("step2") {
    group = "application"
    description = "2단계: 도구 사용(tool use) 예제 실행"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "playground.Step2ToolLoop"
}

// `./gradlew step3` 로 3단계 실행
tasks.register<JavaExec>("step3") {
    group = "application"
    description = "3단계: 에이전트 루프 직접 구현"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "playground.Step3ManualLoop"
}

// `./gradlew step4` 로 4단계 실행
tasks.register<JavaExec>("step4") {
    group = "application"
    description = "4단계: RAG"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "playground.Step4Rag"
}

// `./gradlew step6` 로 6단계 실행 (stdin 승인 입력을 위해 표준입력 연결)
tasks.register<JavaExec>("step6") {
    group = "application"
    description = "6단계: 미니 harness (레지스트리/권한/컨텍스트관리/MCP 통합)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "playground.Step6Harness"
    standardInput = System.`in`
}

// 5단계: MCP 서버를 실행 가능한 단일 jar 로 패키징한다.
// `./gradlew mcpJar` -> build/libs/mcp-calc-server.jar
// MCP 호스트(Claude Desktop/Claude Code)가 `java -jar` 로 이 파일을 실행한다.
tasks.register<Jar>("mcpJar") {
    group = "application"
    description = "5단계: MCP 계산기 서버 fat jar"
    archiveBaseName = "mcp-calc-server"
    archiveVersion = ""
    manifest { attributes["Main-Class"] = "playground.McpCalculatorServer" }
    from(sourceSets["main"].output)
    dependsOn(configurations["runtimeClasspath"])
    from({
        configurations["runtimeClasspath"].filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
