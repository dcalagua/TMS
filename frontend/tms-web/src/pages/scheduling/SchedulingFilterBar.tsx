import { useEffect, useState } from "react";
import { Box, Button, InputAdornment, MenuItem, TextField, Typography } from "@mui/material";
import {
  AltRouteRounded, EventRounded, FlagRounded, RadioButtonCheckedRounded, SearchRounded, TuneRounded,
  WarehouseRounded,
} from "@mui/icons-material";
import { ORDER_PRIORITIES, type OrderPriority } from "../../shared/api/ordersApi";
import { NO_ROUTE } from "../../shared/api/schedulingApi";
import {
  DateInput, FilterMenuChip, FilterOptionList, type FilterOption,
} from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";
import { T } from "../../theme";
import { defaultFilters, type Filters } from "./schedulingFilterModel";

function rangeLabel(from: string, to: string): string | null {
  if (!from && !to) return null;
  if (from && to) return from === to ? fmtDate(from) : `${fmtDate(from)} – ${fmtDate(to)}`;
  return from ? `${t("desde")} ${fmtDate(from)}` : `${t("hasta")} ${fmtDate(to)}`;
}

/** Un texto libre que se aplica tras una pausa breve, para no consultar en cada tecla. */
function useDebouncedText(value: string, apply: (next: string) => void) {
  const [text, setText] = useState(value);
  const [synced, setSynced] = useState(value);
  if (value !== synced) { setSynced(value); setText(value); }
  useEffect(() => {
    if (text === value) return;
    const timer = setTimeout(() => apply(text), 350);
    return () => clearTimeout(timer);
  }, [text, value, apply]);
  return [text, setText] as const;
}

const sectionLabel = {
  fontSize: T.micro, fontWeight: 700, color: "text.secondary", textTransform: "uppercase", letterSpacing: ".05em",
} as const;

/**
 * Filtros de Programación y Liberación, aplicados al momento. La elegibilidad no tiene chip: la
 * filtran los indicadores de arriba. Frecuencia y Retención, que se usan poco, van en «Más filtros».
 */
export function SchedulingFilterBar({ value, onChange, origins, routes }: {
  value: Filters;
  onChange: (next: Filters) => void;
  origins: FilterOption[];
  routes: FilterOption[] | null;
}) {
  const set = (patch: Partial<Filters>) => onChange({ ...value, ...patch });
  const [customer, setCustomer] = useDebouncedText(value.customer, (customerText) => onChange({ ...value, customer: customerText }));
  const [frequency, setFrequency] = useDebouncedText(value.frequency, (frequencyText) => onChange({ ...value, frequency: frequencyText }));

  const labelOf = (options: FilterOption[], id: string) =>
    id ? (options.find((o) => o.id === id)?.label ?? id) : null;
  const moreCount = (value.frequency ? 1 : 0) + (value.hold ? 1 : 0);
  const defaults = defaultFilters();
  const isDefault = (Object.keys(defaults) as (keyof Filters)[]).every((key) => key === "eligibility" || value[key] === defaults[key]);

  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", mb: 2 }}>
      <TextField
        size="small"
        value={customer}
        onChange={(e) => setCustomer(e.target.value)}
        placeholder={t("Cliente")}
        slotProps={{
          htmlInput: { "aria-label": t("Buscar por cliente") },
          input: { startAdornment: <InputAdornment position="start"><SearchRounded sx={{ fontSize: 19 }} /></InputAdornment> },
        }}
        sx={{ width: { xs: "100%", sm: 220 } }}
      />

      <FilterMenuChip
        icon={<EventRounded />}
        label={t("Despacho")}
        valueLabel={rangeLabel(value.serviceDateFrom, value.serviceDateTo)}
        onClear={() => set({ serviceDateFrom: "", serviceDateTo: "" })}
      >
        {() => (
          <Box sx={{ p: 2, display: "grid", gap: 1.5, width: 260 }}>
            <Typography sx={sectionLabel}>{t("Fecha de despacho")}</Typography>
            <DateInput label={t("Desde")} value={value.serviceDateFrom} fullWidth
              max={value.serviceDateTo || undefined} onChange={(v) => set({ serviceDateFrom: v })} />
            <DateInput label={t("Hasta")} value={value.serviceDateTo} fullWidth
              min={value.serviceDateFrom || undefined} onChange={(v) => set({ serviceDateTo: v })} />
          </Box>
        )}
      </FilterMenuChip>

      <FilterMenuChip
        icon={<WarehouseRounded />}
        label={t("Origen")}
        valueLabel={labelOf(origins, value.originId)}
        onClear={() => set({ originId: "" })}
      >
        {(close) => (
          <FilterOptionList options={origins} value={value.originId} allLabel={t("Todos los orígenes")}
            onSelect={(id) => { set({ originId: id }); close(); }} />
        )}
      </FilterMenuChip>

      {routes && (
        <FilterMenuChip
          icon={<AltRouteRounded />}
          label={t("Ruta")}
          valueLabel={value.routeCode === NO_ROUTE ? t("Sin ruta única") : labelOf(routes, value.routeCode)}
          onClear={() => set({ routeCode: "" })}
        >
          {(close) => (
            <FilterOptionList
              options={[{ id: NO_ROUTE, label: t("Sin ruta única") }, ...routes]}
              value={value.routeCode} allLabel={t("Todas las rutas")}
              onSelect={(id) => { set({ routeCode: id }); close(); }}
            />
          )}
        </FilterMenuChip>
      )}

      <FilterMenuChip
        icon={<FlagRounded />}
        label={t("Prioridad")}
        valueLabel={value.priority ? enumLabel("orderPriority", value.priority) : null}
        onClear={() => set({ priority: "" })}
      >
        {(close) => (
          <FilterOptionList
            options={ORDER_PRIORITIES.map((p) => ({ id: p, label: enumLabel("orderPriority", p) }))}
            value={value.priority} allLabel={t("Todas las prioridades")}
            onSelect={(id) => { set({ priority: id as OrderPriority | "" }); close(); }}
          />
        )}
      </FilterMenuChip>

      <FilterMenuChip
        icon={<RadioButtonCheckedRounded />}
        label={t("Estado")}
        valueLabel={value.status ? enumLabel("orderStatus", value.status) : null}
        onClear={() => set({ status: "" })}
      >
        {(close) => (
          <FilterOptionList
            options={(["NOT_READY", "READY_FOR_PLANNING"] as const).map((s) => ({ id: s, label: enumLabel("orderStatus", s) }))}
            value={value.status} allLabel={t("Pendientes y liberados")}
            onSelect={(id) => { set({ status: id as Filters["status"] }); close(); }}
          />
        )}
      </FilterMenuChip>

      <FilterMenuChip
        icon={<TuneRounded />}
        label={t("Más filtros")}
        valueLabel={moreCount > 0 ? String(moreCount) : null}
        onClear={() => set({ frequency: "", hold: "" })}
      >
        {() => (
          <Box sx={{ p: 2, display: "grid", gap: 1.5, width: 260 }}>
            <Typography sx={sectionLabel}>{t("Más filtros")}</Typography>
            <TextField size="small" label={t("Frecuencia")} value={frequency} fullWidth
              onChange={(e) => setFrequency(e.target.value)} />
            <TextField select size="small" label={t("Retención")} value={value.hold} fullWidth
              onChange={(e) => set({ hold: e.target.value as Filters["hold"] })}>
              <MenuItem value="">{t("Todas")}</MenuItem>
              <MenuItem value="with">{t("Con retención")}</MenuItem>
              <MenuItem value="without">{t("Sin retención")}</MenuItem>
            </TextField>
          </Box>
        )}
      </FilterMenuChip>

      <Box sx={{ flex: 1 }} />
      {!isDefault && (
        <Button size="small" sx={{ fontWeight: 700 }}
          onClick={() => onChange({ ...defaultFilters(), eligibility: value.eligibility })}>
          {t("Limpiar")}
        </Button>
      )}
    </Box>
  );
}
