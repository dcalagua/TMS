import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, IconButton, MenuItem, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from "@mui/material";
import { AddRounded, DeleteRounded, BlockRounded, CheckCircleRounded, FactCheckRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { fetchFrequencies } from "../../shared/api/frequenciesApi";
import {
  activateLocationFrequency, createLocationFrequency, deactivateLocationFrequency,
  deleteLocationFrequency, fetchLocationEligibility, fetchLocationFrequencies,
  type EligibilityView, type LocationFrequencyView,
} from "../../shared/api/locationFrequenciesApi";
import { ActiveBadge, LoadingState, dataTableSx } from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDate, today } from "../../lib/locale";

interface LocationFrequencyPanelProps {
  companyId: string;
  locationId: string;
}

/**
 * El calendario de servicio de una ubicación: qué frecuencias tiene asociadas, desde y hasta
 * cuándo, y una comprobación de elegibilidad para una fecha concreta.
 *
 * Las asociaciones se guardan por su cuenta y no con el formulario que las rodea. Es
 * deliberado: asociar una frecuencia es una decisión completa en sí misma, y hacerla depender
 * de que alguien pulse "Guardar" arriba significaría que una ubicación puede quedarse a medio
 * configurar sin que nada lo diga.
 *
 * La elegibilidad la calcula el backend. Aquí no se deriva: la respuesta trae el veredicto, la
 * frecuencia que lo decidió, la hora de corte y el lead time, y el panel solo los presenta.
 */
export function LocationFrequencyPanel({ companyId, locationId }: LocationFrequencyPanelProps) {
  const queryClient = useQueryClient();
  const [frequencyId, setFrequencyId] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [effectiveTo, setEffectiveTo] = useState("");
  const [busy, setBusy] = useState(false);
  const [checkDate, setCheckDate] = useState(today);
  const [eligibility, setEligibility] = useState<EligibilityView | null>(null);
  const [checking, setChecking] = useState(false);

  const associationsKey = ["location-frequencies", companyId, locationId];

  const associations = useQuery({
    queryKey: associationsKey,
    queryFn: () => fetchLocationFrequencies(companyId, locationId),
  });

  const frequencies = useQuery({
    queryKey: ["frequencies-for-location", companyId],
    queryFn: ({ signal }) => fetchFrequencies({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: associationsKey });

  async function associate() {
    if (frequencyId === "") return;
    setBusy(true);
    try {
      await createLocationFrequency(companyId, locationId, {
        frequencyId,
        effectiveFrom: effectiveFrom || null,
        effectiveTo: effectiveTo || null,
      });
      setFrequencyId("");
      setEffectiveFrom("");
      setEffectiveTo("");
      notifySuccess(t("Registro creado"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(association: LocationFrequencyView) {
    try {
      if (association.active) await deactivateLocationFrequency(companyId, locationId, association.id);
      else await activateLocationFrequency(companyId, locationId, association.id);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  async function remove(association: LocationFrequencyView) {
    const confirmed = await confirmDialog({
      title: t("¿Eliminar {{name}}?", { name: association.frequencyName ?? association.frequencyCode ?? "" }),
      text: t("Esta acción no se puede deshacer."),
      confirmLabel: t("Eliminar"),
      dangerous: true,
    });
    if (!confirmed) return;
    try {
      await deleteLocationFrequency(companyId, locationId, association.id);
      notifySuccess(t("Registro eliminado"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  async function check() {
    setChecking(true);
    setEligibility(null);
    try {
      setEligibility(await fetchLocationEligibility(companyId, locationId, checkDate));
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    } finally {
      setChecking(false);
    }
  }

  const rows = associations.data ?? [];

  return (
    <Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t("Asocia una o más frecuencias para definir en qué fechas puede despacharse o recibir servicio esta ubicación.")}
      </Typography>

      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1.5, alignItems: "flex-start", mb: 2 }}>
        <TextField
          select size="small" label={t("Frecuencia")} value={frequencyId}
          onChange={(e) => setFrequencyId(e.target.value)}
          sx={{ minWidth: 220, flex: 1 }}
        >
          <MenuItem value="">{t("Selecciona una frecuencia")}</MenuItem>
          {(frequencies.data?.content ?? []).map((frequency) => (
            <MenuItem key={frequency.id} value={frequency.id}>{frequency.code} · {frequency.name}</MenuItem>
          ))}
        </TextField>
        <TextField
          size="small" type="date" label={t("Vigente desde")} value={effectiveFrom}
          onChange={(e) => setEffectiveFrom(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
          sx={{ minWidth: 165 }}
        />
        <TextField
          size="small" type="date" label={t("Vigente hasta")} value={effectiveTo}
          onChange={(e) => setEffectiveTo(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
          sx={{ minWidth: 165 }}
        />
        <Button
          variant="outlined" startIcon={<AddRounded />} onClick={() => void associate()}
          disabled={frequencyId === "" || busy}
        >
          {t("Asociar frecuencia")}
        </Button>
      </Box>

      {associations.isPending ? (
        <LoadingState minHeight={120} />
      ) : rows.length === 0 ? (
        <Alert severity="info" sx={{ mb: 3 }}>{t("Aún no hay frecuencias asociadas a esta ubicación.")}</Alert>
      ) : (
        <TableContainer component={Paper} variant="outlined" sx={{ mb: 3 }}>
          <Table size="small" sx={dataTableSx}>
            <TableHead>
              <TableRow>
                <TableCell>{t("Frecuencia")}</TableCell>
                <TableCell>{t("Vigente desde")}</TableCell>
                <TableCell>{t("Vigente hasta")}</TableCell>
                <TableCell>{t("Estado")}</TableCell>
                <TableCell className="actions-col">{t("Acciones")}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((association) => (
                <TableRow key={association.id}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {association.frequencyName ?? "-"}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">{association.frequencyCode ?? ""}</Typography>
                  </TableCell>
                  <TableCell>{association.effectiveFrom ? fmtDate(association.effectiveFrom) : "-"}</TableCell>
                  <TableCell>{association.effectiveTo ? fmtDate(association.effectiveTo) : "-"}</TableCell>
                  <TableCell><ActiveBadge active={association.active} /></TableCell>
                  <TableCell className="actions-col">
                    <Tooltip title={association.active ? t("Desactivar") : t("Activar")}>
                      <IconButton size="small" onClick={() => void toggle(association)}>
                        {association.active ? <BlockRounded fontSize="small" /> : <CheckCircleRounded fontSize="small" />}
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t("Eliminar")}>
                      <IconButton size="small" onClick={() => void remove(association)} sx={{ color: "error.main" }}>
                        <DeleteRounded fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Typography variant="subtitle2" sx={{ mb: 0.5 }}>{t("Verificar elegibilidad")}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {t("Comprueba si esta ubicación puede despachar o recibir servicio en una fecha concreta, según sus frecuencias asociadas.")}
      </Typography>
      <Box sx={{ display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap", mb: 1.5 }}>
        <TextField
          size="small" type="date" label={t("Fecha a verificar")} value={checkDate}
          onChange={(e) => setCheckDate(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
          sx={{ minWidth: 175 }}
        />
        <Button variant="outlined" startIcon={<FactCheckRounded />} onClick={() => void check()} disabled={checking}>
          {t("Verificar")}
        </Button>
      </Box>

      {eligibility && (
        <Alert severity={eligibility.eligible ? "success" : "warning"}>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>
            {eligibility.eligible ? t("Elegible para despacho") : t("No elegible")}
          </Typography>
          <Typography variant="body2">{eligibility.reason}</Typography>
          {(eligibility.cutoffTime || eligibility.leadTimeDays !== null) && (
            <Typography variant="caption" color="text.secondary">
              {eligibility.cutoffTime && `${t("Corte")}: ${eligibility.cutoffTime}`}
              {eligibility.cutoffTime && eligibility.leadTimeDays !== null && " · "}
              {eligibility.leadTimeDays !== null && `${t("Lead time")}: ${eligibility.leadTimeDays} ${t("días")}`}
            </Typography>
          )}
        </Alert>
      )}
    </Box>
  );
}
