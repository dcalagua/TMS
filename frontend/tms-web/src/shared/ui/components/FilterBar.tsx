import { useEffect, useState, type ReactNode } from "react";
import { Box, Button, InputAdornment, MenuItem, TextField, Typography } from "@mui/material";
import { EventRounded, SearchRounded, TuneRounded } from "@mui/icons-material";
import { t } from "../../../lib/i18n";
import { fmtDate, fmtDateTime } from "../../../lib/locale";
import { T } from "../../../theme";
import { DateInput } from "./DateInputs";
import { FilterMenuChip, FilterOptionList, type FilterOption } from "./FilterChip";

/**
 * Un filtro de la barra, descrito como dato. `key` (o `from`/`to`) es la clave del objeto de
 * filtros de la pantalla; todos los valores son texto, con `""` como «sin filtro» salvo que el
 * valor por defecto de la pantalla diga otra cosa.
 *
 * `more: true` lo manda a «Más filtros»: para lo que se usa poco y no merece un chip propio.
 */
export type FilterField<K extends string = string> =
  | { type: "search"; key: K; placeholder: string; width?: number }
  | { type: "select"; key: K; label: string; icon?: ReactNode; options: FilterOption[]; allLabel?: string; more?: boolean }
  | { type: "text"; key: K; label: string; icon?: ReactNode; placeholder?: string; more?: boolean }
  | { type: "date"; key: K; label: string; icon?: ReactNode; mode?: "date" | "datetime"; more?: boolean }
  | { type: "dateRange"; from: K; to: K; label: string; icon?: ReactNode; mode?: "date" | "datetime"; more?: boolean };

export interface FilterBarProps<V extends object> {
  /** Los filtros aplicados. No hay borrador: cada cambio llama a `onChange` al momento. */
  value: V;
  /** Lo que restaura «Limpiar», y contra lo que se decide si un chip está activo. */
  defaults: V;
  onChange: (next: V) => void;
  fields: FilterField<Extract<keyof V, string>>[];
  /** Algo más al final de la fila, p. ej. una acción de la lista. */
  trailing?: ReactNode;
}

/** Un texto que se aplica tras una pausa breve, para no consultar en cada tecla. */
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

function DebouncedField({ value, onApply, label, placeholder, autoFocus }: {
  value: string; onApply: (next: string) => void; label?: string; placeholder?: string; autoFocus?: boolean;
}) {
  const [text, setText] = useDebouncedText(value, onApply);
  return (
    <TextField size="small" fullWidth label={label} placeholder={placeholder} value={text} autoFocus={autoFocus}
      onChange={(e) => setText(e.target.value)}
      onKeyDown={(e) => { if (e.key === "Enter") onApply(text); }} />
  );
}

function SearchField({ value, onApply, placeholder, width }: {
  value: string; onApply: (next: string) => void; placeholder: string; width?: number;
}) {
  const [text, setText] = useDebouncedText(value, onApply);
  return (
    <TextField
      size="small" value={text} placeholder={placeholder}
      onChange={(e) => setText(e.target.value)}
      slotProps={{
        htmlInput: { "aria-label": placeholder },
        input: { startAdornment: <InputAdornment position="start"><SearchRounded sx={{ fontSize: 19 }} /></InputAdornment> },
      }}
      sx={{ width: { xs: "100%", sm: width ?? 240 } }}
    />
  );
}

const fmtFor = (mode: "date" | "datetime" | undefined) => (mode === "datetime" ? fmtDateTime : fmtDate);

function rangeLabel(from: string, to: string, mode?: "date" | "datetime"): string | null {
  const fmt = fmtFor(mode);
  if (!from && !to) return null;
  if (from && to) return from === to ? fmt(from) : `${fmt(from)} – ${fmt(to)}`;
  return from ? `${t("desde")} ${fmt(from)}` : `${t("hasta")} ${fmt(to)}`;
}

const panelTitle = {
  fontSize: T.micro, fontWeight: 700, color: "text.secondary", textTransform: "uppercase", letterSpacing: ".05em",
} as const;

/**
 * La barra de filtros de las listas: un buscador y un chip por filtro, aplicados al momento. Un
 * chip con valor se pinta en el color de acción, dice a qué está puesto y lleva una ✕ para
 * quitarlo sin abrirlo; «Limpiar» aparece en cuanto algo difiere del valor por defecto.
 */
export function FilterBar<V extends object>({ value, defaults, onChange, fields, trailing }: FilterBarProps<V>) {
  const get = (key: string) => String((value as Record<string, unknown>)[key] ?? "");
  const def = (key: string) => String((defaults as Record<string, unknown>)[key] ?? "");
  const set = (patch: Record<string, string>) => onChange({ ...value, ...patch } as V);
  const keysOf = (field: FilterField) => (field.type === "dateRange" ? [field.from, field.to] : [field.key]);
  const isActive = (field: FilterField) => keysOf(field).some((key) => get(key) !== def(key));
  const reset = (field: FilterField) => set(Object.fromEntries(keysOf(field).map((key) => [key, def(key)])));

  const search = fields.filter((f) => f.type === "search");
  const chips = fields.filter((f) => f.type !== "search" && !("more" in f && f.more));
  const more = fields.filter((f) => f.type !== "search" && "more" in f && f.more);
  const anyActive = fields.some(isActive);

  const valueLabel = (field: FilterField): string | null => {
    if (!isActive(field)) return null;
    switch (field.type) {
      case "select": return field.options.find((o) => o.id === get(field.key))?.label ?? get(field.key);
      case "text": return get(field.key);
      case "date": return fmtFor(field.mode)(get(field.key));
      case "dateRange": return rangeLabel(get(field.from), get(field.to), field.mode);
      default: return null;
    }
  };

  /**
   * Un filtro cuyo valor por defecto no está vacío (Estado = Activos, Día = hoy) filtra aunque nadie
   * lo haya tocado: el chip lo dice en su rótulo, sin el resaltado de un filtro puesto a mano.
   */
  const chipLabel = (field: FilterField): string => {
    if (field.type === "search" || isActive(field)) return field.type === "search" ? "" : field.label;
    let shown: string | null = null;
    if (field.type === "select" && def(field.key) !== "") {
      shown = field.options.find((o) => o.id === def(field.key))?.label ?? null;
    } else if (field.type === "date" && def(field.key) !== "") {
      shown = fmtFor(field.mode)(def(field.key));
    } else if (field.type === "dateRange") {
      shown = rangeLabel(def(field.from), def(field.to), field.mode);
    }
    return shown ? `${field.label}: ${shown}` : field.label;
  };

  /** El control de un filtro dentro de un panel (el de su chip o el de «Más filtros»). */
  const control = (field: FilterField, close: () => void, inMore: boolean): ReactNode => {
    switch (field.type) {
      case "select":
        return inMore ? (
          <TextField select size="small" fullWidth label={field.label} value={get(field.key)}
            onChange={(e) => set({ [field.key]: e.target.value })}>
            {field.allLabel !== undefined && <MenuItem value="">{field.allLabel}</MenuItem>}
            {field.options.map((o) => <MenuItem key={o.id} value={o.id}>{o.label}</MenuItem>)}
          </TextField>
        ) : (
          <FilterOptionList options={field.options} value={get(field.key)} allLabel={field.allLabel}
            onSelect={(id) => { set({ [field.key]: id }); close(); }} />
        );
      case "text":
        return <DebouncedField label={inMore ? field.label : undefined} placeholder={field.placeholder ?? field.label}
          value={get(field.key)} autoFocus={!inMore} onApply={(v) => set({ [field.key]: v.trim() })} />;
      case "date":
        return <DateInput mode={field.mode ?? "date"} label={field.label} value={get(field.key)} fullWidth
          onChange={(v) => set({ [field.key]: v })} />;
      case "dateRange":
        return (
          <Box sx={{ display: "grid", gap: 1.5 }}>
            <DateInput mode={field.mode ?? "date"} label={t("Desde")} value={get(field.from)} fullWidth
              max={get(field.to) || undefined} onChange={(v) => set({ [field.from]: v })} />
            <DateInput mode={field.mode ?? "date"} label={t("Hasta")} value={get(field.to)} fullWidth
              min={get(field.from) || undefined} onChange={(v) => set({ [field.to]: v })} />
          </Box>
        );
      default:
        return null;
    }
  };

  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", mb: 2 }}>
      {search.map((field) => field.type === "search" && (
        <SearchField key={field.key} value={get(field.key)} placeholder={field.placeholder} width={field.width}
          onApply={(v) => set({ [field.key]: v.trim() })} />
      ))}

      {chips.map((field) => {
        if (field.type === "search") return null;
        const panel = field.type === "select" ? null : field.type === "dateRange" || field.type === "date" ? 270 : 260;
        return (
          <FilterMenuChip
            key={keysOf(field).join("-")}
            icon={field.icon ?? (field.type === "date" || field.type === "dateRange" ? <EventRounded /> : <TuneRounded />)}
            label={chipLabel(field)}
            valueLabel={valueLabel(field)}
            onClear={() => reset(field)}
          >
            {(close) => panel === null ? control(field, close, false) : (
              <Box sx={{ p: 2, display: "grid", gap: 1.5, width: panel }}>
                <Typography sx={panelTitle}>{field.label}</Typography>
                {control(field, close, false)}
              </Box>
            )}
          </FilterMenuChip>
        );
      })}

      {more.length > 0 && (
        <FilterMenuChip
          icon={<TuneRounded />}
          label={t("Más filtros")}
          valueLabel={more.filter(isActive).length > 0 ? String(more.filter(isActive).length) : null}
          onClear={() => set(Object.fromEntries(more.flatMap(keysOf).map((key) => [key, def(key)])))}
        >
          {(close) => (
            <Box sx={{ p: 2, display: "grid", gap: 1.5, width: 280 }}>
              <Typography sx={panelTitle}>{t("Más filtros")}</Typography>
              {more.map((field) => (
                <Box key={keysOf(field).join("-")}>
                  {field.type === "dateRange" && <Typography sx={{ ...panelTitle, mb: 1 }}>{field.label}</Typography>}
                  {control(field, close, true)}
                </Box>
              ))}
            </Box>
          )}
        </FilterMenuChip>
      )}

      <Box sx={{ flex: 1 }} />
      {anyActive && (
        <Button size="small" sx={{ fontWeight: 700 }} onClick={() => onChange(defaults)}>
          {t("Limpiar")}
        </Button>
      )}
      {trailing}
    </Box>
  );
}
