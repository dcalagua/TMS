import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Box, Button, IconButton, InputAdornment, Paper, TextField, Tooltip, Typography } from "@mui/material";
import { ContentCopyRounded, LinkOffRounded, LinkRounded, WhatsApp } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  fetchTrackingLinks, issueTrackingLink, publicTrackingUrl, revokeTrackingLink, whatsAppShareUrl,
  type TrackingLinkState, type TrackingLinkView,
} from "../../shared/api/trackingLinksApi";
import { useCompany } from "../../shared/company/CompanyContext";
import { LoadingState, SectionHeader, StatusChip } from "../../shared/ui/components";
import type { StatusTone } from "../../theme";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { trackingMessage } from "./trackingLinks";

const STATE_TONE: Record<TrackingLinkState, StatusTone> = { ACTIVE: "done", EXPIRED: "neutral", REVOKED: "overdue" };

interface Props {
  companyId: string;
  orderId: string;
  orderNumber: string;
  /** Sin encabezado propio cuando lo pinta un drawer que ya lo lleva. */
  withHeader?: boolean;
}

/**
 * «Compartir seguimiento» de un pedido (ADR-029): generar un enlace, copiarlo o mandarlo por WhatsApp, y
 * revocarlo. El token sólo existe en la respuesta de la generación: se guarda aquí, en el estado de este
 * componente, mientras el drawer esté abierto, y nunca vuelve a pedirse - la lista no lo trae.
 */
export function TrackingLinksPanel({ companyId, orderId, orderNumber, withHeader = true }: Props) {
  const { selected } = useCompany();
  const queryClient = useQueryClient();
  const [issuedUrl, setIssuedUrl] = useState<string | null>(null);
  const queryKey = ["tracking-links", companyId, orderId];

  const linksQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTrackingLinks(companyId, orderId, signal),
    enabled: companyId !== "" && orderId !== "",
    retry: false,
  });

  const issue = useMutation({
    mutationFn: () => issueTrackingLink(companyId, orderId),
    onSuccess: (issued) => {
      setIssuedUrl(publicTrackingUrl(issued.path));
      void queryClient.invalidateQueries({ queryKey });
      notifySuccess(t("Enlace de seguimiento generado"), orderNumber);
    },
    onError: (error) => notifyError(t("No se pudo generar el enlace"), describeApiError(error as ApiError)),
  });

  const revoke = useMutation({
    mutationFn: ({ link, reason }: { link: TrackingLinkView; reason: string }) =>
      revokeTrackingLink(companyId, orderId, link.id, reason, link.version),
    onSuccess: () => {
      setIssuedUrl(null);
      void queryClient.invalidateQueries({ queryKey });
      notifySuccess(t("Enlace revocado"), t("Deja de funcionar ahora mismo."));
    },
    onError: (error) => notifyError(t("No se pudo revocar el enlace"), describeApiError(error as ApiError)),
  });

  async function askRevoke(link: TrackingLinkView) {
    const reason = await promptDialog({
      title: t("¿Revocar este enlace de seguimiento?"),
      text: t("Quien lo tenga dejará de ver el pedido. Esto no se puede deshacer; puedes generar otro."),
      inputLabel: t("Motivo"),
      inputPlaceholder: t("Por ejemplo: enviado al cliente equivocado"),
      required: true,
      maxLength: 500,
      confirmLabel: t("Revocar"),
      dangerous: true,
    });
    if (reason) revoke.mutate({ link, reason });
  }

  async function copy(url: string) {
    try {
      await navigator.clipboard.writeText(url);
      notifySuccess(t("Enlace copiado"));
    } catch {
      notifyError(t("No se pudo copiar"), t("Selecciona el enlace y cópialo a mano."));
    }
  }

  const links = linksQuery.data ?? [];

  return (
    <Box>
      {withHeader && <SectionHeader title={t("Compartir seguimiento")} />}
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {t("Un enlace sin contraseña para que el cliente vea el estado, la hora estimada y la entrega de este pedido. No muestra precios, direcciones ni otros pedidos, y vence 7 días después de la entrega.")}
      </Typography>

      {issuedUrl ? (
        <Paper variant="outlined" sx={{ p: 1.5, mb: 1.5, borderRadius: 2 }}>
          <TextField
            value={issuedUrl} size="small" fullWidth label={t("Enlace de seguimiento")}
            slotProps={{
              input: {
                readOnly: true,
                endAdornment: (
                  <InputAdornment position="end">
                    <Tooltip title={t("Copiar enlace")}>
                      <IconButton edge="end" onClick={() => void copy(issuedUrl)} aria-label={t("Copiar enlace")}>
                        <ContentCopyRounded fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </InputAdornment>
                ),
              },
            }}
            onFocus={(event) => event.target.select()}
          />
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mt: 1.25 }}>
            <Button size="small" variant="outlined" startIcon={<ContentCopyRounded />} onClick={() => void copy(issuedUrl)}>
              {t("Copiar enlace")}
            </Button>
            <Button
              size="small" variant="contained" color="success" startIcon={<WhatsApp />}
              component="a" href={whatsAppShareUrl(trackingMessage(orderNumber, selected?.name, issuedUrl))}
              target="_blank" rel="noopener noreferrer"
            >
              {t("Enviar por WhatsApp")}
            </Button>
          </Box>
          <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 1 }}>
            {t("Por seguridad el enlace solo se muestra ahora. Si lo pierdes, genera otro y revoca este.")}
          </Typography>
        </Paper>
      ) : (
        <Button
          size="small" variant="contained" startIcon={<LinkRounded />} sx={{ mb: 1.5 }}
          disabled={issue.isPending} onClick={() => issue.mutate()}
        >
          {issue.isPending ? t("Generando...") : t("Generar enlace")}
        </Button>
      )}

      {linksQuery.isPending ? (
        <LoadingState minHeight={60} />
      ) : linksQuery.isError ? (
        <Alert severity="warning">{describeApiError(linksQuery.error as ApiError)}</Alert>
      ) : links.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("Este pedido todavía no tiene enlaces de seguimiento.")}</Typography>
      ) : (
        <Box component="ul" aria-label={t("Enlaces de seguimiento")} sx={{ listStyle: "none", p: 0, m: 0, display: "grid", gap: 0.75 }}>
          {links.map((link) => (
            <Box
              component="li" key={link.id}
              sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", px: 1.25, py: 0.75,
                border: 1, borderColor: "divider", borderRadius: 1.5 }}
            >
              <StatusChip label={enumLabel("trackingLinkState", link.state)} tone={STATE_TONE[link.state]} />
              <Box sx={{ minWidth: 0, flex: 1 }}>
                <Typography variant="caption" component="p" color="text.secondary">
                  {t("Creado {{when}} · vence {{expires}}", { when: fmtDateTime(link.createdAt), expires: fmtDateTime(link.expiresAt) })}
                </Typography>
                <Typography variant="caption" component="p" color="text.secondary">
                  {link.viewCount === 0
                    ? t("Aún no lo abrieron")
                    : t("Abierto {{n}} veces · última {{when}}", { n: link.viewCount, when: fmtDateTime(link.lastViewedAt) })}
                  {link.revokeReason && ` · ${t("Motivo")}: ${link.revokeReason}`}
                </Typography>
              </Box>
              {link.state === "ACTIVE" && (
                <Tooltip title={t("Revocar")}>
                  <span>
                    <IconButton size="small" color="error" aria-label={t("Revocar enlace")}
                      disabled={revoke.isPending} onClick={() => void askRevoke(link)}>
                      <LinkOffRounded fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
}
