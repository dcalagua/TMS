import { useState } from "react";
import { useForm } from "react-hook-form";
import { Alert, Box, Button, TextField, Typography } from "@mui/material";
import { KeyRounded } from "@mui/icons-material";
import { useAuth } from "../../shared/auth/AuthContext";
import { getRememberSession } from "../../shared/auth/supabaseClient";
import { AppCard } from "../../shared/ui/components";
import { notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";

interface PasswordValues {
  current: string;
  password: string;
  confirm: string;
}

/** La misma longitud mínima que la pantalla de recuperación. */
const MIN_LENGTH = 8;

/**
 * Cambiar la contraseña desde «Mi cuenta» (D5).
 *
 * Pide la actual y la comprueba volviendo a entrar con ella antes de cambiarla: con una sesión
 * abierta, Supabase aceptaría la nueva sin preguntar, y una pantalla desbloqueada en una oficina
 * bastaría para quedarse con la cuenta. La reautenticación conserva la elección de «recordarme»
 * que hizo la persona al entrar.
 */
export function PasswordCard({ email }: { email: string | null }) {
  const { signIn, updatePassword } = useAuth();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register, handleSubmit, getValues, reset, setError,
    formState: { errors, isSubmitting },
  } = useForm<PasswordValues>({ defaultValues: { current: "", password: "", confirm: "" } });

  async function onSubmit(values: PasswordValues) {
    setFormError(null);
    if (!email) {
      setFormError(t("La sesión no tiene correo: vuelve a iniciar sesión para cambiar la contraseña."));
      return;
    }
    const verified = await signIn(email, values.current, getRememberSession());
    if (!verified.ok) {
      setError("current", { message: t("La contraseña actual no es correcta.") });
      return;
    }
    const updated = await updatePassword(values.password);
    if (!updated.ok) {
      setFormError(updated.message ?? t("No se pudo guardar la contraseña. Inténtalo de nuevo."));
      return;
    }
    reset();
    notifySuccess(t("Contraseña actualizada"), t("La próxima vez entra con la nueva."));
  }

  return (
    <AppCard title={t("Contraseña")}>
      <Box component="form" aria-label={t("Cambiar la contraseña")} noValidate
        onSubmit={(event) => void handleSubmit(onSubmit)(event)} sx={{ display: "grid", gap: 1.5 }}>
        <Typography variant="body2" color="text.secondary">
          {t("Usa al menos {{count}} caracteres.", { count: MIN_LENGTH })}
        </Typography>
        {formError && <Alert severity="error">{formError}</Alert>}
        {/* El correo, oculto, ayuda a los gestores de contraseñas a asociar el cambio a la cuenta. */}
        <input type="text" name="username" autoComplete="username" value={email ?? ""} readOnly hidden />
        <TextField
          label={t("Contraseña actual")} type="password" autoComplete="current-password" required size="small" fullWidth
          error={Boolean(errors.current)} helperText={errors.current?.message}
          {...register("current", { required: t("La contraseña actual es obligatoria") })}
        />
        <TextField
          label={t("Nueva contraseña")} type="password" autoComplete="new-password" required size="small" fullWidth
          error={Boolean(errors.password)} helperText={errors.password?.message}
          {...register("password", {
            required: t("La contraseña es obligatoria"),
            minLength: { value: MIN_LENGTH, message: t("Usa al menos {{count}} caracteres.", { count: MIN_LENGTH }) },
            validate: (value) => value !== getValues("current") || t("La nueva contraseña debe ser distinta de la actual."),
          })}
        />
        <TextField
          label={t("Confirma la contraseña")} type="password" autoComplete="new-password" required size="small" fullWidth
          error={Boolean(errors.confirm)} helperText={errors.confirm?.message}
          {...register("confirm", {
            validate: (value) => value === getValues("password") || t("Las contraseñas no coinciden."),
          })}
        />
        <Box>
          <Button type="submit" variant="contained" startIcon={<KeyRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Cambiar contraseña")}
          </Button>
        </Box>
      </Box>
    </AppCard>
  );
}
