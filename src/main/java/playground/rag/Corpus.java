package playground.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** docs/ 폴더의 .txt 를 읽어 문단 단위 청크로 쪼갠다. (4편과 동일한 코퍼스) */
public final class Corpus {

    public record Chunk(String source, String content) {}

    private Corpus() {}

    public static List<Chunk> load(Path docsDir) {
        try (Stream<Path> files = Files.list(docsDir)) {
            List<Chunk> out = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                for (String para : content.split("\\n\\s*\\n")) {
                    String t = para.strip();
                    if (!t.isBlank()) out.add(new Chunk(p.getFileName().toString(), t));
                }
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("docs/ 를 읽지 못했습니다. 프로젝트 루트에서 실행하세요.", e);
        }
    }
}
