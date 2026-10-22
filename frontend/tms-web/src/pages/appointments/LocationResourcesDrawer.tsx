import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, IconButton, MenuItem, Paper, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from "@mui/material";
import {
  AddRounded, ArrowBackRounded, BlockRounded, CheckCircleRounded, CheckRounded, ContentCopyRounded,
  DeleteOutlineRounded, EditRounded, EventBusyRounded, MeetingRoomOutlined, ScheduleRounded,
} from "@mui/icons-material";
import {
  RESOURCE_TYPES, activateLocationResource, blockLocationResource, createLocationResource,
  deactivateLocationResource, fetchLocationResources, fetchResourceBlocks, liftResourceBlock,
  replaceResourceCalendar, updateLocationResource,
  type LocationResourceView, type ResourceType,
} from "../../shared/api/appointmentsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  ActionMenu, ActiveBadge, DateTimeInput, EmptyState, ErrorState, FormDrawer, LoadingState, StatusChip,
  TimeInput, dataTableSx,
} from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { fmtDateTime } from "../../lib/locale";
import { t } from "../../lib/i18n";
import { R } from "../../theme";
import { dayName, dockResourcesKey, hoursFromRows, rowError, rowsFromHours, summarizeHours, type DayRow } from "./dockCalendar";

/** El sitio cuyas puertas se configuran: lo mínimo para nombrarlo y decir en qué hora vive. */
export interface DockSite {
  id: string;
  code: string;
  name: string;
  timeZone?: string | null;
}

interface LocationResourcesDrawerProps {
  companyId: string;
  site: DockSite;
  onClose: () => void;
}

type View =
  | { kind: "list" }
  | { kind: "form"; resource: LocationResourceView | null }
  | { kind: "calendar"; resource: LocationResourceView }
  | { kind: "blocks"; resource: LocationResourceView };

/**
 * Puertas y muelles de un sitio (migración V41): darlas de alta, su semana de horario y sus cierres
 * puntuales.
 *
 * <h2>Por qué un panel y no una pantalla</h2>
 * Se configura una vez y se consulta desde dos lugares — el tablero de citas, donde uno descubre
 * que falta una puerta, y la ficha de la ubicación, donde vive el sitio. Un panel sirve a los dos
 * sin que ninguno pierda lo que estaba mirando.
 *
 * <h2>Por qué se dice qué significa un horario vacío</h2>
 * Sin horario, las reglas de reserva leen la puerta como **abierta siempre**; con horario, el día
 * que falta es **cerrado**. Quitar todos los días no cierra la puerta: la abre las 24 horas. Si la
 * pantalla no lo dijera, el gesto más natural para "cerrar este muelle" haría lo contrario.
 *
 * Sólo lo abre quien tiene `appointments.resource:manage`; el backend lo vuelve a comprobar en
 * cada escritura.
 */
export function LocationResourcesDrawer({ companyId, site, onClose }: LocationResourcesDrawerProps) {
  const [view, setView] = useState<View>({ kind: "list" });
  const [dirty, setDirty] = useState(false);
  const queryClient = useQueryClient();
  const key = dockResourcesKey(companyId, site.id);

  const resourcesQuery = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => fetchLocationResources(companyId, site.id, signal),
    enabled: companyId !== "",
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: key });
  const back = () => { setDirty(false); setView({ kind: "list" }); };
  const done = async (message: string) => { notifySuccess(t(message)); await refresh(); back(); };

  async function toggleActive(resource: LocationResourceView) {
    if (resource.active) {
      const confirmed = await confirmDialog({
        title: t("¿Poner {{code}} fuera de servicio?", { code: resource.code }),
        text: t("Las reservas nuevas se rechazan. Las que ya existen se mantienen: un camión en camino no pierde su hora en silencio."),
        confirmLabel: t("Poner fuera de servicio"),
        dangerous: true,
      });
      if (!confirmed) return;
    }
    try {
      await (resource.active ? deactivateLocationResource : activateLocationResource)(companyId, resource.id);
      notifySuccess(resource.active ? t("Puerta fuera de servicio") : t("Puerta en servicio"));
      await refresh();
    } catch (error) {
      notifyError(t("No se pudo cambiar el estado de la puerta"), describeApiError(error as ApiError));
    }
  }

  const resources = resourcesQuery.data ?? [];
  const zoneNote = site.timeZone
    ? t("Horario en hora local del sitio ({{zone}}).", { zone: site.timeZone })
    : t("Horario en hora local del sitio.");

  const titles: Record<View["kind"], string> = {
    list: t("Puertas y muelles"),
    form: view.kind === "form" && view.resource ? t("Editar {{code}}", { code: view.resource.code }) : t("Nueva puerta"),
    calendar: view.kind === "calendar" ? t("Horario de {{code}}", { code: view.resource.code }) : "",
    blocks: view.kind === "blocks" ? t("Cierres de {{code}}", { code: view.resource.code }) : "",
  };

  return (
    <FormDrawer
      open
      title={titles[view.kind]}
      subtitle={`${site.code} · ${site.name}`}
      icon={<MeetingRoomOutlined />}
      onClose={onClose}
      dirty={dirty}
      size="lg"
      footer={view.kind === "list" ? (
        <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onClose}>{t("Cerrar")}</Button>
      ) : undefined}
    >
      {view.kind !== "list" && (
        <Button size="small" color="inherit" startIcon={<ArrowBackRounded />} sx={{ mb: 1.5, color: "text.secondary" }}
          onClick={() => {
            if (!dirty) { back(); return; }
            void confirmDialog({
              title: t("¿Descartar cambios?"), text: t("Tienes cambios sin guardar."),
              confirmLabel: t("Descartar"), cancelLabel: t("Seguir editando"), dangerous: true,
            }).then((confirmed) => { if (confirmed) back(); });
          }}>
          {t("Volver a las puertas")}
        </Button>
      )}

      {view.kind === "list" && (
        <Box sx={{ display: "grid", gap: 1.5 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Typography variant="body2" color="text.secondary" sx={{ flex: 1, minWidth: 220 }}>
              {t("Cada puerta atiende un vehículo a la vez. Un sitio con tres muelles son tres puertas.")}
            </Typography>
            <Button variant="contained" startIcon={<AddRounded />} onClick={() => setView({ kind: "form", resource: null })}>
              {t("Nueva puerta")}
            </Button>
          </Box>
          {resourcesQuery.isPending ? (
            <LoadingState minHeight={120} />
          ) : resourcesQuery.isError ? (
            <ErrorState message={describeApiError(resourcesQuery.error as ApiError)} />
          ) : resources.length === 0 ? (
            <EmptyState
              icon={<MeetingRoomOutlined />}
              title={t("Este sitio no tiene puertas")}
              message={t("Da de alta la primera para poder reservar citas aquí.")}
            />
          ) : (
            <TableContainer component={Paper} variant="outlined" sx={{ borderRadius: `${R.lg}px` }}>
              <Table size="small" sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Puerta")}</TableCell>
                    <TableCell>{t("Tipo")}</TableCell>
                    <TableCell align="right">{t("Duración")}</TableCell>
                    <TableCell>{t("Horario")}</TableCell>
                    <TableCell>{t("Estado")}</TableCell>
                    <TableCell className="actions-col">{t("Acciones")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {resources.map((resource) => (
                    <TableRow key={resource.id}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>{resource.code}</Typography>
                        <Typography variant="caption" color="text.secondary">{resource.name}</Typography>
                      </TableCell>
                      <TableCell>{enumLabel("resourceType", resource.resourceType)}</TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
                        {t("{{m}} min", { m: resource.defaultSlotMinutes })}
                      </TableCell>
                      <TableCell sx={{ fontVariantNumeric: "tabular-nums" }}>{summarizeHours(resource.openingHours)}</TableCell>
                      <TableCell>
                        <ActiveBadge active={resource.active} labels={{ yes: t("En servicio"), no: t("Fuera de servicio") }} />
                      </TableCell>
                      <TableCell className="actions-col">
                        <ActionMenu
                          label={t("Acciones de {{code}}", { code: resource.code })}
                          items={[
                            { key: "edit", label: t("Editar"), icon: <EditRounded />,
                              onSelect: () => setView({ kind: "form", resource }) },
                            { key: "calendar", label: t("Horario semanal"), icon: <ScheduleRounded />,
                              onSelect: () => setView({ kind: "calendar", resource }) },
                            { key: "blocks", label: t("Cierres puntuales"), icon: <EventBusyRounded />,
                              onSelect: () => setView({ kind: "blocks", resource }) },
                            { key: "active", divider: true, dangerous: resource.active,
                              label: resource.active ? t("Poner fuera de servicio") : t("Poner en servicio"),
                              icon: resource.active ? <BlockRounded /> : <CheckCircleRounded />,
                              onSelect: () => void toggleActive(resource) },
                          ]}
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Box>
      )}

      {view.kind === "form" && (
        <ResourceForm
          companyId={companyId} locationId={site.id} resource={view.resource}
          onDirty={() => setDirty(true)} onCancel={back}
          onSaved={() => void done(view.resource ? "Puerta actualizada" : "Puerta creada")}
        />
      )}
      {view.kind === "calendar" && (
        <CalendarForm
          companyId={companyId} resource={view.resource} zoneNote={zoneNote}
          onDirty={() => setDirty(true)} onCancel={back} onSaved={() => void done("Horario guardado")}
        />
      )}
      {view.kind === "blocks" && (
        <BlocksPanel companyId={companyId} resource={view.resource} onDirty={setDirty} />
      )}
    </FormDrawer>
  );
}

// --- alta y edición ------------------------------------------------------------------------------

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

function ResourceForm({ companyId, locationId, resource, onDirty, onCancel, onSaved }: {
  companyId: string; locationId: string; resource: LocationResourceView | null;
  onDirty: () => void; onCancel: () => void; onSaved: () => void;
}) {
  const [code, setCode] = useState(resource?.code ?? "");
  const [name, setName] = useState(resource?.name ?? "");
  const [type, setType] = useState<ResourceType>(resource?.resourceType ?? "DOCK");
  const [slot, setSlot] = useState(String(resource?.defaultSlotMinutes ?? 60));
  const [submitting, setSubmitting] = useState(false);

  const slotNumber = Number(slot);
  const codeError = code !== "" && !CODE_PATTERN.test(code.trim())
    ? t("Hasta 32 caracteres: letras, números, guion o guion bajo.") : null;
  const slotError = slot !== "" && (!Number.isInteger(slotNumber) || slotNumber < 5 || slotNumber > 1440)
    ? t("Entre 5 y 1440 minutos.") : null;
  const invalid = code.trim() === "" || name.trim() === "" || slot === "" || codeError !== null || slotError !== null;
  const change = <V,>(set: (v: V) => void) => (value: V) => { set(value); onDirty(); };

  async function submit() {
    setSubmitting(true);
    const request = {
      locationId, code: code.trim().toUpperCase(), name: name.trim(), resourceType: type,
      defaultSlotMinutes: slotNumber,
    };
    try {
      if (resource) await updateLocationResource(companyId, resource.id, request);
      else await createLocationResource(companyId, request);
      onSaved();
    } catch (error) {
      notifyError(t("No se pudo guardar la puerta"), describeApiError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box sx={{ display: "grid", gap: 2, maxWidth: 520 }}>
      <TextField
        size="small" label={t("Código")} value={code} required
        onChange={(e) => change(setCode)(e.target.value)}
        error={codeError !== null} helperText={codeError ?? t("Único dentro del sitio, p. ej. MUE-01.")}
        slotProps={{ htmlInput: { maxLength: 32 } }}
      />
      <TextField
        size="small" label={t("Nombre")} value={name} required
        onChange={(e) => change(setName)(e.target.value)}
        slotProps={{ htmlInput: { maxLength: 120 } }}
      />
      <TextField
        select size="small" label={t("Tipo de recurso")} value={type}
        onChange={(e) => change(setType)(e.target.value as ResourceType)}
      >
        {RESOURCE_TYPES.map((value) => (
          <MenuItem key={value} value={value}>{enumLabel("resourceType", value)}</MenuItem>
        ))}
      </TextField>
      <TextField
        size="small" type="number" label={t("Duración por defecto (minutos)")} value={slot} required
        onChange={(e) => change(setSlot)(e.target.value)}
        error={slotError !== null}
        helperText={slotError ?? t("Lo que dura una reserva aquí cuando nadie indica la hora de fin.")}
        slotProps={{ htmlInput: { min: 5, max: 1440, step: 5 } }}
      />
      <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end" }}>
        <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onCancel} disabled={submitting}>{t("Cancelar")}</Button>
        <Button variant="contained" startIcon={<CheckRounded />} disabled={invalid || submitting} onClick={() => void submit()}>
          {submitting ? t("Guardando...") : resource ? t("Guardar") : t("Crear puerta")}
        </Button>
      </Box>
    </Box>
  );
}

// --- horario semanal ----------------------------------------------------------------------------

function CalendarForm({ companyId, resource, zoneNote, onDirty, onCancel, onSaved }: {
  companyId: string; resource: LocationResourceView; zoneNote: string;
  onDirty: () => void; onCancel: () => void; onSaved: () => void;
}) {
  const [rows, setRows] = useState<DayRow[]>(() => rowsFromHours(resource.openingHours));
  const [submitting, setSubmitting] = useState(false);

  const update = (index: number, patch: Partial<DayRow>) => {
    setRows((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)));
    onDirty();
  };
  const copyMonday = () => {
    const monday = rows[0];
    setRows((current) => current.map((row) => (row.open ? { ...row, opensAt: monday.opensAt, closesAt: monday.closesAt } : row)));
    onDirty();
  };

  const errors = rows.map(rowError);
  const noneOpen = rows.every((row) => !row.open);
  const invalid = errors.some((error) => error !== null);

  async function submit() {
    setSubmitting(true);
    try {
      await replaceResourceCalendar(companyId, resource.id, hoursFromRows(rows));
      onSaved();
    } catch (error) {
      notifyError(t("No se pudo guardar el horario"), describeApiError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box sx={{ display: "grid", gap: 1.5 }}>
      <Alert severity="info" variant="outlined">
        {zoneNote} {t("Un día sin marcar queda cerrado. Un turno nocturno son dos días.")}
      </Alert>
      {noneOpen && (
        <Alert severity="warning">
          {t("Sin ningún día abierto la puerta queda sin horario, y sin horario se considera abierta siempre. Para dejar de recibir camiones, ponla fuera de servicio.")}
        </Alert>
      )}
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 1 }}>
        {rows.map((row, index) => (
          <Box component="li" key={row.day} sx={{
            display: "grid", alignItems: "center", gap: 1.5,
            gridTemplateColumns: { xs: "1fr 1fr", sm: "150px 160px 160px" },
          }}>
            <FormControlLabel
              sx={{ gridColumn: { xs: "1 / -1", sm: "auto" } }}
              control={<Checkbox checked={row.open} onChange={(e) => update(index, { open: e.target.checked })} />}
              label={dayName(row.day)}
            />
            <TimeInput
              size="small" label={t("Abre")} value={row.opensAt} disabled={!row.open} required={row.open}
              ariaLabel={t("Abre el {{day}}", { day: dayName(row.day) })}
              onChange={(value) => update(index, { opensAt: value })}
            />
            <TimeInput
              size="small" label={t("Cierra")} value={row.closesAt} disabled={!row.open} required={row.open}
              ariaLabel={t("Cierra el {{day}}", { day: dayName(row.day) })}
              error={errors[index] !== null}
              onChange={(value) => update(index, { closesAt: value })}
            />
            {errors[index] && (
              <Typography variant="caption" color="error" sx={{ gridColumn: "1 / -1" }}>{errors[index]}</Typography>
            )}
          </Box>
        ))}
      </Box>
      <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", justifyContent: "space-between", mt: 1 }}>
        <Button size="small" color="inherit" startIcon={<ContentCopyRounded />} disabled={!rows[0].open}
          sx={{ color: "text.secondary" }} onClick={copyMonday}>
          {t("Copiar el horario del lunes a los días abiertos")}
        </Button>
        <Box sx={{ display: "flex", gap: 1 }}>
          <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onCancel} disabled={submitting}>{t("Cancelar")}</Button>
          <Button variant="contained" startIcon={<CheckRounded />} disabled={invalid || submitting} onClick={() => void submit()}>
            {submitting ? t("Guardando...") : t("Guardar horario")}
          </Button>
        </Box>
      </Box>
    </Box>
  );
}

// --- cierres puntuales ----------------------------------------------------------------------------

function BlocksPanel({ companyId, resource, onDirty }: {
  companyId: string; resource: LocationResourceView; onDirty: (dirty: boolean) => void;
}) {
  const [startsAt, setStartsAt] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const queryClient = useQueryClient();
  const key = ["dock-resource-blocks", companyId, resource.id] as const;

  const blocksQuery = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => fetchResourceBlocks(companyId, resource.id, signal),
  });

  const start = startsAt ? new Date(startsAt) : null;
  const end = endsAt ? new Date(endsAt) : null;
  const orderError = start && end && end <= start ? t("El fin tiene que ser después del inicio.") : null;
  const invalid = !start || !end || reason.trim() === "" || orderError !== null;
  const edit = (set: (v: string) => void) => (value: string) => { set(value); onDirty(true); };

  async function submit() {
    if (!start || !end) return;
    setSubmitting(true);
    try {
      await blockLocationResource(companyId, resource.id, start.toISOString(), end.toISOString(), reason.trim());
      notifySuccess(t("Cierre registrado"));
      setStartsAt(""); setEndsAt(""); setReason(""); onDirty(false);
      await queryClient.invalidateQueries({ queryKey: key });
    } catch (error) {
      notifyError(t("No se pudo registrar el cierre"), describeApiError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  async function lift(blockId: string, label: string) {
    const confirmed = await confirmDialog({
      title: t("¿Levantar el cierre?"),
      text: t("La puerta vuelve a aceptar reservas en {{when}}.", { when: label }),
      confirmLabel: t("Levantar cierre"),
      dangerous: true,
    });
    if (!confirmed) return;
    try {
      await liftResourceBlock(companyId, resource.id, blockId);
      notifySuccess(t("Cierre levantado"));
      await queryClient.invalidateQueries({ queryKey: key });
    } catch (error) {
      notifyError(t("No se pudo levantar el cierre"), describeApiError(error as ApiError));
    }
  }

  const blocks = blocksQuery.data ?? [];

  return (
    <Box sx={{ display: "grid", gap: 2 }}>
      <Alert severity="info" variant="outlined">
        {t("Un feriado, un inventario, una rampa averiada. Las reservas que ya caen dentro no se cancelan solas: alguien tiene que avisar a esos camiones. Las horas van en la zona de tu navegador ({{zone}}).", {
          zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        })}
      </Alert>

      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" } }}>
        <DateTimeInput size="small" label={t("Desde")} value={startsAt} required onChange={edit(setStartsAt)} />
        <DateTimeInput size="small" label={t("Hasta")} value={endsAt} required onChange={edit(setEndsAt)}
          error={orderError !== null} helperText={orderError ?? undefined} />
        <TextField
          size="small" label={t("Motivo")} value={reason} required sx={{ gridColumn: "1 / -1" }}
          onChange={(e) => edit(setReason)(e.target.value)}
          helperText={t("Una puerta cerrada sin motivo es una que nadie se anima a reabrir.")}
          slotProps={{ htmlInput: { maxLength: 500 } }}
        />
      </Box>
      <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
        <Button variant="contained" color="warning" startIcon={<EventBusyRounded />} disabled={invalid || submitting}
          onClick={() => void submit()}>
          {submitting ? t("Registrando...") : t("Registrar cierre")}
        </Button>
      </Box>

      <Typography variant="subtitle2">{t("Cierres vigentes y próximos")}</Typography>
      {blocksQuery.isPending ? (
        <LoadingState minHeight={60} />
      ) : blocksQuery.isError ? (
        <ErrorState message={describeApiError(blocksQuery.error as ApiError)} />
      ) : blocks.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("Esta puerta no tiene cierres por delante.")}</Typography>
      ) : (
        <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 1 }}>
          {blocks.map((block) => {
            const label = `${fmtDateTime(block.startsAt)} → ${fmtDateTime(block.endsAt)}`;
            return (
              <Box component="li" key={block.id} sx={{
                display: "flex", alignItems: "center", gap: 1, p: 1, border: 1, borderColor: "divider",
                borderRadius: `${R.md}px`,
              }}>
                <StatusChip tone="inProgress" label={t("Cerrada")} />
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700 }}>{label}</Typography>
                  <Typography variant="caption" color="text.secondary">{block.reason}</Typography>
                </Box>
                <Tooltip title={t("Levantar cierre")}>
                  <IconButton size="small" aria-label={t("Levantar el cierre de {{when}}", { when: label })}
                    onClick={() => void lift(block.id, label)}>
                    <DeleteOutlineRounded fontSize="small" />
                  </IconButton>
                </Tooltip>
              </Box>
            );
          })}
        </Box>
      )}
    </Box>
  );
}
