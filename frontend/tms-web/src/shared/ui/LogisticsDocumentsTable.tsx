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
/** El segundo renglón de una celda: el tipo bajo el número, el transporte bajo el destinatario. */
const SECONDARY_SX = { display: "block", fontSize: 11.5, color: "text.secondary", mt: "1px" } as const;

/** Un destinatario o un transportista largos se cortan con «…» aquí; el detalle fiscal los da enteros. */
const TEXT_MAX_WIDTH = 220;

export function LogisticsDocumentsTable({ documents, showOrder, maxHeight }: {
  documents: (LogisticsDocumentView & { orderLabel?: string })[];
  /** Añade la columna de pedido, para la vista por viaje. */
  showOrder?: boolean;
  /** Alto máximo en px: la tabla se desplaza con la cabecera fija. Sin él crece con sus filas. */
  maxHeight?: number;
}) {
  // Ninguna celda parte su texto: en una columna angosta (el viaje la pone en la columna derecha)
  // nueve columnas que se doblan apilaban cada dato en tres renglones. Si no cabe, la tabla se
  // desplaza de lado, que se lee mejor que una fila del alto de un párrafo.
  const cellSx = { py: 0.5, px: 1, fontSize: 12.5, whiteSpace: "nowrap", verticalAlign: "top" } as const;
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  const columns = 6 + (showOrder ? 1 : 0);

  function toggle(key: string) {
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  return (
    <TableContainer
      tabIndex={maxHeight === undefined ? undefined : 0}
      sx={{
        border: "1px solid", borderColor: "divider", borderRadius: "4px", maxHeight,
        // La cabecera fija lleva el fondo del papel: con el de la página, las filas se veían a
        // través de ella al desplazar.
        "& thead th": { bgcolor: "background.paper" },
      }}
    >
      <Table size="small" stickyHeader={maxHeight !== undefined} aria-label={t("Documentos")}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ ...cellSx, width: 32 }} aria-label={t("Detalle")} />
            {showOrder && <TableCell sx={cellSx}>{t("Pedido")}</TableCell>}
            <TableCell sx={cellSx}>{t("Número y tipo")}</TableCell>
            <TableCell sx={cellSx}>{t("Emisión")}</TableCell>
            <TableCell sx={cellSx}>{t("Destinatario y transporte")}</TableCell>
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
                  <TableCell sx={cellSx}>
                    <Tooltip title={`${t("Origen")}: ${document.sourceSystem}`}>
                      <Typography component="span" variant="body2" sx={{ display: "block", fontSize: 12.5, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
                        {document.documentNumber}
                      </Typography>
                    </Tooltip>
                    <Typography component="span" sx={SECONDARY_SX}>{label}</Typography>
                  </TableCell>
                  <TableCell sx={cellSx}>{document.issueDate ? fmtDate(document.issueDate) : "-"}</TableCell>
                  <TableCell sx={cellSx}>
                    <Typography component="span" variant="body2" sx={{ display: "block", fontSize: 12.5, maxWidth: TEXT_MAX_WIDTH, overflow: "hidden", textOverflow: "ellipsis" }}>
                      {recipient ? `${recipient}${more}` : "-"}
                    </Typography>
                    {transport && <Typography component="span" sx={{ ...SECONDARY_SX, maxWidth: TEXT_MAX_WIDTH, overflow: "hidden", textOverflow: "ellipsis" }}>{transport}</Typography>}
                  </TableCell>
                  <TableCell sx={cellSx} align="right">
                    {document.amount === null ? "-" : fmtMoney(document.amount, document.currency)}
                  </TableCell>
                  <TableCell sx={cellSx}>{document.externalStatus ?? "-"}</TableCell>
                </TableRow>
                {expandable && (
                  // `height: auto`: el tema da a toda fila un alto fijo (MuiTableRow), y esta fila,
                  // vacía mientras el detalle está cerrado, se comía ese alto entero entre documento
                  // y documento. Cerrada no mide nada; abierta, lo que mida el detalle.
                  <TableRow sx={{ height: "auto" }}>
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
