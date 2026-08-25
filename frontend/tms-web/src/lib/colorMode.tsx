import { createContext, useContext, useState, useMemo, useEffect, type ReactNode } from "react";
import { ThemeProvider, CssBaseline, GlobalStyles } from "@mui/material";
import { getTheme, type ColorMode, type ThemeKey, type Density, THEMES, DENSITY } from "../theme";

const KEY = "ebim-color-mode";
const THEME_KEY = "ebim-theme";
const DENSITY_KEY = "ebim-density";

const ColorModeCtx = createContext<{
  mode: ColorMode; toggle: () => void; setMode: (m: ColorMode) => void;
  themeKey: ThemeKey; setThemeKey: (k: ThemeKey) => void;
  brandAccent: string | null; setBrandAccent: (c: string | null) => void;
  density: Density; setDensity: (d: Density) => void;
}>({
  mode: "light", toggle: () => {}, setMode: () => {},
  themeKey: "forest", setThemeKey: () => {},
  brandAccent: null, setBrandAccent: () => {},
  density: "equilibrada", setDensity: () => {},
});
export const useColorMode = () => useContext(ColorModeCtx);

function initialMode(): ColorMode {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === "light" || saved === "dark") return saved;
  } catch { /* noop */ }
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}
function initialTheme(): ThemeKey {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved && (saved in THEMES || saved === "brand")) return saved as ThemeKey;
  } catch { /* noop */ }
  return "forest";
}
function initialDensity(): Density {
  try {
    const saved = localStorage.getItem(DENSITY_KEY);
    if (saved && saved in DENSITY) return saved as Density;
  } catch { /* noop */ }
  return "equilibrada";
}

export function ColorModeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ColorMode>(initialMode);
  const [themeKey, setThemeKey] = useState<ThemeKey>(initialTheme);
  const [brandAccent, setBrandAccent] = useState<string | null>(null);
  const [density, setDensity] = useState<Density>(initialDensity);

  useEffect(() => { try { localStorage.setItem(KEY, mode); } catch { /* noop */ } }, [mode]);
  useEffect(() => { try { localStorage.setItem(THEME_KEY, themeKey); } catch { /* noop */ } }, [themeKey]);
  useEffect(() => {
    try { localStorage.setItem(DENSITY_KEY, density); } catch { /* noop */ }
    // Reflejar en <html> para CSS no-MUI y homologar el contrato de suite (data-density).
    try { document.documentElement.setAttribute("data-density", density); } catch { /* noop */ }
  }, [density]);

  const value = useMemo(() => ({
    mode,
    toggle: () => setMode((m) => (m === "light" ? "dark" : "light")),
    setMode,
    themeKey,
    setThemeKey,
    brandAccent,
    setBrandAccent,
    density,
    setDensity,
  }), [mode, themeKey, brandAccent, density]);

  const theme = useMemo(() => getTheme(mode, themeKey, brandAccent ?? undefined, density), [mode, themeKey, brandAccent, density]);

  return (
    <ColorModeCtx.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <GlobalStyles styles={(th) => ({
          "html, body, #root": { maxWidth: "100%", overflowX: "hidden" },
          ".tnum": { fontFeatureSettings: '"tnum"' },
          ":focus-visible": { outline: `2px solid ${th.palette.primary.dark}`, outlineOffset: "2px", borderRadius: "6px" },
          ":focus:not(:focus-visible)": { outline: "none" },
        })} />
        {children}
      </ThemeProvider>
    </ColorModeCtx.Provider>
  );
}
