package transcriptor.app;

import java.nio.file.Path;

public record TranscriptionResult(
    Path txtPath,
    Path srtPath,
    Path jsonPath,
    String language,
    double durationSeconds,
    String device,
    String computeType,
    int segmentCount
) {
}

