import { useEffect, useRef, useState } from "react";
import { Alert, Box, Button, InputAdornment, TextField, Typography } from "@mui/material";
import { SearchRounded } from "@mui/icons-material";
import { isGoogleMapsConfigured, loadGeocodingLibrary, loadMapsLibrary, loadMarkerLibrary } from "./googleMapsLoader";
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

// Lima, el mercado actual del producto, para que un operador todavía sin coordenadas abra un
// mapa centrado en el país donde opera el negocio.
const DEFAULT_CENTER: google.maps.LatLngLiteral = { lat: -12.046374, lng: -77.042793 };
const ZOOM_NO_MARKER = 5;
const ZOOM_WITH_MARKER = 15;

/**
 * Búsqueda de dirección + clic para colocar + marcador arrastrable, cableado a `latitude` y
 * `longitude` como números planos para que encaje en los campos de coordenadas que ya tenga
 * cualquier formulario.
 *
 * Degrada a un aviso no bloqueante, y deja intacta la entrada manual, cuando no hay clave de
 * API configurada o el script falla al cargar: nunca lanza y nunca bloquea el formulario que lo
 * rodea.
 */
export function LocationPickerMap({
  latitude, longitude, onChange, initialSearchValue, height = 260,
}: LocationPickerMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<google.maps.Map | null>(null);
  const markerRef = useRef<google.maps.Marker | null>(null);
  const geocoderRef = useRef<google.maps.Geocoder | null>(null);
  const dragListenerRef = useRef<google.maps.MapsEventListener | null>(null);
  const clickListenerRef = useRef<google.maps.MapsEventListener | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const [status, setStatus] = useState<MapLoadStatus>(() => (isGoogleMapsConfigured() ? "loading" : "unavailable"));
  const [searchValue, setSearchValue] = useState(initialSearchValue ?? "");
  const [searchError, setSearchError] = useState<string | null>(null);

  useEffect(() => {
    if (!isGoogleMapsConfigured()) return;
    let cancelled = false;

    void Promise.all([loadMapsLibrary(), loadMarkerLibrary(), loadGeocodingLibrary()])
      .then(([{ Map }, { Marker }, { Geocoder }]) => {
        if (cancelled || !containerRef.current) return;
        const hasCoordinates = latitude !== null && longitude !== null;
        const center = hasCoordinates ? { lat: latitude, lng: longitude } : DEFAULT_CENTER;

        const map = new Map(containerRef.current, {
          center,
          zoom: hasCoordinates ? ZOOM_WITH_MARKER : ZOOM_NO_MARKER,
          streetViewControl: false,
          mapTypeControl: false,
          fullscreenControl: false,
        });
        const marker = new Marker({ map, position: center, draggable: true, visible: hasCoordinates });

        dragListenerRef.current = marker.addListener("dragend", () => {
          const position = marker.getPosition();
          if (position) onChangeRef.current(position.lat(), position.lng());
        });
        clickListenerRef.current = map.addListener("click", (event: google.maps.MapMouseEvent) => {
          if (!event.latLng) return;
          marker.setPosition(event.latLng);
          marker.setVisible(true);
          onChangeRef.current(event.latLng.lat(), event.latLng.lng());
        });

        mapRef.current = map;
        markerRef.current = marker;
        geocoderRef.current = new Geocoder();
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("unavailable");
      });

    return () => {
      cancelled = true;
      dragListenerRef.current?.remove();
      clickListenerRef.current?.remove();
      dragListenerRef.current = null;
      clickListenerRef.current = null;
      mapRef.current = null;
      markerRef.current = null;
      geocoderRef.current = null;
    };
    // El mapa se crea una vez; las actualizaciones de coordenadas posteriores las sincroniza el
    // efecto de abajo en lugar de recrearlo, para no perder el paneo y el zoom que ya hizo el
    // operador.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const marker = markerRef.current;
    const map = mapRef.current;
    if (!marker || !map) return;
    if (latitude === null || longitude === null) {
      marker.setVisible(false);
      return;
    }
    const position = { lat: latitude, lng: longitude };
    marker.setPosition(position);
    marker.setVisible(true);
    map.panTo(position);
  }, [latitude, longitude]);

  async function handleSearch() {
    const geocoder = geocoderRef.current;
    const map = mapRef.current;
    if (!geocoder || !map || searchValue.trim() === "") return;
    setSearchError(null);
    try {
      const { results } = await geocoder.geocode({ address: searchValue });
      const first = results[0];
      if (!first) {
        setSearchError(t("No se encontraron resultados para esa dirección."));
        return;
      }
      const location = first.geometry.location;
      onChangeRef.current(location.lat(), location.lng());
      map.panTo(location);
      map.setZoom(ZOOM_WITH_MARKER);
    } catch (error) {
      // Una dirección sin coincidencias rechaza la promesa con estado `ZERO_RESULTS` en vez de
      // resolver con un array vacío — para el operador las dos cosas son "no se encontró nada".
      const code = error && typeof error === "object" && "code" in error ? (error as { code: unknown }).code : null;
      setSearchError(code === "ZERO_RESULTS"
        ? t("No se encontraron resultados para esa dirección.")
        : t("No se pudo buscar la dirección. Inténtalo de nuevo."));
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
          aria-label={t("Buscar una dirección")}
          value={searchValue}
          onChange={(event) => setSearchValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") { event.preventDefault(); void handleSearch(); }
          }}
          disabled={status !== "ready"}
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
          }}
        />
        <Button
          variant="outlined" size="small"
          onClick={() => void handleSearch()}
          disabled={status !== "ready" || searchValue.trim() === ""}
          sx={{ flexShrink: 0, alignSelf: "flex-start" }}
        >
          {t("Buscar")}
        </Button>
      </Box>

      <Box
        ref={containerRef}
        role="application"
        aria-label={t("Mapa para seleccionar la ubicación")}
        sx={{ height, borderRadius: "10px", border: "1px solid", borderColor: "divider", overflow: "hidden" }}
      />

      {status === "loading" && (
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
          {t("Cargando el mapa...")}
        </Typography>
      )}
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
        {t("Haz clic en el mapa o arrastra el marcador para ajustar la ubicación.")}
      </Typography>
    </Box>
  );
}
