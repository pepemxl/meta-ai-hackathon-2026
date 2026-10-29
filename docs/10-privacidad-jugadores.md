# Privacidad: cómo identificamos a los jugadores

> Orientación de diseño, **no es asesoría legal**. Antes de lanzar el producto con usuarios reales (sobre todo con menores) hay que revisarla con un abogado.

## Decisión

**El pipeline trabaja con IDs, no con nombres.** El nombre es un dato opcional que el entrenador agrega, que se queda en su cuenta y que **nunca se manda a servicios de terceros** (Meta API incluida).

| Nivel | Ejemplo | Dónde vive | Se manda a la Meta API |
|---|---|---|---|
| `track_id` | `17` | Por clip, lo genera el tracker; no identifica a nadie | Sí (ya viene de ahí) |
| `player_id` + dorsal | `J3`, `#7` | Base de datos de la API | Sí, como "J3 (#7)" |
| Nombre visible | "Diego R." o un apodo | Base de datos de la API (cuenta del entrenador) y el teléfono | **No** |

Muse Spark recibe "J3 (#7) pierde la marca en el minuto 1:12". La app cambia "J3" por el nombre al mostrar el insight. Así el entrenador ve nombres, pero ningún tercero los recibe.

## Por qué no basta con "Player 1, Player 2"

Cambiar el nombre por un número ayuda, pero **no saca el proyecto de la ley de protección de datos**:

- **El video ya es un dato personal.** Se ven caras, cuerpos y dorsales. Cualquiera que conozca al equipo identifica a los jugadores sin necesidad del nombre.
- **Un dato seudonimizado sigue siendo personal.** El GDPR lo dice expresamente (considerando 26): si existe la tabla que une "J3" con "Diego", es dato personal. Lo mismo aplica, en la práctica, bajo la ley mexicana.

Entonces el objetivo no es "evitar el GDPR", sino **minimizar los datos**: guardar solo lo necesario, en el menor número de lugares, y no compartirlo con terceros. Usar IDs en el pipeline reduce mucho el riesgo si hay una fuga y simplifica cumplir. No elimina las obligaciones.

## Qué ley aplica

| Ley | Cuándo aplica | Qué implica para nosotros |
|---|---|---|
| **LFPDPPP (México)** | Siempre: somos responsables en México y los usuarios iniciales son clubes mexicanos | Aviso de privacidad, consentimiento, derechos ARCO (acceso, rectificación, cancelación, oposición). Para menores, consentimiento de padres o tutores |
| **GDPR (UE)** | Solo si hay usuarios o jugadores en la Unión Europea, o si ofrecemos el servicio allá | Base legal, minimización, derecho de supresión, contratos con encargados (Meta como procesador) |
| **COPPA (EE. UU.)** y similares | Si la app se dirige a menores de 13 en EE. UU. | No aplica en el MVP: la usa el entrenador, no los niños |

Para el hackathon, el caso realista es la LFPDPPP. Diseñar pensando en el GDPR no cuesta más y deja la puerta abierta a otros países.

## Reglas de diseño

1. **Nombres opcionales.** La app permite etiquetar solo con dorsal y posición. El nombre (o un apodo, o iniciales) se agrega solo si el entrenador quiere.
2. **Sin nombres hacia afuera.** El JSON que se manda a Muse Spark solo tiene `player_id`, dorsal, posición y equipo. Lo mismo aplica a logs, métricas y reportes de error.
3. **Sin biometría.** No hacemos reconocimiento facial ni re-identificación por rasgos físicos entre clips. La identidad entre clips la asigna el entrenador a mano (post-tagging). Eso además evita la categoría de "datos biométricos", que en ambas leyes tiene reglas más estrictas.
4. **Consentimiento antes de grabar y al subir.** El aviso de privacidad, el consentimiento que acepta el entrenador al enviar video y el formato para jugadores y tutores están en [legal/](legal/README.md).
5. **Borrado real.** Borrar un jugador borra su nombre y desliga sus tracks. Borrar un partido borra videos, tracks e insights. Se puede pedir la exportación o el borrado de todo (derechos ARCO).
6. **Retención corta del video.** Opción para borrar el video del servidor después de procesarlo y conservar solo tracks e insights.
7. **Meta API.** No usar el tier *contributor* (que permite a Meta usar los datos) con video de menores. Revisar los términos de uso de datos de la API antes del demo.
8. **Datasets de terceros.** Nunca se etiquetan nombres en video de datasets o transmisiones (por ejemplo, el dataset de Quantigo). Ahí solo existen IDs.

## Cambios al modelo de datos

Cuando se agregue el plantel (etapa 2) y la API lo guarde:

```
players(id, team_id, number, position,
        display_name NULL,        -- opcional: nombre, apodo o iniciales
        consent_status[none|pending|granted|revoked],
        created_at)
```

- `display_name` se omite en cualquier payload hacia el worker o hacia la Meta API.
- Los insights se guardan con `player_id` en `players_json`; la API resuelve los nombres al responder a la app del entrenador dueño del partido.

## Cambios a la app

Cuando la app permita etiquetar los tracks:

- Al tocar un track se elige **dorsal y posición** (obligatorios) y **nombre** (opcional, con el texto de ayuda "puedes usar un apodo o iniciales").
- En v0.2, el alta de un partido incluye un aviso: "Graba solo con el consentimiento de los jugadores o de sus tutores".
