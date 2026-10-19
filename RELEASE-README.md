# HyRemote 1.0

Launcher y control remoto para el proyector Android **HY320** (y compatibles).

- El proyector arranca con **HyRemote** como pantalla de inicio.
- Desde el celular (Safari o Chrome, sin instalar nada) se controla el
  proyector: D-pad, Inicio, Atrás, volumen, power y escritura de texto.
- El QR de la pantalla de inicio ya trae la IP y el puerto de ese proyector:
  no hay nada que configurar.

## Cómo instalarlo en el proyector

1. Abre este enlace en el navegador del proyector (o en el celular y pásalo
   al proyector):

   **https://github.com/Ericknvp/hyremote-launcher/releases/download/v1.0/hyremote-fase0.apk**

2. Se descargará en `Download`. Ábrelo con el explorador de archivos del
   proyector (**Archivos** → *Download* → `hyremote-fase0.apk`) y acepta la
   instalación. Android pedirá permiso para instalar apps de fuentes
   desconocidas: márcalo en *Ajustes → Seguridad → Fuentes desconocidas*.

3. Abre **HyRemote** una vez y toca **"Poner como launcher"**.

4. Listo: en cada arranque el proyector aparece con HyRemote, y el QR de la
   pantalla ya sirve para controlar ese proyector.

> **Nota:** el control desde el celular necesita que el "puente" (bridge) se
> instale una vez con un PC en la misma red (ver `setup.ps1` en el proyecto).
> Sin eso, la web abre pero los botones no pulses.

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
