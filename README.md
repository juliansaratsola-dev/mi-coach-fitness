# mi-coach-fitness

Coach personal de running + calistenia con memoria persistente en el repo.

MVP Android (Kotlin) que vigila capturas de pantalla y, si Garmin Connect estuvo en primer plano, pregunta si quieres analizarlas con Copilot.

## Qué hace

1. Una pantalla activa o detiene el monitoreo.
2. Un `ForegroundService` registra un `ContentObserver` sobre `MediaStore.Images`.
3. Una imagen nueva cuenta como screenshot si el nombre o la ruta contiene `Screenshots`, `screenshot` o `captura`.
4. Con acceso de uso, se comprueba que `com.garmin.android.apps.connectmobile` estuvo en primer plano en una ventana de 10 segundos alrededor de la captura (`DATE_TAKEN`, o `DATE_ADDED` si no hay fecha de toma).
5. Si coincide, aparece una notificación con la acción **Analizar con Copilot**. Esa acción abre un share intent (`ACTION_SEND`) con la URI `content://` de la imagen.
6. La imagen no se sube a ningún servidor. El análisis lo hace la app que elijas en el menú de compartir.

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

## Permisos

| Permiso | Para qué | Cómo se concede |
| --- | --- | --- |
| Notificaciones (`POST_NOTIFICATIONS`, Android 13+) | Servicio en primer plano y aviso de análisis | Diálogo de la app |
| Fotos (`READ_MEDIA_IMAGES` en Android 13+, `READ_EXTERNAL_STORAGE` en 11–12) | Leer `MediaStore.Images` | Diálogo de la app. Hay que elegir **permitir todas las fotos**, no solo una selección |
| Acceso de uso (`PACKAGE_USAGE_STATS`) | Saber si Garmin Connect estaba activa | Ajustes del sistema → Acceso de uso. No es un permiso runtime normal |
| Servicio en primer plano (`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`) | Mantener el observer con la app cerrada | Se declara en el manifest. En Android 14+ el tipo es `specialUse` |
| Arranque (`RECEIVE_BOOT_COMPLETED`) | Reanudar el monitoreo si el switch quedó activo | Automático |

Sin acceso de uso el filtro Garmin no puede ejecutarse. En ese caso el MVP sigue y notifica cualquier screenshot reciente (fallback), y la pantalla lo indica.

## Cómo probar

1. Compila e instala el APK debug en un dispositivo o emulador con Android 11 o superior.
2. Abre **Mi Coach Fitness**.
3. Pulsa **Revisar permisos** y concede notificaciones y acceso a todas las fotos.
4. Pulsa **Abrir acceso de uso** y activa Mi Coach Fitness.
5. Activa el switch **Monitoreo activo**. Debe quedar una notificación permanente de monitoreo.
6. Abre Garmin Connect (`com.garmin.android.apps.connectmobile`) y toma una captura. El archivo suele llamarse `Screenshot_….png` o `Captura de pantalla….png` y vivir en `Pictures/Screenshots`.
7. En unos segundos debe llegar la notificación **Captura de Garmin detectada** con la acción **Analizar con Copilot**.
8. Al pulsarla se abre el menú de compartir con la URI de esa imagen. Elige Copilot (o cualquier app que acepte `image/*`).
9. Desactiva el switch, o pulsa **Detener** en la notificación permanente, y comprueba que el observer se va.

Prueba del fallback: deja el monitoreo activo sin conceder acceso de uso, toma un screenshot de cualquier app y confirma que igual aparece la notificación. Con el acceso de uso concedido, un screenshot tomado fuera de la ventana de 10 s de Garmin no debe notificar.

Los tests de JVM cubren los marcadores de ruta y la ventana de 10 s:

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
- La app no pide `MANAGE_EXTERNAL_STORAGE` ni accede a la red.

## Mapa rápido

- `MainActivity`: switch de monitoreo y estado de permisos.
- `ScreenshotMonitorService`: foreground service + `ContentObserver`.
- `ScreenshotClassifier` / `GarminActivityWindow`: reglas puras, con tests.
- `GarminUsageChecker`: puente a `UsageStatsManager`.
- `AnalyzeWithCopilotActivity`: share intent con la URI de la captura.
