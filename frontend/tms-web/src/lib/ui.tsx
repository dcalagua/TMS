import { useEffect, useState } from "react";
import {
  Snackbar, Alert, AlertTitle, Dialog, DialogTitle, DialogContent, DialogContentText,
  DialogActions, Button, TextField, Box,
} from "@mui/material";
import { t } from "./i18n";

type Severity = "success" | "info" | "warning" | "error";

interface ToastReq { id: number; message: string; detail?: string; severity: Severity }
interface ConfirmReq {
  id: number; title: string; message?: string; confirmText: string; cancelText: string;
  severity: Severity; resolve: (v: boolean) => void;
}
interface PromptReq {
  id: number; title: string; message?: string; label?: string; placeholder?: string;
  required: boolean; requiredMessage: string; maxLength?: number;
  confirmText: string; cancelText: string; severity: Severity;
  resolve: (v: string | null) => void;
}

let toastListener: ((v: ToastReq) => void) | null = null;
let confirmListener: ((v: ConfirmReq) => void) | null = null;
let promptListener: ((v: PromptReq) => void) | null = null;
let seq = 1;

/** Muestra un toast (reemplaza a alert()). */
export function toast(message: string, severity: Severity = "success", detail?: string) {
  toastListener?.({ id: seq++, message, detail, severity });
}

/** Confirmación de éxito de una acción. Equivalente al `notifySuccess` de la suite. */
export function notifySuccess(title: string, text?: string): void {
  toast(title, "success", text);
}

/** Fallo de una acción. Se queda más tiempo en pantalla que un éxito: hay algo que leer. */
export function notifyError(title: string, text?: string): void {
  toast(title, "error", text);
}

export interface ConfirmDialogOptions {
  title: string;
  text?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Pinta el botón de confirmar como destructivo (borrar, revocar, desactivar…). */
  dangerous?: boolean;
}

/**
 * Diálogo de confirmación modal (reemplaza a confirm() y al SweetAlert2 de TMS).
 * Es el único punto de entrada de las pantallas: ninguna monta su propio diálogo de
 * "¿seguro?", para que todas se vean y se comporten igual.
 */
export function confirmDialog(opts: string | ConfirmDialogOptions): Promise<boolean> {
  const o = typeof opts === "string" ? { title: opts } : opts;
  return new Promise((resolve) => {
    if (!confirmListener) { resolve(false); return; }
    confirmListener({
      id: seq++,
      title: o.title,
      message: o.text,
      confirmText: o.confirmLabel ?? t("Confirmar"),
      cancelText: o.cancelLabel ?? t("Cancelar"),
      severity: o.dangerous ? "error" : "warning",
      resolve,
    });
  });
}

export interface PromptDialogOptions {
  title: string;
  text?: string;
  inputLabel?: string;
  inputPlaceholder?: string;
  /** Rechaza una respuesta vacía dentro del diálogo, sin perder la acción. */
  required?: boolean;
  requiredMessage?: string;
  maxLength?: number;
  confirmLabel?: string;
  cancelLabel?: string;
  dangerous?: boolean;
}

/**
 * La confirmación-con-motivo, hermana de {@link confirmDialog}: el "por qué" detrás de una
 * acción destructiva (anular un viaje confirmado es el caso para el que se escribió).
 *
 * Deliberadamente un diálogo y no un drawer: el motivo *es* la confirmación, y partirlo en
 * "¿seguro?" seguido de un formulario son dos oportunidades de abandonar una acción que o se
 * hace con explicación o no se hace.
 *
 * Resuelve al texto recortado, o `null` si se descartó. Nunca devuelve cadena vacía.
 */
export function promptDialog(opts: PromptDialogOptions): Promise<string | null> {
  return new Promise((resolve) => {
    if (!promptListener) { resolve(null); return; }
    promptListener({
      id: seq++,
      title: opts.title,
      message: opts.text,
      label: opts.inputLabel,
      placeholder: opts.inputPlaceholder,
      required: opts.required === true,
      requiredMessage: opts.requiredMessage ?? t("Este campo es obligatorio"),
      maxLength: opts.maxLength,
      confirmText: opts.confirmLabel ?? t("Confirmar"),
      cancelText: opts.cancelLabel ?? t("Cancelar"),
      severity: opts.dangerous ? "error" : "warning",
      resolve,
    });
  });
}

/** Host único de UI (toasts + confirmaciones + prompts). Montar una vez en la raíz de la app. */
export function UiHost() {
  const [toastState, setToastState] = useState<ToastReq | null>(null);
  const [open, setOpen] = useState(false);
  const [confirmState, setConfirmState] = useState<ConfirmReq | null>(null);
  const [promptState, setPromptState] = useState<PromptReq | null>(null);
  const [promptValue, setPromptValue] = useState("");
  const [promptError, setPromptError] = useState<string | null>(null);

  useEffect(() => {
    toastListener = (v) => { setToastState(v); setOpen(true); };
    confirmListener = (v) => setConfirmState(v);
    promptListener = (v) => { setPromptState(v); setPromptValue(""); setPromptError(null); };
    return () => { toastListener = null; confirmListener = null; promptListener = null; };
  }, []);

  const closeConfirm = (val: boolean) => {
    confirmState?.resolve(val);
    setConfirmState(null);
  };

  const closePrompt = (accepted: boolean) => {
    if (!promptState) return;
    if (!accepted) { promptState.resolve(null); setPromptState(null); return; }
    const value = promptValue.trim();
    if (promptState.required && value === "") { setPromptError(promptState.requiredMessage); return; }
    promptState.resolve(value);
    setPromptState(null);
  };

  return (
    <>
      {toastState && (
        <Snackbar
          key={toastState.id}
          open={open}
          autoHideDuration={toastState.severity === "error" ? 8000 : 4000}
          onClose={() => setOpen(false)}
          anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
        >
          <Alert severity={toastState.severity} variant="filled" onClose={() => setOpen(false)} sx={{ width: "100%", maxWidth: 520 }}>
            {toastState.detail ? <AlertTitle sx={{ fontWeight: 800 }}>{toastState.message}</AlertTitle> : toastState.message}
            {toastState.detail}
          </Alert>
        </Snackbar>
      )}

      <Dialog open={!!confirmState} onClose={() => closeConfirm(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontWeight: 800 }}>{confirmState?.title}</DialogTitle>
        {confirmState?.message && (
          <DialogContent>
            <DialogContentText>{confirmState.message}</DialogContentText>
          </DialogContent>
        )}
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => closeConfirm(false)}>{confirmState?.cancelText}</Button>
          <Button variant="contained" color={confirmState?.severity === "error" ? "error" : "primary"}
            onClick={() => closeConfirm(true)} autoFocus>
            {confirmState?.confirmText}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!promptState} onClose={() => closePrompt(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontWeight: 800 }}>{promptState?.title}</DialogTitle>
        <DialogContent>
          {promptState?.message && (
            <DialogContentText sx={{ mb: 2 }}>{promptState.message}</DialogContentText>
          )}
          <Box
            component="form"
            onSubmit={(e) => { e.preventDefault(); closePrompt(true); }}
          >
            <TextField
              autoFocus fullWidth size="small"
              label={promptState?.label}
              placeholder={promptState?.placeholder}
              value={promptValue}
              onChange={(e) => { setPromptValue(e.target.value); setPromptError(null); }}
              error={promptError !== null}
              helperText={promptError ?? " "}
              slotProps={{ htmlInput: { maxLength: promptState?.maxLength } }}
            />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => closePrompt(false)}>{promptState?.cancelText}</Button>
          <Button variant="contained" color={promptState?.severity === "error" ? "error" : "primary"}
            onClick={() => closePrompt(true)}>
            {promptState?.confirmText}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
