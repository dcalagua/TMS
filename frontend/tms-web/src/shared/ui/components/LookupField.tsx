import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Autocomplete, Box, CircularProgress, TextField, Typography } from "@mui/material";
import { t } from "../../../lib/i18n";

/** Un registro maestro seleccionable. `code` y `name` se muestran juntos porque los operadores
 * conocen unos registros por uno y otros por el otro. */
export interface LookupOption {
  id: string;
  code: string;
  name: string;
}

export interface LookupFieldProps {
  label: string;
  /** El id del registro elegido, o `''` si no hay nada elegido. */
  value: string;
  /** Cómo se lee la selección actual mientras el campo no se está editando. La aporta quien
   * llama en vez de resolverse aquí: un pedido que se está editando ya conoce el código y el
   * nombre de su origen, y volver a pedirlos para pintar un valor que ya está en pantalla
   * haría que el campo parpadeara vacío cada vez que se abre el drawer. */
  selected: LookupOption | null;
  onChange: (option: LookupOption | null) => void;
  /** Ejecuta la búsqueda. Recibe el término recortado y un AbortSignal; devuelve como mucho una página. */
  search: (term: string, signal: AbortSignal) => Promise<LookupOption[]>;
  /** Distingue las búsquedas cacheadas de este campo de las de cualquier otro lookup. */
  queryKey: readonly unknown[];
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
  error?: string;
  helperText?: string;
  fullWidth?: boolean;
  size?: "small" | "medium";
}

/** Cuánto tiene que parar la escritura antes de que salga una petición. Suficiente para que
 * teclear un código de seis letras sea una consulta y no seis; poco para que no parezca que el
 * campo se lo está pensando. */
const DEBOUNCE_MS = 250;

/**
 * Un autocompletado asíncrono sobre los maestros de una empresa.
 *
 * Un `<select>` plano deja de funcionar en cuanto un tenant tiene más ubicaciones de las que
 * caben en un fetch: el drawer de pedidos cargaba 200 orígenes y 200 destinos y en silencio no
 * ofrecía forma de llegar al 201. Esto le pregunta al servidor, una página cada vez, filtrando
 * por lo que el operador escribió — que además es la única versión que escala al objetivo de
 * 10.000 pedidos/día contra el que está escrita la arquitectura.
 *
 * La selección es por id y el id es lo único que sale de aquí. Un término escrito que no
 * coincide con nada no selecciona nada: no hay adivinanza de "la coincidencia más cercana",
 * porque un envío mandado a la tienda equivocada no es un error con el que valga la pena ser
 * listo.
 */
export function LookupField({
  label, value, selected, onChange, search, queryKey, placeholder, disabled = false,
  required = false, error, helperText, fullWidth = true, size = "small",
}: LookupFieldProps) {
  const [open, setOpen] = useState(false);
  const [term, setTerm] = useState("");
  const [debounced, setDebounced] = useState("");

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term.trim()), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [term]);

  // Solo mientras el panel está abierto: un lookup cerrado no tiene lista que llenar, y un
  // drawer de pedido con dos de estos dispararía en el montaje dos búsquedas que nadie pidió.
  const optionsQuery = useQuery({
    queryKey: [...queryKey, debounced],
    queryFn: ({ signal }) => search(debounced, signal),
    enabled: open && !disabled,
    // La lista conserva su contenido anterior mientras carga el término siguiente, para que el
    // panel no se colapse a una caja vacía entre pulsaciones.
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });

  const options = optionsQuery.data ?? [];
  // La opción elegida puede no estar en la página actual de resultados; se añade para que
  // Autocomplete pueda casarla por id y no pinte el campo vacío.
  const merged = selected && !options.some((o) => o.id === selected.id) ? [selected, ...options] : options;

  return (
    <Autocomplete<LookupOption, false, false, false>
      size={size}
      fullWidth={fullWidth}
      disabled={disabled}
      open={open}
      onOpen={() => setOpen(true)}
      onClose={() => setOpen(false)}
      value={selected ?? null}
      onChange={(_e, option) => onChange(option)}
      onInputChange={(_e, next, reason) => { if (reason === "input") setTerm(next); }}
      options={merged}
      loading={optionsQuery.isFetching}
      filterOptions={(x) => x}  // el filtrado lo hace el servidor
      isOptionEqualToValue={(option, current) => option.id === current.id}
      getOptionLabel={(option) => `${option.code} · ${option.name}`}
      noOptionsText={t("Sin coincidencias")}
      loadingText={t("Cargando...")}
      renderOption={(props, option) => {
        const { key, ...rest } = props as { key: string } & Record<string, unknown>;
        return (
          <Box component="li" key={key} {...rest} sx={{ display: "block !important", py: 0.85 }}>
            <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>{option.name}</Typography>
            <Typography variant="caption" color="text.secondary">{option.code}</Typography>
          </Box>
        );
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          label={label}
          required={required}
          placeholder={placeholder}
          error={error !== undefined}
          helperText={error ?? helperText}
          slotProps={{
            ...params.slotProps,
            input: {
              ...params.slotProps.input,
              endAdornment: (
                <>
                  {optionsQuery.isFetching ? <CircularProgress size={16} sx={{ mr: 1 }} /> : null}
                  {params.slotProps.input.endAdornment}
                </>
              ),
            },
          }}
        />
      )}
      // El id nunca se deduce del texto: si `value` no casa con ninguna opción, no hay selección.
      key={value === "" && selected === null ? "empty" : undefined}
    />
  );
}
