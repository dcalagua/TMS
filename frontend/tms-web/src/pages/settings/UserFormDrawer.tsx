import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, Paper, TextField, Typography,
} from "@mui/material";
import { PersonAddRounded, PersonRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  fetchAssignableRoles, inviteUser, updateUserProfile, updateUserRoles,
  type AdministeredUserView,
} from "../../shared/api/administrationApi";
import { FormDrawer, SectionHeader } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "user-form";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface UserFormValues {
  email: string;
  fullName: string;
  roleCodes: string[];
}

interface UserFormDrawerProps {
  companyId: string;
  /** `null` invita a alguien nuevo; si no, edita esta membresía. */
  user: AdministeredUserView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof UserFormValues>(["email", "fullName", "roleCodes"]);

/**
 * Invitar a alguien a esta empresa, o cambiarle el nombre y los roles.
 *
 * Lo que se concede y se revoca es una *membresía*, no una cuenta: la misma persona puede tener
 * varias, y el identificador sobre el que se actúa es `membershipId` y no `appUserId`.
 *
 * El correo solo se pide al invitar. Cambiarlo después sería invitar a otra persona, y el modelo
 * lo trata así.
 *
 * Los roles de ámbito ORGANIZACIÓN no se ofrecen: sobre una membresía de empresa no conceden
 * nada, y el backend los rechaza. Se enseña por qué en vez de esconderlos sin más.
 */
export function UserFormDrawer({ companyId, user, onClose, onSaved }: UserFormDrawerProps) {
  const isEdit = user !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const rolesQuery = useQuery({
    queryKey: ["assignable-roles", companyId],
    queryFn: ({ signal }) => fetchAssignableRoles(companyId, signal),
  });
  const roles = rolesQuery.data ?? [];

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<UserFormValues>({
    defaultValues: {
      email: user?.email ?? "",
      fullName: user?.fullName ?? "",
      roleCodes: user?.roleCodes ?? [],
    },
  });

  async function onSubmit(values: UserFormValues) {
    setFormError(null);
    if (values.roleCodes.length === 0) {
      setFormError(t("Selecciona al menos un rol"));
      return;
    }

    try {
      if (isEdit) {
        // Dos llamadas y no una: el nombre y los roles son endpoints distintos porque son
        // decisiones distintas — corregir cómo se llama alguien no es cambiar lo que puede hacer.
        await updateUserProfile(companyId, user.membershipId, values.fullName.trim());
        await updateUserRoles(companyId, user.membershipId, values.roleCodes);
      } else {
        await inviteUser(companyId, {
          email: values.email.trim(),
          fullName: values.fullName.trim(),
          roleCodes: values.roleCodes,
        });
      }
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={isEdit ? <PersonRounded /> : <PersonAddRounded />}
      title={isEdit ? t("Editar acceso") : t("Invitar a alguien")}
      subtitle={isEdit ? user.email : t("Se le da acceso a esta empresa con los roles que elijas.")}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar") : t("Invitar")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <SectionHeader title={t("Identidad")} />
        <Box sx={{ display: "grid", gap: 2, mb: 3 }}>
          {!isEdit && (
            <TextField
              label={t("Correo electrónico")} required size="small" fullWidth type="email"
              error={Boolean(errors.email)} helperText={errors.email?.message}
              {...register("email", {
                required: t("Este campo es obligatorio"),
                pattern: { value: EMAIL_PATTERN, message: t("Ingresa un correo electrónico válido") },
              })}
            />
          )}
          <TextField
            label={t("Nombre")} required size="small" fullWidth
            error={Boolean(errors.fullName)} helperText={errors.fullName?.message}
            {...register("fullName", { required: t("Este campo es obligatorio") })}
          />
        </Box>

        <SectionHeader title={t("Roles")} />
        {user?.organizationWide && (
          <Alert severity="info" sx={{ mb: 2 }}>
            {t("Esta persona tiene un rol de organización: alcanza a todas las empresas y no se cambia desde aquí.")}
          </Alert>
        )}
        <Controller
          control={control}
          name="roleCodes"
          render={({ field }) => (
            <Box sx={{ display: "grid", gap: 1 }}>
              {roles.map((role) => (
                <Paper key={role.code} variant="outlined" sx={{ p: 1.25, opacity: role.assignable ? 1 : 0.6 }}>
                  <FormControlLabel
                    sx={{ alignItems: "flex-start", m: 0 }}
                    control={
                      <Checkbox
                        sx={{ mt: -0.5 }}
                        disabled={!role.assignable || user?.organizationWide}
                        checked={field.value.includes(role.code)}
                        onChange={(e) => {
                          const next = e.target.checked
                            ? [...field.value, role.code]
                            : field.value.filter((code) => code !== role.code);
                          field.onChange(next);
                        }}
                      />
                    }
                    label={
                      <Box>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>{role.name}</Typography>
                        {role.description && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                            {role.description}
                          </Typography>
                        )}
                        {!role.assignable && (
                          <Typography variant="caption" color="warning.main" sx={{ display: "block", fontWeight: 700 }}>
                            {t("Es un rol de organización: sobre una membresía de empresa no concede nada.")}
                          </Typography>
                        )}
                      </Box>
                    }
                  />
                </Paper>
              ))}
            </Box>
          )}
        />
      </Box>
    </FormDrawer>
  );
}
