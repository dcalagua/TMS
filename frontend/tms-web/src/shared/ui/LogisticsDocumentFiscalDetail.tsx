import { Box, Chip, IconButton, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from "@mui/material";
import { ContentCopyRounded, VerifiedOutlined } from "@mui/icons-material";
import type { LogisticsDocumentView } from "../api/logisticsDocumentsApi";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal } from "../../lib/locale";
import { notifyError, notifySuccess } from "../../lib/ui";
import { KeyFacts, type KeyFact } from "./components/DetailLayout";
import { authorizationLabel, documentCountry, receivedFromErpNote } from "./logisticsDocumentLabel";

/**
 * El detalle fiscal de un documento recibido del ERP (ADR-028): la clave de acceso que se copia, el
 * traslado, el transportista y la placa, el punto de partida y los destinatarios con sus líneas.
 *
 * Todo se enseña como llegó. El TMS no emite, no firma y no valida nada ante el SRI ni la SUNAT; la
 * nota lo dice al lado del dato, para que nadie lea «autorizado» donde solo hay «recibido».
 */
export function LogisticsDocumentFiscalDetail({ document }: { document: LogisticsDocumentView }) {
  const fiscal = document.fiscal;
  const country = documentCountry(document.documentType, fiscal);
  const key = fiscal?.authorizationNumber ?? null;
  const keyLabel = authorizationLabel(country);

  async function copy(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      notifySuccess(t("Copiado al portapapeles"));
    } catch {
      notifyError(t("No se pudo copiar"));
    }
  }

  const facts: KeyFact[] = [];
  if (key) {
    facts.push({
      label: keyLabel,
      span: true,
      value: (
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, minWidth: 0 }}>
          <Typography component="span" sx={{ fontFamily: "monospace", fontSize: 12.5, fontWeight: 700, wordBreak: "break-all" }}>
            {key}
          </Typography>
          <Tooltip title={t("Copiar")}>
            <IconButton size="small" aria-label={t("Copiar {{what}}", { what: keyLabel.toLowerCase() })} onClick={() => void copy(key)}>
              <ContentCopyRounded sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
        </Box>
      ),
    });
  }
  if (document.issuerTaxId) {
    facts.push({ label: country === "EC" || country === "PE" ? t("RUC emisor") : t("Id. fiscal del emisor"), value: document.issuerTaxId });
  }
  if (fiscal?.transportStartDate || fiscal?.transportEndDate) {
    facts.push({
      label: t("Traslado"),
      value: `${fmtDate(fiscal.transportStartDate)} – ${fmtDate(fiscal.transportEndDate)}`,
    });
  }
  if (fiscal?.carrierName || fiscal?.carrierTaxId) {
    facts.push({ label: t("Transportista"), value: fiscal.carrierName ?? fiscal.carrierTaxId, sub: fiscal.carrierName ? fiscal.carrierTaxId : undefined });
  }
  if (fiscal?.vehiclePlate) facts.push({ label: t("Placa"), value: fiscal.vehiclePlate });
  if (fiscal?.originAddress) facts.push({ label: t("Punto de partida"), value: fiscal.originAddress, span: true });
  if (fiscal?.transferReason) facts.push({ label: t("Motivo de traslado"), value: fiscal.transferReason });

  const recipients = document.recipients ?? [];
  return (
    <Box sx={{ display: "grid", gap: 1.25, py: 1 }}>
      <Chip
        size="small" variant="outlined" icon={<VerifiedOutlined />}
        label={receivedFromErpNote(country)}
        sx={{ justifySelf: "start", fontWeight: 600 }}
      />
      {facts.length > 0 && <KeyFacts items={facts} columns={3} />}
      {recipients.length > 0 && (
        <Box sx={{ display: "grid", gap: 1 }} aria-label={t("Destinatarios")} role="list">
          <Typography sx={{ fontSize: 11, fontWeight: 800, letterSpacing: ".08em", textTransform: "uppercase", color: "text.secondary" }}>
            {t("Destinatarios")}
          </Typography>
          {recipients.map((recipient, index) => (
            <Box key={index} role="listitem" sx={{ border: "1px solid", borderColor: "divider", borderRadius: "4px", p: 1, display: "grid", gap: 0.5 }}>
              <Typography sx={{ fontSize: 13, fontWeight: 700 }}>
                {recipient.name ?? "-"}{recipient.taxId ? ` · ${recipient.taxId}` : ""}
              </Typography>
              {recipient.destinationAddress && (
                <Typography variant="body2" sx={{ fontSize: 12.5 }}>{recipient.destinationAddress}</Typography>
              )}
              <Typography variant="body2" color="text.secondary" sx={{ fontSize: 12.5 }}>
                {[
                  recipient.transferReason ? `${t("Motivo")}: ${recipient.transferReason}` : null,
                  recipient.relatedDocumentNumber
                    ? `${t("Documento de sustento")}: ${recipient.relatedDocumentNumber}`
                      + (recipient.relatedDocumentDate ? ` (${fmtDate(recipient.relatedDocumentDate)})` : "")
                    : null,
                  recipient.route ? `${t("Ruta")}: ${recipient.route}` : null,
                ].filter(Boolean).join(" · ") || null}
              </Typography>
              {recipient.items.length > 0 && (
                <Table size="small" aria-label={t("Líneas de la guía")}>
                  <TableHead>
                    <TableRow>
                      <TableCell sx={{ py: 0.25, px: 1, fontSize: 12 }}>{t("Código")}</TableCell>
                      <TableCell sx={{ py: 0.25, px: 1, fontSize: 12 }}>{t("Descripción")}</TableCell>
                      <TableCell sx={{ py: 0.25, px: 1, fontSize: 12 }} align="right">{t("Cantidad")}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {recipient.items.map((item, line) => (
                      <TableRow key={line}>
                        <TableCell sx={{ py: 0.25, px: 1, fontSize: 12 }}>{item.code ?? "-"}</TableCell>
                        <TableCell sx={{ py: 0.25, px: 1, fontSize: 12 }}>{item.description}</TableCell>
                        <TableCell sx={{ py: 0.25, px: 1, fontSize: 12, fontVariantNumeric: "tabular-nums" }} align="right">
                          {item.quantity === null ? "-" : `${fmtDecimal(item.quantity, 6)}${item.unit ? ` ${item.unit}` : ""}`}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
}
