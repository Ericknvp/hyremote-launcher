# HyRemote — control web para el proyector HY320

Controla el proyector Android HY320 desde el iPhone por navegador. Sin apps
en el celular, sin PC encendida, sin servicios externos.

```
iPhone (Safari)  ──WiFi──►  HY320  ──►  Android
   http://192.168.40.14:8080
```

---

## Estado actual

**Funciona.** Verificado sobre el equipo real:

| Función | Estado |
|---|---|
| Control por web (D-pad, Home, Atrás, Vol±, Power) | Funciona |
| Escribir texto con el teclado del iPhone | Funciona |
| Lista de apps con íconos reales | Funciona, 23 apps |
| Apps ordenadas por uso reciente | Funciona |
| Ordenar y ocultar apps | Funciona |
| Mute con estado real | Funciona |
| Barra de volumen real | Funciona |
| Modo mouse nativo | Depende de activar el servicio del fabricante |
| Arranque automático al prender | Funciona |
| QR en el launcher del proyector | Funciona, **generado en vivo** |
| Panel de Bluetooth propio (lista, búsqueda, switch) | Funciona |
| Entrada HDMI, ajustes, archivos, proyección keystone | Funciona |
| Latencia y uso de CPU | 62-88 ms, 1 solo proceso `nc` |

**Latencia:** estable en 135-160 ms. Antes oscilaba entre 100 ms y 2100 ms.
Causa y arreglo en [Latencia y doble arranque](#latencia-y-doble-arranque).

**El bridge corre como root**, no como la app. No es un detalle: Android 13
le niega `INJECT_EVENTS` a un uid de app, así que un bridge de app responde
`{"ok":1}` a cada botón sin que pase nada en pantalla. Ver
[Root](#por-qué-el-bridge-necesita-root).

---

## Cómo usarlo

1. Encende el proyector y **espera ~6 segundos**.
2. Escanea el QR con la cámara del iPhone.
3. Listo.

El bridge lo arranca `init` solo, temprano en el boot, así que ya está
escuchando antes de que aparezca el launcher.

En el launcher del proyector también ves el QR y el estado del bridge.

**Requisitos:** el iPhone y el proyector en la misma red WiFi.

Si la página parece rota, cerra la pestaña y volve a abrir la URL: Safari
cachea el HTML y puede mostrar una versión vieja.

---

## El QR se genera en vivo

El QR **no** es una imagen fija: el launcher lo dibuja en pantalla, en el
momento, con la dirección IP de ese proyector y con el puerto que el bridge
realmente tomó.

- La IP se lee de la interfaz de red del propio equipo (`NetworkInterface`),
  no de un archivo del proyecto.
- El puerto se **descubre**: el launcher prueba `127.0.0.1` en el rango
  8080-8095 y se queda con el que responde `pong`. `serve.sh` hace lo mismo
  desde el otro lado: si el 8080 está ocupado, toma el siguiente libre y lo
  escribe en `/data/local/tmp/hyremote/port`.

Consecuencia: **el mismo APK funciona en cualquier proyector y en cualquier
casa**, sin recompilar y sin editar nada. El QR que ves en pantalla siempre
apunta a la dirección real de ese equipo. Debajo se muestra la URL en texto
por si hay que teclearla.

El QR se dibuja con un generador embebido (`src/com/hyremote/ctrl/qr/`, el
algoritmo de Project Nayuki, licencia MIT), así que no hace falta ninguna
librería externa ni conexión a internet.

---

## El flujo la primera vez

Cuando alguien instala el APK (sideload) la app está en estado *stopped*: no
recibe el `BOOT_COMPLETED` y no es el launcher. El flujo completo es:

1. **Instala** el APK y la abre una vez (desde el explorador de archivos o
   `adb install`).
2. Aparece el panel de bienvenida: logo, una línea de explicación y
   **"Poner como launcher"**.
3. Al pulsarlo la app se pone de launcher **sola**: lo pide al bridge root
   (que hace `set-home-activity` + el rol `HOME` + desactiva el launcher de
   fábrica, porque el HOME por tecla lo despacha el `default_home` del
   sistema). Sin bridge root, sale el diálogo del sistema para confirmarlo, o
   la pantalla de ajustes del launcher.
4. A partir de ahí, en cada arranque: el bridge levanta 1 s después del boot y
   el launcher en pantalla ~15 s después, solo.

La app **no vuelve a preguntar**: el panel solo sale la primera vez. Y el chip
**"Inicio"** de la barra inferior permite cambiar de launcher cuando quieras
(HyRemote ↔ original), con el mismo mecanismo.

---

## El launcher

Una sola identidad visual, "cinematográfico oscuro": fondo profundo con
degradado, superficies de cristal, un solo acento azul y tipografía grande.

| Zona | Qué hay |
|---|---|
| Barra superior | Logo, marca, **INICIO**, **icono WiFi con el nombre de la red**, **icono de Bluetooth**, estado del bridge con la latencia |
| Columna izquierda | Fecha, reloj hero, y a su derecha el QR dinámico |
| Accesos rápidos | Seis tiles grandes con iconos dibujados a mano: Ajustes · Archivos · HDMI · Proyección · Bluetooth · Inicio |
| Columna derecha | **RECIENTES** (las apps usadas hace poco, más grandes) y debajo **APLICACIONES**, ordenadas por uso reciente |
| Barra inferior | Accesibilidad · Bridge · Apps · Acerca de |

- **WiFi**: el icono abre los ajustes de red; debajo va el nombre de la red
  (Android solo da el SSID con el permiso de ubicación; sin él se muestra la IP).
- **Bluetooth**: abre un panel propio con el interruptor del radio, los
  dispositivos emparejados y un buscador de los que están cerca.
- **Inicio**: permite volver al launcher de fábrica cuando quieras, y
  regresar a HyRemote igual de fácil.
- **RECIENTES**: las apps abiertas en los últimos 7 días, con tiles más
  grandes. La fila se alimenta de lo que el bridge ve en primer plano, así que
  cuenta también lo que abriste con el control físico, no solo lo que abriste
  desde aquí.
- **HDMI**: cambia a la entrada HDMI1 (es lo que hace el botón "Source" del
  control físico).
- **Proyección**: abre la pantalla real de ajuste del proyector,
  `com.softwinner.tcorrection` → *Projection Settings*: Projection Mode,
  Zoom in/out, Auto Trapezoid, Fast Keystone Correction, Corner Correction y
  Projection Horizontal calibration. Es la app que usa el launcher de fábrica,
  y sus actividades están exportadas, así que HyRemote puede abrirlas.
- **Ajustes**: los ajustes del launcher de fábrica, `com.htc.htcsettings` →
  Networking, Bluetooth, Language, Other, Projection, Audio, Date and time y
  About device. (Si no están, cae al *Quick Setting* del fabricante y luego a
  los ajustes normales de Android TV.)
- **Acerca de**: logo, crédito y portfolio **ericknvp.dev**.

Todo se maneja con D-pad: cada control pinta su propio anillo de foco.

---

## Arquitectura

El bridge es un servidor HTTP que corre **dentro del proyector**, escrito en
shell sobre `busybox`. Android ejecuta las teclas nativas con `input keyevent`,
que son los mismos keycodes que usa el control físico.

```
iPhone                    HY320
  │                         │
  │  POST /key  body="66"   │
  ├────────────────────────►│  nc → handler.sh
  │                         │      → input keyevent 66
  │◄──── {"ok":1} ──────────┤
```

Cada botón del control es un número de keycode de Android:

| Botón | Código | | Botón | Código |
|---|---|---|---|---|
| Home | 3 | | OK/Enter | 66 |
| Atrás | 4 | | Vol + | 24 |
| Arriba | 19 | | Vol − | 25 |
| Abajo | 20 | | Mute | 164 |
| Izquierda | 21 | | Power | 26 |
| Derecha | 22 | | Menú | 82 |

### Endpoints

| Ruta | Método | Qué hace |
|---|---|---|
| `/` | GET | Devuelve el HTML del control |
| `/ping` | GET | `pong` — chequeo de vida |
| `/key` | POST | Envía un keycode. Body: número |
| `/text` | POST | Escribe texto. Body: texto (`%s` = espacio) |
| `/tap` | POST | Toque. Body: `x,y` |
| `/swipe` | POST | Deslizamiento. Body: `x1,y1,x2,y2,dur` |
| `/apps` | GET | Lista de apps con íconos embebidos |
| `/app` | POST | Abre una app. Body: package name |
| `/volume` | GET | Volumen real: `actual/max/mute` |
| `/focus` | GET | Package en primer plano |
| `/radio` | POST | Enciende o apaga el Bluetooth. Body: `on` u `off` (nada más) |
| `/pkg` | POST | Oculta o vuelve a mostrar una app. Body: `disable <paquete>` o `enable <paquete>` |
| `/home` | POST | Cambia el launcher predeterminado. Body: `hyremote` u `original` |
| `/icon` | GET | Ícono PNG (obsoleto, ahora van embebidos) |

---

## Archivos

```
hyremote/
├── src/                      Código Java del launcher
│   └── com/hyremote/ctrl/
│       ├── MainActivity.java     El launcher del proyector
│       ├── StatusIconView.java   Los iconos de WiFi y Bluetooth, dibujados a mano
│       ├── BluetoothPanel.java   Panel de Bluetooth (lista, búsqueda, switch)
│       ├── RemoteService.java    Notificación + watchdog (no sirve el puerto)
│       ├── BootReceiver.java     Se ejecuta al prender
│       ├── RemoteAccessibilityService.java   (obsoleto)
│       └── qr/                   Generador de QR embebido (Nayuki, MIT)
│
├── res/
│   ├── drawable/logo_mark.png   El logo, recortado en círculo
│   ├── mipmap-*/ic_launcher.png  Icono de la app (adaptativo en anydpi-v26)
│   └── values/colors.xml
│
├── assets/img/logo.jpg        El logo original (fuente de los recortes)
├── tools/make-logo.py         Genera los recortes del logo y los iconos
│
├── www/
│   ├── src.js               Plantilla del control web
│   └── index.html            Generado — no editar
│
├── handler.sh               ★ El corazón: rutas HTTP → input keyevent
├── serve.sh                 Listener persistente (nc -L), toma un puerto libre
├── boot.sh                  Arranque del bridge, en primer plano
├── buildapps.sh             Prepara /apps con íconos embebidos
├── listapps.sh              Respaldo para listar apps
│
├── hyremote.rc              Servicio de init: levanta el bridge como root
├── setup.ps1                Instala el bridge en /system (una vez)
├── verify.ps1               Chequea que todo responda
├── build.ps1                Compila el APK
├── build-web.js             Genera index.html y lo valida
├── gen-qr.js                QR estático, solo para depurar
└── hyremote-fase0.apk       APK compilado
```

### Archivos que importan

**`handler.sh`** — traduce HTTP a `input keyevent`. Todo el control pasa por
acá. Ojo con los saltos de línea: **deben ser LF**, no CRLF (ver más abajo).

**`hyremote.rc`** — le dice a `init` que arranque el bridge como root. Es lo
que hace que los botones funcionen.

**`RemoteService.java`** — foreground service. Solo muestra la notificación y
vigila que el bridge esté vivo; **no** se queda con el puerto 8080. Si lo
hace, se queda con un puente sin permisos y el control deja de funcionar.

**`MainActivity.java`** — el launcher. Publica la lista de apps con íconos,
dibuja el QR con la IP y el puerto del momento, y trae los accesos rápidos
(Ajustes, Archivos, HDMI, Proyección, Bluetooth).

---

## Compilar

```powershell
# El APK
powershell -ExecutionPolicy Bypass -File build.ps1

# La interfaz web (obligatorio si tocaste www/src.js)
node build-web.js

# Los recortes del logo y los iconos (solo si cambiaste assets/img/logo.jpg)
python tools/make-logo.py
```

## Instalar

En un proyector nuevo son **tres pasos**:

```powershell
# 1. El launcher
adb install -r hyremote-fase0.apk

# 2. El bridge como root (una vez por dispositivo)
powershell -ExecutionPolicy Bypass -File setup.ps1

# 3. Dejarlo como launcher y apagar el del fabricante
adb shell cmd package set-home-activity com.hyremote.ctrl/.MainActivity
adb shell pm disable-user --user 0 com.htc.htclauncherhighenglishd08
```

El paso 2 reinicia el proyector la primera vez (init solo lee los `.rc` al
boot). Las siguientes veces aplica los cambios sin reiniciar. Después,
`verify.ps1` comprueba que todo responda.

> El paso 2 es el único que necesita un PC en la misma red. Sin él, el APK
> sigue instalando y el launcher funciona, y la página web también abre
> (el bridge de respaldo la sirve), pero **los botones no inyectan teclas**:
> eso solo lo puede hacer un proceso root.

Si tocaste `handler.sh`, `serve.sh` o `boot.sh`, corré `setup.ps1` además de
recompilar el APK: los scripts que ejecuta `init` viven en `/system`, no en la
app.

`build-web.js` valida sintaxis, encoding y acentos. **Si falla, no subas nada**:
una versión con JavaScript roto se ve perfecta pero ningún botón responde.

### Trampa: CRLF

Android no tolera saltos de línea de Windows en los `.sh`:

```
handler.sh[16]: : inaccessible or not found
handler.sh[22]: syntax error: unexpected 'in'
```

Los espacios al inicio de línea también rompen los `case` de `sh`. Si un
endpoint devuelve 404 sin razón, sospechá de esto primero.

`build.ps1` ya fuerza LF al copiar los scripts al APK.

---

## Diagnóstico

```powershell
# ¿Contesta el bridge?
curl http://192.168.40.14:8080/ping     # debe decir pong

# ¿Cuántos listeners hay? (debe ser 1)
adb shell "netstat -ltnp | grep 8080"

# ¿Cuántos procesos? (debe ser 1 nc, 1 serve.sh)
adb shell "ps -A -o NAME | grep -w nc | wc -l"

# ¿Qué escribió el servicio al arrancar?
adb logcat -d -s HyRemoteBoot HyRemoteSvc

# Historial del bridge
adb shell "tail -20 /data/local/tmp/hyremote/bridge.log"
```

Conectar por ADB:

```powershell
adb connect 192.168.40.14:5555
```

El `adbd` del HY320 corre como **root** y escucha en el 5555. Eso es lo que
permite controlar el equipo sin PC.

---

## Desbloqueos que hubo que hacer

El firmware del HY320 bloquea varias cosas por defecto. Esto se hizo **una
vez**; si reinstalas de fabrica, hay que repetirlo.

**1. Accesibilidad habilitada sin diálogo**

La app era el plan original: un `AccessibilityService` que inyecta teclas.
Funcionaba y el sistema lo revocaba ~1 segundo después. Log:

```
accessibility CONNECTED
accessibility UNBOUND     ← a los 1,3 s
```

Android 13 lo hace por *restricted settings*, que bloquea apps instaladas
fuera de la Play Store. Declarar la app como **launcher** la exime, porque
Android necesita ese excepción justamente para TVs y proyectores.

Por eso el proyecto virou a `input keyevent` con root: es la ruta que usan
scrcpy y las herramientas reales.

**2. Launcher del fabricante desactivado**

Había dos launchers y ganaba el del fabricante:

```powershell
adb shell pm disable-user --user 0 com.htc.htclauncherhighenglishd08
```

No se desinstala, solo se apaga. Para revertirlo: `pm enable`.

**3. Visibilidad de apps**

Android 11+ esconde las apps que no interactuaste. Sin
`QUERY_ALL_PACKAGES` el launcher solo veía apps del sistema y la lista salía
con 11 entradas en vez de 24.

---

## Por qué el bridge necesita root

El síntoma era desconcertante: cada botón respondía `{"ok":1}` y no pasaba
nada. No era el navegador, ni el WiFi, ni la latencia.

La causa es que `input keyevent`, `monkey` y `dumpsys` son herramientas de
sistema, y Android 13 no se las presta a un uid de app. El bridge lo arrancaba
`RemoteService`, así que corría como `u0_a85` y cada `input keyevent` moría con
un `SecurityException` que `handler.sh` descartaba a `/dev/null`:

```sh
input keyevent "$KEY" >/dev/null 2>&1     # el error se perdía aquí
printf '{"ok":1}'                        # ... y el bridge decía que todo bien
```

Lo mismo pasaba con `/volume` y `/focus`: `dumpsys` le devuelve la nada a un
uid de app, así que el launcher informaba `0/50/1` (volumen 0, muteado)
mientras el equipo tenía 35.

Cómo se comprobó, sin depender de la intuición:

```powershell
adb shell input keyevent 3                # como root -> funciona
curl -X POST -d 3 http://192.168.40.14:8080/key   # como app -> no hace nada
```

`INJECT_EVENTS` es `signature|privileged`, así que `pm grant` la rechaza:

```
SecurityException: Permission android.permission.INJECT_EVENTS requested by
com.hyremote.ctrl is not a changeable permission type
```

Y la accesibilidad —el plan original— Android 13 la revoca a los 1,3 s aunque
la app se declare launcher:

```
accessibility CONNECTED
accessibility UNBOUND     ← 1,3 s después
```

Lo que sí funciona en este firmware: **`adbd` corre como root** (dev build,
`ro.build.type=userdebug`), y `adb remount` deja `/system` escribible. La
solución es que `init` levante el bridge como root desde `/system`, en vez de
que lo arranque la app.

## Latencia y doble arranque

```
antes:  249 ms ... 2363 ms ... 1196 ms   (y 29 sockets en TIME_WAIT)
ahora:  135 ... 159 ms                    (1 socket)
```

**Causa:** `nc -l` sirve una conexión y sale. El socket siguiente queda en
`TIME_WAIT` y el re-listening corre antes de que se libere, así que perdía la
carrera y el `while` tenía que dormir entre intentos. Además `boot.sh` corría
dos veces (una por el boot, otra al abrir el launcher) y cada una levantaba su
propio `serve.sh`: llegaron a coexistir 9 loops compitiendo por el 8080.

**Arreglo:** `nc -L` —el modo servidor de toybox— mantiene el listener vivo y
forkea un handler por conexión. Desaparece el ciclo de rebind, el `sleep`, y
con él la latencia variable. Y `boot.sh` ahora es idempotente, con un mutex
`mkdir`, así que un segundo arranque no toca el puerto.

**Arranque:** `init` levanta el bridge en `class main`, antes de
`BOOT_COMPLETED`. Se eliminó además el `Thread.sleep(8000)` de
`RemoteService`, que era una apuesta sobre cuándo estaría listo el WiFi — y el
bridge no lo necesita, `nc` abre el socket antes de que exista ninguna
interfaz. De ~20 s a **~6 s** después de prender.

`RemoteService` ya no se queda con el puerto 8080: solo avisa si está libre. Un
bridge de app puede ocupar el puerto perfectamente, y ganar esa carrera
dejaría el control sin permisos otra vez.

### Modo mouse

Requiere activar el servicio del fabricante:

```
Ajustes → Accesibilidad → accessibility
```

Sin ese interruptor, el modo mouse no tiene a quién escuchar. El firmware no
permite habilitarlo por ADB: el sistema lo revierte.

---

## Notas del hardware

| | |
|---|---|
| SoC | Allwinner T950S (`t950s_be311`) |
| Android | 13 (API 33) |
| RAM | 4 GB |
| Pantalla | 1280×720 |
| Root | `adbd` corre como root, ADB por red en el 5555 |

**No se puede encender el proyector por red.** Sin IR ni wake-on-lan. Desde
la web solo se apaga; para encender, el control físico.

---

## Arranque en frío

Dos cosas rompían el arranque, y las dos son trampas de Android 12+:

**1. La app congelada no recibe el `BOOT_COMPLETED`.**
Android 12+ congela (*freezer*) las apps que quedan en segundo plano, y una
app congelada no recibe broadcasts: el AMS los **aplaza** y quedan en la cola
`Deferred BOOT_COMPLETED broadcasts` para siempre. Como el launcher del
fabricante (`com.jm.jmlauncher`) toma la pantalla al arrancar, HyRemote quedaba
cached → congelado → nunca se enteraba del arranque, y el proyector se
quedaba en el splash "Smart projector" hasta que pulsabas HOME.

Dos avisos:

- `boot.sh` (que corre como root en `class main`) desactiva el congelador:
  `settings put global cached_app_freezer disabled`.
- `launch.sh` (servicio `hyremote-launcher` de init) arranca el launcher con
  `monkey`, que sí funciona aunque el paquete siga congelado
  (`am start` responde *"Activity class does not exist"*).

**2. El mutex de `boot.sh` no puede vivir en `/data/local/tmp`.**
El lock era un `mkdir $DIR/boot.lock`, y ese directorio **sobrevive al
reinicio**: si el proyector se apagaba con el lock tomado, todos los arranques
posteriores salían con *"another start in flight, skipping"* y **el bridge
nunca levantaba**. El lock ahora vive en `/dev` (tmpfs), que se limpia en cada
arranque.

Con las dos cosas arregladas, en frío: el bridge está escuchando 1 s después
del boot y el launcher en pantalla ~15 s después.

## Desinstalar

Para dejar el proyector como salió de fábrica:

```powershell
powershell -ExecutionPolicy Bypass -File uninstall.ps1
```

Quita el bridge de `/system`, borra el servicio de init, devuelve el launcher
del fabricante como pantalla de inicio y desinstala la app (`-KeepApp` la deja
instalada pero desactivada).

**Un detalle que costó un disgusto:** el fabricante **reactiva su propio
launcher** en algunos reinicios, y el botón HOME del control físico lo
prefiere siempre al launcher que tenga el rol. Por eso `launch.sh` desactiva
`com.htc.htclauncherhighenglishd08` en cada arranque cuando HyRemote es el
launcher: sin eso, el HOME se lo queda el de fábrica y parece que HyRemote no
está instalado.

---

## Autor

Desarrollado por **@ericknvp** — [ericknvp.dev](https://ericknvp.dev)