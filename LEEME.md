# Soporty para Android

App de «solo ver» para que el técnico vea en vivo la pantalla del celular Android del cliente, con voz y chat. El técnico **no puede tocar ni controlar** el teléfono.

© 2026 Jonathan Josue Chourio Gonzalez

## Requisitos

- Una cuenta gratuita de **GitHub**. No necesitas instalar nada en tu computadora.
- Servidor con **Soporty 1.2.0** o posterior.
- El celular del cliente: Android 8.0 o superior.

## 1. Subir el proyecto a GitHub

1. Entra a github.com → **New repository** → nombre `soporty-android` → elige **Private** → **Create repository**.
2. En la página del repositorio vacío toca **«uploading an existing file»**.
3. Arrastra **todo el contenido** de esta carpeta, incluida la carpeta `.github`, y presiona **Commit changes**.

   Si tu explorador oculta la carpeta `.github`, activa «mostrar archivos ocultos» o pide que suban el proyecto por ti.

## 2. Agregar la llave de firma (una sola vez)

Abre el archivo **SECRETOS-GITHUB.txt**, que se entrega aparte del proyecto, y en GitHub:

1. **Settings → Secrets and variables → Actions → New repository secret**.
2. Crea los 4 secrets con el nombre y el valor exactos del archivo: `KEY_ALIAS`, `KEY_PASSWORD`, `KEYSTORE_PASSWORD` y `KEYSTORE_BASE64`.

Guarda `SECRETOS-GITHUB.txt` y `soporty-llave-firma.jks` en un lugar seguro y **no** los subas al repositorio. Usa siempre la misma llave: si la pierdes, los clientes tendrán que desinstalar la app para recibir versiones nuevas.

## 3. Compilar el APK

1. En GitHub abre la pestaña **Actions** → **Compilar APK** → **Run workflow**. También se ejecuta solo cada vez que subes cambios.
2. Tarda unos 5 a 8 minutos. Al terminar, entra a la ejecución (palomita verde) y en **Artifacts** descarga `soporty-apk-1.0.N`. Es un ZIP que trae el `.apk`.
3. Si sale una ✗ roja, abre la ejecución y copia el mensaje del paso que falló para corregirlo.

El número de versión sube solo con cada compilación (1.0.1, 1.0.2…), así que los clientes pueden actualizar encima de la versión anterior.

### Configurar la dirección del servidor

En `app/build.gradle.kts` está la línea:

```kotlin
val serverUrl = "https://soporty.com"
```

Si tu plataforma está en otro dominio, edita ese archivo desde GitHub con el lápiz ✏️ y guarda. GitHub vuelve a compilar solo.

### Alternativa: Android Studio

Si algún día instalas Android Studio, abre esta carpeta con **Open** y usa **Build → Generate Signed App Bundle / APK** con el mismo archivo `soporty-llave-firma.jks`.

## 4. Publicarlo en Soporty

1. Entra a **Super admin → App Android**.
2. Sube el APK y escribe la versión, por ejemplo `1.0.0`.
3. Desde ese momento, los clientes con Android ven en su página de soporte los botones **«Abrir en la app»** y **«Descargar la app»**. La página pública de descarga queda en `https://tu-dominio/app.php`.

Para una versión nueva, sube los cambios a GitHub, descarga el APK nuevo de Actions y vuelve a subirlo en Soporty.

## Cómo funciona para el cliente

1. Entra a la sesión desde el navegador como siempre (voz y chat).
2. En Android ve **«Abrir en la app»**.
   - Si ya tiene la app instalada, la sesión pasa a la app sin volver a escribir el código; el navegador queda libre.
   - Si no la tiene, se abre la página de descarga.
3. También puede abrir la app y escribir el código directamente.
4. En la app toca **«Compartir pantalla»** y acepta el aviso de Android. Debe elegir «Toda la pantalla».
5. Puede salir de la app: la llamada y la pantalla siguen mientras haya una notificación activa. Desde esa notificación puede silenciar, dejar de compartir o terminar.
6. Opcional: con **«Ver las marcas del técnico sobre mi pantalla»** (permiso «Mostrar sobre otras apps») ve tu puntero y tus trazos encima de cualquier app.

## Detalles técnicos

- **Paquete:** `com.soporty.app`. Si lo cambias, cámbialo también en `api/app.php` del servidor, en la línea del `intent://` (`package=com.soporty.app`).
- **WebRTC:** `io.getstream:stream-webrtc-android` (Maven Central), API `org.webrtc`.
- **Protocolo:** el mismo que usa la página web. Sondeo HTTP a `api/rtc.php`, señales `hello`/`ack`/`desc`/`ice`/`ctl` y negociación «perfecta», donde la app es el lado cortés. La app se autentica con la cabecera `X-Client-Auth`.
- **Servicio en primer plano:** tipos `microphone` y `mediaProjection`, como exige Android 14 o superior. Cada vez que se comparte, Android pide confirmar.
- **Capa de marcas:** usa opacidad 0.8 para que los toques del cliente pasen a la app de abajo, como exige Android 12 o superior.
- **TURN:** usa los servidores configurados en Soporty, incluido Cloudflare, así que funciona también en redes móviles.

## Si algo falla al compilar

- **«Falta el secret KEYSTORE_BASE64»:** revisa el paso 2. Los nombres deben ser exactos.
- **Cualquier otro error:** copia el texto rojo del paso que falló en Actions y mándalo para corregirlo.
