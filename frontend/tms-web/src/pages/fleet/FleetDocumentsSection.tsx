import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Box, Button, IconButton, Stack, Tooltip, Typography } from "@mui/material";
import { AddRounded, BlockRounded, CheckCircleRounded, EditRounded } from "@mui/icons-material";
import {
  activateFleetDocument, deactivateFleetDocument, fetchSubjectCompliance,
  type FleetDocumentSubject, type FleetDocumentView,
} from "../../shared/api/fleetDocumentsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormSection } from "../../shared/ui/components";
import { notifySaved, toggleActiveRecord } from "../../shared/ui/masterActions";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";
import { FleetDocumentFormDrawer } from "./FleetDocumentFormDrawer";
import { expiryText, findingText } from "./fleetDocumentText";
import { FleetDocumentStatusChip } from "./fleetDocumentUi";

interface Props {
  companyId: string;
  subjectType: FleetDocumentSubject;
  subjectId: string;
  /** Placa o código, para el subtítulo del formulario. */
  subjectLabel: string;
  canRead: boolean;
  canManage: boolean;
}

/**
 * «Documentos» dentro del drawer de un vehículo o de un conductor (V72): sus papeles, el estado de
 * cada uno hoy y lo que está mal, con la misma regla que aplica la planificación. Fuera del
 * `<form>` del maestro a propósito: registrar un papel no es editar el camión, y se guarda solo.
 */
export function FleetDocumentsSection({ companyId, subjectType, subjectId, subjectLabel, canRead, canManage }: Props) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<FleetDocumentView | "new" | null>(null);

  const query = useQuery({
    queryKey: ["fleet-subject-compliance", companyId, subjectType, subjectId],
    queryFn: ({ signal }) => fetchSubjectCompliance(companyId, subjectType, subjectId, signal),
    enabled: companyId !== "" && canRead,
  });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["fleet-subject-compliance", companyId, subjectType, subjectId] });
    void queryClient.invalidateQueries({ queryKey: ["fleet-documents", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["fleet-documents-summary", companyId] });
  };

  if (!canRead) return null;
  const data = query.data;

  async function toggle(document: FleetDocumentView) {
    const changed = await toggleActiveRecord({
      name: enumLabel("fleetDocumentType", document.documentType),
      active: document.active,
      activate: () => activateFleetDocument(companyId, document.id),
      deactivate: () => deactivateFleetDocument(companyId, document.id),
    });
    if (changed) refresh();
  }

  return (
    <FormSection
      title={t("Documentos")}
      help={t("Los papeles con vencimiento. Uno vencido puede bloquear la asignación a un viaje según la política de la empresa.")}
      actions={canManage ? (
        <Button size="small" startIcon={<AddRounded />} onClick={() => setEditing("new")}>{t("Agregar documento")}</Button>
      ) : undefined}
    >
      <Box sx={{ display: "grid", gap: 1, minWidth: 0 }}>
        {query.isError && <Alert severity="error">{describeApiError(query.error as ApiError)}</Alert>}
        {data?.findings.map((finding) => (
          <Alert key={`${finding.documentType}-${finding.documentId ?? "missing"}`}
            severity={finding.blocking ? "error" : "warning"} variant="outlined">
            {findingText(finding)}
            {finding.blocking ? ` · ${t("Bloquea la asignación a viajes")}` : ""}
          </Alert>
        ))}
        {data && data.documents.length === 0 && (
          <Typography variant="body2" color="text.secondary">{t("Sin documentos registrados.")}</Typography>
        )}
        {data?.documents.map((document) => (
          <Box key={document.id} sx={{
            display: "flex", alignItems: "center", gap: 1.5, py: 0.75, minWidth: 0,
            opacity: document.active ? 1 : 0.55, "& + &": { borderTop: "1px solid", borderColor: "divider" },
          }}>
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography variant="body2" sx={{ fontWeight: 700 }}>
                {enumLabel("fleetDocumentType", document.documentType)}
                {document.documentNumber ? ` · ${document.documentNumber}` : ""}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {[document.issuer, document.expiresOn ? t("Vence el {{date}}", { date: fmtDate(document.expiresOn) }) : null,
                  document.active ? expiryText(document.daysToExpiry) : t("Inactivo")].filter(Boolean).join(" · ")}
              </Typography>
            </Box>
            <FleetDocumentStatusChip status={document.status} />
            {canManage && (
              <Stack direction="row" spacing={0.5}>
                <Tooltip title={t("Editar")}>
                  <IconButton size="small" aria-label={t("Editar")} onClick={() => setEditing(document)}><EditRounded fontSize="small" /></IconButton>
                </Tooltip>
                <Tooltip title={document.active ? t("Desactivar") : t("Activar")}>
                  <IconButton size="small" aria-label={document.active ? t("Desactivar") : t("Activar")}
                    onClick={() => void toggle(document)}>
                    {document.active ? <BlockRounded fontSize="small" /> : <CheckCircleRounded fontSize="small" />}
                  </IconButton>
                </Tooltip>
              </Stack>
            )}
          </Box>
        ))}
      </Box>

      {editing && (
        <FleetDocumentFormDrawer
          companyId={companyId}
          document={editing === "new" ? null : editing}
          fixedSubject={{ subjectType, subjectId, label: subjectLabel }}
          onClose={() => setEditing(null)}
          onSaved={() => {
            notifySaved(editing !== "new");
            setEditing(null);
            refresh();
          }}
        />
      )}
    </FormSection>
  );
}
