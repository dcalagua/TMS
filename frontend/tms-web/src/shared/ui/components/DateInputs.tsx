import { useEffect, useRef, useState, type ReactNode, type Ref } from "react";
import dayjs, { type Dayjs } from "dayjs";
import customParseFormat from "dayjs/plugin/customParseFormat";
import "dayjs/locale/es";
import "dayjs/locale/en-gb";
import { alpha, type SxProps, type Theme } from "@mui/material/styles";
import { DatePicker, DateTimePicker, LocalizationProvider, TimePicker } from "@mui/x-date-pickers";
import { AdapterDayjs } from "@mui/x-date-pickers/AdapterDayjs";
import { enUS, esES } from "@mui/x-date-pickers/locales";
import type { PickersActionBarAction } from "@mui/x-date-pickers/PickersActionBar";
import EventRoundedIcon from "@mui/icons-material/EventRounded";
import ScheduleRoundedIcon from "@mui/icons-material/ScheduleRounded";
import EditCalendarRoundedIcon from "@mui/icons-material/EditCalendarRounded";
import {
  useController, type Control, type FieldPath, type FieldValues, type RegisterOptions,
} from "react-hook-form";
import { getLang } from "../../../lib/i18n";

dayjs.extend(customParseFormat);

/**
 * Selectores de fecha y hora de la aplicación. Sustituyen a los `<input type="date|time|
 * datetime-local">` nativos, que cada navegador pinta a su manera.
 *
 * El contrato con las pantallas es de *texto*, el mismo que tenían los inputs nativos, para que
 * ningún formulario cambie su estado ni lo que envía al backend:
 *   - fecha      -> `yyyy-mm-dd`
 *   - hora       -> `HH:mm`
 *   - fecha+hora -> `yyyy-mm-ddTHH:mm` (hora local, como `datetime-local`)
 * y `""` cuando el campo está vacío. Lo que ve el usuario sí es regional (dd/mm/aaaa, 24 h).
 */

export type DateInputMode = "date" | "time" | "datetime";

const VALUE_FORMAT: Record<DateInputMode, string> = {
  date: "YYYY-MM-DD",
  time: "HH:mm",
  datetime: "YYYY-MM-DDTHH:mm",
};

/** Formas que se aceptan al leer: el backend devuelve horas con segundos e instantes con zona. */
const PARSE_FORMATS: Record<DateInputMode, string[]> = {
  date: ["YYYY-MM-DD"],
  time: ["HH:mm", "HH:mm:ss"],
  datetime: ["YYYY-MM-DDTHH:mm", "YYYY-MM-DDTHH:mm:ss", "YYYY-MM-DD HH:mm"],
};

const DISPLAY_FORMAT: Record<DateInputMode, string> = {
  date: "DD/MM/YYYY",
  time: "HH:mm",
  datetime: "DD/MM/YYYY HH:mm",
};

function parse(value: string | null | undefined, mode: DateInputMode): Dayjs | null {
  if (!value) return null;
  const strict = dayjs(value, PARSE_FORMATS[mode], true);
  if (strict.isValid()) return strict;
  // Un instante ISO completo (con zona) se muestra en hora local.
  const loose = mode === "time" ? null : dayjs(value);
  return loose && loose.isValid() ? loose : null;
}

const ICON: Record<DateInputMode, typeof EventRoundedIcon> = {
  date: EventRoundedIcon,
  time: ScheduleRoundedIcon,
  datetime: EditCalendarRoundedIcon,
};

/** El popup: tarjeta redondeada con borde suave, día seleccionado en el color primario. */
const paperSx: SxProps<Theme> = (theme) => ({
  mt: 0.75,
  borderRadius: 3,
  border: `1px solid ${theme.palette.divider}`,
  boxShadow: theme.palette.mode === "dark"
    ? "0 12px 32px rgba(0,0,0,.55)"
    : "0 12px 32px rgba(16,24,40,.14), 0 2px 6px rgba(16,24,40,.06)",
  overflow: "hidden",
  backgroundImage: "none",
});

const layoutSx: SxProps<Theme> = (theme) => ({
  "& .MuiPickersCalendarHeader-root": { mt: 1.5, mb: 0.5, pl: 2.5, pr: 1.5 },
  "& .MuiPickersCalendarHeader-label": { fontWeight: 700, textTransform: "capitalize" },
  "& .MuiDayCalendar-weekDayLabel": {
    fontWeight: 700, color: theme.palette.text.secondary, textTransform: "uppercase", fontSize: 11,
  },
  "& .MuiPickerDay-root, & .MuiPickersDay-root": { borderRadius: 2, fontWeight: 500 },
  "& .MuiPickerDay-today:not(.Mui-selected), & .MuiPickersDay-today:not(.Mui-selected)": {
    borderColor: theme.palette.primary.main, color: theme.palette.primary.main, fontWeight: 700,
  },
  "& .Mui-selected": { fontWeight: 700 },
  "& .MuiMultiSectionDigitalClockSection-item, & .MuiDigitalClock-item": { borderRadius: 2, mx: 0.5 },
  "& .MuiPickersYear-yearButton, & .MuiPickersMonth-monthButton": { borderRadius: 2 },
  "& .MuiPickersLayout-actionBar": {
    borderTop: `1px solid ${theme.palette.divider}`,
    backgroundColor: alpha(theme.palette.primary.main, theme.palette.mode === "dark" ? 0.06 : 0.03),
    px: 1.5, py: 1,
    "& .MuiButton-root": { fontWeight: 700, borderRadius: 2 },
  },
});

export interface DateInputProps {
  mode?: DateInputMode;
  label?: ReactNode;
  /** Nombre accesible cuando el campo no lleva etiqueta visible (p. ej. dentro de una tabla). */
  ariaLabel?: string;
  value: string | null | undefined;
  onChange: (value: string) => void;
  onBlur?: () => void;
  name?: string;
  required?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  error?: boolean;
  helperText?: ReactNode;
  placeholder?: string;
  size?: "small" | "medium";
  fullWidth?: boolean;
  sx?: SxProps<Theme>;
  /** Límites en el mismo formato de texto que `value`. */
  min?: string;
  max?: string;
  /** Pasos del reloj, en minutos (por defecto 5). */
  minutesStep?: number;
  /** Mostrar "Limpiar" en el popup. Por defecto, cuando el campo no es obligatorio. */
  clearable?: boolean;
  inputRef?: Ref<HTMLInputElement>;
  autoFocus?: boolean;
  id?: string;
}

/** Selector de fecha, hora o fecha y hora con el contrato de texto de los inputs nativos. */
export function DateInput({
  mode = "date", label, ariaLabel, value, onChange, onBlur, name, required, disabled, readOnly, error,
  helperText, placeholder, size = "small", fullWidth, sx, min, max, minutesStep = 5, clearable,
  inputRef, autoFocus, id,
}: DateInputProps) {
  // El picker trabaja con un Dayjs que puede estar a medio escribir (inválido). Se guarda aquí
  // para no borrarle al usuario lo que teclea mientras el texto todavía no es una fecha.
  const [inner, setInner] = useState<Dayjs | null>(() => parse(value, mode));
  const lastEmitted = useRef<string>(value ?? "");
  useEffect(() => {
    const next = value ?? "";
    if (next !== lastEmitted.current) {
      lastEmitted.current = next;
      setInner(parse(next, mode));
    }
  }, [value, mode]);

  const handleChange = (d: Dayjs | null) => {
    setInner(d);
    const out = d && d.isValid() ? d.format(VALUE_FORMAT[mode]) : "";
    // Un texto incompleto no se propaga como vacío: el formulario sigue viendo el último valor
    // válido hasta que el usuario termine o borre el campo del todo.
    if (d && !d.isValid()) return;
    if (out !== lastEmitted.current) {
      lastEmitted.current = out;
      onChange(out);
    }
  };

  const showClear = clearable ?? !required;
  const actions: PickersActionBarAction[] = mode === "time"
    ? (showClear ? ["clear", "accept"] : ["accept"])
    : mode === "datetime"
      ? (showClear ? ["today", "clear", "accept"] : ["today", "accept"])
      : (showClear ? ["today", "clear"] : ["today"]);

  const minD = parse(min, mode) ?? undefined;
  const maxD = parse(max, mode) ?? undefined;
  const Icon = ICON[mode];

  const common = {
    label,
    value: inner,
    onChange: handleChange,
    onClose: onBlur,
    disabled,
    readOnly,
    autoFocus,
    format: DISPLAY_FORMAT[mode],
    slots: { openPickerIcon: Icon },
    slotProps: {
      textField: {
        size, fullWidth, required, error, helperText, name, id, placeholder, onBlur, inputRef, sx,
        ...(ariaLabel ? { slotProps: { input: { "aria-label": ariaLabel } } } : {}),
      },
      openPickerButton: { size: "small" as const, sx: { color: "text.secondary", "&:hover": { color: "primary.main" } } },
      openPickerIcon: { fontSize: "small" as const },
      desktopPaper: { sx: paperSx },
      mobilePaper: { sx: { borderRadius: 3 } },
      layout: { sx: layoutSx },
      actionBar: { actions },
      popper: { placement: "bottom-start" as const },
    },
  };

  const picker = mode === "time" ? (
    <TimePicker
      {...common}
      ampm={false}
      minutesStep={minutesStep}
      minTime={minD}
      maxTime={maxD}
      timeSteps={{ minutes: minutesStep }}
    />
  ) : mode === "datetime" ? (
    <DateTimePicker
      {...common}
      ampm={false}
      minutesStep={minutesStep}
      minDateTime={minD}
      maxDateTime={maxD}
      timeSteps={{ minutes: minutesStep }}
    />
  ) : (
    <DatePicker {...common} minDate={minD} maxDate={maxD} showDaysOutsideCurrentMonth />
  );

  // El campo trae su propio proveedor de localización: así funciona montado fuera de la app
  // (una prueba que renderiza solo un panel, un portal) sin depender de que alguien más arriba
  // lo haya puesto. Anidado bajo el de `main.tsx` no cambia nada.
  return <DatePickersProvider>{picker}</DatePickersProvider>;
}

export const TimeInput = (props: Omit<DateInputProps, "mode">) => <DateInput {...props} mode="time" />;
export const DateTimeInput = (props: Omit<DateInputProps, "mode">) => <DateInput {...props} mode="datetime" />;

/** Versión para react-hook-form: sustituye a `{...register(name, rules)}` en un input nativo. */
export function FormDateInput<T extends FieldValues>({
  control, name, rules, ...rest
}: Omit<DateInputProps, "value" | "onChange" | "name"> & {
  control: Control<T>;
  name: FieldPath<T>;
  rules?: Omit<RegisterOptions<T, FieldPath<T>>, "valueAsNumber" | "valueAsDate" | "setValueAs" | "disabled">;
}) {
  const { field } = useController({ control, name, rules });
  return (
    <DateInput
      {...rest}
      name={field.name}
      value={(field.value as string | null | undefined) ?? ""}
      onChange={field.onChange}
      onBlur={field.onBlur}
      inputRef={field.ref}
      disabled={rest.disabled ?? field.disabled}
    />
  );
}

/** Proveedor único: idioma del calendario y textos ("Hoy", "Limpiar") según el idioma activo. */
export function DatePickersProvider({ children }: { children: ReactNode }) {
  const lang = getLang();
  const texts = (lang === "en" ? enUS : esES).components.MuiLocalizationProvider.defaultProps.localeText;
  return (
    <LocalizationProvider
      dateAdapter={AdapterDayjs}
      adapterLocale={lang === "en" ? "en-gb" : "es"}
      localeText={texts}
    >
      {children}
    </LocalizationProvider>
  );
}
