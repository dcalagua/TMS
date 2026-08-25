import { useState } from "react";
import { Alert, Tab, Tabs } from "@mui/material";
import { PowerRounded, LoginRounded, SendRounded } from "@mui/icons-material";
import { useCompany } from "../../shared/company/CompanyContext";
import { PageHeader } from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { t } from "../../lib/i18n";
import { InboundPanel } from "./InboundPanel";
import { OutboundPanel } from "./OutboundPanel";

type Half = "inbound" | "outbound";

/**
 * El hub de integraciones, en dos mitades.
 *
 * **Entrada**: las credenciales de máquina con las que se autentican los socios, y la bandeja de
 * lo que mandaron. **Salida**: los destinos a los que se empujan los eventos de esta empresa, y
 * el registro de lo que se entregó.
 *
 * Son una sola pantalla porque son una sola pregunta —"¿qué hay conectado a nosotros y está
 * funcionando?"— y dos recursos del backend, con dos permisos, porque fallan en direcciones
 * opuestas: una credencial es una forma de entrar, una suscripción es una forma de salir.
 */
export function IntegrationsPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManageInbound = hasPermission("integration.client:manage");
  const canManageOutbound = hasPermission("integration.webhook:manage");
  const canReadOutbound = canManageOutbound || hasPermission("integration.webhook:read");

  const [half, setHalf] = useState<Half>("inbound");

  return (
    <>
      <PageHeader
        icon={<PowerRounded />}
        tint={ICON_TINTS["/settings/integrations"]}
        title={t("Integraciones")}
        subtitle={t("Qué hay conectado a esta empresa, en las dos direcciones.")}
      />

      <Tabs
        value={half}
        onChange={(_e, value: Half) => setHalf(value)}
        sx={{ mb: 3, borderBottom: "1px solid", borderColor: "divider" }}
      >
        <Tab value="inbound" label={t("Entrada")} icon={<LoginRounded />} iconPosition="start" sx={{ minHeight: 48 }} />
        <Tab value="outbound" label={t("Salida")} icon={<SendRounded />} iconPosition="start" sx={{ minHeight: 48 }} />
      </Tabs>

      {half === "inbound" && <InboundPanel companyId={companyId} canManage={canManageInbound} />}

      {half === "outbound" && (
        canReadOutbound
          ? <OutboundPanel companyId={companyId} canManage={canManageOutbound} />
          : (
            // Las dos mitades tienen permisos distintos a propósito: quien puede emitir una
            // credencial no necesariamente puede configurar a dónde salen los eventos.
            <Alert severity="info">{t("No disponible para tu cuenta.")}</Alert>
          )
      )}
    </>
  );
}
