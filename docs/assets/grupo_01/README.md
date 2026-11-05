# Grupo 1: imágenes generadas con IA

**AI-generated with Meta muse-image-1.0.** Una escena inventada (granates contra azules vista desde la grada, en cancha de pasto y en una de tierra) para mostrar el flujo de la app en el demo, el pitch y las capturas. No es un partido real ni sirve para medir la precisión del análisis: eso se mide con video real ([../../results/](../../results/README.md)).

| Archivo | SHA-256 (inicio) |
|---|---|
| `generated-image-1_correcta.png` | `91e479b5b715caf6` |
| `generated-image-1_for_app.png` | `15be24e912b11e56` |
| `generated-image-1_v2.png` | `e55c0ee8399f350c` |
| `jugada_01_sin_pasto.png` | `264046e3762d49cd` |
| `jugada_01_pasto_sintetico.png` | `b9ef6b135f3e1fbf` |

- **Herramienta:** Meta muse-image-1.0, por la Meta Model API (playground), el 2 de noviembre de 2026.
- **`jugada_01_sin_pasto.png`:** la misma escena en una cancha de tierra; es la de la prueba de la cancha sin pasto (la pregunta "Does the pitch have grass?", la muestra de tierra con el overlay morado y el análisis en tierra).
- **`jugada_01_pasto_sintetico.png`:** la misma escena en pasto sintético; es la de la muestra de pasto con el overlay morado y el toque rechazado.
- **Términos:** según los [Meta Model API Terms of Service](https://dev.meta.ai/legal/terms-of-service), lo generado es del usuario (4.1) y el servicio es para uso comercial (1). El registro completo está en [08-uso-de-video.md](../../08-uso-de-video.md#contenido-generado-con-ia).
- **Son los originales, sin modificar:** conservan en sus metadatos la marca IPTC de contenido generado por IA (`trainedAlgorithmicMedia`). No volver a guardarlos ni editarlos; para usarlos en otro tamaño, hacer una copia aparte.
- **Copia con las cajas de SAM 3.1:** [results/06-cajas-de-sam-en-una-imagen.jpg](../../results/06-cajas-de-sam-en-una-imagen.md) es `jugada_01_sin_pasto.png` reducida a 1280 px con las cajas dibujadas, y [06-cancha-y-lineas-de-sam.jpg](../../results/06-cajas-de-sam-en-una-imagen.md#la-cancha-las-líneas-y-la-homografía) le suma la cancha, las líneas y la cancha completa proyectada con la homografía; las dos lo dicen en la propia imagen.
- **Al usarlos, decirlo** en la diapositiva, la captura o el video, con la herramienta.

Los videos de esta escena (generados con Meta AI, Vibes) no se versionan: sus términos no dicen de quién son los resultados ni si se permite el uso comercial.
