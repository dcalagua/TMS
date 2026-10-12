import { useEffect, useRef } from "react";
import { Alert, Box, Typography, useTheme } from "@mui/material";
import { t } from "../../lib/i18n";
import { STOP_STATE_COLOR, type StopDocumentState } from "./shipmentMarkers";
import { StopStateLegend } from "./ShipmentOverviewMap";
import { shapeIcon, useGoogleMapCanvas, whiteLabel } from "./useGoogleMapCanvas";

export interface RouteMapStop {
  id: string;
  sequence: number;
  latitude: number | null;
  longitude: number | null;
  label: string;
  state: StopDocumentState;
}

/** Una posición reportada por el rastreo (ADR-007), la más antigua primero. */
export interface RouteMapPosition {
  latitude: number;
  longitude: number;
  occurredAt: string;
}

export interface ShipmentRouteMapProps {
  origin: { latitude: number | null; longitude: number | null; label: string } | null;
  stops: RouteMapStop[];
  positions: RouteMapPosition[];
  height?: number;
}

const PLANNED = "#6c757d";

/**
 * La ruta de un envío despachado: el origen, sus paradas en secuencia coloreadas por el estado de
 * sus documentos, la línea recta entre ellas (el plan, nunca una ruta calculada) y, aparte, las
 * últimas posiciones reportadas como traza discontinua con el último punto marcado.
 *
 * Plan y posiciones no se mezclan en una misma línea, por la razón que da `TripStopMap`: dónde está
 * el camión y a dónde se le dijo que fuera son dos afirmaciones distintas. Y una posición informa,
 * no mueve nada (ADR-007).
 */
export function ShipmentRouteMap({ origin, stops, positions, height = 300 }: ShipmentRouteMapProps) {
  const theme = useTheme();
  const trackColor = theme.palette.info.main;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const { status, canvas } = useGoogleMapCanvas(containerRef);
  const overlaysRef = useRef<{ setMap: (map: google.maps.Map | null) => void }[]>([]);

  useEffect(() => {
    if (!canvas) return;
    const { map, Marker, Polyline, LatLngBounds } = canvas;
    overlaysRef.current.forEach((overlay) => overlay.setMap(null));
    overlaysRef.current = [];
    const bounds = new LatLngBounds();
    const path: google.maps.LatLngLiteral[] = [];

    if (origin && origin.latitude !== null && origin.longitude !== null) {
      const position = { lat: origin.latitude, lng: origin.longitude };
      path.push(position);
      overlaysRef.current.push(new Marker({
        map, position, title: origin.label, label: whiteLabel("0"), icon: shapeIcon("#1a1a1a", { square: true, size: 24 }), zIndex: 500,
      }));
    }
    [...stops].sort((a, b) => a.sequence - b.sequence).forEach((stop) => {
      if (stop.latitude === null || stop.longitude === null) return;
      const position = { lat: stop.latitude, lng: stop.longitude };
      path.push(position);
      overlaysRef.current.push(new Marker({
        map, position, title: `${stop.sequence}. ${stop.label}`, label: whiteLabel(String(stop.sequence)),
        icon: shapeIcon(STOP_STATE_COLOR[stop.state]), zIndex: 100 + stop.sequence,
      }));
    });
    if (path.length >= 2) {
      overlaysRef.current.push(new Polyline({ map, path, strokeColor: PLANNED, strokeOpacity: 0.8, strokeWeight: 2, geodesic: true }));
    }
    path.forEach((p) => bounds.extend(p));

    if (positions.length > 0) {
      const trail = positions.map((p) => ({ lat: p.latitude, lng: p.longitude }));
      if (trail.length >= 2) {
        overlaysRef.current.push(new Polyline({
          map, path: trail, strokeOpacity: 0, geodesic: true,
          icons: [{ icon: { path: "M 0,-1 0,1", strokeOpacity: 0.9, strokeColor: trackColor, scale: 3 }, offset: "0", repeat: "12px" }],
        }));
      }
      overlaysRef.current.push(new Marker({
        map, position: trail[trail.length - 1], title: t("Última posición reportada"),
        icon: shapeIcon(trackColor, { size: 22, emphasis: true }), zIndex: 900,
      }));
      trail.forEach((p) => bounds.extend(p));
    }

    if (path.length > 0 || positions.length > 0) map.fitBounds(bounds);
  }, [canvas, origin, stops, positions, trackColor]);

  useEffect(() => () => {
    overlaysRef.current.forEach((overlay) => overlay.setMap(null));
    overlaysRef.current = [];
  }, []);

  if (status === "unavailable") {
    return (
      <Alert severity="info" role="status">
        {t("El mapa no está disponible. Las paradas y sus documentos se listan abajo.")}
      </Alert>
    );
  }

  const unmapped = stops.filter((stop) => stop.latitude === null || stop.longitude === null).length;
  return (
    <Box sx={{ display: "grid", gap: 1 }}>
      <Box
        ref={containerRef}
        role="application"
        aria-label={t("Mapa de la ruta del despacho")}
        sx={{ height, borderRadius: "10px", border: "1px solid", borderColor: "divider", overflow: "hidden" }}
      />
      <StopStateLegend />
      {status === "loading" && <Typography variant="caption" color="text.secondary">{t("Cargando el mapa...")}</Typography>}
      {unmapped > 0 && (
        <Typography variant="caption" color="text.secondary">
          {unmapped === 1
            ? t("{{count}} destino no tiene coordenadas y no aparece en el mapa.", { count: unmapped })
            : t("{{count}} destinos no tienen coordenadas y no aparecen en el mapa.", { count: unmapped })}
        </Typography>
      )}
    </Box>
  );
}
