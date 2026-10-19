import { useEffect, useRef, useState } from "react";
import { Alert, Box, Typography, useTheme } from "@mui/material";
import {
  DARK_TILE_FILTER, DEFAULT_CENTER, L, needsDarkTileFilter, stopIcon, tileLayer, vehicleIcon, ZOOM_COUNTRY,
} from "./leafletMap";
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

const LINE = "#6c757d";

/**
 * La secuencia de paradas de un viaje planificado sobre un mapa: el origen como marcador "0", un
 * marcador numerado por parada en su secuencia real de `trip_stop`, y una polilínea de líneas
 * rectas entre las coordenadas conocidas — nunca una ruta calculada. Una parada sin coordenadas
 * simplemente no se dibuja; decir cuántas quedaron fuera es trabajo de este componente, y lo hace
 * debajo del mapa.
 *
 * Va sobre Leaflet y teselas de OpenStreetMap, así que **no necesita clave de API**. Antes
 * dependía de `VITE_GOOGLE_MAPS_API_KEY` y sin ella la pestaña Mapa mostraba un aviso en lugar
 * del mapa, que es justo lo que no sirve cuando alguien quiere ver por dónde va un viaje.
 */
export function TripStopMap({
  origin, stops, vehicle = null, selectedStopId = null, onSelectStop, height = 320,
}: TripStopMapProps) {
  const theme = useTheme();
  const ink = theme.palette.text.primary;
  // El vehículo es lo único de este mapa que está pasando ahora, así que toma el color de acción
  // del tema activo en lugar de un azul fijo.
  const vehicleColor = theme.palette.info.main;

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layersRef = useRef<L.Layer[]>([]);
  const onSelectRef = useRef(onSelectStop);
  // En un efecto y no durante el render: el marcador guarda esta referencia en su `click`, asi
  // que basta con que este al dia antes del siguiente evento, y tocar un ref mientras React
  // renderiza es justo lo que no hay que hacer.
  useEffect(() => { onSelectRef.current = onSelectStop; }, [onSelectStop]);

  /**
   * Solo se guarda el FRACASO, no el ciclo completo.
   *
   * Que el mapa este listo no necesita estado: el efecto que dibuja corre despues del que crea
   * el mapa, asi que le basta con mirar `mapRef`. Un estado "ready" solo anadiria un render de
   * mas en cada montaje. Que Leaflet no haya podido arrancar si necesita estado, porque el
   * render ya ocurrio y hay que volver a pintar el aviso en su lugar.
   */
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let map: L.Map;
    try {
      map = L.map(container, {
        center: DEFAULT_CENTER,
        zoom: ZOOM_COUNTRY,
        // La rueda se deja fuera a propósito: este mapa vive dentro de un drawer que se desplaza,
        // y un mapa que captura la rueda deja atrapado a quien sólo quería seguir bajando.
        scrollWheelZoom: false,
      });
      tileLayer().addTo(map);
      mapRef.current = map;
    } catch {
      // El linter marca este setState dentro de un efecto, y aqui es correcto: no hay otra forma
      // de volver a pintar cuando una biblioteca externa falla al inicializarse sobre un nodo que
      // solo existe despues del render. Es el unico camino que lo hace, y solo si Leaflet revienta.
      setFailed(true);
      return;
    }

    // Este mapa vive detrás de una pestaña dentro de un drawer, así que casi nunca se monta con
    // su tamaño final: Leaflet mide un contenedor a medio abrir - o de altura cero, si la
    // pestaña aún no estaba activa - y deja las teselas grises hasta que algo lo toca. Observar
    // el tamaño lo corrige sin depender de la animación ni de qué pestaña se abrió primero.
    const observer = new ResizeObserver(() => map.invalidateSize());
    observer.observe(container);

    return () => {
      observer.disconnect();
      map.remove();
      mapRef.current = null;
      layersRef.current = [];
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    layersRef.current.forEach((layer) => layer.remove());
    layersRef.current = [];

    const path: L.LatLngExpression[] = [];
    const add = (layer: L.Layer) => { layer.addTo(map); layersRef.current.push(layer); };

    if (origin && origin.latitude !== null && origin.longitude !== null) {
      const position: L.LatLngTuple = [origin.latitude, origin.longitude];
      path.push(position);
      add(L.marker(position, {
        icon: stopIcon("0", true, ink), title: origin.label, zIndexOffset: 500, keyboard: false,
      }));
    }

    stops.forEach((stop) => {
      if (stop.latitude === null || stop.longitude === null) return;
      const position: L.LatLngTuple = [stop.latitude, stop.longitude];
      path.push(position);
      const selected = stop.id === selectedStopId;
      const marker = L.marker(position, {
        icon: stopIcon(String(stop.sequence), selected, ink),
        title: stop.label,
        zIndexOffset: selected ? 400 : 100 + stop.sequence,
      });
      marker.on("click", () => onSelectRef.current?.(stop.id));
      add(marker);
    });

    // La polilínea va DEBAJO de los marcadores, y nunca incluye al vehículo: une las paradas
    // planificadas en su orden, en líneas rectas, porque una ruta calculada afirmaría un camino
    // que nadie eligió.
    if (path.length >= 2) {
      add(L.polyline(path, { color: LINE, weight: 2, opacity: 0.8 }));
    }

    // Por encima de todos los marcadores de parada: cuando el camión está parado en la parada 3,
    // la respuesta a "dónde está" no puede quedar escondida detrás de la respuesta a "a dónde
    // debe ir".
    let vehiclePosition: L.LatLngTuple | null = null;
    if (vehicle) {
      vehiclePosition = [vehicle.latitude, vehicle.longitude];
      add(L.marker(vehiclePosition, {
        icon: vehicleIcon(vehicleColor), title: vehicle.label, zIndexOffset: 900, keyboard: false,
      }));
    }

    if (path.length === 0 && !vehiclePosition) return;
    const everything = vehiclePosition ? [...path, vehiclePosition] : path;
    const bounds = L.latLngBounds(everything as L.LatLngTuple[]);
    // Un solo punto da unos límites sin área, y encuadrarlos lleva al zoom máximo sobre un
    // tejado. Con uno se centra a escala de ciudad; con varios se encuadran todos.
    if (everything.length === 1) {
      map.setView(bounds.getCenter(), 14);
    } else {
      map.fitBounds(bounds, { padding: [32, 32] });
    }
  }, [origin, stops, vehicle, selectedStopId, ink, vehicleColor]);

  if (failed) {
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
        sx={{
          height,
          borderRadius: "10px",
          border: "1px solid",
          borderColor: "divider",
          overflow: "hidden",
          // Leaflet se dibuja en capas posicionadas; sin esto se monta sobre el drawer.
          "& .leaflet-pane": { zIndex: 0 },
          "& .leaflet-top, & .leaflet-bottom": { zIndex: 1 },
          ...(theme.palette.mode === "dark" && needsDarkTileFilter()
            ? { "& .leaflet-tile": { filter: DARK_TILE_FILTER } }
            : {}),
        }}
      />
      {unmappedCount > 0 && (
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
          {unmappedCount === 1
            ? t("{{count}} destino no tiene coordenadas y no aparece en el mapa.", { count: unmappedCount })
            : t("{{count}} destinos no tienen coordenadas y no aparecen en el mapa.", { count: unmappedCount })}
        </Typography>
      )}
    </Box>
  );
}
