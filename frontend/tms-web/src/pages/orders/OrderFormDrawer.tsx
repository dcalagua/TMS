import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import {
  Alert, Box, Button, IconButton, MenuItem, Paper, TextField, Tooltip, Typography,
} from "@mui/material";
import {
  AssignmentTurnedInRounded, AddRounded, DeleteRounded, CalculateRounded, EditNoteRounded,
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
  FormDrawer, LoadingState, LookupField, SectionHeader, StatusChip, type LookupOption,
} from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";

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

  const grid2 = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, mb: 3 } as const;

  return (
    <FormDrawer
      open
      icon={<AssignmentTurnedInRounded />}
      title={isEdit ? `${t("Pedido")} ${order.orderNumber}` : t("Nuevo pedido")}
      subtitle={t("Cabecera, ventana de servicio y líneas.")}
      size="xl"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{isEditable ? t("Cancelar") : t("Cerrar")}</Button>
          {isEditable && (
            <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
              {isSubmitting ? t("Guardando...") : t("Guardar")}
            </Button>
          )}
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        {isEdit && (
          <Box sx={{ display: "flex", gap: 1, mb: 3, flexWrap: "wrap" }}>
            <StatusChip label={enumLabel("orderStatus", order.status)} tone="open" variant="solid" />
            <StatusChip label={enumLabel("orderFulfillmentStatus", order.fulfillmentStatus)} tone="neutral" />
            {!isEditable && <StatusChip label={t("Solo lectura")} tone="cancelled" />}
          </Box>
        )}
        {isEdit && !isEditable && (
          <Alert severity="info" sx={{ mb: 3 }}>
            {t("Este registro es de solo lectura y no puede modificarse.")}
          </Alert>
        )}

        <SectionHeader title={t("Ruta")} />
        <Box sx={grid2}>
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

        <SectionHeader title={t("Programación")} />
        <Box sx={grid2}>
          <TextField
            label={t("Fecha de servicio")} required size="small" fullWidth type="date"
            disabled={!isEditable}
            slotProps={{ inputLabel: { shrink: true } }}
            error={Boolean(errors.serviceDate)} helperText={errors.serviceDate?.message}
            {...register("serviceDate", { required: t("Este campo es obligatorio") })}
          />
          <Controller
            control={control}
            name="priority"
            render={({ field }) => (
              <TextField
                select label={t("Prioridad")} size="small" fullWidth disabled={!isEditable}
                value={field.value} onChange={(e) => field.onChange(e.target.value as OrderPriority)}
              >
                {ORDER_PRIORITIES.map((priority) => (
                  <MenuItem key={priority} value={priority}>{enumLabel("orderPriority", priority)}</MenuItem>
                ))}
              </TextField>
            )}
          />
          <TextField
            label={t("Ventana desde")} size="small" fullWidth type="time" disabled={!isEditable}
            slotProps={{ inputLabel: { shrink: true } }}
            {...register("requestedWindowStart")}
          />
          <TextField
            label={t("Ventana hasta")} size="small" fullWidth type="time" disabled={!isEditable}
            slotProps={{ inputLabel: { shrink: true } }}
            {...register("requestedWindowEnd")}
          />
        </Box>

        <SectionHeader title={t("Cliente")} />
        <Box sx={grid2}>
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
          <TextField
            label={t("Sistema de origen")} size="small" fullWidth disabled={!isEditable}
            placeholder={t("p. ej. EWM, ERP")}
            {...register("externalSource")}
          />
          <TextField
            label={t("Referencia externa")} size="small" fullWidth disabled={!isEditable}
            {...register("externalReference")}
          />
        </Box>

        <SectionHeader
          title={t("Líneas")}
          actions={isEditable && (
            <Button size="small" startIcon={<AddRounded />} onClick={() => append({ ...BLANK_LINE })}>
              {t("Añadir línea")}
            </Button>
          )}
        />
        {fields.length === 0 ? (
          <Alert severity="info" sx={{ mb: 3 }}>
            {t("Sin líneas, el pedido se planifica con las cifras declaradas de abajo.")}
          </Alert>
        ) : (
          <Box sx={{ display: "grid", gap: 1, mb: 3 }}>
            {fields.map((field, index) => (
              <Paper key={field.id} variant="outlined" sx={{ p: 1.5 }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1.5 }}>
                  <Typography variant="caption" sx={{ fontWeight: 800, color: "text.secondary" }}>
                    {t("Línea")} {index + 1}
                  </Typography>
                  <Box sx={{ flex: 1 }} />
                  {isEditable && (
                    <Tooltip title={t("Quitar")}>
                      <IconButton size="small" sx={{ color: "error.main" }} onClick={() => remove(index)}>
                        <DeleteRounded fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  )}
                </Box>
                <Box sx={{
                  display: "grid", gap: 1.5,
                  gridTemplateColumns: { xs: "1fr", sm: "repeat(2, 1fr)", md: "repeat(4, 1fr)" },
                }}>
                  <TextField
                    label={t("Material")} required size="small" disabled={!isEditable}
                    error={Boolean(errors.lines?.[index]?.materialCode)}
                    helperText={errors.lines?.[index]?.materialCode?.message}
                    {...register(`lines.${index}.materialCode` as const, { required: t("Este campo es obligatorio") })}
                  />
                  <TextField
                    label={t("Descripción")} required size="small" disabled={!isEditable}
                    sx={{ gridColumn: { md: "span 3" } }}
                    error={Boolean(errors.lines?.[index]?.materialDescription)}
                    helperText={errors.lines?.[index]?.materialDescription?.message}
                    {...register(`lines.${index}.materialDescription` as const, { required: t("Este campo es obligatorio") })}
                  />
                  <TextField
                    label={t("Cantidad")} required size="small" type="number" disabled={!isEditable}
                    error={Boolean(errors.lines?.[index]?.quantity)}
                    helperText={errors.lines?.[index]?.quantity?.message}
                    {...register(`lines.${index}.quantity` as const, {
                      required: t("Este campo es obligatorio"),
                      validate: (value) => Number(value) > 0 || t("Debe ser un número mayor que cero"),
                    })}
                  />
                  <TextField
                    label={t("Unidad")} required size="small" disabled={!isEditable}
                    {...register(`lines.${index}.uom` as const, { required: t("Este campo es obligatorio") })}
                  />
                  <TextField
                    label={t("Peso unitario (kg)")} size="small" type="number" disabled={!isEditable}
                    {...register(`lines.${index}.unitWeightKg` as const)}
                  />
                  <TextField
                    label={t("Volumen unitario (m³)")} size="small" type="number" disabled={!isEditable}
                    {...register(`lines.${index}.unitVolumeM3` as const)}
                  />
                  <TextField
                    label={t("Pallets")} size="small" type="number" disabled={!isEditable}
                    {...register(`lines.${index}.palletQuantity` as const)}
                  />
                </Box>
              </Paper>
            ))}
          </Box>
        )}

        <SectionHeader title={t("Totales")} />
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {t("Las cifras declaradas son lo que el operador afirma que pesa u ocupa el pedido. Donde las líneas también lo digan, las dos tienen que coincidir con un 1% de margen o el backend rechaza el guardado.")}
        </Typography>
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" }, mb: 2 }}>
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

        {/* La previsualización dice de dónde sale cada número, no solo cuál es: con líneas se
            calcula, sin ellas se declara, y esa es exactamente la duda que tiene delante quien
            está rellenando el formulario. */}
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1.5 }}>
            {totals.calculated ? <CalculateRounded fontSize="small" color="primary" /> : <EditNoteRounded fontSize="small" color="primary" />}
            <Typography variant="subtitle2">
              {totals.calculated ? t("Calculado desde las líneas") : t("Según las cifras declaradas")}
            </Typography>
          </Box>
          <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" } }}>
            {[
              { label: t("Peso"), value: fmtWeightKg(totals.weight) },
              { label: t("Volumen"), value: fmtVolumeM3(totals.volume) },
              { label: t("Pallets"), value: fmtDecimal(totals.pallets) },
            ].map((item) => (
              <Box key={item.label}>
                <Typography variant="caption" color="text.secondary" sx={{ textTransform: "uppercase", fontWeight: 700, letterSpacing: ".06em" }}>
                  {item.label}
                </Typography>
                <Typography sx={{ fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>{item.value}</Typography>
              </Box>
            ))}
          </Box>
          <Typography variant="caption" color="text.disabled" sx={{ display: "block", mt: 1.5 }}>
            {t("Previsualización. Los totales definitivos los calcula el backend al guardar.")}
          </Typography>
        </Paper>
      </Box>
    </FormDrawer>
  );
}
