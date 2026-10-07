import { useEffect, useState, type ReactNode } from "react";
import { Box, Button, InputAdornment, Tab, Tabs, TextField, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  EventRounded, FlagRounded, LocationOnRounded,
  SearchRounded, WarehouseRounded,
} from "@mui/icons-material";
import {
  ORDER_PRIORITIES, ORDER_STATUSES, type OrderPriority, type OrderStatus,
} from "../../shared/api/ordersApi";
import { DEFAULT_ORDER_FILTERS, type OrderFilters } from "./orderFilterModel";
import { DateInput, FilterMenuChip, FilterOptionList, type FilterOption } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtQuantity } from "../../lib/locale";
import { R, T } from "../../theme";

type Option = FilterOption;

/**
 * Pestañas por estado del pedido, con cuántos hay en cada uno para los demás filtros activos.
 *
 * Sustituyen al desplegable de Estado: el estado es el filtro que más se cambia, y con pestañas
 * el volumen de cada bandeja se ve sin abrir nada. `counts` llega `undefined` mientras carga.
 */
export function OrderStatusTabs({ value, onChange, counts }: {
  value: OrderStatus | "";
  onChange: (status: OrderStatus | "") => void;
  counts: Partial<Record<OrderStatus, number>> | undefined;
}) {
  const all = counts ? Object.values(counts).reduce((sum, n) => sum + (n ?? 0), 0) : undefined;
  const tabs: { key: OrderStatus | ""; label: string; count: number | undefined }[] = [
    { key: "", label: t("Todos"), count: all },
    ...ORDER_STATUSES.map((status) => ({
      key: status, label: enumLabel("orderStatus", status), count: counts?.[status],
    })),
  ];

  return (
    <Tabs
      value={value}
      onChange={(_e, next: OrderStatus | "") => onChange(next)}
      variant="scrollable"
      scrollButtons="auto"
      allowScrollButtonsMobile
      aria-label={t("Estado del pedido")}
      sx={{
        minHeight: 42, mb: 2, borderBottom: "1px solid", borderColor: "divider",
        "& .MuiTabs-indicator": { height: 2, borderRadius: 1 },
        "& .MuiTab-root": {
          minHeight: 42, minWidth: 0, px: 1.25, py: 0, textTransform: "none",
          fontSize: T.body, fontWeight: 500, color: "text.secondary",
          "&.Mui-selected": { fontWeight: 700, color: "text.primary" },
        },
      }}
    >
      {tabs.map((tab) => (
        <Tab
          key={tab.key || "all"}
          value={tab.key}
          disableRipple
          label={
            <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
              {tab.label}
              <Box component="span" sx={(th) => ({
                minWidth: 22, px: 0.75, borderRadius: `${R.pill}px`, fontSize: 11.5, fontWeight: 700,
                lineHeight: "18px", textAlign: "center", fontVariantNumeric: "tabular-nums",
                bgcolor: value === tab.key
                  ? alpha(th.palette.primary.main, th.palette.mode === "dark" ? 0.25 : 0.12)
                  : "action.hover",
                color: value === tab.key ? "primary.main" : "text.secondary",
              })}>
                {tab.count === undefined ? "·" : fmtQuantity(tab.count)}
              </Box>
            </Box>
          }
        />
      ))}
    </Tabs>
  );
}

function rangeLabel(from: string, to: string): string | null {
  if (!from && !to) return null;
  if (from && to) return from === to ? fmtDate(from) : `${fmtDate(from)} – ${fmtDate(to)}`;
  return from ? `${t("desde")} ${fmtDate(from)}` : `${t("hasta")} ${fmtDate(to)}`;
}

/**
 * Buscador y filtros de la lista de pedidos. Se aplican al momento: el número de pedido con una
 * pausa breve para no consultar en cada tecla, el resto al elegir.
 */
export function OrderFilterBar({ value, onChange, origins, destinations, trailing }: {
  value: OrderFilters;
  onChange: (next: OrderFilters) => void;
  origins: Option[];
  destinations: Option[];
  trailing?: ReactNode;
}) {
  const [search, setSearch] = useState(value.orderNumber);
  // Si el número cambia desde fuera (limpiar filtros, enlace "Ir al pedido"), el texto lo sigue.
  const [synced, setSynced] = useState(value.orderNumber);
  if (value.orderNumber !== synced) { setSynced(value.orderNumber); setSearch(value.orderNumber); }
  useEffect(() => {
    if (search === value.orderNumber) return;
    const timer = setTimeout(() => onChange({ ...value, orderNumber: search.trim() }), 350);
    return () => clearTimeout(timer);
  }, [search, value, onChange]);

  const set = (patch: Partial<OrderFilters>) => onChange({ ...value, ...patch });
  const labelOf = (options: Option[], id: string) =>
    id ? (options.find((o) => o.id === id)?.label ?? t("Seleccionado")) : null;
  const active = value.orderNumber || value.originId || value.destinationId
    || value.serviceDateFrom || value.serviceDateTo || value.priority;

  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", mb: 2 }}>
      <TextField
        size="small"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder={t("Número de pedido")}
        slotProps={{
          htmlInput: { "aria-label": t("Buscar por número de pedido") },
          input: {
            startAdornment: (
              <InputAdornment position="start"><SearchRounded sx={{ fontSize: 19 }} /></InputAdornment>
            ),
          },
        }}
        sx={{ width: { xs: "100%", sm: 260 } }}
      />

      <FilterMenuChip
        icon={<EventRounded />}
        label={t("Servicio")}
        valueLabel={rangeLabel(value.serviceDateFrom, value.serviceDateTo)}
        onClear={() => set({ serviceDateFrom: "", serviceDateTo: "" })}
      >
        {() => (
          <Box sx={{ p: 2, display: "grid", gap: 1.5, width: 260 }}>
            <Typography sx={{ fontSize: T.micro, fontWeight: 700, color: "text.secondary", textTransform: "uppercase", letterSpacing: ".05em" }}>
              {t("Fecha de servicio")}
            </Typography>
            <DateInput label={t("Desde")} value={value.serviceDateFrom} fullWidth
              max={value.serviceDateTo || undefined}
              onChange={(v) => set({ serviceDateFrom: v })} />
            <DateInput label={t("Hasta")} value={value.serviceDateTo} fullWidth
              min={value.serviceDateFrom || undefined}
              onChange={(v) => set({ serviceDateTo: v })} />
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

      <FilterMenuChip
        icon={<LocationOnRounded />}
        label={t("Destino")}
        valueLabel={labelOf(destinations, value.destinationId)}
        onClear={() => set({ destinationId: "" })}
      >
        {(close) => (
          <FilterOptionList options={destinations} value={value.destinationId} allLabel={t("Todos los destinos")}
            onSelect={(id) => { set({ destinationId: id }); close(); }} />
        )}
      </FilterMenuChip>

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

      <Box sx={{ flex: 1 }} />
      {active && (
        <Button
          size="small"
          onClick={() => { setSearch(""); onChange({ ...DEFAULT_ORDER_FILTERS, status: value.status }); }}
          sx={{ fontWeight: 700 }}
        >
          {t("Limpiar filtros")}
        </Button>
      )}
      {trailing}
    </Box>
  );
}
