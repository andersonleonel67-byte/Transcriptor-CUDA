from __future__ import annotations

import argparse
import json
import os
import sys
import time
import traceback
from pathlib import Path
from urllib.parse import quote

os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")


def register_windows_gpu_dll_dirs() -> None:
    if os.name != "nt" or not hasattr(os, "add_dll_directory"):
        return

    candidates: list[Path] = []

    cuda_root = Path(r"C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA")
    if cuda_root.is_dir():
        cuda_versions = sorted(
            [item for item in cuda_root.iterdir() if item.is_dir() and item.name.lower().startswith("v12.")],
            key=lambda item: item.name,
            reverse=True,
        )
        for version in cuda_versions:
            bin_dir = version / "bin"
            if bin_dir.is_dir():
                candidates.append(bin_dir)

    cudnn_root = Path(r"C:\Program Files\NVIDIA\CUDNN")
    if cudnn_root.is_dir():
        for runtime_root in sorted([item for item in cudnn_root.iterdir() if item.is_dir()], key=lambda item: item.name, reverse=True):
            bin_root = runtime_root / "bin"
            if not bin_root.is_dir():
                continue
            cuda_branches = sorted(
                [item for item in bin_root.iterdir() if item.is_dir() and item.name.startswith("12.")],
                key=lambda item: item.name,
                reverse=True,
            )
            for branch in cuda_branches:
                x64_dir = branch / "x64"
                if x64_dir.is_dir():
                    candidates.append(x64_dir)

    seen: set[str] = set()
    for candidate in candidates:
        resolved = str(candidate.resolve())
        if resolved in seen:
            continue
        seen.add(resolved)
        os.add_dll_directory(resolved)


register_windows_gpu_dll_dirs()

import av
import ctranslate2
from faster_whisper import WhisperModel


def emit(event_type: str, **fields: object) -> None:
    parts = ["EVENT", event_type]
    for key, value in fields.items():
        if value is None:
            continue
        parts.append(f"{key}={quote(str(value), safe='')}")
    print("\t".join(parts), flush=True)


def format_timestamp(seconds: float) -> str:
    millis = max(0, round(seconds * 1000))
    hours, remainder = divmod(millis, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, millis = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


def sanitize_stem(stem: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in {"-", "_"} else "_" for ch in stem)
    cleaned = cleaned.strip("._")
    return cleaned or "transcripcion"


def unique_output_base(output_dir: Path, input_path: Path) -> Path:
    base_name = sanitize_stem(input_path.stem)
    candidate = output_dir / base_name
    if not any((output_dir / f"{base_name}{suffix}").exists() for suffix in (".txt", ".srt", ".json")):
        return candidate
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    return output_dir / f"{base_name}-{timestamp}"


def detect_device(preference: str) -> tuple[str, str]:
    if preference == "cpu":
        return "cpu", "int8"
    try:
        if ctranslate2.get_cuda_device_count() > 0:
            return "cuda", "float16"
    except Exception:
        pass
    return "cpu", "int8"


def is_cuda_runtime_issue(exc: Exception) -> bool:
    message = str(exc).lower()
    hints = [
        "cublas",
        "cudnn",
        "cuda",
        "curand",
        "cufft",
        "cannot be loaded",
        "dll",
    ]
    return any(hint in message for hint in hints)


def media_duration_seconds(input_path: Path) -> float | None:
    try:
        with av.open(str(input_path)) as container:
            if container.duration:
                return float(container.duration / av.time_base)
            stream = next((item for item in container.streams if item.type in {"audio", "video"}), None)
            if stream and stream.duration and stream.time_base:
                return float(stream.duration * stream.time_base)
    except Exception:
        return None
    return None


def write_txt(path: Path, segments: list[dict[str, object]]) -> None:
    lines = [str(segment["text"]).strip() for segment in segments if str(segment["text"]).strip()]
    path.write_text("\n".join(lines), encoding="utf-8")


def write_srt(path: Path, segments: list[dict[str, object]]) -> None:
    chunks: list[str] = []
    for index, segment in enumerate(segments, start=1):
        text = str(segment["text"]).strip()
        if not text:
            continue
        chunks.append(str(index))
        chunks.append(
            f"{format_timestamp(float(segment['start']))} --> {format_timestamp(float(segment['end']))}"
        )
        chunks.append(text)
        chunks.append("")
    path.write_text("\n".join(chunks), encoding="utf-8")


def write_json_output(
    path: Path,
    *,
    input_path: Path,
    model: str,
    language: str,
    language_probability: float | None,
    duration_seconds: float | None,
    device: str,
    compute_type: str,
    segments: list[dict[str, object]],
) -> None:
    payload = {
        "input": str(input_path),
        "model": model,
        "language": language,
        "language_probability": language_probability,
        "duration_seconds": duration_seconds,
        "device": device,
        "compute_type": compute_type,
        "segment_count": len(segments),
        "segments": segments,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Backend local de transcripción para la app Swing.")
    parser.add_argument("--input", required=True, help="Ruta del archivo de audio o video.")
    parser.add_argument("--output-dir", required=True, help="Directorio de salida.")
    parser.add_argument("--language", default="auto", choices=["auto", "es", "en"])
    parser.add_argument("--model", default="small", help="Modelo de Whisper para faster-whisper.")
    parser.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    parser.add_argument("--beam-size", type=int, default=5)
    parser.add_argument("--model-cache", default=None, help="Directorio para cachear modelos descargados.")
    return parser


def main() -> int:
    parser = build_argument_parser()
    args = parser.parse_args()

    input_path = Path(args.input).expanduser().resolve()
    if not input_path.exists():
        raise FileNotFoundError(f"No se encontró el archivo de entrada: {input_path}")

    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    output_base = unique_output_base(output_dir, input_path)

    emit("STATUS", message=f"Preparando transcripción para {input_path.name}")

    duration_seconds = media_duration_seconds(input_path)

    def run_transcription(device: str, compute_type: str) -> tuple[list[dict[str, object]], object, float]:
        emit(
            "ENV",
            backend="faster-whisper",
            device=device,
            compute_type=compute_type,
            requested_device=args.device,
            model=args.model,
        )
        emit("STATUS", message="Cargando modelo y preparando el audio")

        model_kwargs: dict[str, object] = {
            "device": device,
            "compute_type": compute_type,
            "cpu_threads": max(4, os.cpu_count() or 4),
        }
        if args.model_cache:
            model_kwargs["download_root"] = str(Path(args.model_cache).expanduser().resolve())

        model = WhisperModel(args.model, **model_kwargs)

        emit("STATUS", message="Transcribiendo en vivo")
        segments_iter, info = model.transcribe(
            str(input_path),
            language=None if args.language == "auto" else args.language,
            task="transcribe",
            beam_size=args.beam_size,
            vad_filter=True,
            condition_on_previous_text=True,
        )

        segments: list[dict[str, object]] = []
        processed_seconds = 0.0
        for segment in segments_iter:
            text = segment.text.strip()
            item = {
                "start": round(float(segment.start), 3),
                "end": round(float(segment.end), 3),
                "text": text,
            }
            segments.append(item)
            processed_seconds = max(processed_seconds, float(segment.end))
            emit("SEGMENT", start=item["start"], end=item["end"], text=text)
            if duration_seconds and duration_seconds > 0:
                progress = min(0.99, processed_seconds / duration_seconds)
                emit(
                    "PROGRESS",
                    progress=f"{progress:.4f}",
                    processed_seconds=f"{processed_seconds:.2f}",
                    total_seconds=f"{duration_seconds:.2f}",
                )
        return segments, info, processed_seconds

    device, compute_type = detect_device(args.device)
    try:
        segments, info, processed_seconds = run_transcription(device, compute_type)
    except Exception as exc:
        if device == "cuda" and is_cuda_runtime_issue(exc):
            emit("STATUS", message="CUDA no quedó lista en este equipo. Reintentando en CPU.")
            device, compute_type = "cpu", "int8"
            segments, info, processed_seconds = run_transcription(device, compute_type)
        else:
            raise

    language = info.language or (args.language if args.language != "auto" else "unknown")
    emit(
        "PROGRESS",
        progress="1.0000",
        processed_seconds=f"{processed_seconds:.2f}",
        total_seconds=f"{(duration_seconds or processed_seconds):.2f}",
    )
    emit("STATUS", message="Escribiendo archivos de salida")

    txt_path = output_base.with_suffix(".txt")
    srt_path = output_base.with_suffix(".srt")
    json_path = output_base.with_suffix(".json")

    write_txt(txt_path, segments)
    write_srt(srt_path, segments)
    write_json_output(
        json_path,
        input_path=input_path,
        model=args.model,
        language=language,
        language_probability=info.language_probability,
        duration_seconds=duration_seconds or processed_seconds,
        device=device,
        compute_type=compute_type,
        segments=segments,
    )

    emit(
        "RESULT",
        language=language,
        language_probability=f"{(info.language_probability or 0.0):.4f}",
        duration_seconds=f"{(duration_seconds or processed_seconds):.2f}",
        device=device,
        compute_type=compute_type,
        segments=len(segments),
        output_txt=txt_path,
        output_srt=srt_path,
        output_json=json_path,
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        emit("ERROR", message=str(exc))
        print(traceback.format_exc(), file=sys.stderr, flush=True)
        sys.exit(1)
