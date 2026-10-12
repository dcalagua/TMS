import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Box, Button, ButtonBase, CircularProgress, Dialog, DialogActions, DialogContent, Typography,
} from "@mui/material";
import { BrokenImageRounded, DescriptionRounded, DownloadRounded, DrawRounded } from "@mui/icons-material";
import { saveDownloadedFile, type DownloadedFile } from "../api/httpClient";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { R } from "../../theme";

export interface EvidenceItem {
  id: string;
  evidenceType: "PHOTO" | "SIGNATURE";
  contentType: string;
  uploadedAt?: string;
  /** Nombre con el que se guarda al descargarla, si el servidor no sugiere otro. */
  fileName?: string | null;
}

interface LoadedEvidence {
  file: DownloadedFile;
  /** Data URL de la imagen, o `null` si no es una imagen que se pueda pintar. */
  dataUrl: string | null;
}

function readAsDataUrl(blob: Blob): Promise<string | null> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : null);
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(blob);
  });
}

interface EvidenceGalleryProps {
  items: EvidenceItem[];
  /** Prefijo de la clave de caché, único por documento: las miniaturas no se vuelven a pedir. */
  queryKey: readonly unknown[];
  /** Trae los bytes de una evidencia por la API (bearer + `X-Company-Id`). */
  load: (evidenceId: string, signal?: AbortSignal) => Promise<DownloadedFile>;
  emptyText?: string;
}

/**
 * Las fotos y la firma de un documento, como miniaturas.
 *
 * Los bytes llegan por la API y no por un enlace, porque no tienen dirección pública (ADR-006):
 * la petición lleva el token y la empresa como cualquier otra. Se pintan como data URL, que no
 * hay que revocar, y quedan en la caché de react-query: abrir el mismo documento dos veces no
 * descarga dos veces.
 */
export function EvidenceGallery({ items, queryKey, load, emptyText }: EvidenceGalleryProps) {
  const [open, setOpen] = useState<{ item: EvidenceItem; loaded: LoadedEvidence } | null>(null);

  if (items.length === 0) {
    return <Typography variant="body2" color="text.secondary">{emptyText ?? t("Sin fotos ni firma.")}</Typography>;
  }

  return (
    <>
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(96px, 1fr))", gap: 1 }}>
        {items.map((item) => (
          <EvidenceThumb key={item.id} item={item} queryKey={queryKey} load={load}
            onOpen={(loaded) => setOpen({ item, loaded })} />
        ))}
      </Box>

      <Dialog open={open !== null} onClose={() => setOpen(null)} maxWidth="md" fullWidth>
        <DialogContent sx={{ p: 1, display: "grid", placeItems: "center", bgcolor: "background.default" }}>
          {open?.loaded.dataUrl && (
            <Box component="img" src={open.loaded.dataUrl} alt={enumLabel("evidenceType", open.item.evidenceType)}
              sx={{ maxWidth: "100%", maxHeight: "75vh", objectFit: "contain" }} />
          )}
        </DialogContent>
        <DialogActions>
          <Button startIcon={<DownloadRounded />}
            onClick={() => open && saveDownloadedFile(open.loaded.file, open.item.fileName ?? `evidencia-${open.item.id}`)}>
            {t("Descargar")}
          </Button>
          <Button variant="contained" onClick={() => setOpen(null)}>{t("Cerrar")}</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function EvidenceThumb({ item, queryKey, load, onOpen }: {
  item: EvidenceItem;
  queryKey: readonly unknown[];
  load: EvidenceGalleryProps["load"];
  onOpen: (loaded: LoadedEvidence) => void;
}) {
  const query = useQuery({
    queryKey: [...queryKey, "evidence", item.id],
    queryFn: async ({ signal }): Promise<LoadedEvidence> => {
      const file = await load(item.id, signal);
      const isImage = (file.blob.type || item.contentType).startsWith("image/");
      return { file, dataUrl: isImage ? await readAsDataUrl(file.blob) : null };
    },
    staleTime: Infinity,
    retry: 1,
  });

  const label = enumLabel("evidenceType", item.evidenceType);
  const loaded = query.data;

  function activate() {
    if (!loaded) return;
    if (loaded.dataUrl) onOpen(loaded);
    else saveDownloadedFile(loaded.file, item.fileName ?? `evidencia-${item.id}`);
  }

  return (
    <ButtonBase
      onClick={activate}
      disabled={!loaded}
      aria-label={item.uploadedAt ? `${label} · ${fmtDateTime(item.uploadedAt)}` : label}
      sx={{
        position: "relative", aspectRatio: "1", borderRadius: `${R.md}px`, overflow: "hidden",
        border: "1px solid", borderColor: "divider", bgcolor: item.evidenceType === "SIGNATURE" ? "#FFFFFF" : "action.hover",
        display: "grid", placeItems: "center",
      }}
    >
      {query.isPending ? <CircularProgress size={20} />
        : query.isError ? <BrokenImageRounded color="disabled" />
          : loaded?.dataUrl
            ? <Box component="img" src={loaded.dataUrl} alt="" sx={{
                width: "100%", height: "100%", objectFit: item.evidenceType === "SIGNATURE" ? "contain" : "cover",
              }} />
            : <DescriptionRounded color="action" />}
      <Box sx={{
        position: "absolute", left: 0, right: 0, bottom: 0, px: 0.75, py: 0.25, display: "flex", alignItems: "center", gap: 0.5,
        bgcolor: "rgba(0,0,0,.55)", color: "#fff", fontSize: 11, fontWeight: 700,
      }}>
        {item.evidenceType === "SIGNATURE" && <DrawRounded sx={{ fontSize: 12 }} />}
        {label}
      </Box>
    </ButtonBase>
  );
}
