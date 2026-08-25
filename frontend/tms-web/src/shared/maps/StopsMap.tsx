import { useEffect, useRef, useState } from "react";
import { Alert, Box, Typography } from "@mui/material";
import { isGoogleMapsConfigured, loadCoreLibrary, loadMapsLibrary, loadMarkerLibrary } from "./googleMapsLoader";
import type { MapLoadStatus, MapStop } from "./types";
import { t } from "../../lib/i18n";

export interface StopsMapProps {
  stops: MapStop[];
  /** Alto en píxeles del lienzo del mapa. */
  height?: number;
  /** Cuántas paradas quedaron fuera por no tener coordenadas. Se dice, no se esconde: un mapa
   * con tres marcadores cuando el viaje tiene cinco paradas miente por omisión. */
  unmappedCount?: number;
}

const DEFAULT_CENTER: google.maps.LatLngLiteral = { lat: -12.046374, lng: -77.042793 };
const DEFAULT_ZOOM = 5;

/**
 * Base de solo lectura para mostrar un conjunto de paradas en un mapa: pinta un marcador por
 * parada y ajusta la vista a todas ellas. Deliberadamente no hace secuenciación, líneas de ruta
 * ni arrastrar-para-reordenar: eso pertenece al trabajo de secuencia de paradas, que construye
 * su interacción encima de este componente en vez de que este la adivine.
 */
export function StopsMap({ stops, height = 320, unmappedCount = 0 }: StopsMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<google.maps.Map | null>(null);
  const markerCtorRef = useRef<typeof google.maps.Marker | null>(null);
  const boundsCtorRef = useRef<typeof google.maps.LatLngBounds | null>(null);
  const markersRef = useRef<google.maps.Marker[]>([]);
  const [status, setStatus] = useState<MapLoadStatus>(() => (isGoogleMapsConfigured() ? "loading" : "unavailable"));

  useEffect(() => {
    if (!isGoogleMapsConfigured()) return;
    let cancelled = false;

    void Promise.all([loadMapsLibrary(), loadMarkerLibrary(), loadCoreLibrary()])
      .then(([{ Map }, { Marker }, { LatLngBounds }]) => {
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
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("unavailable");
      });

    return () => {
      cancelled = true;
      markersRef.current.forEach((marker) => marker.setMap(null));
      markersRef.current = [];
      mapRef.current = null;
      markerCtorRef.current = null;
      boundsCtorRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    const Marker = markerCtorRef.current;
    const LatLngBounds = boundsCtorRef.current;
    if (!map || !Marker || !LatLngBounds || status !== "ready") return;

    markersRef.current.forEach((marker) => marker.setMap(null));
    markersRef.current = stops.map(
      (stop) => new Marker({ map, position: { lat: stop.latitude, lng: stop.longitude }, title: stop.label }),
    );

    if (stops.length === 0) return;
    const bounds = new LatLngBounds();
    stops.forEach((stop) => bounds.extend({ lat: stop.latitude, lng: stop.longitude }));
    map.fitBounds(bounds);
  }, [stops, status]);

  if (status === "unavailable") {
    return (
      <Alert severity="info" sx={{ mb: 2 }} role="status">
        {t("El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.")}
      </Alert>
    );
  }

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
      {unmappedCount > 0 && (
        <Typography variant="caption" color="warning.main" sx={{ display: "block", mt: 0.75, fontWeight: 700 }}>
          {unmappedCount === 1
            ? t("{{count}} destino no tiene coordenadas y no aparece en el mapa.", { count: unmappedCount })
            : t("{{count}} destinos no tienen coordenadas y no aparecen en el mapa.", { count: unmappedCount })}
        </Typography>
      )}
    </Box>
  );
}
