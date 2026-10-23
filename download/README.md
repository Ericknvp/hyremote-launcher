# Instalar con Downloader (código corto)

La app **Downloader** (de la Play Store) es un navegador con atajos: le
puedes dar un nombre corto a una URL y luego, en vez de escribir el enlace,
escribes ese nombre y te descarga el archivo.

Enlace a la app en la Play Store:
**https://play.google.com/store/apps/details?id=com.ellison.francisco.mm.dld**

## El atajo de HyRemote

| | |
|---|---|
| **Código** | `8080` |
| **Apunta a** | `https://github.com/Ericknvp/hyremote-launcher/releases/download/v1.2/hyremote-1.2.apk` |

## Cómo crearlo (en el equipo donde se instala)

1. Abre **Downloader**.
2. Menú (⋮) → **Paquetes** (Packages) → **+**.
3. Nombre: `8080` · URL: el enlace de arriba → **Guardar**.
4. Menú → **Exportar** para compartir el `.dlpackage` (o crea el paquete a
   mano en el equipo de tu primo, que es un minuto).

## En el proyector de tu primo

1. Instala **Downloader** desde la Play Store (el proyector la tiene).
2. Escribe `8080` en la barra de Downloader → **Enter** → descarga el APK.
3. Ábrelo (Downloader te ofrece "Instalar" al terminar) → acepta.
4. Toca **Abrir** cuando el instalador lo ofrezca. El QR aparece solo.
5. Si sale **"¿Permitir depuración USB?"**, acéptalo una vez y marca
   **"Permitir siempre"**: eso habilita el D-pad. Si quieres HyRemote como
   launcher, toca **Inicio** en la barra inferior.

El archivo de este repo (`download/hyremote.dlpackage`) ya viene con el
código `8080` y la URL puesta: se puede importar directamente en Downloader
(*Paquetes → Importar*).

## Alternativa sin Downloader

La página de descarga también sirve:
**https://ericknvp.github.io/hyremote-install/**
con un botón y un QR. Y el APK suelto:
**https://github.com/Ericknvp/hyremote-launcher/releases/download/v1.2/hyremote-1.2.apk**
