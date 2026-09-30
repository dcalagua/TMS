import { Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tooltip, Typography } from "@mui/material";
import type { LogisticsDocumentView } from "../api/logisticsDocumentsApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtMoney } from "../../lib/locale";

/**
 * Los documentos de un pedido (ADR-015), en una tabla compacta y de solo lectura. La comparten el
 * detalle del pedido y el espacio de trabajo del viaje, para que un mismo documento no se lea
 * distinto en cada sitio.
 *
 * El número se enseña tal y como se emitió: la serie es cosa del ERP y de la autoridad
 * tributaria, y TMS nunca la interpreta. El importe es informativo y, si no llegó, se deja el
 * hueco en lugar de un cero.
 */
export function LogisticsDocumentsTable({ documents, showOrder }: {
  documents: (LogisticsDocumentView & { orderLabel?: string })[];
  /** Añade la columna de pedido, para la vista por viaje. */
  showOrder?: boolean;
}) {
  const cellSx = { py: 0.5, px: 1, fontSize: 12.5 } as const;
  return (
    <TableContainer sx={{ border: "1px solid", borderColor: "divider", borderRadius: 1 }}>
      <Table size="small" aria-label={t("Documentos")}>
        <TableHead>
          <TableRow>
            {showOrder && <TableCell sx={cellSx}>{t("Pedido")}</TableCell>}
            <TableCell sx={cellSx}>{t("Tipo")}</TableCell>
            <TableCell sx={cellSx}>{t("Número")}</TableCell>
            <TableCell sx={cellSx}>{t("Emisión")}</TableCell>
            <TableCell sx={cellSx}>{t("Destinatario")}</TableCell>
            <TableCell sx={cellSx} align="right">{t("Importe")}</TableCell>
            <TableCell sx={cellSx}>{t("Estado")}</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {documents.map((document, index) => (
            <TableRow key={`${document.id}-${document.orderLabel ?? ""}-${index}`}>
              {showOrder && <TableCell sx={{ ...cellSx, fontWeight: 700 }}>{document.orderLabel ?? "-"}</TableCell>}
              <TableCell sx={cellSx}>{enumLabel("logisticsDocumentType", document.documentType)}</TableCell>
              <TableCell sx={cellSx}>
                <Tooltip title={`${t("Origen")}: ${document.sourceSystem}`}>
                  <Typography component="span" variant="body2" sx={{ fontSize: 12.5, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
                    {document.documentNumber}
                  </Typography>
                </Tooltip>
              </TableCell>
              <TableCell sx={cellSx}>{document.issueDate ? fmtDate(document.issueDate) : "-"}</TableCell>
              <TableCell sx={cellSx}>{document.recipientName ?? "-"}</TableCell>
              <TableCell sx={cellSx} align="right">
                {document.amount === null ? "-" : fmtMoney(document.amount, document.currency ?? "PEN")}
              </TableCell>
              <TableCell sx={cellSx}>{document.externalStatus ?? "-"}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
