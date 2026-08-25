import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, IconButton, MenuItem, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from "@mui/material";
import { AddRounded, DeleteRounded } from "@mui/icons-material";
import {
  createFrequencyException, deleteFrequencyException, fetchFrequencyExceptions,
} from "../../shared/api/frequenciesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { LoadingState, StatusChip, dataTableSx } from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";

interface FrequencyExceptionsPanelProps {
  companyId: string;
  frequencyId: string;
  canManage: boolean;
}

/** `<input type="time">` da HH:MM; la API espera un LocalTime, que quiere HH:MM:SS. */
function toApiTime(value: string) {
  return value.length === 5 ? `${value}:00` : value;
}

/**
 * Excepciones por fecha sobre un calendario de servicio: las dos respuestas que la cadencia
 * semanal no puede dar.
 *
 * Una regla semanal dice "lunes, miércoles y viernes". Navidad cae en miércoles y el depósito
 * está cerrado; el sábado anterior, todo el mundo trabaja. Las dos son excepciones a la cadencia
 * y ninguna se puede expresar editándola: cambiar la regla cambiaría todas las semanas.
 *
 * Dos tipos, porque el modelo tiene exactamente dos (`frequency_exception.service_override`):
 * **cerrado** quita una fecha que la cadencia sí habría servido, **abierto** añade una que no.
 *
 * Una fecha abierta puede además cerrar antes de lo habitual —el 24/12 abierto hasta las 11:00
 * en lugar de las 15:00 de siempre— que es la tercera cosa que la cadencia no sabe decir. El
 * campo de corte solo aparece para una fecha abierta: una cerrada no despacha nada, así que no
 * hay último momento para pedir, y el backend rechaza la combinación en vez de guardar una hora
 * sobre la que nadie podría actuar. Déjalo vacío y sigue aplicando el corte de la regla semanal.
 */
export function FrequencyExceptionsPanel({ companyId, frequencyId, canManage }: FrequencyExceptionsPanelProps) {
  const queryClient = useQueryClient();

  const [date, setDate] = useState("");
  const [kind, setKind] = useState<"closed" | "open">("closed");
  const [cutoff, setCutoff] = useState("");
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);

  const exceptionsQuery = useQuery({
    queryKey: ["frequency-exceptions", companyId, frequencyId],
    queryFn: ({ signal }) => fetchFrequencyExceptions(companyId, frequencyId, signal),
  });
  const exceptions = exceptionsQuery.data ?? [];

  const refresh = () =>
    void queryClient.invalidateQueries({ queryKey: ["frequency-exceptions", companyId, frequencyId] });

  async function add() {
    if (date === "") return;
    setSaving(true);
    try {
      await createFrequencyException(companyId, frequencyId, {
        exceptionDate: date,
        serviceOverride: kind === "open",
        // Una fecha cerrada nunca puede llevarlo, quede lo que quede en el campo: el input se
        // esconde para ella, pero lo que el backend juzga es la petición.
        cutoffTimeOverride: kind === "open" && cutoff !== "" ? toApiTime(cutoff) : null,
        note: note.trim() === "" ? null : note.trim(),
      });
      setDate("");
      setCutoff("");
      setNote("");
      notifySuccess(t("Registro creado"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    } finally {
      setSaving(false);
    }
  }

  async function remove(exceptionId: string, exceptionDate: string) {
    const confirmed = await confirmDialog({
      title: t("¿Eliminar {{name}}?", { name: fmtDate(exceptionDate) }),
      text: t("Esta acción no se puede deshacer."),
      confirmLabel: t("Eliminar"),
      dangerous: true,
    });
    if (!confirmed) return;

    try {
      await deleteFrequencyException(companyId, frequencyId, exceptionId);
      notifySuccess(t("Registro eliminado"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  return (
    <>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t("Fechas concretas que se apartan de la cadencia: un feriado cerrado, o un día abierto que normalmente no lo estaría.")}
      </Typography>

      {exceptionsQuery.isError && (
        <Alert severity="error" sx={{ mb: 2 }}>{describeApiError(exceptionsQuery.error as ApiError)}</Alert>
      )}

      {canManage && (
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1.5, alignItems: "flex-start", mb: 2 }}>
          <TextField
            size="small" type="date" label={t("Fecha")} value={date}
            onChange={(e) => setDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
            sx={{ minWidth: 165 }}
          />
          <TextField
            select size="small" label={t("Tipo")} value={kind}
            onChange={(e) => setKind(e.target.value as "closed" | "open")}
            sx={{ minWidth: 150 }}
          >
            <MenuItem value="closed">{t("Cerrado")}</MenuItem>
            <MenuItem value="open">{t("Abierto")}</MenuItem>
          </TextField>
          {/* Solo para una fecha abierta: una cerrada no despacha, así que un corte no
              significaría nada y el backend lo rechazaría. */}
          {kind === "open" && (
            <TextField
              size="small" type="time" label={t("Corte")} value={cutoff}
              onChange={(e) => setCutoff(e.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ minWidth: 130 }}
            />
          )}
          <TextField
            size="small" label={t("Nota")} value={note}
            onChange={(e) => setNote(e.target.value)}
            sx={{ minWidth: 200, flex: 1 }}
          />
          <Button variant="outlined" startIcon={<AddRounded />} onClick={() => void add()} disabled={date === "" || saving}>
            {t("Añadir")}
          </Button>
        </Box>
      )}

      {exceptionsQuery.isPending ? (
        <LoadingState minHeight={120} />
      ) : exceptions.length === 0 ? (
        <Alert severity="info">{t("Esta frecuencia no tiene excepciones.")}</Alert>
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" sx={dataTableSx}>
            <TableHead>
              <TableRow>
                <TableCell>{t("Fecha")}</TableCell>
                <TableCell>{t("Tipo")}</TableCell>
                <TableCell>{t("Corte")}</TableCell>
                <TableCell>{t("Nota")}</TableCell>
                {canManage && <TableCell className="actions-col">{t("Acciones")}</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {exceptions.map((exception) => (
                <TableRow key={exception.id}>
                  <TableCell sx={{ fontWeight: 600 }}>{fmtDate(exception.exceptionDate)}</TableCell>
                  <TableCell>
                    <StatusChip
                      tone={exception.serviceOverride ? "done" : "cancelled"}
                      label={exception.serviceOverride ? t("Abierto") : t("Cerrado")}
                    />
                  </TableCell>
                  <TableCell>{exception.cutoffTimeOverride?.slice(0, 5) ?? "-"}</TableCell>
                  <TableCell>{exception.note ?? "-"}</TableCell>
                  {canManage && (
                    <TableCell className="actions-col">
                      <Tooltip title={t("Eliminar")}>
                        <IconButton
                          size="small" sx={{ color: "error.main" }}
                          onClick={() => void remove(exception.id, exception.exceptionDate)}
                        >
                          <DeleteRounded fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </>
  );
}
