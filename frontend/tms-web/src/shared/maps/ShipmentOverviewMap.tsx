import { useEffect, useMemo, useRef } from "react";
import { Alert, Box, ButtonBase, Typography } from "@mui/material";
import { t } from "../../lib/i18n";
import { isGoogleMapsConfigured } from "./googleMapsLoader";
import {
  STOP_STATE_COLOR, STOP_STATE_LABEL, STOP_STATE_ORDER, type StopDocumentState,
} from "./shipmentMarkers";
import { shapeIcon, useGoogleMapCanvas, whiteLabel } from "./useGoogleMapCanvas";

/**
 * Un punto del mapa de conjunto. Un origen o una parada de un envío; `groupId` es el envío, que
 * es lo que se selecciona al pulsar. Las coordenadas pueden faltar: el mapa no lo dibuja y lo
 * cuenta, la lista alternativa lo enseña igual.
 */
export interface OverviewMapPoint {
  id: string;
  kind: "origin" | "stop";
  /** El envío al que pertenece; `null` para un origen compartido por varios. */
  groupId: string | null;
  /** Cómo se nombra el envío en la lista alternativa. */
  groupLabel: string;
  latitude: number | null;
  longitude: number | null;
  /** Texto completo: título del marcador y línea de la lista. */
  title: string;
  /** Texto corto sobre el marcador (la secuencia). */
  label?: string;
  /** Solo paradas. */
  state?: StopDocumentState;
}

export interface ShipmentOverviewMapProps {
  points: OverviewMapPoint[];
  selectedGroupId?: string | null;
  onSelectGroup?: (groupId: string) => void;
  height?: number | string;
}

const ORIGIN_COLOR = "#1a1a1a";

/** La leyenda: color y palabra, nunca solo color. */
export function StopStateLegend() {
  return (
    <Box component="ul" aria-label={t("Leyenda del mapa")}
      sx={{ display: "flex", flexWrap: "wrap", gap: 1.5, listStyle: "none", m: 0, p: 0 }}>
      <LegendItem color={ORIGIN_COLOR} square label={t("Origen")} />
      {STOP_STATE_ORDER.map((state) => (
        <LegendItem key={state} color={STOP_STATE_COLOR[state]} label={t(STOP_STATE_LABEL[state])} />
      ))}
    </Box>
  );
}

function LegendItem({ color, label, square }: { color: string; label: string; square?: boolean }) {
  return (
    <Box component="li" sx={{ display: "inline-flex", alignItems: "center", gap: 0.6 }}>
      <Box aria-hidden sx={{ width: 10, height: 10, borderRadius: square ? "2px" : "50%", bgcolor: color, flexShrink: 0 }} />
      <Typography variant="caption" color="text.secondary">{label}</Typography>
    </Box>
  );
}

/**
 * Los orígenes y las paradas de los envíos listados, coloreadas por el estado de sus documentos
 * (`stopDocumentState`). Sin agrupación de marcadores: a la escala objetivo (cientos de paradas
 * en un día) el mapa sigue siendo legible, y una agrupación escondería justo la parada roja.
 *
 * Es un informe, no un mapa de flota en vivo (ADR-019 §2): no hay posiciones aquí. Pulsar un
 * marcador selecciona su envío; la selección atenúa el resto.
 *
 * Sin clave de Google Maps, o si el script no carga, pinta el aviso de los demás mapas y una
 * lista accesible con lo mismo que diría el mapa, envío por envío.
 */
export function ShipmentOverviewMap({ points, selectedGroupId = null, onSelectGroup, height = 420 }: ShipmentOverviewMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const { status, canvas } = useGoogleMapCanvas(containerRef);
  const markersRef = useRef<google.maps.Marker[]>([]);
  const fittedKeyRef = useRef<string>("");

  const mapped = useMemo(
    () => points.filter((p): p is OverviewMapPoint & { latitude: number; longitude: number } =>
      p.latitude !== null && p.longitude !== null),
    [points],
  );
  const unmappedStops = points.filter((p) => p.kind === "stop" && (p.latitude === null || p.longitude === null)).length;

  useEffect(() => {
    if (!canvas) return;
    const { map, Marker, LatLngBounds } = canvas;
    markersRef.current.forEach((marker) => marker.setMap(null));
    markersRef.current = mapped.map((point) => {
      const inSelection = selectedGroupId === null || point.groupId === selectedGroupId;
      const selected = selectedGroupId !== null && point.groupId === selectedGroupId;
      const marker = new Marker({
        map,
        position: { lat: point.latitude, lng: point.longitude },
        title: point.title,
        label: point.label ? whiteLabel(point.label) : undefined,
        icon: point.kind === "origin"
          ? shapeIcon(ORIGIN_COLOR, { square: true, size: 22 })
          : shapeIcon(STOP_STATE_COLOR[point.state ?? "NONE"], { emphasis: selected, faded: !inSelection, size: selected ? 30 : 26 }),
        zIndex: point.kind === "origin" ? 50 : selected ? 800 : point.state === "PROBLEM" ? 300 : 100,
      });
      const groupId = point.groupId;
      if (onSelectGroup && groupId) marker.addListener("click", () => onSelectGroup(groupId));
      return marker;
    });

    // Se encuadra cuando cambia el conjunto de puntos, no cuando cambia la selección o llega el
    // refresco de cada minuto con lo mismo: el operador que acercó el mapa no pierde su zoom.
    const key = mapped.map((p) => p.id).join("|");
    if (key === fittedKeyRef.current || mapped.length === 0) return;
    fittedKeyRef.current = key;
    const bounds = new LatLngBounds();
    mapped.forEach((p) => bounds.extend({ lat: p.latitude, lng: p.longitude }));
    map.fitBounds(bounds);
  }, [canvas, mapped, selectedGroupId, onSelectGroup]);

  useEffect(() => () => {
    markersRef.current.forEach((marker) => marker.setMap(null));
    markersRef.current = [];
  }, []);

  if (status === "unavailable") {
    return <OverviewFallback points={points} selectedGroupId={selectedGroupId} onSelectGroup={onSelectGroup} configured={isGoogleMapsConfigured()} />;
  }

  return (
    <Box sx={{ display: "grid", gap: 1 }}>
      <Box
        ref={containerRef}
        role="application"
        aria-label={t("Mapa de despachos")}
        sx={{ height, minHeight: 280, borderRadius: "10px", border: "1px solid", borderColor: "divider", overflow: "hidden" }}
      />
      <StopStateLegend />
      {status === "loading" && (
        <Typography variant="caption" color="text.secondary">{t("Cargando el mapa...")}</Typography>
      )}
      {unmappedStops > 0 && (
        <Typography variant="caption" color="warning.main" sx={{ fontWeight: 700 }}>
          {unmappedStops === 1
            ? t("{{count}} destino no tiene coordenadas y no aparece en el mapa.", { count: unmappedStops })
            : t("{{count}} destinos no tienen coordenadas y no aparecen en el mapa.", { count: unmappedStops })}
        </Typography>
      )}
    </Box>
  );
}

interface FallbackGroup {
  groupId: string;
  label: string;
  stops: OverviewMapPoint[];
}

function OverviewFallback({ points, selectedGroupId, onSelectGroup, configured }: {
  points: OverviewMapPoint[];
  selectedGroupId: string | null;
  onSelectGroup?: (groupId: string) => void;
  configured: boolean;
}) {
  const groups = useMemo(() => {
    const byGroup = new Map<string, FallbackGroup>();
    for (const point of points) {
      if (point.kind !== "stop" || point.groupId === null) continue;
      const group = byGroup.get(point.groupId) ?? { groupId: point.groupId, label: point.groupLabel, stops: [] };
      group.stops.push(point);
      byGroup.set(point.groupId, group);
    }
    return [...byGroup.values()];
  }, [points]);

  return (
    <Box sx={{ display: "grid", gap: 1 }}>
      <Alert severity="info" role="status">
        {configured
          ? t("El mapa no está disponible. Abajo, las paradas de cada despacho con el estado de sus documentos.")
          : t("El mapa no está disponible en esta instalación. Abajo, las paradas de cada despacho con el estado de sus documentos.")}
      </Alert>
      <StopStateLegend />
      {groups.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("No hay paradas que mostrar.")}</Typography>
      ) : (
        <Box component="ul" aria-label={t("Paradas de los despachos")}
          sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 0.75, maxHeight: 520, overflowY: "auto" }}>
          {groups.map((group) => {
            const selected = group.groupId === selectedGroupId;
            return (
              <Box component="li" key={group.groupId}>
                <ButtonBase
                  onClick={onSelectGroup ? () => onSelectGroup(group.groupId) : undefined}
                  aria-pressed={selected}
                  aria-label={t("Seleccionar el despacho {{shipment}}", { shipment: group.label })}
                  sx={{
                    width: "100%", display: "block", textAlign: "left", px: 1.25, py: 0.75, borderRadius: "8px",
                    border: "1px solid", borderColor: selected ? "primary.main" : "divider",
                    "&:hover": { borderColor: "primary.main" },
                  }}
                >
                  <Typography variant="body2" sx={{ fontWeight: 800 }}>{group.label}</Typography>
                  <Box sx={{ display: "flex", flexWrap: "wrap", columnGap: 1.5, rowGap: 0.25, mt: 0.25 }}>
                    {group.stops.map((stop) => (
                      <Box key={stop.id} component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.5 }}>
                        <Box aria-hidden component="span"
                          sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: STOP_STATE_COLOR[stop.state ?? "NONE"], flexShrink: 0 }} />
                        <Typography variant="caption" color="text.secondary">
                          {stop.title} · {t(STOP_STATE_LABEL[stop.state ?? "NONE"])}
                        </Typography>
                      </Box>
                    ))}
                  </Box>
                </ButtonBase>
              </Box>
            );
          })}
        </Box>
      )}
    </Box>
  );
}
