# Diseño visual de la app

La app pp-coach-tracker sigue el **estilo de las apps de Meta** (Facebook, Instagram, Threads, Meta AI): fondos neutros, un solo azul para las acciones, tarjetas planas, barra de navegación inferior y un degradado que distingue lo que hace la IA. Todas las etapas de la app ([01](prompts/01-app-base.md), [02](prompts/02-app-plantel-stats.md), [03](prompts/03-app-chunks.md), [05](prompts/05-app-subida.md), [07](prompts/07-consentimiento.md), [10](prompts/10-app-analisis.md), [13](prompts/13-cancha-en-metros.md)) siguen este documento; la etapa 1 crea el tema y los componentes compartidos.

> **Inspirado en Meta, no igual a Meta.** Las reglas del hackathon piden no violar marcas registradas. Se toma el lenguaje visual (colores neutros, azul de acción, formas, navegación), **no** la identidad: nada del logo del infinito, ni del anillo de Meta AI, ni de los íconos o la tipografía propios de Meta, ni la palabra "Meta" en la interfaz salvo para nombrar los modelos ("Analizado con SAM 3.1 y Muse Spark de Meta"). El nombre, el ícono y el degradado son propios.

## Principios

1. **Neutro primero, azul para actuar.** Casi toda la pantalla es blanco, gris y texto. El azul marca solo lo que se toca o lo que está activo: el botón principal, el elemento seleccionado y los enlaces.
2. **Los colores de camiseta son de los equipos.** Rojo, amarillo y los demás colores de `JerseyColor` aparecen solo como puntos, barras de posesión y mapas de los equipos. Nunca como color de un botón ni de un estado.
3. **La IA se ve.** Lo que producen SAM 3.1 y Muse Spark (hallazgos, "Analizando…", el resumen) lleva el degradado *Spark*. Así se nota en el demo el uso de los modelos de Meta, que vale el 25 % de la evaluación.
4. **Se usa en la cancha.** Hay sol de frente y partidos de noche: contraste AA (4.5:1) en todo el texto, objetivos táctiles de 48 dp o más, y la pantalla de grabar siempre en oscuro.
5. **Contenido como feed.** Las listas (partidos, clips, hallazgos) se leen como un feed: tarjetas a todo el ancho, con la información más importante arriba y las acciones abajo.

## Color

El tema sigue al sistema (claro u oscuro) y se puede fijar en Ajustes → Apariencia (Sistema, Claro, Oscuro). Los **colores dinámicos de Android 12+ van apagados**: la app se ve igual en todos los teléfonos.

### Tokens

| Token | Claro | Oscuro | Uso |
|---|---|---|---|
| `background` | `#F0F2F5` | `#18191A` | Fondo de las pantallas, entre tarjetas |
| `surface` | `#FFFFFF` | `#242526` | Tarjetas, barras superior e inferior, hojas |
| `surfaceHigh` | `#E4E6EB` | `#3A3B3C` | Botón secundario, campo de texto, chip sin seleccionar |
| `divider` | `#CED0D4` | `#3E4042` | Divisores de 1 dp y bordes de los campos |
| `textPrimary` | `#080809` | `#E4E6EB` | Títulos y texto |
| `textSecondary` | `#65676B` | `#B0B3B8` | Fechas, metadatos, ayudas |
| `accent` | `#0866FF` | `#0866FF` | Fondo del botón principal, ícono seleccionado, interruptores |
| `onAccent` | `#FFFFFF` | `#FFFFFF` | Texto sobre `accent` |
| `accentText` | `#0866FF` | `#4599FF` | Enlaces y botones de texto (solo sobre `surface`) |
| `accentSoft` | `#E7F0FF` | `#1D2A3D` | Chip seleccionado, fila seleccionada |
| `onAccentSoft` | `#0054D1` | `#9DC4FF` | Texto sobre `accentSoft` |
| `positive` | `#1B7D35` | `#5CC97A` | "Listo", "Enviado" |
| `positiveSoft` | `#E6F4EA` | `#16301F` | Fondo del chip positivo |
| `warning` | `#8C5300` | `#FFD066` | "A revisar", espera de conexión |
| `warningSoft` | `#FFF4D6` | `#3A2F12` | Fondo del chip de aviso |
| `negative` | `#C8102E` | `#FF8A9B` | Errores, "Importante", borrar |
| `negativeSoft` | `#FDE7EA` | `#3D1A20` | Fondo del chip de error |
| `record` | `#FF3B30` | `#FF3B30` | Botón de grabar y punto "REC" (solo en la pantalla de grabar) |
| `star` | `#F7B928` | `#F7B928` | Clip destacado |

Los contrastes se midieron con la fórmula de WCAG 2.1: todo texto normal pasa 4.5:1 sobre el fondo donde se usa (por ejemplo, `onAccent` sobre `accent` 4.8:1, `textSecondary` sobre `background` claro 5.0:1, `accentText` oscuro sobre `surface` 5.3:1). **`accentText` claro sobre `background` da 4.3:1:** el texto azul va sobre `surface` (tarjetas, barras), no sobre el gris del fondo. En oscuro, `accent` se queda en `#0866FF` porque el blanco sobre un azul más claro no llega a 4.5:1.

### Degradado Spark (lo que hace la IA)

`SparkGradient`: lineal a 135°, `#0866FF` → `#7B2FF7` → `#C9356F`. Se usa en:

- el borde de 2 dp de la tarjeta de resumen del análisis y el ícono ✦ de cada hallazgo;
- la barra de progreso indeterminada de "Analizando…" (segmentando, analizando);
- el botón "Ver análisis" cuando el clip está en `ready` (relleno con el degradado, texto blanco: pasa 4.5:1 en todo el degradado; el punto más bajo es el azul, con 4.8:1).

No se usa en nada más: ni en fondos completos, ni en la navegación, ni en botones que no sean de la IA.

### Colores de los equipos

Los de `JerseyColor` ([01-app-base](prompts/01-app-base.md)) no cambian, porque sus keys entran en los prompts de SAM. Para que todos se vean en los dos temas, el punto del equipo lleva un borde de 1 dp `divider`; así se ven el blanco en claro y el negro en oscuro.

### Estados del clip y del análisis

| Estado | Chip |
|---|---|
| `local` | `surfaceHigh` con texto `textSecondary`: "En el teléfono" |
| `queued`, `uploading`, `uploaded` | `accentSoft` con texto `onAccentSoft` y progreso |
| `segmenting`, `analyzing` | Borde con `SparkGradient`, texto `textPrimary`: "Analizando…" |
| `ready` | `positiveSoft` con texto `positive`: "Listo" |
| `failed` | `negativeSoft` con texto `negative`: "Falló" y el motivo debajo |

Importancia de los hallazgos: **IMPORTANTE** `negative`, **A REVISAR** `warning`, **DETALLE** `accentText`, **DATO** `textSecondary`, cada uno como chip sobre su color suave (`surfaceHigh` para DATO).

## Tipografía

Roboto, la fuente del sistema en Android: no agrega dependencias ni licencias. La fuente propia de Meta no es pública y no se usa.

| Estilo (Material 3) | Tamaño / interlineado | Peso | Uso |
|---|---|---|---|
| `headlineSmall` | 24 / 30 sp | Bold | Nombre de la app en la barra superior, título de una pantalla raíz |
| `titleLarge` | 20 / 26 sp | Bold | Título de una pantalla secundaria |
| `titleMedium` | 17 / 22 sp | SemiBold | Título de una tarjeta ("Pumitas vs Halcones"), título de un hallazgo |
| `bodyLarge` | 15 / 20 sp | Normal | Texto de un hallazgo, descripción |
| `bodyMedium` | 13 / 18 sp | Normal | Metadatos ("22 oct · Deportivo Norte · 3 clips") en `textSecondary` |
| `labelLarge` | 15 / 20 sp | SemiBold | Botones |
| `labelMedium` | 12 / 16 sp | SemiBold | Chips, etiquetas de la barra inferior |
| `displaySmall` | 32 / 38 sp | Bold, cifras tabulares | Estadística grande (posesión, valor por 90 min) |

Las cifras de tiempos y estadísticas usan `fontFeatureSettings = "tnum"` para que no salten al cambiar.

## Formas, espacio y elevación

- **Rejilla de 4 dp.** Margen lateral de 16 dp; 12 dp entre tarjetas; 12 dp de relleno dentro de una tarjeta.
- **Esquinas:** tarjetas 12 dp; botones 8 dp; campos de texto 8 dp; miniaturas 8 dp; hojas inferiores 16 dp arriba; chips y la barra de posesión, píldora completa; avatar del jugador, círculo.
- **Sin sombras.** La profundidad se marca con el cambio de `background` a `surface` y con divisores de 1 dp. Solo la hoja inferior y los diálogos usan la sombra de Material.
- **Toques:** 48 dp mínimo; el botón de grabar de 72 dp.

## Navegación

La arquitectura de la app no cambia (una Activity, la pila de `Screen` en `CoachViewModel`, sin librería de navegación). Lo que cambia es cómo se ve la raíz:

- **Barra superior** de las pantallas raíz: el nombre "pp-coach-tracker" a la izquierda en `headlineSmall` y `textPrimary` (no en azul, para no parecer un logo de Meta), y a la derecha los íconos de la pantalla (filtro, buscar). Fondo `surface`, divisor abajo. En las pantallas secundarias: flecha atrás, título `titleLarge` y acciones a la derecha.
- **Barra inferior** con cinco destinos, como Instagram y Threads. Ícono de contorno sin seleccionar y relleno al seleccionar, en `textPrimary`; etiqueta corta debajo; sin indicador de píldora.

  | Destino | Ícono (Material Symbols Rounded) | Pantalla |
  |---|---|---|
  | Partidos | `home` | Lista de partidos |
  | Estadísticas | `bar_chart` | Pestaña de estadísticas ([10](prompts/10-app-analisis.md)) |
  | Crear | `add_box` | Abre una hoja: "Grabar jugada", "Importar video", "Nuevo partido" |
  | Plantel | `group` | Plantel ([02](prompts/02-app-plantel-stats.md)) |
  | Ajustes | `settings` | Ajustes |

  Cambiar de destino reemplaza la pila por esa raíz; "Atrás" en una raíz que no es Partidos vuelve a Partidos. La barra inferior se oculta en Grabar, en el detalle del clip con el video a pantalla completa y en los diálogos. Si las etapas 2 y 10 todavía no están, sus destinos no aparecen (la barra queda con tres).
- **"Grabar jugada" desde Crear** pregunta el partido con una hoja (los partidos recientes primero, más "Nuevo partido").

Los íconos son los de Material Symbols (Apache-2.0), que vienen con `material-icons-extended`; anotarlo en `THIRD_PARTY.md`.

## Componentes compartidos (`ui/components/`)

| Componente | Cómo se ve |
|---|---|
| `PpTopBar` | La barra superior de arriba, en sus dos variantes (raíz y secundaria) |
| `PpBottomBar` | La barra inferior de cinco destinos |
| `PrimaryButton` | Relleno `accent`, texto `onAccent`, 8 dp, 44 dp de alto, ancho completo en formularios |
| `SecondaryButton` | Relleno `surfaceHigh`, texto `textPrimary`, misma forma |
| `SparkButton` | Relleno `SparkGradient`, texto blanco; solo para "Ver análisis" |
| `PpTextButton` | Texto `accentText`, sin fondo |
| `PpCard` | `surface`, 12 dp, sin borde ni sombra, relleno 12 dp |
| `MatchCard` | `PpCard`: puntos de color de los dos equipos y "Local vs Visitante" en `titleMedium`; debajo, fecha, lugar y "N clips · duración" en `bodyMedium`; a la derecha la miniatura del último clip |
| `ClipCarousel` | Fila horizontal de clips (destacados o recientes) arriba de la lista de partidos: miniaturas redondas de 64 dp con un anillo de 2.5 dp (`SparkGradient` si el análisis está listo, `accent` si se está subiendo, `divider` si no) y el título debajo, como las historias de Instagram |
| `ClipRow` | Miniatura 16:9 de 96 dp, título, tipo, duración y `StatusChip` |
| `StatusChip` | El chip de estado de la tabla de arriba |
| `PpChip` / `FilterChips` | Píldora: `surfaceHigh` sin seleccionar; `accentSoft` + `onAccentSoft` seleccionado |
| `InsightCard` | Un hallazgo como una publicación: ✦ con el degradado y chip de importancia en la primera línea, el minuto a la derecha ("12:40"); título en `titleMedium`; texto en `bodyLarge`; "Qué hacer" en una caja `surfaceHigh`; abajo, divisor y la acción "Ver en el video" (`PpTextButton`) |
| `SummaryCard` | Resumen del análisis con borde `SparkGradient` y la línea "Analizado con SAM 3.1 y Muse Spark de Meta" en `bodyMedium` |
| `PossessionBar` | Píldora de 12 dp dividida con el color de cada equipo y el porcentaje grande arriba de cada lado |
| `TeamDot` | Punto de 12 o 14 dp con el color de la camiseta y el borde `divider` |
| `EmptyState` | Ícono grande en `textSecondary`, título, una línea de ayuda y un `PrimaryButton` |
| `PpSheet` | Hoja inferior `surface` con la manija y opciones de 56 dp con ícono a la izquierda |
| `PpSnackbar` | Fondo `textPrimary` invertido (gris muy oscuro en claro, gris claro en oscuro), 8 dp, acción en `accentText` invertido |
| `ConsentDialog`, `PitchDialog` | Diálogos de [07](prompts/07-consentimiento.md) y [13](prompts/13-cancha-en-metros.md) con `PpCard` y los botones de arriba; las casillas en `accent` |

## Pantallas

| Pantalla | Qué cambia con el estilo |
|---|---|
| Partidos | Barra superior con el nombre; `ClipCarousel` si hay clips; feed de `MatchCard`; sin FAB (crear va en la barra inferior) |
| Detalle del partido | Encabezado en una `PpCard` con los dos equipos grandes y sus colores; `FilterChips` de tipo y destacados; lista de `ClipRow` |
| Grabar | Siempre oscuro (fondo negro), sin barras; botón `record` de 72 dp con anillo blanco, contador en `labelLarge` sobre una píldora translúcida |
| Detalle del clip | Video arriba a todo el ancho; abajo, tarjetas: datos, notas, envío (con el progreso y `StatusChip`), y `SparkButton` "Ver análisis" cuando está listo; las acciones (compartir, exportar, borrar) en la barra superior o en un menú ⋮ |
| Análisis del clip | `SummaryCard`; `PossessionBar`; feed de `InsightCard` de más a menos importante; equipos lado a lado en dos `PpCard`; mapas de calor en tarjetas; en la vista técnica, una tarjeta con los modelos y los cuadros usados |
| Estadísticas | `FilterChips` del filtro; filas tipo lista de seguidores: avatar con el dorsal, nombre o apodo, y la estadística elegida grande a la derecha |
| Plantel | Filas con avatar circular (dorsal en el centro, color del equipo de fondo), nombre y posición |
| Ajustes | Secciones en `PpCard` con filas de 56 dp (ícono, título, valor en `textSecondary`, chevron), como la configuración de las apps de Meta: Cuenta y sistema de análisis, Apariencia, Idioma, Almacenamiento, Formato de grabación, Consentimiento, Acerca de |

## Ícono y nombre

- **Ícono adaptativo propio:** una cancha estilizada en blanco (rectángulo redondeado con la línea central y el círculo) sobre un fondo `SparkGradient`, con un punto blanco como el balón. Sin el infinito ni nada que recuerde a un logo de Meta. Versión monocroma para los íconos temáticos de Android 13+.
- **Nombre visible:** "pp-coach-tracker" (en textos, PP-Coach-Tracker).
- **Pantalla de inicio (splash):** la de Android 12+ con el ícono sobre `surface`; sin animación propia.

## Movimiento

- Transiciones de 200 a 250 ms con `FastOutSlowInEasing`: la pantalla nueva entra deslizándose desde la derecha y "Atrás" la saca hacia la derecha; cambiar de destino en la barra inferior es un fundido.
- Mientras carga `library.json` se muestran tarjetas esqueleto (`surfaceHigh` con brillo), nunca una lista vacía ([01](prompts/01-app-base.md#trampas-conocidas)).
- Al terminar un análisis, la tarjeta del clip hace un brillo del degradado una sola vez.

## Implementación en Compose

- `ui/theme/Color.kt`: los tokens como `PpColors` (data class) con `LightPpColors` y `DarkPpColors`, más `SparkGradient` (`Brush.linearGradient`).
- `ui/theme/Type.kt`: la `Typography` de la tabla de arriba.
- `ui/theme/Shape.kt`: `Shapes(extraSmall = 8.dp, small = 8.dp, medium = 12.dp, large = 16.dp)`.
- `ui/theme/Theme.kt`: `PpTheme(appearance)` provee `LocalPpColors` y arma el `ColorScheme` de Material 3 desde los tokens, para que los componentes de Material salgan bien sin tocarlos:

  | Rol de Material 3 | Token |
  |---|---|
  | `primary` / `onPrimary` | `accent` / `onAccent` |
  | `primaryContainer` / `onPrimaryContainer` | `accentSoft` / `onAccentSoft` |
  | `secondary` / `onSecondary` | `surfaceHigh` / `textPrimary` |
  | `background` / `onBackground` | `background` / `textPrimary` |
  | `surface` / `onSurface` | `surface` / `textPrimary` |
  | `surfaceVariant` / `onSurfaceVariant` | `surfaceHigh` / `textSecondary` |
  | `surfaceContainer*` | `surface` (todos) |
  | `outline` / `outlineVariant` | `divider` |
  | `error` / `onError` | `negative` / `onAccent` |

- La apariencia (sistema, claro, oscuro) se guarda con los demás ajustes; las barras del sistema siguen al tema (`enableEdgeToEdge` con íconos claros u oscuros).
- Ningún color escrito a mano fuera de `ui/theme/`, salvo los de `JerseyColor`.

## Criterios de aceptación (para cada etapa de la app)

- [ ] Prueba JVM `ThemeContrastTest`: calcula el contraste WCAG de cada par de texto y fondo de la tabla de tokens, en claro y en oscuro, y falla si alguno queda bajo 4.5:1 (salvo `accentText` claro sobre `background`, que se documenta como prohibido).
- [ ] `git grep -nE "Color\(0x" -- 'android_native/app/src/main/**/ui/**' ':!**/theme/**'` no encuentra nada.
- [ ] Capturas de cada pantalla nueva **en claro y en oscuro** en `docs/versions/<versión>/`.
- [ ] Sin el logo, el anillo de Meta AI ni la palabra "Meta" en la interfaz, salvo en la línea de los modelos.
