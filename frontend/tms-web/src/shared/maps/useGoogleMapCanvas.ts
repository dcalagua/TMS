import { useEffect, useState, type RefObject } from "react";
import { isGoogleMapsConfigured, loadCoreLibrary, loadMapsLibrary, loadMarkerLibrary } from "./googleMapsLoader";
import type { MapLoadStatus } from "./types";

/** Lo que un mapa de solo lectura necesita una vez cargado: el mapa y los constructores. */
export interface MapCanvas {
  map: google.maps.Map;
  Marker: typeof google.maps.Marker;
  Polyline: typeof google.maps.Polyline;
  LatLngBounds: typeof google.maps.LatLngBounds;
}

const DEFAULT_CENTER: google.maps.LatLngLiteral = { lat: -12.046374, lng: -77.042793 };
const DEFAULT_ZOOM = 5;

/**
 * El arranque que `StopsMap` y `TripStopMap` repiten, una vez, para los mapas de despachos: un
 * solo cargador (`googleMapsLoader`), el mismo centro y los mismos controles, y los mismos tres
 * estados. Sin clave configurada nunca pide el script y queda en `unavailable` desde el primer
 * render, que es lo que permite a quien lo usa pintar su alternativa accesible sin parpadeo.
 */
export function useGoogleMapCanvas(containerRef: RefObject<HTMLDivElement | null>): {
  status: MapLoadStatus;
  canvas: MapCanvas | null;
} {
  const [status, setStatus] = useState<MapLoadStatus>(() => (isGoogleMapsConfigured() ? "loading" : "unavailable"));
  const [canvas, setCanvas] = useState<MapCanvas | null>(null);

  useEffect(() => {
    if (!isGoogleMapsConfigured()) return;
    let cancelled = false;
    void Promise.all([loadMapsLibrary(), loadMarkerLibrary(), loadCoreLibrary()])
      .then(([{ Map, Polyline }, { Marker }, { LatLngBounds }]) => {
        if (cancelled || !containerRef.current) return;
        const map = new Map(containerRef.current, {
          center: DEFAULT_CENTER,
          zoom: DEFAULT_ZOOM,
          streetViewControl: false,
          mapTypeControl: false,
          fullscreenControl: false,
        });
        setCanvas({ map, Marker, Polyline, LatLngBounds });
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("unavailable");
      });
    return () => {
      cancelled = true;
      setCanvas(null);
    };
  }, [containerRef]);

  return { status, canvas };
}

/** Un círculo SVG como icono de marcador; `square` para el origen, que no es una parada. */
export function shapeIcon(color: string, options: { square?: boolean; size?: number; emphasis?: boolean; faded?: boolean } = {}): google.maps.Icon {
  const size = options.size ?? 26;
  const half = size / 2;
  const stroke = options.emphasis ? "#1a1a1a" : "#ffffff";
  const strokeWidth = options.emphasis ? 3 : 2;
  const opacity = options.faded ? 0.45 : 1;
  const shape = options.square
    ? `<rect x="2" y="2" width="${size - 4}" height="${size - 4}" rx="4" fill="${color}" fill-opacity="${opacity}" stroke="${stroke}" stroke-width="${strokeWidth}"/>`
    : `<circle cx="${half}" cy="${half}" r="${half - 2}" fill="${color}" fill-opacity="${opacity}" stroke="${stroke}" stroke-width="${strokeWidth}"/>`;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">${shape}</svg>`;
  return { url: `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}` };
}

export function whiteLabel(text: string): google.maps.MarkerLabel {
  return { text, color: "#ffffff", fontWeight: "700", fontSize: "11px" };
}
