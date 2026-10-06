import { useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router-dom";
import { Alert, Box, Button, CircularProgress, Link, TextField, Typography } from "@mui/material";
import { useAuth } from "../shared/auth/AuthContext";
import { AuthShell, LabelledField } from "../shared/ui/AuthShell";
import { authFieldSx } from "../shared/ui/authStyles";
import { R, T } from "../theme";
import { t } from "../lib/i18n";

interface ResetPasswordValues {
  password: string;
  confirm: string;
}

const MIN_LENGTH = 8;

/**
 * Destino del enlace de recuperación. Supabase devuelve aquí al usuario con una sesión de
 * recuperación en la URL; `supabaseClient` la detecta y `AuthContext` la publica como una
 * sesión normal. Sin sesión, el enlace caducó, ya se usó o nunca fue válido.
 *
 * Fuera de `ProtectedRoute` a propósito: un enlace caducado tiene que explicar qué pasó en vez
 * de rebotar al login sin decir nada.
 */
export function ResetPasswordPage() {
  const { status, updatePassword } = useAuth();
  const navigate = useNavigate();
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, getValues, formState: { errors, isSubmitting } } = useForm<ResetPasswordValues>();

  async function onSubmit(values: ResetPasswordValues) {
    setFormError(null);
    const result = await updatePassword(values.password);
    if (result.ok) navigate("/", { replace: true });
    else setFormError(result.message ?? t("No se pudo guardar la contraseña. Inténtalo de nuevo."));
  }

  return (
    <AuthShell>
      <Typography component="h1" sx={{ fontSize: 30, fontWeight: 800, letterSpacing: "-0.02em", lineHeight: 1.15, mb: 1 }}>
        {t("Crea una contraseña nueva")}
      </Typography>

      {status === "loading" && (
        <Box sx={{ display: "grid", placeItems: "center", py: 6 }}><CircularProgress size={28} /></Box>
      )}

      {status === "signedOut" && (
        <>
          <Alert severity="warning" sx={{ mt: 2.5, mb: 3, borderRadius: `${R.md}px` }}>
            {t("El enlace no es válido o ya expiró. Pide uno nuevo desde la pantalla de acceso.")}
          </Alert>
          <Box sx={{ display: "flex", justifyContent: "center" }}>
            <Link href="/login" sx={{ fontSize: T.bodyStrong, fontWeight: 700, color: "accentDeep" }}>
              {t("Volver al inicio de sesión")}
            </Link>
          </Box>
        </>
      )}

      {status === "signedIn" && (
        <>
          <Typography sx={{ fontSize: 14, lineHeight: 1.5, color: "text.secondary", mb: 3.75 }}>
            {t("Usa al menos {{count}} caracteres. Al guardarla entrarás directamente.", { count: MIN_LENGTH })}
          </Typography>

          {formError && <Alert severity="error" sx={{ mb: 2.5, borderRadius: `${R.md}px` }}>{formError}</Alert>}

          <Box component="form" onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <LabelledField id="new-password" label={t("Nueva contraseña")}>
              <TextField
                id="new-password"
                type="password"
                autoComplete="new-password"
                autoFocus
                required
                fullWidth
                error={Boolean(errors.password)}
                helperText={errors.password?.message}
                sx={authFieldSx}
                {...register("password", {
                  required: t("La contraseña es obligatoria"),
                  minLength: { value: MIN_LENGTH, message: t("Usa al menos {{count}} caracteres.", { count: MIN_LENGTH }) },
                })}
              />
            </LabelledField>

            <LabelledField id="confirm-password" label={t("Confirma la contraseña")}>
              <TextField
                id="confirm-password"
                type="password"
                autoComplete="new-password"
                required
                fullWidth
                error={Boolean(errors.confirm)}
                helperText={errors.confirm?.message}
                sx={authFieldSx}
                {...register("confirm", {
                  validate: (value) => value === getValues("password") || t("Las contraseñas no coinciden."),
                })}
              />
            </LabelledField>

            <Button
              type="submit"
              variant="contained"
              fullWidth
              disableElevation
              disabled={isSubmitting}
              sx={{ mt: 0.5, py: 1.5, borderRadius: `${R.md}px`, fontSize: 15, fontWeight: 700 }}
            >
              {isSubmitting ? t("Guardando...") : t("Guardar y entrar")}
            </Button>
          </Box>
        </>
      )}
    </AuthShell>
  );
}
