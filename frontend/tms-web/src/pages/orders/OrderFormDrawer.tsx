import { isPartlyPlanned } from "../../shared/api/ordersApi";
import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import {
  Alert, Box, Button, Collapse, IconButton, TextField, ToggleButton, ToggleButtonGroup, Tooltip, Typography,
} from "@mui/material";
import {
  AssignmentTurnedInRounded, AddRounded, ArrowForwardRounded, CheckRounded, DeleteOutlineRounded,
  EditNoteRounded, FunctionsRounded, KeyboardArrowRightRounded,
} from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import {
  ORDER_PRIORITIES, createOrder, fetchOrder, updateOrder,
  type OrderDetailView, type OrderPriority, type OrderRequest,
} from "../../shared/api/ordersApi";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  FormDateInput, FormDrawer, LoadingState, LookupField, StatusChip, type LookupOption,
} from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { T } from "../../theme";
import { OrderDocumentsSection } from "./OrderDocumentsSection";

const FORM_ID = "order-form";

/** Cuántos maestros pide una pulsación del lookup. Una página, no un catálogo: el operador está
 * estrechando al teclear, y una lista más larga que esto indica que el término sigue siendo
 * demasiado amplio. */
const LOOKUP_PAGE_SIZE = 20;

interface OrderLineFormValues {
  materialCode: string;
  materialDescription: string;
  quantity: string;
  uom: string;
  unitWeightKg: string;
  unitVolumeM3: string;
  palletQuantity: string;
}

interface OrderFormValues {
  externalSource: string;
  externalReference: string;
  originId: string;
  destinationId: string;
  customerName: string;
  customerReference: string;
  serviceDate: string;
  priority: OrderPriority;
  requestedWindowStart: string;
  requestedWindowEnd: string;
  declaredWeightKg: string;
  declaredVolumeM3: string;
  declaredPallets: string;
  lines: OrderLineFormValues[];
}

interface OrderFormDrawerProps {
  companyId: string;
  /** `null` crea un pedido nuevo; si no, el drawer carga y edita (o solo muestra) el detalle
   * completo de este pedido, incluidas sus líneas: la fila de la lista no las trae. */
  orderId: string | null;
  canManage: boolean;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof OrderFormValues>([
  "externalSource", "externalReference", "originId", "destinationId", "customerName", "customerReference",
  "serviceDate", "priority", "requestedWindowStart", "requestedWindowEnd",
  "declaredWeightKg", "declaredVolumeM3", "declaredPallets",
]);

const BLANK_LINE: OrderLineFormValues = {
  materialCode: "", materialDescription: "", quantity: "1", uom: "EA",
  unitWeightKg: "", unitVolumeM3: "", palletQuantity: "",
};

/** Cómo se lee el origen/destino actual del pedido en su lookup antes de teclear nada. Se toma
 * del propio pedido en vez de volver a pedirlo, y se conserva aunque el maestro se haya
 * desactivado desde entonces: desactivar no rompe en silencio el editor. */
function assignedOption(id: string | null, code: string | null, name: string | null): LookupOption | null {
  if (id === null || id === "") return null;
  return { id, code: code ?? id, name: name ?? code ?? id };
}

function toNumberOrUndefined(value: string): number | undefined {
  return value.trim() === "" ? undefined : Number(value);
}

/** Una cifra declarada tal y como la quiere la API: `null` para "no indicado", nunca `0` para
 * eso. La distinción es todo el sentido de las columnas declaradas. */
function toDeclared(value: string): number | null {
  return toNumberOrUndefined(value) ?? null;
}

function fromDeclared(value: number | null | undefined): string {
  return value === null || value === undefined ? "" : String(value);
}

/** Solo los inputs declarados, para que `previewTotals` lea tres campos y no el formulario entero. */
type DeclaredFormValues = Pick<OrderFormValues, "declaredWeightKg" | "declaredVolumeM3" | "declaredPallets">;

/**
 * Previsualización solo de cliente, replicando `OrderTotals.resolve` y `TransportOrderLine
 * .applyInput`: nunca se envía al backend ni se toma como el total autoritativo. El servidor
 * siempre recalcula y devuelve los totales reales al guardar.
 *
 * La regla que reproduce: con líneas, cada medida es su suma, cayendo a la cifra declarada para
 * una medida que ninguna línea describe; sin ninguna línea, las cifras declaradas se sostienen
 * solas. Se reproduce aquí —y no solo se enseña después de guardar— para que el operador vea,
 * mientras escribe, cuál de los dos números que tiene delante es el que se va a planificar.
 */
function previewTotals(lines: OrderLineFormValues[], declared: DeclaredFormValues) {
  let weight: number | null = null;
  let volume: number | null = null;
  let pallets: number | null = null;
  for (const line of lines) {
    const quantity = Number(line.quantity) || 0;
    const unitWeight = toNumberOrUndefined(line.unitWeightKg);
    const unitVolume = toNumberOrUndefined(line.unitVolumeM3);
    const palletQuantity = toNumberOrUndefined(line.palletQuantity);
    if (unitWeight !== undefined) weight = (weight ?? 0) + quantity * unitWeight;
    if (unitVolume !== undefined) volume = (volume ?? 0) + quantity * unitVolume;
    if (palletQuantity !== undefined) pallets = (pallets ?? 0) + palletQuantity;
  }

  const declaredWeight = toNumberOrUndefined(declared.declaredWeightKg);
  const declaredVolume = toNumberOrUndefined(declared.declaredVolumeM3);
  const declaredPallets = toNumberOrUndefined(declared.declaredPallets);

  return {
    calculated: lines.length > 0,
    weight: weight ?? declaredWeight ?? 0,
    volume: volume ?? declaredVolume ?? 0,
    pallets: pallets ?? declaredPallets ?? 0,
  };
}

export function OrderFormDrawer({ companyId, orderId, canManage, onClose, onSaved }: OrderFormDrawerProps) {
  const orderQuery = useQuery({
    queryKey: ["order", companyId, orderId],
    queryFn: ({ signal }) => fetchOrder(companyId, orderId as string, signal),
    enabled: orderId !== null,
  });

  // El editor no puede pintarse antes de que lleguen las líneas, así que el drawer abre con su
  // propio estado de carga en vez de parpadear un formulario vacío.
  if (orderId !== null && !orderQuery.data) {
    return (
      <FormDrawer
        open
        icon={<AssignmentTurnedInRounded />}
        title={t("Pedido")}
        subtitle={t("Cabecera, ventana de servicio y líneas.")}
        size="xl"
        onClose={onClose}
      >
        {orderQuery.isError
          ? <Alert severity="error">{describeApiError(orderQuery.error as ApiError)}</Alert>
          : <LoadingState label={t("Cargando pedido...")} />}
      </FormDrawer>
    );
  }

  return (
    <OrderForm
      companyId={companyId}
      order={orderQuery.data ?? null}
      canManage={canManage}
      onClose={onClose}
      onSaved={onSaved}
    />
  );
}

function OrderForm({
  companyId, order, canManage, onClose, onSaved,
}: {
  companyId: string;
  order: OrderDetailView | null;
  canManage: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const isEdit = order !== null;
  // Un pedido planificado o cancelado se abre en solo lectura: el ciclo de estados es del
  // backend, y ofrecer campos editables que él va a rechazar es una promesa que no se cumple.
  const isEditable = canManage && (order === null || order.status === "NOT_READY" || order.status === "READY_FOR_PLANNING");
  const [formError, setFormError] = useState<string | null>(null);

  // Se guardan aquí y no se derivan del formulario, porque el id por sí solo no puede pintar
  // "LIM-01 · Almacén Lima": el lookup devuelve el registro entero cuando el operador elige uno,
  // y aquí es donde esa etiqueta vive hasta que se cierra el drawer.
  const [origin, setOrigin] = useState<LookupOption | null>(
    assignedOption(order?.originId ?? null, order?.originCode ?? null, order?.originName ?? null),
  );
  const [destination, setDestination] = useState<LookupOption | null>(
    assignedOption(order?.destinationId ?? null, order?.destinationCode ?? null, order?.destinationName ?? null),
  );

  async function searchOrigins(term: string, signal: AbortSignal): Promise<LookupOption[]> {
    const page = await fetchOrigins({
      companyId, size: LOOKUP_PAGE_SIZE, active: true, sort: "code,asc",
      search: term === "" ? undefined : term, signal,
    });
    return page.content.map(({ id, code, name }) => ({ id, code, name }));
  }

  async function searchDestinations(term: string, signal: AbortSignal): Promise<LookupOption[]> {
    const page = await fetchDestinations({
      companyId, size: LOOKUP_PAGE_SIZE, active: true, sort: "code,asc",
      search: term === "" ? undefined : term, signal,
    });
    return page.content.map(({ id, code, name }) => ({ id, code, name }));
  }

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<OrderFormValues>({
    defaultValues: {
      externalSource: order?.externalSource ?? "",
      externalReference: order?.externalReference ?? "",
      originId: order?.originId ?? "",
      destinationId: order?.destinationId ?? "",
      customerName: order?.customerName ?? "",
      customerReference: order?.customerReference ?? "",
      serviceDate: order?.serviceDate ?? "",
      priority: order?.priority ?? "NORMAL",
      requestedWindowStart: order?.requestedWindowStart?.slice(0, 5) ?? "",
      requestedWindowEnd: order?.requestedWindowEnd?.slice(0, 5) ?? "",
      declaredWeightKg: fromDeclared(order?.declaredWeightKg),
      declaredVolumeM3: fromDeclared(order?.declaredVolumeM3),
      declaredPallets: fromDeclared(order?.declaredPallets),
      lines: (order?.lines ?? []).map((line) => ({
        materialCode: line.materialCode,
        materialDescription: line.materialDescription,
        quantity: String(line.quantity),
        uom: line.uom,
        unitWeightKg: line.unitWeightKg === null ? "" : String(line.unitWeightKg),
        unitVolumeM3: line.unitVolumeM3 === null ? "" : String(line.unitVolumeM3),
        palletQuantity: line.palletQuantity === null ? "" : String(line.palletQuantity),
      })),
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "lines" });
  const watchedLines = useWatch({ control, name: "lines" }) ?? [];
  const watchedDeclared: DeclaredFormValues = {
    declaredWeightKg: useWatch({ control, name: "declaredWeightKg" }) ?? "",
    declaredVolumeM3: useWatch({ control, name: "declaredVolumeM3" }) ?? "",
    declaredPallets: useWatch({ control, name: "declaredPallets" }) ?? "",
  };
  // `useWatch` ya devuelve un array nuevo en cada render, así que un useMemo aquí recalcularía
  // igualmente: solo añadiría una trampa de dependencias sin ningún beneficio.
  const totals = previewTotals(watchedLines, watchedDeclared);

  async function onSubmit(values: OrderFormValues) {
    setFormError(null);

    const request: OrderRequest = {
      externalSource: values.externalSource.trim() === "" ? null : values.externalSource.trim(),
      externalReference: values.externalReference.trim() === "" ? null : values.externalReference.trim(),
      originId: values.originId,
      destinationId: values.destinationId,
      customerName: values.customerName.trim() === "" ? null : values.customerName.trim(),
      customerReference: values.customerReference.trim() === "" ? null : values.customerReference.trim(),
      serviceDate: values.serviceDate,
      priority: values.priority,
      requestedWindowStart: values.requestedWindowStart.trim() === "" ? null : values.requestedWindowStart,
      requestedWindowEnd: values.requestedWindowEnd.trim() === "" ? null : values.requestedWindowEnd,
      declaredWeightKg: toDeclared(values.declaredWeightKg),
      declaredVolumeM3: toDeclared(values.declaredVolumeM3),
      declaredPallets: toDeclared(values.declaredPallets),
      // El backend exige la versión al actualizar (`OrderService.requireCurrentVersion`): es lo
      // que impide que dos despachadores se pisen el mismo pedido sin enterarse.
      version: order?.version,
      lines: values.lines.map((line) => ({
        materialCode: line.materialCode.trim(),
        materialDescription: line.materialDescription.trim(),
        quantity: Number(line.quantity),
        uom: line.uom.trim(),
        unitWeightKg: toNumberOrUndefined(line.unitWeightKg) ?? null,
        unitVolumeM3: toNumberOrUndefined(line.unitVolumeM3) ?? null,
        palletQuantity: toNumberOrUndefined(line.palletQuantity) ?? null,
      })),
    };

    try {
      if (isEdit) await updateOrder(companyId, order.id, request);
      else await createOrder(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const hasExternal = Boolean(order?.externalSource || order?.externalReference || errors.externalSource || errors.externalReference);
  const hasDeclared = order?.declaredWeightKg != null || order?.declaredVolumeM3 != null || order?.declaredPallets != null
    || Boolean(errors.declaredWeightKg || errors.declaredVolumeM3 || errors.declaredPallets);
  const [showExternal, setShowExternal] = useState(hasExternal);
  const [showDeclared, setShowDeclared] = useState(hasDeclared);
  // Sin líneas, las cifras declaradas son lo único que se planifica: no pueden quedar plegadas.
  const declaredOpen = showDeclared || fields.length === 0 || hasDeclared;

  const row2 = { display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" } } as const;
  const lineError = (index: number) => {
    const e = errors.lines?.[index];
    return e?.materialCode?.message ?? e?.materialDescription?.message ?? e?.quantity?.message ?? e?.uom?.message;
  };

  return (
    <FormDrawer
      open
      icon={<AssignmentTurnedInRounded />}
      title={isEdit ? `${t("Pedido")} ${order.orderNumber}` : t("Nuevo pedido")}
      subtitle={isEdit
        ? t("Cabecera, ventana de servicio y líneas.")
        : t("El número se asigna al guardar. Queda como «No listo» hasta liberarlo.")}
      size="lg"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          {/* Previsualización de cliente: el backend recalcula al guardar. */}
          <Tooltip title={t("Previsualización. Los totales definitivos los calcula el backend al guardar.")}>
            <Box sx={{ flex: 1, display: "flex", alignItems: "center", gap: { xs: 1.5, sm: 2.5 }, minWidth: 0, flexWrap: "wrap" }}>
              {[
                { label: t("Peso"), value: fmtWeightKg(totals.weight) },
                { label: t("Volumen"), value: fmtVolumeM3(totals.volume) },
                { label: t("Pallets"), value: fmtDecimal(totals.pallets) },
                { label: t("Líneas"), value: String(fields.length) },
              ].map((item) => (
                <Box key={item.label}>
                  <Typography sx={{ fontSize: T.micro - 0.5, fontWeight: 700, letterSpacing: ".05em", textTransform: "uppercase", color: "text.secondary" }}>
                    {item.label}
                  </Typography>
                  <Typography sx={{ fontWeight: 800, fontVariantNumeric: "tabular-nums", lineHeight: 1.2 }}>{item.value}</Typography>
                </Box>
              ))}
            </Box>
          </Tooltip>
          <Button onClick={onClose} disabled={isSubmitting}>{isEditable ? t("Cancelar") : t("Cerrar")}</Button>
          {isEditable && (
            <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
              {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar") : t("Guardar pedido")}
            </Button>
          )}
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate
        sx={{ display: "grid", gap: 3.5 }}>
        {(formError || isEdit) && (
          <Box sx={{ display: "grid", gap: 1.5 }}>
            {formError && <Alert severity="error">{formError}</Alert>}
            {isEdit && (
              <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
                <StatusChip label={enumLabel("orderStatus", order.status)} tone="open" variant="solid" />
                <StatusChip label={enumLabel("orderFulfillmentStatus", order.fulfillmentStatus)} tone="neutral" />
                {isPartlyPlanned(order) && (
                  <StatusChip label={`${t("Parcialmente planificado")} · ${t("pendiente")} ${order.pendingPallets ?? 0} pallets`} tone="neutral" />
                )}
                {!isEditable && <StatusChip label={t("Solo lectura")} tone="cancelled" />}
              </Box>
            )}
            {isEdit && !isEditable && (
              <Alert severity="info">{t("Este registro es de solo lectura y no puede modificarse.")}</Alert>
            )}
          </Box>
        )}

        <Step n={1} title={t("Ruta")}>
          <Box sx={{ display: "grid", gap: 1.5, alignItems: "start", gridTemplateColumns: { xs: "1fr", sm: "1fr auto 1fr" } }}>
            <Controller
              control={control}
              name="originId"
              rules={{ required: t("Este campo es obligatorio") }}
              render={({ field }) => (
                <LookupField
                  label={t("Origen")}
                  required
                  disabled={!isEditable}
                  value={field.value}
                  selected={origin}
                  onChange={(option) => { setOrigin(option); field.onChange(option?.id ?? ""); }}
                  search={searchOrigins}
                  queryKey={["origin-lookup", companyId]}
                  placeholder={t("Código, nombre o referencia externa")}
                  error={errors.originId?.message}
                />
              )}
            />
            <Box aria-hidden sx={{ display: { xs: "none", sm: "grid" }, placeItems: "center", height: 40, color: "text.secondary" }}>
              <ArrowForwardRounded fontSize="small" />
            </Box>
            <Controller
              control={control}
              name="destinationId"
              rules={{ required: t("Este campo es obligatorio") }}
              render={({ field }) => (
                <LookupField
                  label={t("Destino")}
                  required
                  disabled={!isEditable}
                  value={field.value}
                  selected={destination}
                  onChange={(option) => { setDestination(option); field.onChange(option?.id ?? ""); }}
                  search={searchDestinations}
                  queryKey={["destination-lookup", companyId]}
                  placeholder={t("Código, nombre o referencia externa")}
                  error={errors.destinationId?.message}
                />
              )}
            />
          </Box>
        </Step>

        <Step n={2} title={t("Programación")}>
          <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "200px 1fr" }, alignItems: "start" }}>
            <FormDateInput
              control={control} name="serviceDate" mode="date"
              rules={{ required: t("Este campo es obligatorio") }}
              label={t("Fecha de servicio")} required size="small" fullWidth
              disabled={!isEditable}
              error={Boolean(errors.serviceDate)} helperText={errors.serviceDate?.message}
            />
            <Box sx={{ display: "grid", gridTemplateColumns: "1fr auto 1fr", gap: 1, alignItems: "center" }}>
              <FormDateInput
                control={control} name="requestedWindowStart" mode="time"
                label={t("Ventana desde")} size="small" fullWidth disabled={!isEditable}
              />
              <Typography aria-hidden sx={{ color: "text.secondary", fontSize: T.body }}>{t("a")}</Typography>
              <FormDateInput
                control={control} name="requestedWindowEnd" mode="time"
                label={t("Ventana hasta")} size="small" fullWidth disabled={!isEditable}
              />
            </Box>
          </Box>
          <Box>
            <FieldLabel id="order-priority-label">{t("Prioridad")}</FieldLabel>
            <Controller
              control={control}
              name="priority"
              render={({ field }) => (
                <ToggleButtonGroup
                  exclusive fullWidth size="small" disabled={!isEditable}
                  aria-labelledby="order-priority-label"
                  value={field.value}
                  onChange={(_e, next: OrderPriority | null) => { if (next) field.onChange(next); }}
                  sx={(th) => ({
                    p: "3px", gap: "3px", borderRadius: "10px", bgcolor: "action.hover",
                    "& .MuiToggleButtonGroup-grouped": {
                      border: 0, borderRadius: "8px !important", textTransform: "none", fontWeight: 500,
                      color: "text.secondary", py: 0.75, gap: 0.75,
                      "&.Mui-selected": {
                        bgcolor: "background.paper", color: "text.primary", fontWeight: 700,
                        boxShadow: th.palette.mode === "dark" ? "none" : "0 1px 3px rgba(16,24,40,.12)",
                        "&:hover": { bgcolor: "background.paper" },
                      },
                    },
                  })}
                >
                  {ORDER_PRIORITIES.map((priority) => (
                    <ToggleButton key={priority} value={priority}>
                      <Box aria-hidden sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: PRIORITY_DOT[priority] }} />
                      {enumLabel("orderPriority", priority)}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
              )}
            />
          </Box>
        </Step>

        <Step n={3} title={t("Cliente")} hint={t("Opcional")}>
          <Box sx={row2}>
            <TextField
              label={t("Nombre del cliente")} size="small" fullWidth disabled={!isEditable}
              {...register("customerName", {
                maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
              })}
              error={Boolean(errors.customerName)} helperText={errors.customerName?.message}
            />
            <TextField
              label={t("Referencia del cliente")} size="small" fullWidth disabled={!isEditable}
              {...register("customerReference")}
            />
          </Box>
          <Disclosure
            open={showExternal}
            onToggle={() => setShowExternal((v) => !v)}
            label={t("Sistema de origen y referencia externa")}
            hint={t("Integración")}
          >
            <Box sx={row2}>
              <TextField
                label={t("Sistema de origen")} size="small" fullWidth disabled={!isEditable}
                placeholder={t("p. ej. EWM, ERP")}
                error={Boolean(errors.externalSource)} helperText={errors.externalSource?.message}
                {...register("externalSource")}
              />
              <TextField
                label={t("Referencia externa")} size="small" fullWidth disabled={!isEditable}
                error={Boolean(errors.externalReference)} helperText={errors.externalReference?.message}
                {...register("externalReference")}
              />
            </Box>
          </Disclosure>
        </Step>

        <Step
          n={4}
          title={t("Líneas")}
          count={fields.length}
          action={isEditable && (
            <Button size="small" startIcon={<AddRounded />} onClick={() => append({ ...BLANK_LINE })} sx={{ fontWeight: 700 }}>
              {t("Añadir línea")}
            </Button>
          )}
        >
          {fields.length === 0 ? (
            <Box sx={{
              border: "1px dashed", borderColor: "divider", borderRadius: "10px", p: 2.5, textAlign: "center",
            }}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{t("Sin líneas")}</Typography>
              <Typography variant="body2" color="text.secondary">
                {t("Sin líneas, el pedido se planifica con las cifras declaradas de abajo.")}
              </Typography>
            </Box>
          ) : (
            <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: "10px", overflowX: "auto" }}>
              <Box sx={{ minWidth: 640 }}>
                <Box sx={{ ...LINE_GRID, bgcolor: "action.hover", py: 0.75 }}>
                  {LINE_COLUMNS.map((column) => (
                    <Typography key={column.key} sx={{
                      fontSize: T.micro - 0.5, fontWeight: 700, letterSpacing: ".05em", textTransform: "uppercase",
                      color: "text.secondary", textAlign: column.numeric ? "right" : "left",
                    }}>
                      {column.label}
                    </Typography>
                  ))}
                  <span />
                </Box>
                {fields.map((field, index) => (
                  <Box key={field.id} sx={{ borderTop: "1px solid", borderColor: "divider" }}>
                    <Box sx={{ ...LINE_GRID, py: 0.75 }}>
                      <TextField
                        size="small" disabled={!isEditable} error={Boolean(errors.lines?.[index]?.materialCode)}
                        slotProps={{ htmlInput: { "aria-label": `${t("Material")} ${index + 1}`, style: { fontFamily: MONO } } }}
                        {...register(`lines.${index}.materialCode` as const, { required: t("Este campo es obligatorio") })}
                      />
                      <TextField
                        size="small" disabled={!isEditable} error={Boolean(errors.lines?.[index]?.materialDescription)}
                        slotProps={{ htmlInput: { "aria-label": `${t("Descripción")} ${index + 1}` } }}
                        {...register(`lines.${index}.materialDescription` as const, { required: t("Este campo es obligatorio") })}
                      />
                      <TextField
                        size="small" type="number" disabled={!isEditable} error={Boolean(errors.lines?.[index]?.quantity)}
                        sx={NUMERIC_INPUT}
                        slotProps={{ htmlInput: { "aria-label": `${t("Cantidad")} ${index + 1}` } }}
                        {...register(`lines.${index}.quantity` as const, {
                          required: t("Este campo es obligatorio"),
                          validate: (value) => Number(value) > 0 || t("Debe ser un número mayor que cero"),
                        })}
                      />
                      <TextField
                        size="small" disabled={!isEditable} error={Boolean(errors.lines?.[index]?.uom)}
                        slotProps={{ htmlInput: { "aria-label": `${t("Unidad")} ${index + 1}` } }}
                        {...register(`lines.${index}.uom` as const, { required: t("Este campo es obligatorio") })}
                      />
                      <TextField
                        size="small" type="number" disabled={!isEditable} sx={NUMERIC_INPUT}
                        slotProps={{ htmlInput: { "aria-label": `${t("Peso unitario (kg)")} ${index + 1}` } }}
                        {...register(`lines.${index}.unitWeightKg` as const)}
                      />
                      <TextField
                        size="small" type="number" disabled={!isEditable} sx={NUMERIC_INPUT}
                        slotProps={{ htmlInput: { "aria-label": `${t("Volumen unitario (m³)")} ${index + 1}` } }}
                        {...register(`lines.${index}.unitVolumeM3` as const)}
                      />
                      <TextField
                        size="small" type="number" disabled={!isEditable} sx={NUMERIC_INPUT}
                        slotProps={{ htmlInput: { "aria-label": `${t("Pallets")} ${index + 1}` } }}
                        {...register(`lines.${index}.palletQuantity` as const)}
                      />
                      {isEditable ? (
                        <Tooltip title={t("Quitar")}>
                          <IconButton size="small" aria-label={`${t("Quitar")} ${t("Línea")} ${index + 1}`}
                            onClick={() => remove(index)} sx={{ color: "text.secondary", "&:hover": { color: "error.main" } }}>
                            <DeleteOutlineRounded fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      ) : <span />}
                    </Box>
                    {lineError(index) && (
                      <Typography role="alert" sx={{ fontSize: T.micro, color: "error.main", px: 1.25, pb: 0.75, mt: -0.25 }}>
                        {t("Línea")} {index + 1}: {lineError(index)}
                      </Typography>
                    )}
                  </Box>
                ))}
              </Box>
            </Box>
          )}
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, color: "text.secondary" }}>
            {totals.calculated ? <FunctionsRounded sx={{ fontSize: 16 }} /> : <EditNoteRounded sx={{ fontSize: 16 }} />}
            <Typography sx={{ fontSize: T.micro + 0.5 }}>
              {totals.calculated ? t("Calculado desde las líneas") : t("Según las cifras declaradas")}
              {": "}{fmtWeightKg(totals.weight)} · {fmtVolumeM3(totals.volume)} · {fmtDecimal(totals.pallets)} {t("pallets")}
            </Typography>
          </Box>
          <Disclosure
            open={declaredOpen}
            onToggle={() => setShowDeclared((v) => !v)}
            label={t("Declarar totales manualmente")}
            hint={fields.length === 0 ? undefined : t("Si no hay líneas")}
          >
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              {t("Las cifras declaradas son lo que el operador afirma que pesa u ocupa el pedido. Donde las líneas también lo digan, las dos tienen que coincidir con un 1% de margen o el backend rechaza el guardado.")}
            </Typography>
            <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" } }}>
              <TextField
                label={t("Peso declarado (kg)")} size="small" type="number" disabled={!isEditable}
                error={Boolean(errors.declaredWeightKg)} helperText={errors.declaredWeightKg?.message}
                {...register("declaredWeightKg")}
              />
              <TextField
                label={t("Volumen declarado (m³)")} size="small" type="number" disabled={!isEditable}
                error={Boolean(errors.declaredVolumeM3)} helperText={errors.declaredVolumeM3?.message}
                {...register("declaredVolumeM3")}
              />
              <TextField
                label={t("Pallets declarados")} size="small" type="number" disabled={!isEditable}
                error={Boolean(errors.declaredPallets)} helperText={errors.declaredPallets?.message}
                {...register("declaredPallets")}
              />
            </Box>
          </Disclosure>
        </Step>

        {/* ADR-015: los documentos del ERP, de solo lectura, solo para un pedido que ya existe. */}
        {isEdit && <OrderDocumentsSection companyId={companyId} orderId={order.id} />}
      </Box>
    </FormDrawer>
  );
}

const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

const PRIORITY_DOT: Record<OrderPriority, string> = {
  LOW: "divider", NORMAL: "text.secondary", HIGH: "warning.main", URGENT: "error.main",
};

const LINE_COLUMNS: { key: string; label: string; numeric?: boolean }[] = [
  { key: "material", label: t("Material") },
  { key: "description", label: t("Descripción") },
  { key: "quantity", label: t("Cant."), numeric: true },
  { key: "uom", label: t("UdM") },
  { key: "weight", label: t("kg/u"), numeric: true },
  { key: "volume", label: t("m³/u"), numeric: true },
  { key: "pallets", label: t("Pallets"), numeric: true },
];

const LINE_GRID = {
  display: "grid", alignItems: "center", gap: 0.75, px: 1.25,
  gridTemplateColumns: "104px minmax(140px, 1fr) 68px 64px 76px 80px 64px 34px",
} as const;

/** Cifras a la derecha y sin las flechas del input numérico, que en una celda estrecha tapan el valor. */
const NUMERIC_INPUT = {
  "& input": { textAlign: "right", MozAppearance: "textfield" },
  "& input::-webkit-outer-spin-button, & input::-webkit-inner-spin-button": { WebkitAppearance: "none", m: 0 },
} as const;

/** Encabezado de un paso del formulario: número, título y, a la derecha, una pista o una acción. */
function Step({ n, title, hint, count, action, children }: {
  n: number; title: string; hint?: string; count?: number; action?: ReactNode; children: ReactNode;
}) {
  return (
    <Box component="section" aria-label={title} sx={{ display: "grid", gap: 1.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, minHeight: 30 }}>
        <Box aria-hidden sx={{
          width: 22, height: 22, borderRadius: "50%", bgcolor: "action.hover", color: "text.secondary",
          display: "grid", placeItems: "center", fontSize: T.micro, fontWeight: 800, flexShrink: 0,
        }}>{n}</Box>
        <Typography component="h3" sx={{ fontSize: T.body + 1, fontWeight: 800 }}>{title}</Typography>
        {count !== undefined && (
          <Box sx={{ px: 0.75, borderRadius: 10, bgcolor: "action.hover", fontSize: 11.5, fontWeight: 700, color: "text.secondary", lineHeight: "18px" }}>
            {count}
          </Box>
        )}
        <Box sx={{ flex: 1 }} />
        {hint && <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary" }}>{hint}</Typography>}
        {action}
      </Box>
      {children}
    </Box>
  );
}

function FieldLabel({ id, children }: { id: string; children: ReactNode }) {
  return (
    <Typography id={id} sx={{ fontSize: T.micro + 0.5, fontWeight: 700, color: "text.secondary", mb: 0.75 }}>
      {children}
    </Typography>
  );
}

/** Una sección plegable para los campos que casi nunca se tocan. */
function Disclosure({ open, onToggle, label, hint, children }: {
  open: boolean; onToggle: () => void; label: string; hint?: string; children: ReactNode;
}) {
  return (
    <Box>
      <Button
        onClick={onToggle}
        aria-expanded={open}
        color="inherit"
        startIcon={<KeyboardArrowRightRounded sx={{ transition: "transform .15s", transform: open ? "rotate(90deg)" : "none" }} />}
        sx={{ px: 0.5, fontWeight: 600, color: "text.secondary", textTransform: "none" }}
      >
        {label}
        {hint && (
          <Typography component="span" sx={{ ml: 1, fontSize: T.micro + 0.5, color: "text.secondary", fontWeight: 400 }}>
            {hint}
          </Typography>
        )}
      </Button>
      <Collapse in={open} unmountOnExit={false}>
        <Box sx={{ pt: 1.25 }}>{children}</Box>
      </Collapse>
    </Box>
  );
}
