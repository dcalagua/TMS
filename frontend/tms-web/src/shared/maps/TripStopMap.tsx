import { useEffect, useRef, useState } from "react";
import { Alert, Box, Typography, useTheme } from "@mui/material";
import { isGoogleMapsConfigured, loadCoreLibrary, loadMapsLibrary, loadMarkerLibrary } from "./googleMapsLoader";
import type { MapLoadStatus } from "./types";
import { t } from "../../lib/i18n";

/** La forma del marcador de origen: un viaje tiene como mucho uno, dibujado como parada "0". */
export interface TripStopMapOrigin {
  latitude: number | null;
  longitude: number | null;
  label: string;
}

/** Un marcador numerado: refleja los campos de `TripStopView` que un mapa realmente necesita. */
export interface TripStopMapStop {
  id: string;
  sequence: number;
  latitude: number | null;
  longitude: number | null;
  label: string;
}

/**
 * La última posición conocida del vehículo, cuando la hay.
 *
 * Se dibuja como marcador y se mantiene deliberadamente fuera de la polilínea: esa línea es la
 * secuencia *planificada* de paradas, y meterle dentro una posición reportada dibujaría una ruta
 * que el plan nunca contuvo. Dónde está el camión y a dónde se le dijo que fuera son dos
 * afirmaciones, y el mapa enseña las dos sin mezclarlas.
 */
export interface TripStopMapVehicle {
  latitude: number;
  longitude: number;
  label: string;
}

export interface TripStopMapProps {
  origin: TripStopMapOrigin | null;
  stops: TripStopMapStop[];
  /** Opcional y null por defecto: la mayoría de viajes no tiene feed, y eso no es un error. */
  vehicle?: TripStopMapVehicle | null;
  /** La selección actual de la lista de paradas, para que mapa y lista siempre coincidan. */
  selectedStopId?: string | null;
  /** Se dispara al pulsar un marcador, para que la lista seleccione y haga scroll a esa parada. */
  onSelectStop?: (stopId: string) => void;
  height?: number;
}

const DEFAULT_CENTER: google.maps.LatLngLiteral = { lat: -12.046374, lng: -77.042793 };
const DEFAULT_ZOOM = 5;
const INK = "#1a1a1a";
const MUTED = "#6c757d";

function markerIcon(filled: boolean): google.maps.Icon {
  const fill = filled ? INK : "#ffffff";
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30">` +
    `<circle cx="15" cy="15" r="12.5" fill="${fill}" stroke="${INK}" stroke-width="2"/></svg>`;
  return { url: `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}` };
}

/**
 * El marcador del vehículo: un disco relleno con anillo blanco, sin número.
 *
 * Deliberadamente una forma distinta de la de los marcadores de parada, y no el mismo círculo en
 * otro color. El color por sí solo sería la única señal que distingue una posición reportada de
 * una parada planificada, y eso falla para un despachador con daltonismo y vuelve a fallar en
 * una impresión.
 */
function vehicleIcon(color: string): google.maps.Icon {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30">` +
    `<circle cx="15" cy="15" r="11" fill="${color}" stroke="#ffffff" stroke-width="3"/>` +
    `<circle cx="15" cy="15" r="13" fill="none" stroke="${color}" stroke-width="1.5"/></svg>`;
  return { url: `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}` };
}

function markerLabel(text: string, filled: boolean): google.maps.MarkerLabel {
  return { text, color: filled ? "#ffffff" : INK, fontWeight: "700", fontSize: "12px" };
}

/**
 * La secuencia de paradas de un viaje planificado sobre un mapa: el origen como marcador "0", un
 * marcador numerado por parada en su secuencia real de `trip_stop`, y una polilínea de líneas
 * rectas entre las coordenadas conocidas — nunca una ruta calculada (Directions/Routes siguen
 * siendo opcionales y sin usar por defecto). Una parada sin coordenadas simplemente no se
 * dibuja; listarla igualmente, con una nota de "no se puede mapear", es trabajo de quien llama.
 *
 * Es hermano de `StopsMap`, no un envoltorio suyo: la numeración, el resaltado de selección y la
 * polilínea viven aquí, que es lo que el comentario de `StopsMap` decía que le correspondía al
 * trabajo de secuencia de paradas.
 */
export function TripStopMap({
  origin, stops, vehicle = null, selectedStopId = null, onSelectStop, height = 320,
}: TripStopMapProps) {
  const theme = useTheme();
  // El vehículo es lo único de este mapa que está pasando ahora, así que toma el color de acción
  // del tema activo en lugar de un azul fijo.
  const vehicleColor = theme.palette.info.main;

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<google.maps.Map | null>(null);
  const markerCtorRef = useRef<typeof google.maps.Marker | null>(null);
  const boundsCtorRef = useRef<typeof google.maps.LatLngBounds | null>(null);
  const polylineCtorRef = useRef<typeof google.maps.Polyline | null>(null);
  const markersRef = useRef<google.maps.Marker[]>([]);
  const polylineRef = useRef<google.maps.Polyline | null>(null);
  const [status, setStatus] = useState<MapLoadStatus>(() => (isGoogleMapsConfigured() ? "loading" : "unavailable"));

  useEffect(() => {
    if (!isGoogleMapsConfigured()) return;
    let cancelled = false;

    void Promise.all([loadMapsLibrary(), loadMarkerLibrary(), loadCoreLibrary()])
      .then(([{ Map, Polyline }, { Marker }, { LatLngBounds }]) => {
        if (cancelled || !containerRef.current) return;
        mapRef.current = new Map(containerRef.current, {
          center: DEFAULT_CENTER,
          zoom: DEFAULT_ZOOM,
          streetViewControl: false,
          mapTypeControl: false,
          fullscreenControl: false,
        });
        markerCtorRef.current = Marker;
        boundsCtorRef.current = LatLngBounds;
        polylineCtorRef.current = Polyline;
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("unavailable");
      });

    return () => {
      cancelled = true;
      markersRef.current.forEach((marker) => marker.setMap(null));
      markersRef.current = [];
      polylineRef.current?.setMap(null);
      polylineRef.current = null;
      mapRef.current = null;
      markerCtorRef.current = null;
      boundsCtorRef.current = null;
      polylineCtorRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    const Marker = markerCtorRef.current;
    const LatLngBounds = boundsCtorRef.current;
    const Polyline = polylineCtorRef.current;
    if (!map || !Marker || !LatLngBounds || !Polyline || status !== "ready") return;

    markersRef.current.forEach((marker) => marker.setMap(null));
    markersRef.current = [];
    polylineRef.current?.setMap(null);
    polylineRef.current = null;

    const path: google.maps.LatLngLiteral[] = [];

    if (origin && origin.latitude !== null && origin.longitude !== null) {
      const position = { lat: origin.latitude, lng: origin.longitude };
      path.push(position);
      markersRef.current.push(
        new Marker({ map, position, title: origin.label, label: markerLabel("0", true), icon: markerIcon(true), zIndex: 500 }),
      );
    }

    stops.forEach((stop) => {
      if (stop.latitude === null || stop.longitude === null) return;
      const position = { lat: stop.latitude, lng: stop.longitude };
      path.push(position);
      const selected = stop.id === selectedStopId;
      const marker = new Marker({
        map,
        position,
        title: stop.label,
        label: markerLabel(String(stop.sequence), selected),
        icon: markerIcon(selected),
        zIndex: selected ? 400 : 100 + stop.sequence,
      });
      if (onSelectStop) marker.addListener("click", () => onSelectStop(stop.id));
      markersRef.current.push(marker);
    });

    if (path.length >= 2) {
      polylineRef.current = new Polyline({ map, path, strokeColor: MUTED, strokeOpacity: 0.8, strokeWeight: 2, geodesic: true });
    }

    // Después de la polilínea, para que la posición reportada nunca forme parte del camino
    // planificado, y por encima de todos los marcadores de parada: cuando el camión está parado
    // en la parada 3, la respuesta a "dónde está" no puede quedar escondida detrás de la
    // respuesta a "a dónde debe ir".
    let vehiclePosition: google.maps.LatLngLiteral | null = null;
    if (vehicle) {
      vehiclePosition = { lat: vehicle.latitude, lng: vehicle.longitude };
      markersRef.current.push(
        new Marker({ map, position: vehiclePosition, title: vehicle.label, icon: vehicleIcon(vehicleColor), zIndex: 900 }),
      );
    }

    if (path.length === 0 && !vehiclePosition) return;
    const bounds = new LatLngBounds();
    path.forEach((position) => bounds.extend(position));
    if (vehiclePosition) bounds.extend(vehiclePosition);
    map.fitBounds(bounds);
  }, [origin, stops, vehicle, selectedStopId, status, onSelectStop, vehicleColor]);

  if (status === "unavailable") {
    return (
      <Alert severity="info" sx={{ mb: 2 }} role="status">
        {t("El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.")}
      </Alert>
    );
  }

  const unmappedCount = stops.filter((stop) => stop.latitude === null || stop.longitude === null).length;

  return (
    <Box sx={{ mb: 2 }}>
      <Box
        ref={containerRef}
        role="application"
        aria-label={t("Mapa de paradas")}
        sx={{ height, borderRadius: "10px", border: "1px solid", borderColor: "divider", overflow: "hidden" }}
      />
      {status === "loading" && (
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
          {t("Cargando el mapa...")}
        </Typography>
      )}
      {status === "ready" && unmappedCount > 0 && (
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
          {unmappedCount === 1
            ? t("{{count}} destino no tiene coordenadas y no aparece en el mapa.", { count: unmappedCount })
            : t("{{count}} destinos no tienen coordenadas y no aparecen en el mapa.", { count: unmappedCount })}
        </Typography>
      )}
    </Box>
  );
}
