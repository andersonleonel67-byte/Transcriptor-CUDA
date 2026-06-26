package transcriptor.app;

import java.nio.file.Path;

public final class TranscriptionJob {
    public enum Status {
        QUEUED("En cola"),
        RUNNING("Procesando"),
        DONE("Listo"),
        FAILED("Error"),
        CANCELED("Cancelado");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Path inputPath;
    private volatile Status status = Status.QUEUED;
    private volatile String transcriptText = "";
    private volatile String errorMessage = "";
    private volatile TranscriptionResult result;

    public TranscriptionJob(Path inputPath) {
        this.inputPath = inputPath;
    }

    public Path inputPath() {
        return inputPath;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String transcriptText() {
        return transcriptText;
    }

    public void appendTranscript(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        transcriptText = transcriptText.isBlank() ? text.trim() : transcriptText + System.lineSeparator() + text.trim();
    }

    public void clearTranscript() {
        transcriptText = "";
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public TranscriptionResult result() {
        return result;
    }

    public void setResult(TranscriptionResult result) {
        this.result = result;
    }
}

