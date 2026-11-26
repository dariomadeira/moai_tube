# Moai Tube (`moai_tube`)

Plugin externo de YouTube sin publicidad para **Moai3**, basado en `MediaServiceCore` (motor MIT de SmartTube).

## Estructura

- `contract/` — Interfaz `IYouTubePlugin` (v1), sin dependencias externas.
- `plugin/` — Implementación `MoaiTubePlugin` (resolución HD vía DASH/HLS, búsqueda, sugerencias sin cuenta, feed cronológico por RSS y catálogo de canal) + redirección de `PluginAssets`.
- `libs/` — Los 4 `.aar` compilados de `MediaServiceCore` (`youtubeapi`, `mediaserviceinterfaces`, `sharedutils`, `commons-io`).
- `dist/` — Artefactos generados listos para subir a GitHub Releases / repositorio de plugins:
  - `manifest.json` (con SHA-256 de todos los archivos)
  - `plugin.dex`
  - `assets.zip` (`nsigsolver/` + `potokennp2/`)
  - `libs/<abi>/libj2v8.so` (`armeabi-v7a`, `arm64-v8a`, `x86_64`)

## Compilar el plugin

```powershell
.\scripts\build_plugin.ps1
```

Requiere JDK 17 (`C:\Program Files\Java\jdk-17`) y Android SDK (`build-tools 35.0.0`).
