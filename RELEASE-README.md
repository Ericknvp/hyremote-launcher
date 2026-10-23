# HyRemote 1.2

Launcher y control remoto para el proyector Android **HY320** (y compatibles).

- El proyector sirve la página de control desde el propio APK y muestra el QR
  con su IP real: no hay que configurar nada.
- Desde el celular (Safari o Chrome, sin instalar nada) se controla el
  proyector: D-pad, Inicio, Atrás, volumen, power, escritura de texto y
  apertura de apps.
- **Ya no necesita PC, `setup.ps1`, `/data/local/tmp` ni root.** El servidor
  HTTP vive dentro del APK. Las teclas salen por el ADB local del propio
  proyector (`127.0.0.1:5555`), que en las unidades HY320 que probamos viene
  activo.

## Cómo instalarlo en el proyector

1. Descarga el APK:

   **https://github.com/Ericknvp/hyremote-launcher/releases/download/v1.2/hyremote-1.2.apk**

2. Ábrelo con el explorador de archivos del proyector
   (**Archivos → Download → `hyremote-1.2.apk`**) y acepta la instalación.
   Android pedirá permiso para instalar apps de fuentes desconocidas:
   actívalo en *Ajustes → Seguridad → Fuentes desconocidas*.

3. Abre **HyRemote** una vez con el botón **Abrir** del instalador. Android no
   deja que una app recién instalada se ejecute antes de ese primer toque.

4. Escanea el QR desde el celular. Ya puedes controlar el proyector.

5. Si aparece **"¿Permitir depuración USB?"**, acéptalo una vez con el control
   físico y marca **"Permitir siempre"**. Ese permiso es lo que habilita el
   D-pad y las teclas Home/Atrás. No es un PC ni un script: es el ADB local del
   proyector.

> **Si quieres que HyRemote sea el launcher**, toca **Inicio** en la barra
> inferior. La app intenta hacerlo sola cuando el ADB ya está autorizado; si
> no, el botón Inicio lo hace en un toque. El QR y el control por web no
> dependen de ese cambio.

> **Si tu unidad tiene la depuración por red apagada**, actívala una vez en
> *Ajustes → Opciones de desarrollador → Depuración por red*. Sin ADB la
> página igual abre y funcionan el volumen, la lista de apps y abrir apps; el
> D-pad, Home y Atrás quedan en **control parcial** hasta autorizar el ADB o
> activar el servicio de accesibilidad. En las unidades que probamos el ADB
> local ya viene activo.

## Verificación del archivo

- Tamaño: **251.109 bytes**
- SHA-256: `AE9A6783D74FD69F9FBDF8BC66B35829B48DD1F7EA8822FA443D88E273067D9F`

## Novedades de la 1.2

- La página web se empaqueta como `assets/index.html` y ya carga siempre; la
  1.1 anterior podía responder "Falta la página en el APK".
- Una sola conexión ADB autorizada se mantiene abierta y se reutiliza para
  todas las teclas. Antes se abría una conexión por pulsación y el proyector
  podía volver a pedir permiso en cada una.
- El servidor se enlaza antes de dibujar la interfaz: el QR aparece en el
  primer frame.
- El cambio de launcher se centraliza en `HomeSwitcher` y se reafirma tras
  cada arranque. Elegir el launcher original borra la preferencia para que
  HyRemote no vuelva a ponerse encima.
- Si la interfaz completa falla en un firmware desconocido, queda una pantalla
  mínima con el logo, la URL y el QR.

## Otros botones

| Botón | Qué abre |
|---|---|
| Ajustes | Los ajustes del proyector (Red, Bluetooth, Idioma, Proyección, Audio…) |
| Archivos | El explorador de archivos del proyector |
| HDMI | Cambia a la entrada HDMI1 |
| Proyección | Projection Settings: Keystone, Corner Correction, Zoom, Trapezoid… |
| Bluetooth | Panel propio: radio, emparejados y búsqueda de dispositivos |
| Inicio | Cambia entre HyRemote y el launcher de fábrica |

Creado por **@ericknvp** — [ericknvp.dev](https://ericknvp.dev)
