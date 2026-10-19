import { useEffect, useRef, useState } from "react";
import { Alert, Box, useTheme } from "@mui/material";
import {
  DARK_TILE_FILTER, L, needsDarkTileFilter, tileLayer, vehicleIcon, ZOOM_ADDRESS,
} from "./leafletMap";
import { t } from "../../lib/i18n";

/** Un punto reportado. Es el subconjunto de `TrackingPositionView` que un mapa necesita. */
export interface VehicleTrackPoint {
  latitude: number;
  longitude: number;
}

export interface VehicleTrackMapProps {
  /** La posición más reciente. Es lo único obligatorio: sin ella no hay nada que situar. */
  position: VehicleTrackPoint;
  /** El rastro reciente, del más antiguo al más nuevo. Vacío es normal y no es un error. */
  track?: VehicleTrackPoint[];
  /** Texto del marcador al pasar el ratón: la placa, normalmente. */
  label?: string;
  height?: number;
}

/**
 * Dónde está el vehículo, sobre el mapa.
 *
 * **Hermano de `TripStopMap`, no un caso suyo**, y la diferencia no es de tamaño: los dos mapas
 * afirman cosas distintas. `TripStopMap` dibuja la secuencia *planificada* de paradas; esto
 * dibuja posiciones *reportadas*. Por eso el rastro va en línea discontinua y no continua - un
 * trazo continuo se lee como un camino decidido, y esto es el registro de por dónde pasó.
 *
 * Y sigue siendo observación, nunca control: ADR-007 es explícito en que una posición informa a
 * una persona y no mueve ningún ciclo de vida. Este mapa no ofrece ninguna acción.
 */
export function VehicleTrackMap({ position, track = [], label, height = 220 }: VehicleTrackMapProps) {
  const theme = useTheme();
  const vehicleColor = theme.palette.info.main;

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layersRef = useRef<L.Layer[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let map: L.Map;
    try {
      map = L.map(container, {
        center: [position.latitude, position.longitude],
        zoom: ZOOM_ADDRESS,
        // Vive dentro de una tarjeta en una página que se desplaza: capturar la rueda dejaría
        // atrapado a quien sólo quería seguir bajando.
        scrollWheelZoom: false,
        zoomControl: true,
      });
      tileLayer().addTo(map);
      mapRef.current = map;
    } catch {
      // Igual que en TripStopMap: es el único camino que repinta cuando una biblioteca externa
      // falla al inicializarse sobre un nodo que sólo existe después del render.
      setFailed(true);
      return;
    }

    const observer = new ResizeObserver(() => map.invalidateSize());
    observer.observe(container);

    return () => {
      observer.disconnect();
      map.remove();
      mapRef.current = null;
      layersRef.current = [];
    };
    // Se crea una vez; las posiciones que lleguen después las sincroniza el efecto de abajo.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    layersRef.current.forEach((layer) => layer.remove());
    layersRef.current = [];
    const add = (layer: L.Layer) => { layer.addTo(map); layersRef.current.push(layer); };

    const trail: L.LatLngTuple[] = track.map((point) => [point.latitude, point.longitude]);
    if (trail.length >= 2) {
      add(L.polyline(trail, {
        color: vehicleColor, weight: 3, opacity: 0.75, dashArray: "6 6", lineCap: "round",
      }));
    }

    const here: L.LatLngTuple = [position.latitude, position.longitude];
    add(L.marker(here, { icon: vehicleIcon(vehicleColor), title: label, keyboard: false }));

    const everything: L.LatLngTuple[] = trail.length > 0 ? [...trail, here] : [here];
    if (everything.length === 1) {
      map.setView(here, ZOOM_ADDRESS);
    } else {
      map.fitBounds(L.latLngBounds(everything), { padding: [28, 28] });
    }
  }, [position, track, label, vehicleColor]);

  if (failed) {
    return (
      <Alert severity="info" role="status">
        {t("El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.")}
      </Alert>
    );
  }

  return (
    <Box
      ref={containerRef}
      role="application"
      aria-label={t("Mapa de la ubicación del vehículo")}
      sx={{
        height,
        borderRadius: "10px",
        border: "1px solid",
        borderColor: "divider",
        overflow: "hidden",
        "& .leaflet-pane": { zIndex: 0 },
        "& .leaflet-top, & .leaflet-bottom": { zIndex: 1 },
        ...(theme.palette.mode === "dark" && needsDarkTileFilter()
          ? { "& .leaflet-tile": { filter: DARK_TILE_FILTER } }
          : {}),
      }}
    />
  );
}
