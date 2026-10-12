import { useSyncExternalStore } from "react";

/**
 * Registro del service worker (`public/sw.js`, ADR-019 §1) y el aviso de versión nueva.
 *
 * Solo en un build de producción y solo donde el navegador lo soporta: en desarrollo un service
 * worker sirve ficheros viejos sobre el servidor de Vite, y en las pruebas (jsdom) no existe.
 *
 * Un despliegue nuevo produce un `sw.js` distinto. El navegador lo instala en segundo plano y lo
 * deja esperando; aquí se detecta y se publica como «hay una versión nueva». Solo cuando la
 * persona pulsa «Actualizar» se le pide `SKIP_WAITING` y se recarga — nunca a mitad de un reporte.
 */

let waitingWorker: ServiceWorker | null = null;
let updateRequested = false;
const listeners = new Set<() => void>();

function publish(worker: ServiceWorker | null): void {
  waitingWorker = worker;
  listeners.forEach((listener) => listener());
}

/** Llamado una vez desde `main.tsx`. */
export function registerServiceWorker(): void {
  if (!import.meta.env.PROD || typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;

  const container = navigator.serviceWorker;
  // La recarga tras activar la versión nueva, y solo si la pidió la persona: el primer
  // `controllerchange` de una instalación limpia (clients.claim) no debe recargar nada.
  container.addEventListener("controllerchange", () => {
    if (!updateRequested) return;
    updateRequested = false;
    window.location.reload();
  });

  const register = () => {
    container.register("/sw.js", { scope: "/" }).then((registration) => {
      if (registration.waiting && container.controller) publish(registration.waiting);
      registration.addEventListener("updatefound", () => {
        const installing = registration.installing;
        installing?.addEventListener("statechange", () => {
          // `installed` con un controlador ya presente = una versión nueva esperando. Sin
          // controlador es la primera instalación: no hay nada que actualizar.
          if (installing.state === "installed" && container.controller) publish(installing);
        });
      });
      // Una app instalada puede pasar días abierta: se busca versión nueva al volver a ella.
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible" && navigator.onLine) void registration.update().catch(() => undefined);
      });
    }).catch(() => {
      // Sin service worker la app funciona igual, solo que no abre sin señal.
    });
  };

  if (document.readyState === "complete") register();
  else window.addEventListener("load", register, { once: true });
}

/** Activa la versión que espera; la página se recarga cuando toma el control. */
export function applyServiceWorkerUpdate(): void {
  if (!waitingWorker) return;
  updateRequested = true;
  waitingWorker.postMessage({ type: "SKIP_WAITING" });
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** `true` mientras hay una versión nueva instalada esperando a que la persona actualice. */
export function useServiceWorkerUpdate(): boolean {
  return useSyncExternalStore(subscribe, () => waitingWorker !== null, () => false);
}
