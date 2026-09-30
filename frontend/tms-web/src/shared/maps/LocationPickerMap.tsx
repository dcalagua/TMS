import { useEffect, useRef, useState } from "react";
import {
  Alert, Box, Button, InputAdornment, List, ListItemButton, ListItemText, Paper, TextField, Typography,
} from "@mui/material";
import { SearchRounded } from "@mui/icons-material";
import { useTheme } from "@mui/material/styles";
import {
  DARK_TILE_FILTER, DEFAULT_CENTER, L, needsDarkTileFilter, pinIcon, tileLayer, ZOOM_ADDRESS, ZOOM_COUNTRY,
} from "./leafletMap";
import { geocodeAddress, GeocoderUnavailableError, type GeocodeResult } from "./geocoding";
import type { MapLoadStatus } from "./types";
import { t } from "../../lib/i18n";

export interface LocationPickerMapProps {
  /** `null` significa que aún no se eligieron coordenadas: el mapa centra una vista por defecto sin marcador. */
  latitude: number | null;
  longitude: number | null;
  /** Se dispara cuando el operador hace clic en el mapa, arrastra el marcador o resuelve una búsqueda de dirección. */
  onChange: (lat: number, lng: number) => void;
  /** Prellena la caja de búsqueda, p. ej. desde los campos de dirección. Se lee una vez, al montar. */
  initialSearchValue?: string;
  height?: number;
}

/**
 * Búsqueda de dirección + clic para colocar + marcador arrastrable, cableado a `latitude` y
 * `longitude` como números planos para que encaje en los campos de coordenadas que ya tenga
 * cualquier formulario.
 *
 * Va sobre Leaflet y teselas de OpenStreetMap, así que **no necesita clave de API**: antes el
 * mapa entero desaparecía sin `VITE_GOOGLE_MAPS_API_KEY` y la persona quedaba tecleando
 * coordenadas a mano. El aviso de degradación se conserva para el caso en que la biblioteca no
 * llegue a inicializar, porque el formulario que lo rodea nunca debe romperse por el mapa.
 *
 * Los dos campos de latitud y longitud siguen siendo la fuente de verdad: este componente
 * escribe en ellos y los lee, y nunca guarda estado de coordenadas propio.
 */
export function LocationPickerMap({
  latitude, longitude, onChange, initialSearchValue, height = 260,
}: LocationPickerMapProps) {
  const theme = useTheme();
  const pinColor = theme.palette.primary.main;

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const markerRef = useRef<L.Marker | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const [status, setStatus] = useState<MapLoadStatus>("loading");
  const [searchValue, setSearchValue] = useState(initialSearchValue ?? "");
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [results, setResults] = useState<GeocodeResult[]>([]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let map: L.Map;
    try {
      const hasCoordinates = latitude !== null && longitude !== null;
      const center = hasCoordinates ? { lat: latitude, lng: longitude } : DEFAULT_CENTER;

      map = L.map(container, {
        center,
        zoom: hasCoordinates ? ZOOM_ADDRESS : ZOOM_COUNTRY,
        // La rueda se deja fuera a propósito. Este mapa vive dentro de un drawer que se
        // desplaza, y un mapa que captura la rueda deja atrapado a quien sólo quería bajar al
        // siguiente campo. Se amplía con los botones, con doble clic o pellizcando en táctil.
        scrollWheelZoom: false,
      });
      tileLayer().addTo(map);

      const marker = L.marker(center, { draggable: true, icon: pinIcon(pinColor), keyboard: true });
      if (hasCoordinates) marker.addTo(map);

      marker.on("dragend", () => {
        const position = marker.getLatLng();
        onChangeRef.current(position.lat, position.lng);
      });
      map.on("click", (event: L.LeafletMouseEvent) => {
        marker.setLatLng(event.latlng);
        if (!map.hasLayer(marker)) marker.addTo(map);
        onChangeRef.current(event.latlng.lat, event.latlng.lng);
      });

      mapRef.current = map;
      markerRef.current = marker;
      setStatus("ready");
    } catch {
      setStatus("unavailable");
      return;
    }

    // Leaflet mide el contenedor al crearse, y dentro de un drawer que todavía se está abriendo
    // esa medida es la de un panel a medio camino: el mapa queda con teselas grises hasta que
    // algo lo toca. Observar el tamaño lo corrige sin depender de la duración de la animación.
    const observer = new ResizeObserver(() => map.invalidateSize());
    observer.observe(container);

    return () => {
      observer.disconnect();
      map.remove();
      mapRef.current = null;
      markerRef.current = null;
    };
    // El mapa se crea una vez; las coordenadas que lleguen después las sincroniza el efecto de
    // abajo en lugar de recrearlo, para no perder el paneo y el zoom que ya hizo el operador.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const marker = markerRef.current;
    const map = mapRef.current;
    if (!marker || !map) return;
    if (latitude === null || longitude === null) {
      marker.remove();
      return;
    }
    const position = L.latLng(latitude, longitude);
    marker.setLatLng(position);
    if (!map.hasLayer(marker)) marker.addTo(map);
    map.panTo(position);
  }, [latitude, longitude]);

  // El color del pin sale de la paleta, así que cambia con el tema.
  useEffect(() => {
    markerRef.current?.setIcon(pinIcon(pinColor));
  }, [pinColor]);

  function applyResult(result: GeocodeResult) {
    setResults([]);
    setSearchValue(result.label);
    onChangeRef.current(result.lat, result.lng);
    mapRef.current?.setView([result.lat, result.lng], ZOOM_ADDRESS);
  }

  async function handleSearch() {
    const map = mapRef.current;
    const query = searchValue.trim();
    if (!map || query === "") return;
    setSearchError(null);
    setResults([]);
    setSearching(true);
    try {
      const bounds = map.getBounds();
      const found = await geocodeAddress(query, {
        viewBox: [bounds.getWest(), bounds.getNorth(), bounds.getEast(), bounds.getSouth()],
      });
      if (found.length === 0) {
        setSearchError(t("No se encontraron resultados para esa dirección."));
        return;
      }
      // Un único candidato no merece una lista que obligue a confirmar lo obvio.
      if (found.length === 1) {
        applyResult(found[0]);
        return;
      }
      setResults(found);
    } catch (error) {
      // Cancelar no es fallar: no hay nada que contarle a nadie.
      if (error instanceof DOMException && error.name === "AbortError") return;
      setSearchError(error instanceof GeocoderUnavailableError
        ? t("El servicio de búsqueda de direcciones no respondió. Puedes ubicar el punto en el mapa.")
        : t("No se pudo buscar la dirección. Inténtalo de nuevo."));
    } finally {
      setSearching(false);
    }
  }

  if (status === "unavailable") {
    return (
      <Alert severity="info" sx={{ mb: 2 }} role="status">
        {t("El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.")}
      </Alert>
    );
  }

  return (
    <Box sx={{ mb: 2 }}>
      <Box sx={{ display: "flex", gap: 1, mb: 1.5 }}>
        <TextField
          size="small"
          fullWidth
          placeholder={t("Buscar una dirección")}
          value={searchValue}
          onChange={(event) => setSearchValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") { event.preventDefault(); void handleSearch(); }
          }}
          error={searchError !== null}
          helperText={searchError ?? undefined}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchRounded fontSize="small" sx={{ color: "text.disabled" }} />
                </InputAdornment>
              ),
            },
            // El nombre accesible va en `htmlInput`, no suelto en el TextField: MUI pasa las
            // props sueltas al FormControl que envuelve, así que un `aria-label` ahí arriba
            // etiqueta un `<div>` y deja el `<input>` anónimo. El campo llevaba ese defecto
            // desde la versión anterior del componente, y un lector de pantalla sólo anunciaba
            // "cuadro de edición".
            htmlInput: { "aria-label": t("Buscar una dirección") },
          }}
        />
        <Button
          variant="outlined" size="small"
          onClick={() => void handleSearch()}
          disabled={searching || searchValue.trim() === ""}
          sx={{ flexShrink: 0, alignSelf: "flex-start" }}
        >
          {searching ? t("Buscando...") : t("Buscar")}
        </Button>
      </Box>

      {results.length > 0 && (
        <Paper variant="outlined" sx={{ mb: 1.5, maxHeight: 176, overflowY: "auto" }}>
          <List dense disablePadding aria-label={t("Resultados de la búsqueda")}>
            {results.map((result) => (
              <ListItemButton key={`${result.lat},${result.lng}`} onClick={() => applyResult(result)}>
                <ListItemText
                  primary={result.label}
                  slotProps={{ primary: { variant: "body2", sx: { lineHeight: 1.35 } } }}
                />
              </ListItemButton>
            ))}
          </List>
        </Paper>
      )}

      <Box
        ref={containerRef}
        role="application"
        aria-label={t("Mapa para seleccionar la ubicación")}
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

      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
        {t("Haz clic en el mapa o arrastra el marcador para ajustar la ubicación.")}
      </Typography>
    </Box>
  );
}
