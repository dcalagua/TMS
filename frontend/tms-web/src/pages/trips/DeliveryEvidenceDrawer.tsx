import { useState, type ChangeEvent } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { AttachFileRounded, UploadFileRounded } from "@mui/icons-material";
import { EVIDENCE_TYPES, type EvidenceType } from "../../shared/api/planningApi";
import { FormDrawer } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

const FORM_ID = "delivery-evidence-form";

export interface DeliveryEvidenceValues {
  evidenceType: EvidenceType;
  capturedAt: string | null;
  file: File;
}

interface DeliveryEvidenceDrawerProps {
  orderNumber: string;
  onClose: () => void;
  /** Lanza un `Error` con la frase del servidor si el backend rechaza. */
  onSubmit: (values: DeliveryEvidenceValues) => Promise<void>;
}

/**
 * Adjuntar una prueba de entrega —una firma, una foto, un documento— a una entrega ya registrada.
 *
 * Un despliegue sin almacén de evidencias responde 503 y aquí se enseña la frase del propio
 * servidor, que dice que los resultados de entrega se registran igualmente. Eso es un hecho de
 * configuración, no un error que haya cometido el operador, y presentarlo como un fallo rojo
 * genérico haría que alguien fuera a buscar el problema donde no está.
 */
export function DeliveryEvidenceDrawer({ orderNumber, onClose, onSubmit }: DeliveryEvidenceDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);

  const {
    register, control, handleSubmit,
    formState: { isDirty, isSubmitting },
  } = useForm<{ evidenceType: EvidenceType; capturedAt: string }>({
    defaultValues: { evidenceType: "SIGNATURE", capturedAt: "" },
  });

  function pickFile(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null);
    setFormError(null);
  }

  async function submit(values: { evidenceType: EvidenceType; capturedAt: string }) {
    setFormError(null);
    if (!file) {
      setFormError(t("Elige un fichero."));
      return;
    }
    try {
      await onSubmit({
        evidenceType: values.evidenceType,
        capturedAt: values.capturedAt || null,
        file,
      });
    } catch (error) {
      setFormError((error as Error).message);
    }
  }

  return (
    <FormDrawer
      open
      icon={<AttachFileRounded />}
      title={t("Adjuntar prueba de entrega")}
      subtitle={orderNumber}
      size="md"
      onClose={onClose}
      dirty={isDirty || file !== null}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting || file === null}>
            {isSubmitting ? t("Subiendo...") : t("Adjuntar")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(submit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 2 }}>
          <Controller
            control={control}
            name="evidenceType"
            render={({ field }) => (
              <TextField
                select label={t("Tipo")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value as EvidenceType)}
              >
                {EVIDENCE_TYPES.map((type) => (
                  <MenuItem key={type} value={type}>{enumLabel("evidenceType", type)}</MenuItem>
                ))}
              </TextField>
            )}
          />

          <TextField
            label={t("Capturado el")} size="small" fullWidth type="datetime-local"
            slotProps={{ inputLabel: { shrink: true } }}
            helperText={t("Cuándo se tomó la firma o la foto. Opcional.")}
            {...register("capturedAt")}
          />

          <Box>
            <Button component="label" variant="outlined" startIcon={<UploadFileRounded />}>
              {file ? file.name : t("Elegir fichero")}
              <input type="file" hidden accept="image/*,application/pdf" onChange={pickFile} />
            </Button>
          </Box>

          <Typography variant="caption" color="text.secondary">
            {t("La prueba se guarda en un almacén privado: nunca queda accesible por una URL pública.")}
          </Typography>
        </Box>
      </Box>
    </FormDrawer>
  );
}
