import { useState } from "react";
import { useForm } from "react-hook-form";
import { Navigate, useLocation } from "react-router-dom";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, IconButton, InputAdornment, Link, TextField, Typography,
} from "@mui/material";
import {
  ArrowBackRounded, ArrowForwardRounded, LockOutlined, MailOutlineRounded, VisibilityOffRounded, VisibilityRounded,
} from "@mui/icons-material";
import { useAuth } from "../shared/auth/AuthContext";
import { getRememberSession } from "../shared/auth/supabaseClient";
import { AuthShell, LabelledField } from "../shared/ui/AuthShell";
import { authFieldSx } from "../shared/ui/authStyles";
import { R, T } from "../theme";
import { t } from "../lib/i18n";

interface LoginFormValues {
  email: string;
  password: string;
  remember: boolean;
}

interface ResetFormValues {
  email: string;
}

interface LocationState {
  from?: { pathname: string };
}

const titleSx = { fontSize: 30, fontWeight: 800, letterSpacing: "-0.02em", lineHeight: 1.15, mb: 1 } as const;
const subtitleSx = { fontSize: 14, lineHeight: 1.5, color: "text.secondary", mb: 3.75 } as const;
const submitSx = { py: 1.5, borderRadius: `${R.md}px`, fontSize: 15, fontWeight: 700 } as const;
const linkSx = { fontSize: T.bodyStrong, fontWeight: 700, color: "accentDeep", textUnderlineOffset: 3 } as const;

/**
 * Pantalla de acceso. El único sitio donde la app habla directamente con Supabase Auth, a
 * través de `useAuth()` — aquí no ocurre ninguna llamada de negocio.
 *
 * Tiene dos vistas en la misma tarjeta: el acceso y "olvidé mi contraseña". La segunda no
 * navega: el correo escrito se conserva al ir y volver, y no hay una ruta más que proteger.
 */
export function LoginPage() {
  const { status } = useAuth();
  const location = useLocation();
  const [view, setView] = useState<"signIn" | "forgot">("signIn");
  const [email, setEmail] = useState("");

  if (status === "signedIn") {
    const redirectTo = (location.state as LocationState | null)?.from?.pathname ?? "/";
    return <Navigate to={redirectTo} replace />;
  }

  return (
    <AuthShell>
      {view === "signIn"
        ? <SignInForm initialEmail={email} onForgot={(current) => { setEmail(current); setView("forgot"); }} />
        : <ForgotPasswordForm initialEmail={email} onBack={(current) => { setEmail(current); setView("signIn"); }} />}
    </AuthShell>
  );
}

function SignInForm({ initialEmail, onForgot }: { initialEmail: string; onForgot(email: string): void }) {
  const { signIn } = useAuth();
  const [formError, setFormError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const { register, handleSubmit, getValues, formState: { errors, isSubmitting } } = useForm<LoginFormValues>({
    defaultValues: { email: initialEmail, password: "", remember: getRememberSession() },
  });

  async function onSubmit(values: LoginFormValues) {
    setFormError(null);
    const result = await signIn(values.email, values.password, values.remember);
    if (!result.ok) {
      setFormError(result.message ?? t("No se pudo iniciar sesión. Revisa tus credenciales e inténtalo de nuevo."));
    }
  }

  return (
    <>
      <Typography component="h1" sx={titleSx}>{t("Bienvenido de vuelta")}</Typography>
      <Typography sx={subtitleSx}>
        {t("Ingresa con el correo y la contraseña que te asignó el administrador de tu compañía.")}
      </Typography>

      {formError && <Alert severity="error" sx={{ mb: 2.5, borderRadius: `${R.md}px` }}>{formError}</Alert>}

      <Box component="form" onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        <LabelledField id="email" label={t("Correo electrónico")}>
          <TextField
            id="email"
            type="email"
            placeholder="nombre@empresa.com"
            autoComplete="username"
            autoFocus
            required
            fullWidth
            error={Boolean(errors.email)}
            helperText={errors.email?.message}
            sx={authFieldSx}
            slotProps={{
              input: { startAdornment: <InputAdornment position="start"><MailOutlineRounded /></InputAdornment> },
            }}
            {...register("email", { required: t("El correo electrónico es obligatorio") })}
          />
        </LabelledField>

        <LabelledField id="password" label={t("Contraseña")}>
          <TextField
            id="password"
            type={showPassword ? "text" : "password"}
            autoComplete="current-password"
            required
            fullWidth
            error={Boolean(errors.password)}
            helperText={errors.password?.message}
            sx={authFieldSx}
            slotProps={{
              input: {
                startAdornment: <InputAdornment position="start"><LockOutlined /></InputAdornment>,
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton
                      onClick={() => setShowPassword((visible) => !visible)}
                      aria-label={showPassword ? t("Ocultar contraseña") : t("Mostrar contraseña")}
                      aria-pressed={showPassword}
                      edge="end"
                      size="small"
                    >
                      {showPassword ? <VisibilityOffRounded fontSize="small" /> : <VisibilityRounded fontSize="small" />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
            }}
            {...register("password", { required: t("La contraseña es obligatoria") })}
          />
        </LabelledField>

        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1, mt: -0.75, mb: 2.25 }}>
          <FormControlLabel
            control={<Checkbox size="small" defaultChecked={getRememberSession()} {...register("remember")} />}
            label={t("Recordarme")}
            sx={{ m: 0, "& .MuiFormControlLabel-label": { fontSize: T.bodyStrong, fontWeight: 500 } }}
          />
          <Link component="button" type="button" onClick={() => onForgot(getValues("email"))} sx={linkSx}>
            {t("¿Olvidaste tu contraseña?")}
          </Link>
        </Box>

        <Button
          type="submit"
          variant="contained"
          fullWidth
          disableElevation
          disabled={isSubmitting}
          endIcon={<ArrowForwardRounded aria-hidden />}
          sx={submitSx}
        >
          {isSubmitting ? t("Ingresando...") : t("Ingresar")}
        </Button>
      </Box>

      <Typography sx={{ textAlign: "center", fontSize: T.bodyStrong, color: "text.secondary", mt: 2.5 }}>
        {t("¿Aún no tienes acceso?")}{" "}
        <Box component="span" sx={{ fontWeight: 700, color: "accentDeep" }}>{t("Contacta a tu administrador")}</Box>
      </Typography>
    </>
  );
}

/**
 * "Olvidé mi contraseña". La respuesta es la misma exista o no la cuenta: decir "ese correo
 * no está registrado" le confirmaría a cualquiera qué direcciones tienen acceso.
 */
function ForgotPasswordForm({ initialEmail, onBack }: { initialEmail: string; onBack(email: string): void }) {
  const { requestPasswordReset } = useAuth();
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, getValues, formState: { errors, isSubmitting } } = useForm<ResetFormValues>({
    defaultValues: { email: initialEmail },
  });

  async function onSubmit(values: ResetFormValues) {
    setFormError(null);
    const result = await requestPasswordReset(values.email);
    if (result.ok) setSentTo(values.email);
    else setFormError(result.message ?? t("No se pudo enviar el correo. Inténtalo de nuevo en unos minutos."));
  }

  return (
    <>
      <Typography component="h1" sx={titleSx}>{t("Recupera tu acceso")}</Typography>
      <Typography sx={subtitleSx}>
        {t("Te enviaremos un enlace para crear una contraseña nueva.")}
      </Typography>

      {sentTo ? (
        <Alert severity="success" sx={{ mb: 3, borderRadius: `${R.md}px` }}>
          {t("Si {{email}} tiene una cuenta, en unos minutos recibirá el enlace. Revisa también la carpeta de spam.", { email: sentTo })}
        </Alert>
      ) : (
        <>
          {formError && <Alert severity="error" sx={{ mb: 2.5, borderRadius: `${R.md}px` }}>{formError}</Alert>}
          <Box component="form" onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <LabelledField id="reset-email" label={t("Correo electrónico")}>
              <TextField
                id="reset-email"
                type="email"
                placeholder="nombre@empresa.com"
                autoComplete="username"
                autoFocus
                required
                fullWidth
                error={Boolean(errors.email)}
                helperText={errors.email?.message}
                sx={authFieldSx}
                slotProps={{
                  input: { startAdornment: <InputAdornment position="start"><MailOutlineRounded /></InputAdornment> },
                }}
                {...register("email", { required: t("El correo electrónico es obligatorio") })}
              />
            </LabelledField>
            <Button type="submit" variant="contained" fullWidth disableElevation disabled={isSubmitting} sx={{ ...submitSx, mt: 0.5 }}>
              {isSubmitting ? t("Enviando...") : t("Enviar enlace")}
            </Button>
          </Box>
        </>
      )}

      <Box sx={{ display: "flex", justifyContent: "center", mt: 2.5 }}>
        <Link
          component="button"
          type="button"
          onClick={() => onBack(sentTo ?? getValues("email"))}
          sx={{ ...linkSx, display: "inline-flex", alignItems: "center", gap: 0.5 }}
        >
          <ArrowBackRounded sx={{ fontSize: 16 }} aria-hidden />
          {t("Volver al inicio de sesión")}
        </Link>
      </Box>
    </>
  );
}
