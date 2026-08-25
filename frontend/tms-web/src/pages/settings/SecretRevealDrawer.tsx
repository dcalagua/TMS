import { useState } from "react";
import { Alert, Box, Button, IconButton, Paper, Tooltip, Typography } from "@mui/material";
import { ContentCopyRounded, CheckRounded, KeyRounded } from "@mui/icons-material";
import { FormDrawer } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

interface SecretField {
  label: string;
  value: string;
  /** El campo que el socio pega tal cual: se marca para que no se copie el equivocado. */
  primary?: boolean;
}

interface SecretRevealDrawerProps {
  title: string;
  /** La frase del backend explicando qué es esto y por qué no vuelve a verse. */
  notice: string;
  fields: SecretField[];
  /** Hasta cuándo sigue valiendo el secreto anterior, si esto fue una rotación. */
  previousValidUntil?: string | null;
  onClose: () => void;
}

/**
 * La única pantalla del producto que enseña un secreto, y lo enseña una sola vez.
 *
 * El backend no lo guarda en claro, así que cerrar este drawer sin copiarlo significa rotarlo de
 * nuevo. Se dice explícitamente en lugar de confiar en que alguien lo intuya, y el botón de
 * cerrar no se disfraza de "listo": cerrar es la acción irreversible aquí.
 *
 * No hay descarga ni "enviar por correo". Un secreto que sale de aquí por un canal que no sea el
 * portapapeles de quien lo pidió es un secreto en el historial de alguien.
 */
export function SecretRevealDrawer({ title, notice, fields, previousValidUntil, onClose }: SecretRevealDrawerProps) {
  const [copied, setCopied] = useState<string | null>(null);

  async function copy(field: SecretField) {
    try {
      await navigator.clipboard.writeText(field.value);
      setCopied(field.label);
      setTimeout(() => setCopied(null), 2000);
    } catch {
      // El portapapeles puede estar bloqueado (contexto no seguro, permiso denegado). El valor
      // sigue visible y seleccionable a mano, que es la razón de enseñarlo en texto.
    }
  }

  return (
    <FormDrawer
      open
      icon={<KeyRounded />}
      title={title}
      subtitle={t("Se muestra una sola vez.")}
      size="md"
      onClose={onClose}
      // Sin cierre por clic fuera: aquí un clic distraído cuesta una rotación.
      closeOnBackdrop={false}
      footer={<Button onClick={onClose} variant="contained">{t("Ya lo copié, cerrar")}</Button>}
    >
      <Alert severity="warning" sx={{ mb: 3 }}>{notice}</Alert>

      <Box sx={{ display: "grid", gap: 2 }}>
        {fields.map((field) => (
          <Paper
            key={field.label}
            variant="outlined"
            sx={{ p: 1.5, ...(field.primary ? { borderColor: "primary.main" } : {}) }}
          >
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
              <Typography variant="caption" sx={{
                textTransform: "uppercase", letterSpacing: ".06em", fontWeight: 700, color: "text.secondary",
              }}>
                {field.label}
              </Typography>
              <Box sx={{ flex: 1 }} />
              <Tooltip title={copied === field.label ? t("Copiado") : t("Copiar")}>
                <IconButton size="small" onClick={() => void copy(field)}>
                  {copied === field.label ? <CheckRounded fontSize="small" color="success" /> : <ContentCopyRounded fontSize="small" />}
                </IconButton>
              </Tooltip>
            </Box>
            <Typography
              component="code"
              sx={{
                display: "block", wordBreak: "break-all", fontFamily: "monospace", fontSize: 13,
                bgcolor: "action.hover", px: 1, py: 0.75, borderRadius: 1,
              }}
            >
              {field.value}
            </Typography>
          </Paper>
        ))}
      </Box>

      {previousValidUntil && (
        <Alert severity="info" sx={{ mt: 3 }}>
          {t("El secreto anterior sigue valiendo hasta {{until}}, para que el socio tenga tiempo de cambiarlo.", {
            until: fmtDateTime(previousValidUntil),
          })}
        </Alert>
      )}
    </FormDrawer>
  );
}
