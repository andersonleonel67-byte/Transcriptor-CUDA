package transcriptor.app;

import java.nio.file.Path;

public record TranscriptionOptions(
    Path outputDirectory,
    Path modelCacheDirectory,
    String language,
    String model,
    String devicePreference
) {
}

