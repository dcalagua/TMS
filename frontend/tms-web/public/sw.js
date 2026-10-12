/*
 * Service worker de eTMS (ADR-019 §1). Escrito a mano, sin dependencias.
 *
 * Solo guarda el ARMAZÓN de la aplicación para que el portal de transportistas abra sin señal:
 *  - navegaciones: red primero; sin red, el `index.html` guardado (la SPA enruta en el cliente);
 *  - estáticos del mismo origen (`/assets/*` con hash, iconos, manifiesto): caché primero;
 *  - NADA de `/api`, `/integration` ni de otro origen (Supabase, el backend, fuentes). Los datos
 *    de un cliente no se dejan en una caché HTTP compartida por quien use el teléfono después:
 *    la app guarda lo suyo en IndexedDB, por usuario, y lo borra al cerrar sesión.
 *
 * `BUILD_ID` lo reescribe `vite build` (plugin `etms-sw-build-id` en vite.config.ts) con una
 * huella de los ficheros emitidos: cada despliegue cambia este fichero, el navegador ve un
 * service worker nuevo y la app ofrece «Nueva versión disponible». El nuevo espera hasta que la
 * app le pida `SKIP_WAITING`, para no cambiar los ficheros bajo los pies de quien está a mitad de
 * un reporte.
 */
const BUILD_ID = "__ETMS_BUILD_ID__";
const PREFIX = "etms-";
const SHELL_CACHE = `${PREFIX}shell-${BUILD_ID}`;
const ASSET_CACHE = `${PREFIX}assets-${BUILD_ID}`;
const INDEX_URL = "/index.html";

const SHELL_URLS = [
  INDEX_URL,
  "/manifest.webmanifest",
  "/favicon.svg",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/icon-maskable-192.png",
  "/icons/icon-maskable-512.png",
  "/icons/apple-touch-icon.png",
];

/**
 * Los ficheros con hash que el armazón necesita para abrir el portal sin señal: la entrada de la
 * app y las pantallas del portal. Los escribe `vite build`; en desarrollo la lista va vacía.
 */
const PRECACHE_ASSETS = [] /* __ETMS_PRECACHE__ */;

/** Rutas cuyo contenido nunca pasa por la caché, aunque fueran del mismo origen. */
const NEVER_CACHE = [/^\/api(\/|$)/, /^\/integration(\/|$)/];

function isStaticAsset(pathname) {
  return pathname.startsWith("/assets/")
    || pathname.startsWith("/icons/")
    || pathname === "/favicon.svg"
    || pathname === "/manifest.webmanifest";
}

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE);
    // Uno a uno: un icono que falte no debe tumbar la instalación entera. `index.html` sí se
    // pide con `cache: "reload"` para no guardar una copia vieja de la caché HTTP.
    await Promise.all(SHELL_URLS.map(async (url) => {
      try {
        const response = await fetch(new Request(url, { cache: "reload" }));
        if (response.ok) await cache.put(url, response);
      } catch {
        /* sin red durante la instalación: se reintenta en la próxima */
      }
    }));
    // Los ficheros con hash sí son todo o nada: un armazón a medias abre en blanco. Si falla
    // uno, la instalación falla y el service worker anterior sigue sirviendo.
    const assets = await caches.open(ASSET_CACHE);
    await assets.addAll(PRECACHE_ASSETS);
  })());
  // Sin skipWaiting aquí: la app lo pide cuando la persona pulsa «Actualizar».
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const keep = new Set([SHELL_CACHE, ASSET_CACHE]);
    const names = await caches.keys();
    await Promise.all(names
      .filter((name) => name.startsWith(PREFIX) && !keep.has(name))
      .map((name) => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  // Otro origen (Supabase, el backend en su dominio, Google Fonts): ni se mira.
  if (url.origin !== self.location.origin) return;
  if (NEVER_CACHE.some((pattern) => pattern.test(url.pathname))) return;

  if (request.mode === "navigate") {
    event.respondWith(networkFirstShell(request));
    return;
  }
  if (isStaticAsset(url.pathname)) {
    event.respondWith(cacheFirst(request));
  }
  // Todo lo demás va a la red sin tocar la caché.
});

async function networkFirstShell(request) {
  try {
    const response = await fetch(request);
    // Toda ruta de la SPA sirve el mismo `index.html`: se guarda como tal para la próxima vez
    // sin red. Solo una respuesta HTML correcta; un 404 o una página de error no lo pisan.
    const type = response.headers.get("content-type") || "";
    if (response.ok && type.includes("text/html")) {
      const cache = await caches.open(SHELL_CACHE);
      await cache.put(INDEX_URL, response.clone());
    }
    return response;
  } catch (error) {
    const cached = await caches.match(INDEX_URL);
    if (cached) return cached;
    throw error;
  }
}

async function cacheFirst(request) {
  const cached = await caches.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  // Solo respuestas completas del mismo origen (`basic`): nunca una opaca ni un 206 parcial.
  if (response.ok && response.status === 200 && response.type === "basic") {
    const cache = await caches.open(ASSET_CACHE);
    await cache.put(request, response.clone());
  }
  return response;
}
