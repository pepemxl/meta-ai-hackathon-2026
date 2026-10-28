package com.ppcoach.tracker.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** Every text of the app. Es and En implement all of it (StringsTest checks). */
interface Strings {
    val code: String

    // navigation
    val appName: String
    val navMatches: String
    val navCreate: String
    val navSettings: String
    val back: String
    val cancel: String
    val save: String
    val delete: String
    val confirmDelete: String
    val ok: String

    // matches
    val matchesEmptyTitle: String
    val matchesEmptyHelp: String
    val newMatch: String
    val editMatch: String
    val home: String
    val away: String
    val homeColor: String
    val awayColor: String
    val date: String
    val venue: String
    val notes: String
    val recent: String
    fun clipsCount(n: Int): String
    val vs: String
    val deleteMatch: String

    // create sheet
    val recordPlay: String
    val importVideo: String
    val chooseMatch: String

    // match detail
    val allClips: String
    val starred: String
    val noClips: String
    val noClipsHelp: String
    fun kindLabel(key: String): String

    // record
    val recordHint: String
    val recording: String
    val cameraPermission: String
    val grantPermission: String
    fun clipSaved(title: String): String
    val recordFailed: String
    val defaultClipTitle: String

    // clip detail
    val clipDetails: String
    val duration: String
    val size: String
    val resolution: String
    val frameRate: String
    val file: String
    val kind: String
    val star: String
    val unstar: String
    val share: String
    val exportGallery: String
    val exported: String
    val exportFailed: String
    val deleteClip: String
    val title: String
    val videoMissing: String

    // status chips
    val statusLocal: String
    val statusPreparing: String
    val statusUploading: String
    val statusUploaded: String
    val statusSegmenting: String
    val statusReady: String
    val statusFailed: String

    // analysis send
    val sendForAnalysis: String
    val sendNoServer: String
    fun preparingChunk(n: Int, of: Int, pct: Int): String
    fun sendingPct(pct: Int): String
    val waitingConnection: String
    val sentOk: String
    val queuedForAnalysis: String
    val analyzing: String
    val analysisReady: String
    val analysisFailed: String
    val viewAnalysis: String
    val consentLine: String
    val sessionExpired: String
    val consentWithdrawnSend: String
    val termsChanged: String
    val partTooLarge: String
    val loginFirst: String
    val retry: String

    // technical
    fun chunksSummary(count: Int, seconds: Int, mb: String, frames: Int): String
    fun samCost(usd: String): String
    val deleteCopy: String
    val copyDeleted: String
    val chunkProfile: String
    val profileDev: String
    val profileDemo: String
    fun overlapLabel(seconds: Int): String
    val highResolution: String
    val highResolutionHelp: String
    fun proxySpec(height: Int, mbps: String, mbMin: String): String
    val tempCopies: String

    // settings
    val language: String
    val appearance: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val storage: String
    val storagePhone: String
    val storagePrivate: String
    val storageSd: String
    fun freeSpace(gb: String, clips: Int): String
    val copyPath: String
    val pathCopied: String
    fun moveHere(n: Int): String
    fun moved(n: Int): String
    val recordingFormat: String
    val recordingFormatValue: String
    val showTechnical: String

    // analysis system (session)
    val analysisSystem: String
    val serverUrl: String
    val email: String
    val password: String
    val login: String
    val logout: String
    fun loggedInAs(name: String): String
    val testConnection: String
    val connectionOk: String
    val connectionFailed: String
    val wrongPassword: String
    val tooManyAttempts: String

    // consent
    val consentTitle: String
    val consentIntro: String
    val consentRights: String
    val consentPeople: String
    val consentAi: String
    val consentRead: String
    val consentPromise: String
    val acceptAndSend: String
    val privacyNotice: String
    val fullTerms: String
    val playersForm: String
    val privacyAndConsent: String
    fun consentAccepted(version: String, date: String): String
    val consentNone: String
    val withdrawConsent: String
    val consentWithdrawn: String

    // analysis screen
    val analysisTitle: String
    val analyzedWith: String
    val simulatorWarning: String
    val possession: String
    val ballSeen: String
    fun possessionChanges(n: Int): String
    val findings: String
    val noFindings: String
    fun severityLabel(key: String): String
    val whatToDo: String
    val watchInVideo: String
    val teamsSideBySide: String
    val playersVisible: String
    val longestPossession: String
    val pressure: String
    val widthInImage: String
    val depthInImage: String
    val heatmapTitle: String
    val heatmapHelp: String
    val limitations: String
    val modelsUsed: String
    fun framesSent(n: Int): String
    val segmenter: String
    val analysisNotYet: String
    val analysisOffline: String
    val analysisSessionExpired: String

    // pitch in meters (stage 13)
    val pitchQuestionTitle: String
    val pitchQuestionIntro: String
    val pitchLinesQuestion: String
    val pitchLines: String
    val pitchNoLines: String
    val pitchOff: String
    val pitchUseForMatch: String
    val computePitch: String
    val pitchAnswerLines: String
    val pitchAnswerNoLines: String
    val pitchAnswerAsk: String
    val changeAnswer: String
    val markPitchPoints: String
    fun pointsMarked(n: Int): String
    val noLinesNoPoints: String
    val pitchTouchesTitle: String
    val football11: String
    val football7: String
    val touchStepImage: String
    val touchStepName: String
    val touchNeedFifth: String
    fun touchReady(n: Int): String
    val undo: String
    val clearAll: String
    val frameAt: String
    fun marksSummary(points: Int, lines: Int): String
    fun referencesSummary(n: Int, strokes: Int): String
    val referenceImages: String
    val referencesTitle: String
    val referencesHelp: String
    val takePhoto: String
    val useThisFrame: String
    val referencesMax: String
    fun strokesCount(n: Int): String
    val referenceFromPhoto: String
    val referenceFromFrame: String
    val paintTitle: String
    val paintHelp: String
    val modePoints: String
    val modeLines: String
    val lineStepPaint: String
    val lineStepName: String
    val justALine: String
    fun touchReadyMarks(points: Int, lines: Int): String
    val pitchInMeters: String
    val metersPerMinute: String
    val sprintSpeed: String
    val teamWidth: String
    val teamLength: String
    fun pitchShare(pct: Int, seconds: Int): String
    val pitchApproximate: String
    val topDownTitle: String
    val fewPitchWarning: String
}

object Es : Strings {
    override val code = "es"
    override val appName = "pp-coach-tracker"
    override val navMatches = "Partidos"
    override val navCreate = "Crear"
    override val navSettings = "Ajustes"
    override val back = "Atrás"
    override val cancel = "Cancelar"
    override val save = "Guardar"
    override val delete = "Borrar"
    override val confirmDelete = "¿Borrar? No se puede deshacer."
    override val ok = "Aceptar"

    override val matchesEmptyTitle = "Todavía no hay partidos"
    override val matchesEmptyHelp = "Crea un partido para grabar o importar sus jugadas."
    override val newMatch = "Nuevo partido"
    override val editMatch = "Editar partido"
    override val home = "Local"
    override val away = "Visitante"
    override val homeColor = "Color del local"
    override val awayColor = "Color del visitante"
    override val date = "Fecha (aaaa-mm-dd)"
    override val venue = "Lugar"
    override val notes = "Notas"
    override val recent = "Recientes"
    override fun clipsCount(n: Int) = if (n == 1) "1 clip" else "$n clips"
    override val vs = "vs"
    override val deleteMatch = "Borrar partido y sus clips"

    override val recordPlay = "Grabar jugada"
    override val importVideo = "Importar video"
    override val chooseMatch = "¿De qué partido?"

    override val allClips = "Todos"
    override val starred = "Destacados"
    override val noClips = "Sin clips"
    override val noClipsHelp = "Graba una jugada o importa un video de la galería."
    override fun kindLabel(key: String) = when (key) {
        "half" -> "Tiempo"
        "full" -> "Partido completo"
        "training" -> "Entrenamiento"
        else -> "Jugada"
    }

    override val recordHint = "Toca el botón rojo para grabar una jugada"
    override val recording = "REC"
    override val cameraPermission = "La app necesita la cámara para grabar. El micrófono es opcional."
    override val grantPermission = "Dar permiso"
    override fun clipSaved(title: String) = "Guardado: $title"
    override val recordFailed = "No se pudo grabar el clip"
    override val defaultClipTitle = "Jugada"

    override val clipDetails = "Detalle del clip"
    override val duration = "Duración"
    override val size = "Tamaño"
    override val resolution = "Resolución"
    override val frameRate = "Cuadros por segundo"
    override val file = "Archivo"
    override val kind = "Tipo"
    override val star = "Destacar"
    override val unstar = "Quitar destacado"
    override val share = "Compartir"
    override val exportGallery = "Guardar en la galería"
    override val exported = "Guardado en la galería"
    override val exportFailed = "No se pudo guardar en la galería"
    override val deleteClip = "Borrar clip"
    override val title = "Título"
    override val videoMissing = "No se encuentra el archivo de video"

    override val statusLocal = "En el teléfono"
    override val statusPreparing = "Preparando"
    override val statusUploading = "Enviando"
    override val statusUploaded = "Subido"
    override val statusSegmenting = "Analizando…"
    override val statusReady = "Listo"
    override val statusFailed = "Falló"

    override val sendForAnalysis = "Enviar a análisis"
    override val sendNoServer = "Sin servidor configurado: se comparten los chunks."
    override fun preparingChunk(n: Int, of: Int, pct: Int) = "Preparando · chunk $n de $of · $pct %"
    override fun sendingPct(pct: Int) = "Enviando… $pct %"
    override val waitingConnection = "Esperando conexión…"
    override val sentOk = "Enviado ✓"
    override val queuedForAnalysis = "En cola para el análisis"
    override val analyzing = "Analizando…"
    override val analysisReady = "Análisis listo"
    override val analysisFailed = "El análisis falló"
    override val viewAnalysis = "Ver análisis"
    override val consentLine = "Al enviar confirmas que tienes el consentimiento de las personas que aparecen en el video."
    override val sessionExpired = "La sesión venció: inicia sesión de nuevo en Ajustes"
    override val consentWithdrawnSend = "Retiraste el consentimiento: vuelve a enviar para aceptarlo de nuevo"
    override val termsChanged = "Cambiaron los términos de uso: actualiza la app"
    override val partTooLarge = "Una parte del video pesa más de lo que acepta el servidor"
    override val loginFirst = "Inicia sesión en Ajustes → Sistema de análisis"
    override val retry = "Reintentar"

    override fun chunksSummary(count: Int, seconds: Int, mb: String, frames: Int) =
        "$count ${if (count == 1) "chunk" else "chunks"} de ${if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"} · $mb MB · $frames cuadros"
    override fun samCost(usd: String) = "Costo estimado en SAM 3.1: US$$usd"
    override val deleteCopy = "Borrar la versión para análisis"
    override val copyDeleted = "Versión para análisis borrada"
    override val chunkProfile = "Chunks del video para análisis"
    override val profileDev = "Chunks de 1 min (desarrollo)"
    override val profileDemo = "Chunks de 5 min (demo)"
    override fun overlapLabel(seconds: Int) = "Traslape: $seconds s"
    override val highResolution = "Alta resolución (1080p)"
    override val highResolutionHelp = "Más detalle para jugadores lejanos; pesa el doble."
    override fun proxySpec(height: Int, mbps: String, mbMin: String) = "${height}p · $mbps Mbps · ~$mbMin MB por minuto"
    override val tempCopies = "Copias temporales"

    override val language = "Idioma"
    override val appearance = "Apariencia"
    override val themeSystem = "Sistema"
    override val themeLight = "Claro"
    override val themeDark = "Oscuro"
    override val storage = "Dónde se guardan los videos"
    override val storagePhone = "Memoria del teléfono"
    override val storagePrivate = "Memoria interna privada"
    override val storageSd = "Tarjeta SD"
    override fun freeSpace(gb: String, clips: Int) = "$gb GB libres · ${clipsCount(clips)}"
    override val copyPath = "Copiar ruta"
    override val pathCopied = "Ruta copiada"
    override fun moveHere(n: Int) = "Mover ${clipsCount(n)} aquí"
    override fun moved(n: Int) = "Se movieron ${clipsCount(n)}"
    override val recordingFormat = "Formato de grabación"
    override val recordingFormatValue = "MP4 H.264, 1080p a 30 fps (720p si la cámara no da 1080p)"
    override val showTechnical = "Mostrar detalles técnicos"

    override val analysisSystem = "Sistema de análisis"
    override val serverUrl = "Dirección del servidor"
    override val email = "Correo"
    override val password = "Contraseña"
    override val login = "Iniciar sesión"
    override val logout = "Cerrar sesión"
    override fun loggedInAs(name: String) = "Sesión iniciada como $name"
    override val testConnection = "Probar conexión"
    override val connectionOk = "Conexión correcta"
    override val connectionFailed = "No se pudo conectar con el servidor"
    override val wrongPassword = "Correo o contraseña incorrectos"
    override val tooManyAttempts = "Demasiados intentos: espera unos minutos"

    override val consentTitle = "Antes de enviar tu primer video"
    override val consentIntro = "PP-Coach-Tracker usa tus videos solo para generar el análisis de tus partidos. Para enviarlo necesitamos que confirmes:"
    override val consentRights = "Tengo derecho a usar este video. Lo grabé yo o mi club, o tengo permiso de quien lo grabó."
    override val consentPeople = "Tengo el consentimiento de las personas que aparecen, y el de sus padres o tutores si son menores de edad."
    override val consentAi = "Autorizo a PP-Coach-Tracker a procesar el video para generar analíticos: detectar y seguir jugadores y balón con inteligencia artificial (incluidos modelos de Meta) y calcular estadísticas e insights tácticos."
    override val consentRead = "Leí el aviso de privacidad y los términos completos."
    override val consentPromise = "No usamos tu video para entrenar modelos, no lo publicamos ni lo compartimos con otros usuarios. Borramos el video del servidor después del análisis y puedes retirar tu consentimiento cuando quieras desde Ajustes."
    override val acceptAndSend = "Aceptar y enviar"
    override val privacyNotice = "Aviso de privacidad"
    override val fullTerms = "Términos completos"
    override val playersForm = "Formato para jugadores y tutores"
    override val privacyAndConsent = "Privacidad y consentimiento"
    override fun consentAccepted(version: String, date: String) = "Aceptaste los términos de uso de video (versión $version) el $date"
    override val consentNone = "Todavía no aceptas los términos de uso de video."
    override val withdrawConsent = "Retirar consentimiento"
    override val consentWithdrawn = "Consentimiento retirado; se cancelaron los envíos pendientes."

    override val analysisTitle = "Análisis"
    override val analyzedWith = "Analizado con SAM 3.1 y Muse Spark de Meta"
    override val simulatorWarning = "Análisis de desarrollo: simulador, no SAM 3.1"
    override val possession = "Posesión"
    override val ballSeen = "Balón visto"
    override fun possessionChanges(n: Int) = if (n == 1) "1 cambio de posesión" else "$n cambios de posesión"
    override val findings = "Hallazgos"
    override val noFindings = "Sin hallazgos para este clip."
    override fun severityLabel(key: String) = when (key) {
        "high" -> "IMPORTANTE"
        "medium" -> "A REVISAR"
        "low" -> "DETALLE"
        else -> "DATO"
    }
    override val whatToDo = "Qué hacer"
    override val watchInVideo = "Ver en el video"
    override val teamsSideBySide = "Los equipos"
    override val playersVisible = "Jugadores a la vista"
    override val longestPossession = "Posesión más larga"
    override val pressure = "Presión (rivales cerca del balón)"
    override val widthInImage = "Ancho en la imagen"
    override val depthInImage = "Alto en la imagen"
    override val heatmapTitle = "Mapa de calor"
    override val heatmapHelp = "Como lo ve la cámara: todavía no es la cancha en metros."
    override val limitations = "Límites"
    override val modelsUsed = "Modelos de Meta usados"
    override fun framesSent(n: Int) = "$n cuadros enviados a SAM"
    override val segmenter = "Segmentador"
    override val analysisNotYet = "El análisis todavía no está listo."
    override val analysisOffline = "Sin conexión: no se pudo descargar el análisis."
    override val analysisSessionExpired = "La sesión venció: inicia sesión para ver el análisis."

    override val pitchQuestionTitle = "¿Calculamos la cancha?"
    override val pitchQuestionIntro = "Así el análisis mide distancias y posiciones en metros, con los puntos de la cancha que marques."
    override val pitchLinesQuestion = "¿Las líneas de la cancha se ven bien en este video?"
    override val pitchLines = "Sí, se ven bien"
    override val pitchNoLines = "Se ven poco o no hay"
    override val pitchOff = "No calcular la cancha"
    override val pitchUseForMatch = "Usar esta respuesta para los demás videos de este partido"
    override val computePitch = "Calcular la cancha"
    override val pitchAnswerLines = "Líneas bien marcadas"
    override val pitchAnswerNoLines = "Sin líneas: con los puntos marcados"
    override val pitchAnswerAsk = "Se pregunta al enviar"
    override val changeAnswer = "Cambiar respuesta"
    override val markPitchPoints = "Marcar puntos de la cancha"
    override fun pointsMarked(n: Int) = if (n == 1) "1 punto marcado" else "$n puntos marcados"
    override val noLinesNoPoints = "Sin puntos marcados la cancha no se calcula."
    override val pitchTouchesTitle = "Puntos de la cancha"
    override val football11 = "Fútbol 11"
    override val football7 = "Fútbol 7"
    override val touchStepImage = "Toca un punto de la cancha en la imagen"
    override val touchStepName = "Ahora toca cuál es en el dibujo"
    override val touchNeedFifth = "Con 4 puntos cualquier nombre encaja: marca un quinto"
    override fun touchReady(n: Int) = "Listo: $n puntos. La cancha calculada se ve en violeta."
    override val undo = "Deshacer"
    override val clearAll = "Borrar todo"
    override val frameAt = "Cuadro"
    override fun marksSummary(points: Int, lines: Int) = buildList {
        if (points > 0) add(if (points == 1) "1 punto marcado" else "$points puntos marcados")
        if (lines > 0) add(if (lines == 1) "1 línea pintada" else "$lines líneas pintadas")
    }.joinToString(" y ")
    override fun referencesSummary(n: Int, strokes: Int) = (if (n == 1) "1 imagen de referencia" else "$n imágenes de referencia") + " · " + strokesCount(strokes)
    override val referenceImages = "Imágenes de referencia de la cancha"
    override val referencesTitle = "Imágenes de referencia"
    override val referencesHelp = "Si hay poca luz o las líneas están borradas: toma fotos de la cancha (mejor desde donde grabas y con buena luz) o usa un cuadro del video, y pinta con el dedo encima de las líneas. SAM las usa como ejemplo de qué es una línea. Sirven para todos los videos de este partido."
    override val takePhoto = "Tomar foto"
    override val useThisFrame = "Usar este cuadro"
    override val referencesMax = "Ya hay 6 imágenes: es el máximo por partido."
    override fun strokesCount(n: Int) = if (n == 1) "1 línea pintada" else "$n líneas pintadas"
    override val referenceFromPhoto = "Foto"
    override val referenceFromFrame = "Cuadro del video"
    override val paintTitle = "Pintar líneas"
    override val paintHelp = "Pasa el dedo encima de cada línea de la cancha, aunque apenas se vea."
    override val modePoints = "Tocar puntos"
    override val modeLines = "Pintar líneas"
    override val lineStepPaint = "Pasa el dedo encima de una línea de la imagen (sirve aunque esté tenue o borrada)"
    override val lineStepName = "¿Qué línea es? Tócala en el dibujo"
    override val justALine = "No sé cuál, solo es una línea"
    override fun touchReadyMarks(points: Int, lines: Int) = "Listo: ${marksSummary(points, lines)}. La cancha calculada se ve en violeta."
    override val pitchInMeters = "En la cancha, en metros"
    override val metersPerMinute = "Metros por jugador por minuto"
    override val sprintSpeed = "Velocidad en los piques"
    override val teamWidth = "Ancho del equipo"
    override val teamLength = "Largo del equipo"
    override fun pitchShare(pct: Int, seconds: Int) = "Sale del $pct % del video ($seconds s con la cancha calculada)."
    override val pitchApproximate = "Medidas por omisión de la cancha: las distancias son aproximadas."
    override val topDownTitle = "La cancha vista desde arriba"
    override val fewPitchWarning = "En este video casi no se ve la cancha; el análisis puede salir vacío. Puedes enviarlo igual."
}

object En : Strings {
    override val code = "en"
    override val appName = "pp-coach-tracker"
    override val navMatches = "Matches"
    override val navCreate = "Create"
    override val navSettings = "Settings"
    override val back = "Back"
    override val cancel = "Cancel"
    override val save = "Save"
    override val delete = "Delete"
    override val confirmDelete = "Delete? This cannot be undone."
    override val ok = "OK"

    override val matchesEmptyTitle = "No matches yet"
    override val matchesEmptyHelp = "Create a match to record or import its plays."
    override val newMatch = "New match"
    override val editMatch = "Edit match"
    override val home = "Home"
    override val away = "Away"
    override val homeColor = "Home color"
    override val awayColor = "Away color"
    override val date = "Date (yyyy-mm-dd)"
    override val venue = "Venue"
    override val notes = "Notes"
    override val recent = "Recent"
    override fun clipsCount(n: Int) = if (n == 1) "1 clip" else "$n clips"
    override val vs = "vs"
    override val deleteMatch = "Delete match and its clips"

    override val recordPlay = "Record play"
    override val importVideo = "Import video"
    override val chooseMatch = "Which match?"

    override val allClips = "All"
    override val starred = "Starred"
    override val noClips = "No clips"
    override val noClipsHelp = "Record a play or import a video from the gallery."
    override fun kindLabel(key: String) = when (key) {
        "half" -> "Half"
        "full" -> "Full match"
        "training" -> "Training"
        else -> "Play"
    }

    override val recordHint = "Tap the red button to record a play"
    override val recording = "REC"
    override val cameraPermission = "The app needs the camera to record. The microphone is optional."
    override val grantPermission = "Allow"
    override fun clipSaved(title: String) = "Saved: $title"
    override val recordFailed = "The clip could not be recorded"
    override val defaultClipTitle = "Play"

    override val clipDetails = "Clip details"
    override val duration = "Duration"
    override val size = "Size"
    override val resolution = "Resolution"
    override val frameRate = "Frames per second"
    override val file = "File"
    override val kind = "Type"
    override val star = "Star"
    override val unstar = "Unstar"
    override val share = "Share"
    override val exportGallery = "Save to gallery"
    override val exported = "Saved to the gallery"
    override val exportFailed = "Could not save to the gallery"
    override val deleteClip = "Delete clip"
    override val title = "Title"
    override val videoMissing = "The video file is missing"

    override val statusLocal = "On the phone"
    override val statusPreparing = "Preparing"
    override val statusUploading = "Sending"
    override val statusUploaded = "Uploaded"
    override val statusSegmenting = "Analyzing…"
    override val statusReady = "Ready"
    override val statusFailed = "Failed"

    override val sendForAnalysis = "Send for analysis"
    override val sendNoServer = "No server set up: the chunks are shared."
    override fun preparingChunk(n: Int, of: Int, pct: Int) = "Preparing · chunk $n of $of · $pct%"
    override fun sendingPct(pct: Int) = "Sending… $pct%"
    override val waitingConnection = "Waiting for a connection…"
    override val sentOk = "Sent ✓"
    override val queuedForAnalysis = "Queued for analysis"
    override val analyzing = "Analyzing…"
    override val analysisReady = "Analysis ready"
    override val analysisFailed = "The analysis failed"
    override val viewAnalysis = "View analysis"
    override val consentLine = "By sending you confirm you have the consent of the people who appear in the video."
    override val sessionExpired = "Your session expired: log in again in Settings"
    override val consentWithdrawnSend = "You withdrew your consent: send again to accept it again"
    override val termsChanged = "The video terms changed: update the app"
    override val partTooLarge = "A part of the video is bigger than the server accepts"
    override val loginFirst = "Log in at Settings → Analysis system"
    override val retry = "Retry"

    override fun chunksSummary(count: Int, seconds: Int, mb: String, frames: Int) =
        "$count ${if (count == 1) "chunk" else "chunks"} of ${if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"} · $mb MB · $frames frames"
    override fun samCost(usd: String) = "Estimated SAM 3.1 cost: US$$usd"
    override val deleteCopy = "Delete the analysis copy"
    override val copyDeleted = "Analysis copy deleted"
    override val chunkProfile = "Analysis video chunks"
    override val profileDev = "1-min chunks (development)"
    override val profileDemo = "5-min chunks (demo)"
    override fun overlapLabel(seconds: Int) = "Overlap: $seconds s"
    override val highResolution = "High resolution (1080p)"
    override val highResolutionHelp = "More detail for distant players; twice the size."
    override fun proxySpec(height: Int, mbps: String, mbMin: String) = "${height}p · $mbps Mbps · ~$mbMin MB per minute"
    override val tempCopies = "Temporary copies"

    override val language = "Language"
    override val appearance = "Appearance"
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val storage = "Where videos are stored"
    override val storagePhone = "Phone storage"
    override val storagePrivate = "Private internal storage"
    override val storageSd = "SD card"
    override fun freeSpace(gb: String, clips: Int) = "$gb GB free · ${clipsCount(clips)}"
    override val copyPath = "Copy path"
    override val pathCopied = "Path copied"
    override fun moveHere(n: Int) = "Move ${clipsCount(n)} here"
    override fun moved(n: Int) = "Moved ${clipsCount(n)}"
    override val recordingFormat = "Recording format"
    override val recordingFormatValue = "MP4 H.264, 1080p at 30 fps (720p if the camera has no 1080p)"
    override val showTechnical = "Show technical details"

    override val analysisSystem = "Analysis system"
    override val serverUrl = "Server address"
    override val email = "Email"
    override val password = "Password"
    override val login = "Log in"
    override val logout = "Log out"
    override fun loggedInAs(name: String) = "Logged in as $name"
    override val testConnection = "Test connection"
    override val connectionOk = "Connection OK"
    override val connectionFailed = "Could not reach the server"
    override val wrongPassword = "Wrong email or password"
    override val tooManyAttempts = "Too many attempts: wait a few minutes"

    override val consentTitle = "Before you send your first video"
    override val consentIntro = "PP-Coach-Tracker uses your videos only to generate the analysis of your matches. To send it, please confirm:"
    override val consentRights = "I have the right to use this video. I or my club recorded it, or I have permission from whoever did."
    override val consentPeople = "I have the consent of the people who appear in it, and of their parents or guardians if they are minors."
    override val consentAi = "I authorize PP-Coach-Tracker to process the video to generate analytics: detecting and tracking players and the ball with artificial intelligence (including Meta models) and computing tactical statistics and insights."
    override val consentRead = "I have read the privacy notice and the full terms."
    override val consentPromise = "We don't use your video to train models, publish it or share it with other users. We delete the video from the server after the analysis, and you can withdraw your consent at any time from Settings."
    override val acceptAndSend = "Accept and send"
    override val privacyNotice = "Privacy notice"
    override val fullTerms = "Full terms"
    override val playersForm = "Form for players and guardians"
    override val privacyAndConsent = "Privacy and consent"
    override fun consentAccepted(version: String, date: String) = "You accepted the video terms of use (version $version) on $date"
    override val consentNone = "You have not accepted the video terms of use yet."
    override val withdrawConsent = "Withdraw consent"
    override val consentWithdrawn = "Consent withdrawn; pending uploads were cancelled."

    override val analysisTitle = "Analysis"
    override val analyzedWith = "Analyzed with SAM 3.1 and Muse Spark by Meta"
    override val simulatorWarning = "Development analysis: simulator, not SAM 3.1"
    override val possession = "Possession"
    override val ballSeen = "Ball seen"
    override fun possessionChanges(n: Int) = if (n == 1) "1 possession change" else "$n possession changes"
    override val findings = "Findings"
    override val noFindings = "No findings for this clip."
    override fun severityLabel(key: String) = when (key) {
        "high" -> "IMPORTANT"
        "medium" -> "TO REVIEW"
        "low" -> "DETAIL"
        else -> "DATA"
    }
    override val whatToDo = "What to do"
    override val watchInVideo = "Watch in video"
    override val teamsSideBySide = "The teams"
    override val playersVisible = "Players in view"
    override val longestPossession = "Longest possession"
    override val pressure = "Pressure (opponents near the ball)"
    override val widthInImage = "Width in the image"
    override val depthInImage = "Depth in the image"
    override val heatmapTitle = "Heatmap"
    override val heatmapHelp = "As the camera sees it: not yet the pitch in meters."
    override val limitations = "Limitations"
    override val modelsUsed = "Meta models used"
    override fun framesSent(n: Int) = "$n frames sent to SAM"
    override val segmenter = "Segmenter"
    override val analysisNotYet = "The analysis is not ready yet."
    override val analysisOffline = "Offline: the analysis could not be downloaded."
    override val analysisSessionExpired = "Your session expired: log in to see the analysis."

    override val pitchQuestionTitle = "Shall we compute the pitch?"
    override val pitchQuestionIntro = "The analysis then measures distances and positions in meters, with the pitch points you mark."
    override val pitchLinesQuestion = "Are the pitch lines clearly visible in this video?"
    override val pitchLines = "Yes, clearly"
    override val pitchNoLines = "Barely or not at all"
    override val pitchOff = "Do not compute the pitch"
    override val pitchUseForMatch = "Use this answer for the other videos of this match"
    override val computePitch = "Compute the pitch"
    override val pitchAnswerLines = "Clear lines"
    override val pitchAnswerNoLines = "No lines: with the marked points"
    override val pitchAnswerAsk = "Asked when sending"
    override val changeAnswer = "Change answer"
    override val markPitchPoints = "Mark pitch points"
    override fun pointsMarked(n: Int) = if (n == 1) "1 point marked" else "$n points marked"
    override val noLinesNoPoints = "Without marked points the pitch is not computed."
    override val pitchTouchesTitle = "Pitch points"
    override val football11 = "11-a-side"
    override val football7 = "7-a-side"
    override val touchStepImage = "Tap a point of the pitch in the image"
    override val touchStepName = "Now tap which one it is in the drawing"
    override val touchNeedFifth = "With 4 points any names fit: mark a fifth"
    override fun touchReady(n: Int) = "Ready: $n points. The computed pitch shows in violet."
    override val undo = "Undo"
    override val clearAll = "Clear all"
    override val frameAt = "Frame"
    override fun marksSummary(points: Int, lines: Int) = buildList {
        if (points > 0) add(if (points == 1) "1 point marked" else "$points points marked")
        if (lines > 0) add(if (lines == 1) "1 line painted" else "$lines lines painted")
    }.joinToString(" and ")
    override fun referencesSummary(n: Int, strokes: Int) = (if (n == 1) "1 reference picture" else "$n reference pictures") + " · " + strokesCount(strokes)
    override val referenceImages = "Reference pictures of the pitch"
    override val referencesTitle = "Reference pictures"
    override val referencesHelp = "If the light is poor or the lines are worn out: take photos of the pitch (best from where you record, in good light) or use a frame of the video, and paint over the lines with your finger. SAM uses them as examples of what a line is. They serve every video of this match."
    override val takePhoto = "Take photo"
    override val useThisFrame = "Use this frame"
    override val referencesMax = "There are 6 pictures already: the most per match."
    override fun strokesCount(n: Int) = if (n == 1) "1 line painted" else "$n lines painted"
    override val referenceFromPhoto = "Photo"
    override val referenceFromFrame = "Video frame"
    override val paintTitle = "Paint lines"
    override val paintHelp = "Run your finger over each line of the pitch, even if it can barely be seen."
    override val modePoints = "Tap points"
    override val modeLines = "Paint lines"
    override val lineStepPaint = "Run your finger over a line of the image (works even if it is faint or worn out)"
    override val lineStepName = "Which line is it? Tap it in the drawing"
    override val justALine = "Not sure, it is just a line"
    override fun touchReadyMarks(points: Int, lines: Int) = "Ready: ${marksSummary(points, lines)}. The computed pitch shows in violet."
    override val pitchInMeters = "On the pitch, in meters"
    override val metersPerMinute = "Meters per player per minute"
    override val sprintSpeed = "Sprint speed"
    override val teamWidth = "Team width"
    override val teamLength = "Team length"
    override fun pitchShare(pct: Int, seconds: Int) = "From $pct% of the video ($seconds s with the pitch computed)."
    override val pitchApproximate = "Default pitch measures: distances are approximate."
    override val topDownTitle = "The pitch from above"
    override val fewPitchWarning = "The pitch is barely visible in this video; the analysis may come out empty. You can send it anyway."
}

val LocalStrings = staticCompositionLocalOf<Strings> { Es }

fun stringsFor(code: String): Strings = if (code == "en") En else Es
