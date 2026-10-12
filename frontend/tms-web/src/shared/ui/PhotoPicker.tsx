import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { Box, Button, CircularProgress, IconButton, Typography } from "@mui/material";
import { AddAPhotoRounded, CloseRounded, ImageRounded } from "@mui/icons-material";
import { downscalePhoto } from "../../lib/images";
import { t } from "../../lib/i18n";
import { R } from "../../theme";

/** Una foto elegida, con la vista previa que se pinta mientras no se ha subido. */
export interface PickedPhoto {
  id: string;
  file: File;
  /** Object URL de la miniatura, o `null` donde el navegador no puede crearlo. */
  previewUrl: string | null;
}

interface PhotoPickerProps {
  value: PickedPhoto[];
  onChange: (next: PickedPhoto[]) => void;
  max: number;
  disabled?: boolean;
  /** Mensaje de validación bajo el control. */
  error?: string | null;
  label?: string;
}

function previewOf(file: File): string | null {
  return typeof URL.createObjectURL === "function" ? URL.createObjectURL(file) : null;
}

function revoke(photo: PickedPhoto): void {
  if (photo.previewUrl && typeof URL.revokeObjectURL === "function") URL.revokeObjectURL(photo.previewUrl);
}

let photoSeq = 0;

/**
 * Tomar o elegir de una a `max` fotos, con miniaturas.
 *
 * `capture="environment"` abre la cámara trasera en un teléfono y deja elegir de la galería en un
 * ordenador; `multiple` permite varias de una vez donde el sistema lo admite. Cada foto se reduce
 * en el propio teléfono antes de guardarse en la lista (ver `lib/images`), así que lo que se sube
 * después es ya lo pequeño.
 *
 * Las miniaturas son object URLs: se crean al añadir, se revocan al quitar y, las que queden, al
 * desmontar el control. Nunca son una URL del servidor.
 */
export function PhotoPicker({ value, onChange, max, disabled = false, error, label }: PhotoPickerProps) {
  const [processing, setProcessing] = useState(false);
  const latest = useRef(value);

  useEffect(() => { latest.current = value; });
  useEffect(() => () => { latest.current.forEach(revoke); }, []);

  const remaining = Math.max(0, max - value.length);

  async function pick(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []).slice(0, remaining);
    // Se limpia para que volver a elegir la misma foto vuelva a disparar `change`.
    event.target.value = "";
    if (files.length === 0) return;
    setProcessing(true);
    try {
      const reduced = await Promise.all(files.map((file) => downscalePhoto(file)));
      const added = reduced.map((file) => ({ id: `photo-${++photoSeq}`, file, previewUrl: previewOf(file) }));
      onChange([...latest.current, ...added].slice(0, max));
    } finally {
      setProcessing(false);
    }
  }

  function remove(photo: PickedPhoto) {
    revoke(photo);
    onChange(latest.current.filter((p) => p.id !== photo.id));
  }

  const input = (
    <input
      type="file"
      hidden
      accept="image/*"
      capture="environment"
      multiple
      aria-label={label ?? t("Fotos")}
      onChange={(event) => void pick(event)}
    />
  );

  return (
    <Box>
      {value.length === 0 && remaining > 0 ? (
        // Sin fotos todavía: un botón grande, a todo el ancho, que se acierta con guantes.
        <Button
          component="label"
          variant="outlined"
          fullWidth
          disabled={disabled || processing}
          startIcon={processing ? <CircularProgress size={24} /> : <AddAPhotoRounded />}
          sx={{
            minHeight: 64, borderStyle: "dashed", borderWidth: 2, borderRadius: `${R.md}px`, fontSize: 17, fontWeight: 800,
            "& .MuiButton-startIcon svg": { fontSize: 28 },
            "&:hover": { borderWidth: 2, borderStyle: "dashed" },
          }}
        >
          {t("Tomar foto")}
          {input}
        </Button>
      ) : (
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(96px, 1fr))", gap: 1 }}>
        {value.map((photo, index) => (
          <Box key={photo.id} sx={{
            position: "relative", aspectRatio: "1", borderRadius: `${R.md}px`, overflow: "hidden",
            border: "1px solid", borderColor: "divider", bgcolor: "action.hover",
            display: "grid", placeItems: "center",
          }}>
            {photo.previewUrl
              ? <Box component="img" src={photo.previewUrl} alt={t("Foto {{n}}", { n: index + 1 })}
                  sx={{ width: "100%", height: "100%", objectFit: "cover" }} />
              : <ImageRounded color="disabled" aria-label={t("Foto {{n}}", { n: index + 1 })} />}
            {!disabled && (
              <IconButton
                onClick={() => remove(photo)} aria-label={t("Quitar foto {{n}}", { n: index + 1 })}
                sx={{
                  position: "absolute", top: 4, right: 4, width: 36, height: 36, bgcolor: "rgba(0,0,0,.6)", color: "#fff",
                  "&:hover": { bgcolor: "rgba(0,0,0,.75)" },
                }}
              >
                <CloseRounded sx={{ fontSize: 20 }} />
              </IconButton>
            )}
          </Box>
        ))}
        {remaining > 0 && (
          <Button
            component="label"
            variant="outlined"
            disabled={disabled || processing}
            sx={{
              aspectRatio: "1", minWidth: 0, borderStyle: "dashed", borderRadius: `${R.md}px`,
              flexDirection: "column", gap: 0.5, textTransform: "none", fontSize: 14, fontWeight: 700,
              "& svg": { fontSize: 28 },
            }}
          >
            {processing ? <CircularProgress size={24} /> : <AddAPhotoRounded />}
            {t("Otra foto")}
            {input}
          </Button>
        )}
      </Box>
      )}
      <Typography variant="body2" color={error ? "error" : "text.secondary"} sx={{ display: "block", mt: 0.75, fontWeight: error ? 700 : 500 }}>
        {error ?? t("{{n}}/{{max}} fotos", { n: value.length, max })}
      </Typography>
    </Box>
  );
}
