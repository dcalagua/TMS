import type { ReactNode } from "react";
import {
  Alert, Box, Button, ButtonBase, CircularProgress, Drawer, IconButton, Paper, Typography,
} from "@mui/material";
import {
  CloseRounded, CloudDoneRounded, CloudOffRounded, CloudSyncRounded, CloudUploadRounded, DeleteOutlineRounded,
  ErrorOutlineRounded, ReplayRounded, SyncRounded,
} from "@mui/icons-material";
import { DocumentStatusChip } from "../../shared/ui/DocumentStatusChip";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtTime } from "../../lib/locale";
import { confirmDialog } from "../../lib/ui";
import { R } from "../../theme";
import { useOutbox } from "./offline/offlineContext";
import { isDocumentEntry, type OutboxEntry } from "./offline/types";
import { SAFE_AREA, TOUCH_TARGET } from "./portalUi";

type Tone = "ok" | "offline" | "pending" | "error" | "syncing";

function useSyncTone(): { tone: Tone; label: string } {
  const { online, pendingCount, failedEntries, syncing } = useOutbox();
  if (!online) {
    return { tone: "offline", label: pendingCount > 0 ? t("Sin conexión · {{n}}", { n: pendingCount }) : t("Sin conexión") };
  }
  if (syncing && pendingCount > 0) return { tone: "syncing", label: t("Sincronizando") };
  if (failedEntries.length > 0) return { tone: "error", label: t("{{n}} con error", { n: failedEntries.length }) };
  if (pendingCount > 0) return { tone: "pending", label: t("{{n}} por sincronizar", { n: pendingCount }) };
  return { tone: "ok", label: t("En línea") };
}

const TONE_STYLE: Record<Tone, { light: [string, string]; dark: [string, string] }> = {
  ok: { light: ["#E5F2EB", "#236245"], dark: ["#163528", "#86D4AB"] },
  syncing: { light: ["#E3F0FA", "#0F4C81"], dark: ["#15314A", "#9FD0F2"] },
  pending: { light: ["#FBEEDD", "#8A5200"], dark: ["#3A2E10", "#F0C25E"] },
  offline: { light: ["#EEF1F0", "#46534C"], dark: ["#27332E", "#B7C6C0"] },
  error: { light: ["#FBE7E8", "#9A2630"], dark: ["#3A1719", "#F0989D"] },
};

const TONE_ICON: Record<Tone, ReactNode> = {
  ok: <CloudDoneRounded />,
  syncing: <CircularProgress size={16} color="inherit" />,
  pending: <CloudUploadRounded />,
  offline: <CloudOffRounded />,
  error: <ErrorOutlineRounded />,
};

/**
 * El indicador de la barra: hay red o no, y si queda algo por enviar o algo que falló. Tocarlo
 * abre la hoja de sincronización.
 */
export function SyncStatusChip({ onOpen }: { onOpen: () => void }) {
  const { tone, label } = useSyncTone();
  const style = TONE_STYLE[tone];
  return (
    <ButtonBase
      onClick={onOpen}
      aria-label={t("Sincronización: {{state}}", { state: label })}
      sx={{
        display: "inline-flex", alignItems: "center", gap: 0.75, minHeight: 40, px: 1.25, borderRadius: `${R.pill}px`,
        fontWeight: 800, fontSize: 13, whiteSpace: "nowrap", flexShrink: 0,
        bgcolor: (theme) => style[theme.palette.mode === "dark" ? "dark" : "light"][0],
        color: (theme) => style[theme.palette.mode === "dark" ? "dark" : "light"][1],
        "& svg": { fontSize: 18 },
      }}
    >
      {TONE_ICON[tone]}
      <Box component="span" sx={{ maxWidth: { xs: 128, sm: "none" }, overflow: "hidden", textOverflow: "ellipsis" }}>{label}</Box>
    </ButtonBase>
  );
}

function entryTitle(entry: OutboxEntry): string {
  // Una acción de parada (V70) ya trae su nombre, puesto al encolarla.
  if (!isDocumentEntry(entry)) return entry.title;
  return `${enumLabel("deliveryDocumentType", entry.documentType)} ${entry.documentNumber}`;
}

/**
 * La hoja de sincronización (ADR-019 §1): qué espera, qué falló y por qué, y las dos salidas de
 * algo que falló — reintentar o descartar. Nada se descarta sin preguntar.
 */
export function SyncSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { entries, online, pendingCount, syncing, sync, retry, discard } = useOutbox();

  async function confirmDiscard(entry: OutboxEntry) {
    const confirmed = await confirmDialog({
      title: t("¿Descartar este reporte?"),
      text: isDocumentEntry(entry)
        ? t("El reporte de {{doc}} no se enviará. Podrás volver a reportar el documento desde el viaje.", { doc: entryTitle(entry) })
        : t("«{{what}}» no se enviará. Podrás volver a registrarlo desde el viaje.", { what: entryTitle(entry) }),
      confirmLabel: t("Descartar"),
      dangerous: true,
    });
    if (confirmed) await discard(entry.id);
  }

  return (
    <Drawer
      anchor="bottom" open={open} onClose={onClose}
      sx={{ zIndex: (theme) => theme.zIndex.drawer + 2 }}
      slotProps={{
        paper: {
          "aria-labelledby": "portal-sync-title",
          sx: {
            maxHeight: "88dvh", borderTopLeftRadius: `${R.xl}px`, borderTopRightRadius: `${R.xl}px`,
            backgroundImage: "none", maxWidth: 720, mx: "auto",
            pb: `calc(${SAFE_AREA.bottom} + 16px)`, pl: SAFE_AREA.left, pr: SAFE_AREA.right,
          },
        },
      }}
    >
      <Box aria-hidden sx={{ width: 40, height: 5, borderRadius: 3, bgcolor: "divider", mx: "auto", mt: 1 }} />
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, px: 2, pt: 1, pb: 1 }}>
        <Typography id="portal-sync-title" component="h2" sx={{ fontWeight: 800, fontSize: 20, flex: 1 }}>
          {t("Sincronización")}
        </Typography>
        <IconButton onClick={onClose} aria-label={t("Cerrar")} sx={{ width: 48, height: 48 }}>
          <CloseRounded />
        </IconButton>
      </Box>

      <Box sx={{ px: 2, display: "grid", gap: 1.5, overflowY: "auto" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          {online ? <CloudDoneRounded color="success" /> : <CloudOffRounded color="disabled" />}
          <Typography sx={{ fontSize: 15, fontWeight: 600 }}>
            {online
              ? t("Con conexión.")
              : t("Sin conexión. Los reportes se enviarán solos al recuperar señal.")}
          </Typography>
        </Box>

        <Button
          variant="contained" size="large" fullWidth
          startIcon={syncing ? <CircularProgress size={20} color="inherit" /> : <SyncRounded />}
          disabled={!online || syncing || pendingCount === 0}
          onClick={() => void sync({ force: true })}
          sx={{ minHeight: TOUCH_TARGET, fontSize: 17, fontWeight: 800 }}
        >
          {syncing ? t("Sincronizando...") : t("Sincronizar ahora")}
        </Button>

        {entries.length === 0 ? (
          <Box sx={{ display: "grid", placeItems: "center", textAlign: "center", gap: 0.5, py: 3 }}>
            <CloudDoneRounded color="success" sx={{ fontSize: 40 }} />
            <Typography sx={{ fontWeight: 800, fontSize: 17 }}>{t("Todo sincronizado")}</Typography>
            <Typography color="text.secondary" sx={{ fontSize: 15 }}>
              {t("No hay reportes esperando en este teléfono.")}
            </Typography>
          </Box>
        ) : (
          <Box component="ul" aria-label={t("Reportes en este teléfono")} sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 1 }}>
            {entries.map((entry) => (
              <Paper component="li" key={entry.id} variant="outlined" sx={{ p: 1.5, borderRadius: `${R.md}px`, display: "grid", gap: 1 }}>
                <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1 }}>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 800, fontSize: 16 }}>{entryTitle(entry)}</Typography>
                    <Typography color="text.secondary" sx={{ fontSize: 14 }}>
                      {[entry.shipmentNumber, isDocumentEntry(entry) ? entry.customerName : null].filter(Boolean).join(" · ")}
                    </Typography>
                    <Typography color="text.secondary" sx={{ fontSize: 14 }}>
                      {t("Guardado {{when}}", { when: fmtDateTime(entry.reportedAt) })}
                    </Typography>
                  </Box>
                  {isDocumentEntry(entry) && <DocumentStatusChip status={entry.status} />}
                </Box>

                {entry.state === "FAILED" ? (
                  <Alert severity="error" sx={{ fontSize: 14 }}>
                    <strong>{t("El servidor no lo aceptó.")}</strong> {entry.lastError}
                  </Alert>
                ) : (
                  <Typography sx={{ fontSize: 14, display: "flex", alignItems: "center", gap: 0.75, color: "text.secondary" }}>
                    {entry.state === "SENDING" ? <CircularProgress size={14} /> : <CloudSyncRounded sx={{ fontSize: 18 }} />}
                    {entry.state === "SENDING"
                      ? t("Enviando...")
                      : entry.attempts > 0 && entry.nextAttemptAt
                        ? t("Intento {{n}} sin éxito. Se reintenta a las {{time}}.", { n: entry.attempts, time: fmtTime(entry.nextAttemptAt) })
                        : t("Esperando para enviar.")}
                  </Typography>
                )}

                {entry.state !== "SENDING" && (
                  <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end", flexWrap: "wrap" }}>
                    <Button
                      color="error" startIcon={<DeleteOutlineRounded />} onClick={() => void confirmDiscard(entry)}
                      sx={{ minHeight: 44 }}
                    >
                      {t("Descartar")}
                    </Button>
                    {(entry.state === "FAILED" || entry.attempts > 0) && (
                      <Button
                        variant="outlined" startIcon={<ReplayRounded />} disabled={!online}
                        onClick={() => void retry(entry.id)} sx={{ minHeight: 44 }}
                      >
                        {t("Reintentar")}
                      </Button>
                    )}
                  </Box>
                )}
              </Paper>
            ))}
          </Box>
        )}

        <Typography color="text.secondary" sx={{ fontSize: 13, pb: 1 }}>
          {t("Los reportes sin sincronizar se guardan solo en este teléfono. Si borras los datos del navegador o cierras sesión, se pierden.")}
        </Typography>
      </Box>
    </Drawer>
  );
}
