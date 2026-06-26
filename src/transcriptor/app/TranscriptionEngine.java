package transcriptor.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.net.URLDecoder;

public final class TranscriptionEngine {
    public interface TranscriptionListener {
        void onStatus(String message);
        void onEngineInfo(String device, String computeType, String model);
        void onProgress(double progress, double processedSeconds, double totalSeconds);
        void onSegment(double startSeconds, double endSeconds, String text);
        void onResult(TranscriptionResult result);
        void onError(String message);
        void onLog(String line);
    }

    private final Path appRoot;
    private volatile Process activeProcess;

    public TranscriptionEngine(Path appRoot) {
        this.appRoot = appRoot;
    }

    public void cancelActiveProcess() {
        Process process = activeProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public TranscriptionResult transcribe(
        TranscriptionJob job,
        TranscriptionOptions options,
        AtomicBoolean cancelRequested,
        TranscriptionListener listener
    ) throws IOException, InterruptedException {
        Files.createDirectories(options.outputDirectory());
        if (options.modelCacheDirectory() != null) {
            Files.createDirectories(options.modelCacheDirectory());
        }

        List<String> command = new ArrayList<>();
        command.add(resolvePythonCommand());
        command.add("-u");
        command.add(appRoot.resolve("backend").resolve("transcribe.py").toString());
        command.add("--input");
        command.add(job.inputPath().toString());
        command.add("--output-dir");
        command.add(options.outputDirectory().toString());
        command.add("--language");
        command.add(options.language());
        command.add("--model");
        command.add(options.model());
        command.add("--device");
        command.add(options.devicePreference());
        command.add("--model-cache");
        command.add(options.modelCacheDirectory().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(appRoot.toFile());
        builder.redirectErrorStream(false);
        augmentNativeLibraryPath(builder.environment());

        AtomicReference<TranscriptionResult> resultRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        Process process = builder.start();
        activeProcess = process;

        Thread stdoutThread = Thread.ofVirtual().name("transcriptor-stdout").start(() ->
            readLines(process.getInputStream(), line -> handleStdoutLine(line, resultRef, errorRef, listener))
        );
        Thread stderrThread = Thread.ofVirtual().name("transcriptor-stderr").start(() ->
            readLines(process.getErrorStream(), line -> listener.onLog("[backend] " + line))
        );

        while (process.isAlive()) {
            if (cancelRequested.get()) {
                listener.onStatus("Cancelando el backend activo");
                cancelActiveProcess();
                break;
            }
            Thread.sleep(200);
        }

        int exitCode = process.waitFor();
        stdoutThread.join();
        stderrThread.join();
        activeProcess = null;

        if (cancelRequested.get()) {
            throw new InterruptedException("Proceso cancelado por el usuario.");
        }
        if (exitCode != 0) {
            throw new IOException(Objects.requireNonNullElse(errorRef.get(), "El backend terminó con error " + exitCode));
        }

        TranscriptionResult result = resultRef.get();
        if (result == null) {
            throw new IOException("El backend terminó sin devolver archivos de salida.");
        }
        return result;
    }

    private String resolvePythonCommand() {
        Path localPython = appRoot.resolve(".venv").resolve("Scripts").resolve("python.exe");
        if (Files.exists(localPython)) {
            return localPython.toString();
        }
        String envPython = System.getenv("TRANSCRIPTOR_PYTHON");
        if (envPython != null && !envPython.isBlank()) {
            return envPython;
        }
        return "python";
    }

    private void augmentNativeLibraryPath(Map<String, String> environment) {
        List<String> prefixes = discoverNvidiaRuntimePaths();
        if (prefixes.isEmpty()) {
            return;
        }
        String existingPath = environment.getOrDefault("PATH", "");
        String joinedPrefixes = String.join(";", prefixes);
        environment.put("PATH", existingPath.isBlank() ? joinedPrefixes : joinedPrefixes + ";" + existingPath);
    }

    private List<String> discoverNvidiaRuntimePaths() {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Path cudaRoot = Path.of("C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA");
        if (Files.isDirectory(cudaRoot)) {
            try (var versions = Files.list(cudaRoot).filter(Files::isDirectory).sorted(cudaVersionComparator())) {
                versions.forEach(version -> {
                    addIfDirectory(version.resolve("bin"), ordered, seen);
                    addIfDirectory(version.resolve("bin").resolve("x64"), ordered, seen);
                });
            } catch (IOException ignored) {
            }
        }

        Path cudnnRoot = Path.of("C:\\Program Files\\NVIDIA\\CUDNN");
        if (Files.isDirectory(cudnnRoot)) {
            try (var roots = Files.list(cudnnRoot).filter(Files::isDirectory).sorted(cudaVersionComparator())) {
                roots.forEach(root -> {
                    addIfDirectory(root.resolve("bin"), ordered, seen);
                    Path nestedBin = root.resolve("bin");
                    if (Files.isDirectory(nestedBin)) {
                        try (var runtimeVersions = Files.list(nestedBin).filter(Files::isDirectory).sorted(cudaVersionComparator())) {
                            runtimeVersions.forEach(runtimeVersion -> addIfDirectory(runtimeVersion.resolve("x64"), ordered, seen));
                        } catch (IOException ignored) {
                        }
                    }
                });
            } catch (IOException ignored) {
            }
        }

        return ordered;
    }

    private Comparator<Path> cudaVersionComparator() {
        return (left, right) -> compareVersionNames(right.getFileName().toString(), left.getFileName().toString());
    }

    private int compareVersionNames(String left, String right) {
        return normalizeVersion(left).compareTo(normalizeVersion(right));
    }

    private String normalizeVersion(String name) {
        String normalized = name.toLowerCase().replace("v", "");
        String[] pieces = normalized.split("[^0-9]+");
        StringBuilder builder = new StringBuilder();
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            builder.append(String.format("%08d", Integer.parseInt(piece)));
        }
        if (builder.length() == 0) {
            return normalized;
        }
        return builder.toString();
    }

    private void addIfDirectory(Path directory, List<String> ordered, Set<String> seen) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        String path = directory.toString();
        if (seen.add(path)) {
            ordered.add(path);
        }
    }

    private void handleStdoutLine(
        String line,
        AtomicReference<TranscriptionResult> resultRef,
        AtomicReference<String> errorRef,
        TranscriptionListener listener
    ) {
        if (!line.startsWith("EVENT\t")) {
            listener.onLog(line);
            return;
        }

        String[] parts = line.split("\t");
        if (parts.length < 2) {
            listener.onLog(line);
            return;
        }

        String eventType = parts[1];
        Map<String, String> fields = parseFields(parts, 2);
        switch (eventType) {
            case "STATUS" -> listener.onStatus(fields.getOrDefault("message", ""));
            case "ENV" -> listener.onEngineInfo(
                fields.getOrDefault("device", "desconocido"),
                fields.getOrDefault("compute_type", "desconocido"),
                fields.getOrDefault("model", "desconocido")
            );
            case "PROGRESS" -> listener.onProgress(
                parseDouble(fields.get("progress")),
                parseDouble(fields.get("processed_seconds")),
                parseDouble(fields.get("total_seconds"))
            );
            case "SEGMENT" -> listener.onSegment(
                parseDouble(fields.get("start")),
                parseDouble(fields.get("end")),
                fields.getOrDefault("text", "")
            );
            case "RESULT" -> {
                TranscriptionResult result = new TranscriptionResult(
                    Path.of(fields.get("output_txt")),
                    Path.of(fields.get("output_srt")),
                    Path.of(fields.get("output_json")),
                    fields.getOrDefault("language", "unknown"),
                    parseDouble(fields.get("duration_seconds")),
                    fields.getOrDefault("device", "desconocido"),
                    fields.getOrDefault("compute_type", "desconocido"),
                    parseInt(fields.get("segments"))
                );
                resultRef.set(result);
                listener.onResult(result);
            }
            case "ERROR" -> {
                String message = fields.getOrDefault("message", "Error desconocido del backend.");
                errorRef.set(message);
                listener.onError(message);
            }
            default -> listener.onLog(line);
        }
    }

    private Map<String, String> parseFields(String[] parts, int fromIndex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = fromIndex; index < parts.length; index++) {
            int delimiter = parts[index].indexOf('=');
            if (delimiter < 1) {
                continue;
            }
            String key = parts[index].substring(0, delimiter);
            String rawValue = parts[index].substring(delimiter + 1);
            fields.put(key, URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        }
        return fields;
    }

    private void readLines(InputStream stream, Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        } catch (IOException ignored) {
        }
    }

    private double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
