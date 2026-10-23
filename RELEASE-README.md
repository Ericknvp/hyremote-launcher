# HyRemote 1.1

Launcher y control remoto para el proyector Android **HY320** (y compatibles).

- El proyector arranca con **HyRemote** como pantalla de inicio.
- Desde el celular (Safari o Chrome, sin instalar nada) se controla el
  proyector: D-pad, Inicio, Atrás, volumen, power y escritura de texto.
- El QR de la pantalla de inicio ya trae la IP y el puerto de ese proyector:
  no hay nada que configurar.
- **Ya no necesita PC ni `setup.ps1`.** El servidor HTTP vive dentro del APK y
  las teclas salen por el ADB local del propio proyector (`127.0.0.1:5555`),
  que en el HY320 viene activo de fábrica.

## Cómo instalarlo en el proyector

1. Abre este enlace en el navegador del proyector (o en el celular y pásalo
   al proyector):

   **https://github.com/Ericknvp/hyremote-launcher/releases/download/v1.1/hyremote-1.1.apk**

2. Se descargará en `Download`. Ábrelo con el explorador de archivos del
   proyector (**Archivos** → *Download* → `hyremote-1.1.apk`) y acepta la
   instalación. Android pedirá permiso para instalar apps de fuentes
   desconocidas: márcalo en *Ajustes → Seguridad → Fuentes desconocidas*.

3. Abre **HyRemote** una vez y toca **"Poner como launcher"**.

4. Listo: en cada arranque el proyector aparece con HyRemote, y el QR de la
   pantalla ya sirve para controlar ese proyector.

> **Nota:** si tu unidad tiene la depuración por red apagada, actívala una vez
> en *Ajustes → Opciones de desarrollador → Depuración por red*. Si aparece
> el diálogo **"¿Permitir depuración?"**, acéptalo con el control físico. En
> el HY320 normalmente ya viene activa y no hay que tocar nada.

## Otros botones

| Botón | Qué abre |
|---|---|
| Ajustes | Los ajustes del proyector (Red, Bluetooth, Idioma, Proyección, Audio…) |
| Archivos | El explorador de archivos del proyector |
| HDMI | Cambia a la entrada HDMI1 |
| Proyección | Projection Settings: Keystone, Corner Correction, Zoom, Trapezoid… |
| Bluetooth | Panel propio: radio, emparejados y búsqueda de dispositivos |
| Inicio | Cambia entre HyRemote y el launcher de fábrica |

Creado por **@ericknvp** · [ericknvp.dev](https://ericknvp.dev)
