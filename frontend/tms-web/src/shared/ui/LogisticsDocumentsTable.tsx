import { Fragment, useState } from "react";
import { Collapse, IconButton, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tooltip, Typography } from "@mui/material";
import { KeyboardArrowDownRounded, KeyboardArrowRightRounded } from "@mui/icons-material";
import type { LogisticsDocumentView } from "../api/logisticsDocumentsApi";
import { t } from "../../lib/i18n";
import { fmtDate, fmtMoney } from "../../lib/locale";
import { LogisticsDocumentFiscalDetail } from "./LogisticsDocumentFiscalDetail";
import { hasFiscalDetail, logisticsDocumentLabel } from "./logisticsDocumentLabel";

/**
 * Los documentos de un pedido (ADR-015), en una tabla compacta y de solo lectura. La comparten el
 * detalle del pedido y el espacio de trabajo del viaje, para que un mismo documento no se lea
 * distinto en cada sitio.
 *
 * El número se enseña tal y como se emitió: la serie es cosa del ERP y de la autoridad
 * tributaria, y TMS nunca la interpreta. El importe es informativo y, si no llegó, se deja el
 * hueco en lugar de un cero.
 *
 * ADR-028: el tipo se nombra por país («Guía de remisión (SRI 06)», «GRE remitente (SUNAT 09)») y un
 * documento con datos fiscales - clave de acceso, traslado, transportista, destinatarios - se
 * despliega en su fila. Todo es lo que mandó el ERP; el TMS no emite ni valida ante la autoridad.
 */
export function LogisticsDocumentsTable({ documents, showOrder }: {
  documents: (LogisticsDocumentView & { orderLabel?: string })[];
  /** Añade la columna de pedido, para la vista por viaje. */
  showOrder?: boolean;
}) {
  const cellSx = { py: 0.5, px: 1, fontSize: 12.5 } as const;
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  const columns = 8 + (showOrder ? 1 : 0);

  function toggle(key: string) {
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  return (
    <TableContainer sx={{ border: "1px solid", borderColor: "divider", borderRadius: "4px" }}>
      <Table size="small" aria-label={t("Documentos")}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ ...cellSx, width: 32 }} aria-label={t("Detalle")} />
            {showOrder && <TableCell sx={cellSx}>{t("Pedido")}</TableCell>}
            <TableCell sx={cellSx}>{t("Tipo")}</TableCell>
            <TableCell sx={cellSx}>{t("Número")}</TableCell>
            <TableCell sx={cellSx}>{t("Emisión")}</TableCell>
            <TableCell sx={cellSx}>{t("Destinatario")}</TableCell>
            <TableCell sx={cellSx}>{t("Transporte")}</TableCell>
            <TableCell sx={cellSx} align="right">{t("Importe")}</TableCell>
            <TableCell sx={cellSx}>{t("Estado")}</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {documents.map((document, index) => {
            const key = `${document.id}-${document.orderLabel ?? ""}-${index}`;
            const expandable = hasFiscalDetail(document);
            const expanded = open.has(key);
            const label = logisticsDocumentLabel(document.documentType, document.fiscal);
            const recipient = document.recipientName ?? document.recipients?.[0]?.name ?? null;
            const more = (document.recipients?.length ?? 0) > 1 ? ` +${(document.recipients?.length ?? 1) - 1}` : "";
            const transport = [document.fiscal?.carrierName, document.fiscal?.vehiclePlate].filter(Boolean).join(" · ");
            return (
              <Fragment key={key}>
                <TableRow sx={expanded ? { "& > td": { borderBottom: "none" } } : undefined}>
                  <TableCell sx={{ ...cellSx, px: 0.5 }}>
                    {expandable && (
                      <IconButton
                        size="small"
                        aria-expanded={expanded}
                        aria-label={t("Ver detalle de {{document}}", { document: `${label} ${document.documentNumber}` })}
                        onClick={() => toggle(key)}
                      >
                        {expanded ? <KeyboardArrowDownRounded fontSize="small" /> : <KeyboardArrowRightRounded fontSize="small" />}
                      </IconButton>
                    )}
                  </TableCell>
                  {showOrder && <TableCell sx={{ ...cellSx, fontWeight: 700 }}>{document.orderLabel ?? "-"}</TableCell>}
                  <TableCell sx={cellSx}>{label}</TableCell>
                  <TableCell sx={cellSx}>
                    <Tooltip title={`${t("Origen")}: ${document.sourceSystem}`}>
                      <Typography component="span" variant="body2" sx={{ fontSize: 12.5, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
                        {document.documentNumber}
                      </Typography>
                    </Tooltip>
                  </TableCell>
                  <TableCell sx={cellSx}>{document.issueDate ? fmtDate(document.issueDate) : "-"}</TableCell>
                  <TableCell sx={cellSx}>{recipient ? `${recipient}${more}` : "-"}</TableCell>
                  <TableCell sx={cellSx}>{transport || "-"}</TableCell>
                  <TableCell sx={cellSx} align="right">
                    {document.amount === null ? "-" : fmtMoney(document.amount, document.currency ?? "PEN")}
                  </TableCell>
                  <TableCell sx={cellSx}>{document.externalStatus ?? "-"}</TableCell>
                </TableRow>
                {expandable && (
                  <TableRow>
                    <TableCell colSpan={columns} sx={{ py: 0, px: 1.5, borderBottom: expanded ? undefined : "none" }}>
                      <Collapse in={expanded} timeout="auto" unmountOnExit>
                        <LogisticsDocumentFiscalDetail document={document} />
                      </Collapse>
                    </TableCell>
                  </TableRow>
                )}
              </Fragment>
            );
          })}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
