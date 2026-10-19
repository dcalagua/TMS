import * as L from "leaflet";
import "leaflet/dist/leaflet.css";
import { appEnv, DEFAULT_TILE_URL } from "../config/env";

/**
 * Lo que comparte cualquier mapa construido sobre Leaflet: de dónde salen las teselas, cómo se
 * atribuyen y qué aspecto tiene un marcador.
 *
 * POR QUÉ LEAFLET Y NO UN PROVEEDOR CON CLAVE. El selector de ubicación desaparecía sin
 * `VITE_GOOGLE_MAPS_API_KEY`, así que en la práctica el mapa no existía hasta que alguien daba
 * de alta una cuenta de facturación. Leaflet es una biblioteca, no un servicio: se empaqueta con
 * la aplicación y funciona sin clave. Lo que sí sigue siendo un servicio son las teselas y el
 * geocodificador, y por eso los dos son configurables.
 *
 * LA ATRIBUCIÓN NO ES DECORATIVA. OpenStreetMap se publica bajo ODbL y exige el crédito visible
 * que monta `tileLayer`. No se quita, y cambiar de proveedor obliga a cambiarla con él.
 *
 * LÍMITES DE USO. Las teselas públicas de openstreetmap.org son cortesía de la fundación y su
 * política desaconseja apoyarse en ellas para tráfico de producción. Sirven para desarrollo y
 * para un piloto; un despliegue con volumen debe apuntar `VITE_MAP_TILE_URL` a su propio
 * servidor o a un proveedor contratado, y entonces nada de este módulo cambia.
 */

/** Lima: el mercado actual del producto, para abrir sobre el país donde opera el negocio. */
export const DEFAULT_CENTER: L.LatLngLiteral = { lat: -12.046374, lng: -77.042793 };
export const ZOOM_COUNTRY = 5;
export const ZOOM_ADDRESS = 16;

export function tileLayer(): L.TileLayer {
  return L.tileLayer(appEnv.mapTileUrl, {
    attribution: appEnv.mapTileAttribution,
    maxZoom: 19,
  });
}

/**
 * Un pin dibujado como SVG en un `divIcon`, y no el marcador PNG que trae Leaflet.
 *
 * Dos razones. La primera es práctica: las imágenes por defecto se resuelven por ruta relativa
 * al CSS y se rompen en cuanto un empaquetador reescribe los nombres - es el fallo más conocido
 * de Leaflet, y se evita entero no usándolas. La segunda es que así el marcador toma un color
 * del tema de MUI (ADR-008) en vez de introducir un azul ajeno a la paleta.
 */
export function pinIcon(color: string): L.DivIcon {
  return L.divIcon({
    className: "tms-map-pin",
    html:
      `<svg width="28" height="38" viewBox="0 0 28 38" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">` +
      `<path d="M14 0C6.8 0 1 5.8 1 13c0 9.4 11.4 23.4 11.9 24a1.4 1.4 0 0 0 2.2 0C15.6 36.4 27 22.4 27 13 27 5.8 21.2 0 14 0z"` +
      ` fill="${color}" stroke="#ffffff" stroke-width="2"/>` +
      `<circle cx="14" cy="13" r="4.5" fill="#ffffff"/>` +
      `</svg>`,
    iconSize: [28, 38],
    iconAnchor: [14, 38],
  });
}

/**
 * Un marcador de parada: un disco con su número dentro.
 *
 * `filled` distingue lo seleccionado - y el origen, que siempre va relleno - del resto. El
 * numero se dibuja dentro del SVG y no como una etiqueta aparte, para que marcador y numero no
 * puedan separarse al hacer zoom.
 */
export function stopIcon(label: string, filled: boolean, ink: string): L.DivIcon {
  const fill = filled ? ink : "#ffffff";
  const text = filled ? "#ffffff" : ink;
  return L.divIcon({
    className: "tms-map-stop",
    html:
      `<svg width="30" height="30" viewBox="0 0 30 30" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">` +
      `<circle cx="15" cy="15" r="12.5" fill="${fill}" stroke="${ink}" stroke-width="2"/>` +
      `<text x="15" y="19.5" text-anchor="middle" font-family="Helvetica, Arial, sans-serif"` +
      ` font-size="12" font-weight="700" fill="${text}">${label}</text>` +
      `</svg>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15],
  });
}

/**
 * El marcador del vehiculo: un disco con anillo blanco y un anillo exterior, sin numero.
 *
 * Deliberadamente una FORMA distinta de la de una parada, y no el mismo circulo en otro color.
 * El color por si solo seria la unica senal que separa una posicion reportada de una parada
 * planificada, y eso falla para un despachador con daltonismo y vuelve a fallar en una
 * impresion en blanco y negro.
 */
export function vehicleIcon(color: string): L.DivIcon {
  return L.divIcon({
    className: "tms-map-vehicle",
    html:
      `<svg width="30" height="30" viewBox="0 0 30 30" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">` +
      `<circle cx="15" cy="15" r="11" fill="${color}" stroke="#ffffff" stroke-width="3"/>` +
      `<circle cx="15" cy="15" r="13" fill="none" stroke="${color}" stroke-width="1.5"/>` +
      `</svg>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15],
  });
}

/**
 * Filtro que oscurece las teselas en modo oscuro.
 *
 * Las teselas estándar de OSM sólo existen en claro, y un rectángulo blanco dentro de una
 * aplicación oscura es justo lo que el modo oscuro viene a evitar. Invertir y rotar el tono
 * mantiene legibles las etiquetas y deja el agua azul. Es un apaño visual, no un mapa oscuro de
 * verdad: un despliegue que quiera uno apunta `VITE_MAP_TILE_URL` a un estilo oscuro y este
 * filtro deja de aplicarse solo, porque entonces sobra.
 */
export const DARK_TILE_FILTER = "invert(1) hue-rotate(180deg) brightness(0.92) contrast(0.9)";

/**
 * Si el despliegue no ha cambiado las teselas, siguen siendo las claras de OSM y el filtro hace
 * falta. En cuanto alguien configura las suyas dejamos de suponer nada sobre su aspecto: invertir
 * un estilo que ya es oscuro lo devolvería a blanco.
 */
export function needsDarkTileFilter(): boolean {
  return appEnv.mapTileUrl === DEFAULT_TILE_URL;
}

export { L };
