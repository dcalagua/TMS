import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, MenuItem, Paper, TextField, Typography,
} from "@mui/material";
import { CheckRounded, PersonAddRounded, PersonRounded } from "@mui/icons-material";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  fetchAssignableRoles, inviteUser, updateUserProfile, updateUserRoles,
  rolesForMembership, type AdministeredUserView,
} from "../../shared/api/administrationApi";
import { ActiveBadge, FormDrawer, FormRow, FormSection } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { roleDescription, roleLabel } from "../../lib/enums";

const FORM_ID = "user-form";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface UserFormValues {
  email: string;
  fullName: string;
  roleCodes: string[];
  /** `""` = membresía de oficina. Solo se elige al invitar. */
  carrierId: string;
}

interface UserFormDrawerProps {
  companyId: string;
  /** `null` invita a alguien nuevo; si no, edita esta membresía. */
  user: AdministeredUserView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof UserFormValues>(["email", "fullName", "roleCodes", "carrierId"]);

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

  // Solo al invitar: el transportista de una membresía no se cambia después (sería otra persona
  // con otro acceso), así que en edición se enseña y no se pide la lista.
  const carriersQuery = useQuery({
    queryKey: ["carriers-for-user-invite", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "businessName,asc", signal }),
    enabled: !isEdit,
  });

  const {
    register, control, handleSubmit, setError, setValue, getValues,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<UserFormValues>({
    defaultValues: {
      email: user?.email ?? "",
      fullName: user?.fullName ?? "",
      roleCodes: user?.roleCodes ?? [],
      carrierId: user?.carrierId ?? "",
    },
  });

  const carrierId = useWatch({ control, name: "carrierId" });
  const carrierScoped = carrierId !== "";
  const roles = rolesForMembership(rolesQuery.data ?? [], carrierScoped);

  /** Cambiar de oficina a transportista (o al revés) deja fuera los roles de la otra familia. */
  function changeCarrier(next: string) {
    setValue("carrierId", next, { shouldDirty: true });
    const allowed = new Set(rolesForMembership(rolesQuery.data ?? [], next !== "").map((role) => role.code));
    setValue("roleCodes", getValues("roleCodes").filter((code) => allowed.has(code)), { shouldDirty: true });
  }

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
          carrierId: values.carrierId || null,
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
      subtitle={isEdit
        ? [user.fullName, user.email].filter(Boolean).join(" · ")
        : t("Se le da acceso a esta empresa con los roles que elijas.")}
      titleAdornment={isEdit ? <ActiveBadge active={user.membershipActive} /> : undefined}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Invitar persona")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        <FormSection
          title={t("Identidad")}
          help={isEdit
            ? t("Cómo se llama la persona. El correo no se cambia: sería invitar a otra.")
            : t("A quién se invita y cómo se llama.")}
        >
        <FormRow cols={1}>
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
        </FormRow>
        </FormSection>

        <FormSection
          title={t("Transportista")}
          help={t("Solo para usuarios de un transportista: entran al portal y ven únicamente los viajes despachados de ese transportista.")}
        >
          {isEdit ? (
            <Typography variant="body2">
              {user.carrierName ?? (user.carrierId ? user.carrierId : t("Ninguno: acceso de oficina"))}
            </Typography>
          ) : (
            <TextField
              select size="small" fullWidth label={t("Transportista")}
              value={carrierId}
              onChange={(e) => changeCarrier(e.target.value)}
              error={Boolean(errors.carrierId) || carriersQuery.isError}
              helperText={errors.carrierId?.message
                ?? (carriersQuery.isError ? t("No se pudo cargar la lista de transportistas.") : undefined)}
            >
              <MenuItem value="">{t("Ninguno: acceso de oficina")}</MenuItem>
              {(carriersQuery.data?.content ?? []).map((carrier) => (
                <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
              ))}
            </TextField>
          )}
          {carrierScoped && (
            <Alert severity="info" sx={{ mt: 1 }}>
              {t("Solo se ofrecen roles de transportista: un usuario de transportista no puede tener roles de oficina.")}
            </Alert>
          )}
        </FormSection>

        <FormSection title={t("Roles")} help={t("Lo que puede hacer en esta empresa. Al menos uno.")}>
        {user?.organizationWide && (
          <Alert severity="info">
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
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>{roleLabel(role.code, role.name)}</Typography>
                        {roleDescription(role.code, role.description) && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                            {roleDescription(role.code, role.description)}
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
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
