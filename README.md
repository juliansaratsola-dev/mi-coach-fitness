# mi-coach-fitness

Coach personal de running + calistenia con historial privado en el dispositivo.

App Android (Kotlin) que detecta capturas de Garmin Connect y permite acumularlas para un review con Gemini guiado por la filosofía **Verdad Pura**: el éxito se mide por el esfuerzo relativo, la constancia y la resiliencia, no por el ritmo. También conserva la opción de compartir una captura con Copilot.

## Qué hace

1. Una pantalla activa o detiene el monitoreo.
2. Un `ForegroundService` registra un `ContentObserver` sobre `MediaStore.Images`.
3. Una imagen nueva cuenta como screenshot si el nombre o la ruta contiene `Screenshots`, `screenshot` o `captura`.
4. Con acceso de uso, se comprueba que `com.garmin.android.apps.connectmobile` estuvo en primer plano en una ventana de 10 segundos alrededor de la captura (`DATE_TAKEN`, o `DATE_ADDED` si no hay fecha de toma).
5. Si coincide, aparece una notificación con **Agregar Coach** (suma la captura sin abrir la app; repítelo con cada captura del entrenamiento) y **Analizar entreno** (abre el Coach y genera el review de inmediato).
6. El Coach envía las capturas elegidas, tus notas de sensaciones, tu Form Score y un resumen de tus últimos 5 entrenos a la API de Gemini (en el chat, hasta 8 sesiones). El historial se guarda en el almacenamiento privado de la app en el dispositivo; Gemini recibe esos datos al generar cada análisis.

## Verdad Pura: esfuerzo, no ritmo

Las apps de fitness premian récords y ritmos rápidos, y esconden el esfuerzo invisible y los días malos. Aquí el éxito se mide por el esfuerzo relativo, la constancia a largo plazo y la resiliencia: un principiante sufriendo a 7:30 min/km suma los mismos puntos que un veterano sufriendo a 4:00 min/km.

- **Puntos de esfuerzo** (`EffortCalculator`, independiente de la IA): carga interna estilo TRIMP, es decir, minutos en cada zona cardíaca por el peso de la zona (zonas 1 a 5 = 1.0 a 5.0, desde el 50, 60, 70, 80 y 90 % de la FC máxima). Como solo hay FC media y máxima de la sesión, los minutos por zona se estiman con una curva normal centrada en la media: es una aproximación, no una medición de laboratorio. La FC máxima es 220 − edad (190 ppm si no indicas tu edad en **Config**). El ritmo y la distancia no intervienen. Sin FC se usa un esfuerzo estándar (todo el tiempo en zona 2) y sin duración se asumen 30 min; la tarjeta avisa de que los puntos son estimados.
- **Día de trinchera**: si en *Sensaciones* aparece sufrimiento (pesado, cansancio, dolor, calor, lluvia…; «sin dolor» no cuenta) y el esfuerzo base es alto (100 puntos o más), la sesión se marca como día de trinchera y suma ×1,2. Sin FC ni duración medidas nunca lo es.
- **Form Score** (gravedad): suma de los puntos de los últimos 30 días dividida entre (días desde el último entreno + 1). Si dejas de entrenar cae día a día; nadie vive de éxitos pasados. En **Inicio** se muestra como nivel de RPG (de Oxidado a Inquebrantable); el nivel *n* empieza en 25·(*n* − 1)² puntos.
- **Consistencia**: el gráfico de **Inicio** dibuja los últimos 30 días. Cada día entrenado es una barra con altura proporcional al esfuerzo (naranja si fue de trinchera) y los días de descanso quedan vacíos.
- **Coach de la Verdad**: el prompt de Gemini es técnico y directo, sin motivación vacía. No alaba tiempos rápidos sin constancia previa, reconoce la resiliencia de los días de trinchera, normaliza el cansancio cuando los números caen y responde con un JSON estricto: `tecnica`, `cardio_y_esfuerzo`, `potencia_y_consistencia`, `comparativa_meritocratica` y `veredicto_trinchera` (más `date`, `type` y `metrics`, con los que la app calcula el esfuerzo en local).

## Coach con Gemini

1. Crea una API key de Gemini y guárdala en la pestaña **Config**. La clave queda en las preferencias privadas de la app en este dispositivo; no se incluye en el repositorio.
2. Desde cada notificación de Garmin, pulsa **Agregar al Coach** para ir reuniendo las capturas de resumen y estadísticas.
3. Abre **Coach**, añade opcionalmente cómo te sentiste (piernas pesadas, calor, lluvia, dolor…) y pulsa **Generar review**.
4. El review abre con tus **puntos de esfuerzo** (y la marca de día de trinchera, si la hay) y sigue con el veredicto de trinchera, el costo cardíaco, la comparativa meritocrática con tu historial, la consistencia del ritmo y la técnica, más los datos detectados. No hay notas del 1 al 10. Los reviews guardados antes de Verdad Pura conservan el formato con notas con el que se guardaron. La sesión analizada pasa al historial local, que muestra fecha, puntos de esfuerzo y datos secundarios para contextualizar próximos reviews.
5. Al final de **Coach**, puedes conversar libremente con la IA sobre la review, tus sensaciones y la evolución de tu historial. La conversación también se guarda localmente y cada respuesta usa la review más reciente, tu Form Score y hasta 8 sesiones como contexto.

La app conserva el historial, la conversación y la clave localmente. Al chatear, el mensaje, hasta 24 mensajes recientes, tu Form Score y el contexto de hasta 8 sesiones (incluida la última review) se transmiten a Google Gemini. Al generar un review se transmiten las capturas que envías, tus notas, tu Form Score y el resumen de tus últimos 5 entrenos; no se envían automáticamente al detectar una captura. Tu edad no se envía: solo se usa en el teléfono para calcular el esfuerzo. Puedes vaciar las capturas pendientes antes de analizarlas. La evaluación es orientativa, no médica, y no debería usarse para diagnosticar lesiones.

## Requisitos para compilar

- JDK 17
- Android SDK 35 y build-tools 35 (si no está 35.0.0, se usa el build-tools más alto instalado)
- Kotlin (`kotlinc` en `PATH` vía `KOTLIN_HOME` o `/usr/share/kotlinc`). Si no está, el build descarga el compilador 2.0.21 desde GitHub.

```bash
export ANDROID_HOME="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

El APK firmado de depuración queda en `app/build/outputs/apk/debug/app-debug.apk`.

`minSdk` es 30 (Android 11). `targetSdk` / `compileSdk` son 35.

El módulo no usa el Android Gradle Plugin. `dl.google.com` (Google Maven) no es accesible en todos los entornos, así que `./gradlew :app:assembleDebug` compila con el toolchain local del SDK: `aapt2`, `d8`, `zipalign` y `apksigner`, más `kotlinc`. No hace falta red para el APK si el SDK y Kotlin ya están instalados. Los tests bajan JUnit desde Maven Central la primera vez.

## Descargar e instalar el APK

1. En GitHub abre la pestaña **Actions** → workflow **Build APK** → la última ejecución verde de `main` (o lánzalo con **Run workflow**).
2. En **Artifacts** descarga `mi-coach-fitness-debug-apk` (es un zip; descomprímelo para obtener `app-debug.apk`).
3. Pasa el APK al teléfono y ábrelo. Android pedirá permitir **instalar apps desconocidas** para la app que uses (Archivos, Chrome...). Acepta y pulsa Instalar.
4. Alternativa por cable, con depuración USB activa: `adb install -r app-debug.apk`.

Es un APK de depuración firmado con una clave de debug, solo para instalación manual.

## Permisos

| Permiso | Para qué | Cómo se concede |
| --- | --- | --- |
| Notificaciones (`POST_NOTIFICATIONS`, Android 13+) | Servicio en primer plano y aviso del Coach | Diálogo de la app |
| Fotos (`READ_MEDIA_IMAGES` en Android 13+, `READ_EXTERNAL_STORAGE` en 11–12) | Leer `MediaStore.Images` | Diálogo de la app. Hay que elegir **permitir todas las fotos**, no solo una selección |
| Acceso de uso (`PACKAGE_USAGE_STATS`) | Saber si Garmin Connect estaba activa | Ajustes del sistema → Acceso de uso. No es un permiso runtime normal |
| Servicio en primer plano (`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`) | Mantener el observer con la app cerrada | Se declara en el manifest. En Android 14+ el tipo es `specialUse` |
| Arranque (`RECEIVE_BOOT_COMPLETED`) | Reanudar el monitoreo si el switch quedó activo | Automático |
| Internet (`INTERNET`) | Enviar solicitudes a Gemini al generar un review o conversar en el chat | Declarado en el manifest |

Sin acceso de uso el filtro Garmin no puede ejecutarse. En ese caso el MVP sigue y notifica cualquier screenshot reciente (fallback), y la pantalla lo indica.

## Cómo probar

1. Compila e instala el APK debug en un dispositivo o emulador con Android 11 o superior.
2. Abre **Mi Coach Fitness**.
3. En la pestaña **Config** (se abre sola la primera vez) pulsa **Revisar permisos** y concede notificaciones y acceso a todas las fotos.
4. Pulsa **Abrir acceso de uso** y activa Mi Coach Fitness.
5. Activa el switch **Monitoreo activo**. Debe quedar una notificación permanente de monitoreo.
6. Abre Garmin Connect (`com.garmin.android.apps.connectmobile`) y toma una captura. El archivo suele llamarse `Screenshot_….png` o `Captura de pantalla….png` y vivir en `Pictures/Screenshots`.
7. En unos segundos debe llegar la notificación **Captura de Garmin detectada**. Pulsa **Agregar Coach** para acumular cada captura; repite con las estadísticas que quieras incluir.
8. Abre **Mi Coach Fitness**, guarda tu API key de Gemini en **Config** (y, si quieres, tu edad para estimar la FC máxima) y entra en la pestaña **Coach**. Añade cómo te sentiste (opcional) y genera el review.
9. Comprueba la tarjeta de esfuerzo (los puntos en grande; si escribiste algo como «piernas pesadas» o «calor» y el esfuerzo fue alto, el borde naranja con la llama de día de trinchera), el veredicto y los datos detectados. En **Inicio** deben aparecer el nivel del Form Score, el gráfico de los últimos 30 días y el último entreno; la sesión se conserva en el historial local para próximos reviews.
### Review sin capturas (Health Connect)

Requiere Android 14 o superior. En Garmin Connect activa la integración con Health Connect (Más > Ajustes > Aplicaciones conectadas) para que escriba tus entrenamientos allí. En el Coach pulsa **Usar Health Connect**, concede los permisos y elige el entrenamiento de la lista (se muestran fecha, distancia, ritmo, FC y app de origen). Se leen distancia, ritmo, FC (mín/media/máx), potencia, cadencia, ascenso, velocidad máxima y calorías si están disponibles, solo de la app que escribió el entrenamiento. Si tienes capturas agregadas en el Coach, se envían junto con los datos para completar zancada, contacto con el suelo, oscilación, TE y Body Battery. Con el monitoreo activo, la app revisa Health Connect cada 3 minutos y, cuando termina un entrenamiento nuevo, muestra **¿Quieres analizar tu entrenamiento?**. Las métricas que Health Connect no recibe (zancada, contacto con el suelo, oscilación vertical, TE, Body Battery) salen de las capturas si las agregaste antes de pulsar el botón.

10. Desactiva el switch, o pulsa **Detener** en la notificación permanente, y comprueba que el observer se va.

Prueba del fallback: deja el monitoreo activo sin conceder acceso de uso, toma un screenshot de cualquier app y confirma que igual aparece la notificación. Con el acceso de uso concedido, un screenshot tomado fuera de la ventana de 10 s de Garmin no debe notificar.

Los tests de JVM cubren los marcadores de ruta, la ventana de 10 s, el cálculo de esfuerzo y de días de trinchera, el Form Score, el contexto oculto para la IA, el JSON de Gemini, la geometría del gráfico y el contexto comparativo del Coach:

```bash
./gradlew :app:testDebugUnitTest
```

## Limitaciones en Android 11+

- No hay API pública para enterarse de screenshots de **otra** app. `Activity.ScreenCaptureCallback` (Android 14, permiso `DETECT_SCREEN_CAPTURE`) solo avisa de capturas de la propia activity. Por eso el MVP observa `MediaStore.Images`.
- Almacenamiento con alcance: no se lee el sistema de archivos a pelo. Se usan `DISPLAY_NAME` y `RELATIVE_PATH`. La columna `DATA` puede venir vacía o redactada; si la consulta falla, se reintenta sin esa columna.
- Android 13+ exige `READ_MEDIA_IMAGES`. En Android 14+, el acceso parcial (“fotos seleccionadas”) no incluye las capturas nuevas. Sin acceso amplio el observer no ve el screenshot.
- Algunos fabricantes insertan la fila como `IS_PENDING` y la confirman después, o usan un nombre que no contiene `screenshot` / `Screenshots` / `captura`. Esas capturas no se detectan. El observer también ignora imágenes de más de 3 minutos para no avisar por fotos viejas de la galería.
- `PACKAGE_USAGE_STATS` es un app-op. Sin él no se puede afirmar que Garmin estaba en primer plano y el MVP notifica igual (fallback). Con él, la fuente principal es `UsageStatsManager.queryEvents` (resume/pause) con un lookback de 6 horas para no perder una sesión ya abierta. Si los eventos no bastan, el fallback es `lastTimeUsed` / `lastTimeVisible` dentro de los mismos 10 segundos. Los buckets de usage stats a veces no responden a consultas tan cortas: en ese caso manda el stream de eventos.
- La ventana es inclusiva: desde 10 s antes de la captura hasta 10 s después. Cubre el momento en que la UI del sistema (barra de capturas) tapa brevemente a Garmin.
- Android 11 oculta paquetes a las apps. El manifest declara `<queries>` para `ACTION_SEND` + `image/*` para que el share sheet vea destinos, incluido Copilot. No se fuerza un package name de Copilot: el usuario elige en el share intent.
- Android 12+ restringe arrancar actividades desde el fondo. La acción de la notificación abre una activity interna no exportada, y esa activity lanza el share intent y concede permiso de lectura temporal sobre la URI. Así el destino recibe la imagen.
- Android 14+ obliga a declarar un tipo de foreground service. Este monitoreo no encaja en `dataSync` ni `mediaProjection`, así que usa `specialUse`. Instalar en local vale; publicar en Play exige declarar ese caso de uso.
- El receiver de arranque solo reanuda el servicio si el switch estaba activo y los permisos runtime siguen concedidos. Si el sistema bloquea el FGS en el boot, hay que volver a abrir la app.
- La app no pide `MANAGE_EXTERNAL_STORAGE`. Solo accede a la red cuando se solicita un review de Gemini.

## Mapa rápido

- `MainActivity` (Inicio): nivel del Form Score, gráfico de consistencia de 30 días, tarjeta de esfuerzo del último entreno y switch de monitoreo. `SettingsActivity` (Config): permisos, acceso de uso, API key y edad. `BottomNav`: barra inferior de 4 pestañas (Inicio, Coach, Próximamente, Config).
- `HomeDashboard`, `EffortCardView` y `ConsistencyChart`: las vistas de Verdad Pura (nivel, tarjeta de esfuerzo y gráfico de barras).
- `CoachActivity`: capturas pendientes, review, historial y chat con el coach.
- `GeminiClient` / `CoachPrompt`: análisis multimodal, prompt de la Verdad con esquema JSON estricto y conversación contextual.
- `CoachChatStore`: persistencia local de la conversación del Coach.
- `WorkoutStore` / `WorkoutRecord` / `WorkoutJson`: historial local con `puntos_esfuerzo`, `es_dia_trinchera` y `texto_sensaciones`, y lectura del JSON guardado.
- `EffortCalculator` / `FormScore` / `HistoricalContext` / `ConsistencyChartLayout`: esfuerzo, gravedad, contexto oculto para la IA y geometría del gráfico. Son lógica pura, con tests.
- `ScreenshotMonitorService`: foreground service + `ContentObserver`.
- `ScreenshotClassifier` / `GarminActivityWindow`: reglas puras, con tests.
- `GarminUsageChecker`: puente a `UsageStatsManager`.
- `AnalyzeWithCopilotActivity`: share intent con la URI de la captura.
