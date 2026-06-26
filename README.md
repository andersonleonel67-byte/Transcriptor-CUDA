# Transcriptor Local

Aplicación de escritorio en Java Swing para transcribir video o audio a texto con salida `TXT`, `SRT` y `JSON`.

## Qué resuelve

- Interfaz de escritorio local con cola de archivos y progreso en vivo.
- Transcripción en inglés y español con detección automática de idioma.
- Aceleración por GPU NVIDIA cuando `CTranslate2` detecta CUDA.
- Fallback automático a CPU `int8` cuando la GPU no está disponible.
- Sin depender de FFmpeg instalado globalmente: `faster-whisper` usa `PyAV`.

## Arquitectura

- `src/transcriptor/app`: frontend Swing y orquestación del proceso.
- `backend/transcribe.py`: backend local basado en `faster-whisper`.
- `scripts/setup.ps1`: descarga un JDK portable oficial e instala dependencias Python.
- `scripts/run.ps1`: compila y ejecuta la app.
- `scripts/smoke-test.ps1`: genera un audio local corto y valida el backend.

## Uso

```powershell
.\scripts\setup.ps1
.\scripts\run.ps1
```

La primera ejecución puede tardar un poco más porque el modelo se descarga y queda cacheado en `cache/models`.

## Verificación rápida

```powershell
.\scripts\smoke-test.ps1
```

## Recomendaciones de modelo

- `tiny`: prueba rápida o hardware limitado.
- `base`: ligero y mejor que `tiny`.
- `small`: buen equilibrio para uso diario.
- `medium`: más preciso, más lento.
- `large-v3`: máxima calidad local, más consumo de VRAM y tiempo.

## Fuentes técnicas

- [SYSTRAN/faster-whisper](https://github.com/SYSTRAN/faster-whisper)
- [OpenNMT/CTranslate2](https://github.com/OpenNMT/CTranslate2)
- [openai/whisper](https://github.com/openai/whisper)
- [Eclipse Temurin / Adoptium](https://adoptium.net/)
