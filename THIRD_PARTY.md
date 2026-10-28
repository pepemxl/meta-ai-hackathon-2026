# Componentes de terceros

Solo licencias permisivas (MIT, BSD, Apache-2.0, ISC) o las propias de la plataforma. Los modelos de visión y lenguaje (SAM 3.1 y Muse Spark) se usan por la Meta Model API, bajo sus términos.

## API Node (`api/`)

| Nombre | Versión | Licencia | Para qué |
|---|---|---|---|
| express | 4.22.3 | MIT | Servidor HTTP |
| multer | 2.5.0 | MIT | Multipart (YAML, índice, análisis) |
| mysql2 | 3.24.6 | MIT | MySQL |
| jsonwebtoken | 9.0.3 | MIT | JWT HS256 |
| supertest | 7.3.1 | MIT | Pruebas (solo desarrollo) |

## Worker (`worker/`)

| Nombre | Versión | Licencia | Para qué |
|---|---|---|---|
| numpy | 2.1.3 | BSD-3-Clause | Métricas y colores |
| opencv-python-headless | 4.10.0.84 | Apache-2.0 (el wheel trae FFmpeg con LGPL, enlazado dinámicamente) | Leer los chunks, color del torso, herramientas |
| PyYAML | 6.0.2 | MIT | YAML de los chunks |
| pytest | 8.3.3 | MIT | Pruebas (solo desarrollo) |

La imagen base es `python:3.12-slim` (PSF y licencias de Debian). No hay modelos locales.
