import { R } from "../../theme";

/** Campos de 48 px y radio 12, sobre papel: lo que fija el diseño. */
export const authFieldSx = {
  "& .MuiOutlinedInput-root": { borderRadius: `${R.md}px`, bgcolor: "background.paper", minHeight: 48 },
  "& .MuiOutlinedInput-input": { py: 1.4, fontSize: 14 },
  "& .MuiInputAdornment-root svg": { fontSize: 18 },
} as const;
