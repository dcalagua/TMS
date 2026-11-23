import { useState, type ReactNode } from "react";
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Chip, IconButton, Tooltip, Typography,
} from "@mui/material";
import {
  CheckRounded, ContentCopyRounded, ExpandMoreRounded, MenuBookRounded,
} from "@mui/icons-material";
import type { IntegrationClientView, WebhookSubscriptionView } from "../../shared/api/integrationsApi";
import { FormDrawer } from "../../shared/ui/components";
import { DetailSection, KeyFacts } from "../../shared/ui/components/DetailLayout";
import { R } from "../../theme";
import { t } from "../../lib/i18n";
import {
  COMMON_ERRORS, EVENT_ORDER, SCOPE_ORDER, WEBHOOK_RESPONSE_RULES,
  eventDoc, integrationBaseUrl, orderedByCatalogue, pingExchange, renderJson, renderRequest, scopeDoc, statusText,
  webhookEnvelope, webhookHeaders,
  type DocExchange, type DocResponse, type HttpMethod,
} from "./integrationDocs";

const METHOD_COLOR: Record<HttpMethod, "success" | "info" | "warning"> = {
  GET: "success",
  POST: "info",
  PUT: "warning",
};

/** Un bloque de código con su botón de copiar: lo que se pega en Postman o en el código del socio. */
function CodeBlock({ label, text }: { label: ReactNode; text: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // Sin permiso de portapapeles: el texto sigue ahí para seleccionarlo a mano.
    }
  }

  return (
    <Box sx={{ minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
        <Typography variant="caption" sx={{ fontWeight: 700, color: "text.secondary" }}>{label}</Typography>
        <Box sx={{ flex: 1 }} />
        <Tooltip title={copied ? t("Copiado") : t("Copiar")}>
          <IconButton size="small" onClick={() => void copy()} aria-label={t("Copiar")}>
            {copied ? <CheckRounded fontSize="small" color="success" /> : <ContentCopyRounded fontSize="small" />}
          </IconButton>
        </Tooltip>
      </Box>
      <Box
        sx={{
          p: 1.5, maxHeight: 360, overflow: "auto", bgcolor: "action.hover",
          border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`,
        }}
      >
        <Typography
          component="pre"
          sx={{ m: 0, fontFamily: "monospace", fontSize: 12, whiteSpace: "pre-wrap", wordBreak: "break-word" }}
        >
          {text}
        </Typography>
      </Box>
    </Box>
  );
}

function responseLabel(response: DocResponse): string {
  return `${response.status} ${statusText(response.status)} · ${t(response.label)}`;
}

function Notes({ notes }: { notes?: string[] }) {
  if (!notes?.length) return null;
  return (
    <Box component="ul" sx={{ m: 0, pl: 2.5, display: "grid", gap: 0.25 }}>
      {notes.map((note) => (
        <Typography key={note} component="li" variant="caption" color="text.secondary">{t(note)}</Typography>
      ))}
    </Box>
  );
}

/** Una operación: método y ruta, la petición tal como viaja y cada respuesta posible. */
function ExchangeView({ exchange, baseUrl, authorization }: {
  exchange: DocExchange;
  baseUrl: string;
  authorization: string;
}) {
  const headers: Record<string, string> = { Authorization: authorization };
  if (exchange.body !== undefined) headers["Content-Type"] = "application/json";
  Object.assign(headers, exchange.headers);

  return (
    <Box sx={{ display: "grid", gap: 1.25, minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <Chip size="small" color={METHOD_COLOR[exchange.method]} label={exchange.method} sx={{ fontWeight: 800, minWidth: 52 }} />
        <Typography variant="body2" sx={{ fontFamily: "monospace", fontWeight: 600, wordBreak: "break-all" }}>
          {exchange.path.split("?")[0]}
        </Typography>
        <Typography variant="body2" color="text.secondary">· {t(exchange.title)}</Typography>
      </Box>
      <Notes notes={exchange.notes} />
      <CodeBlock
        label={t("Solicitud")}
        text={renderRequest(exchange.method, `${baseUrl}${exchange.path}`, headers, exchange.body)}
      />
      {exchange.responses.map((response) => (
        <CodeBlock
          key={response.status}
          label={`${t("Respuesta")} ${responseLabel(response)}`}
          text={response.body === undefined ? "" : renderJson(response.body)}
        />
      ))}
    </Box>
  );
}

function DocAccordion({ title, subtitle, defaultExpanded = false, children }: {
  title: ReactNode;
  subtitle?: string;
  defaultExpanded?: boolean;
  children: ReactNode;
}) {
  return (
    <Accordion
      defaultExpanded={defaultExpanded}
      disableGutters
      variant="outlined"
      slotProps={{ transition: { unmountOnExit: true } }}
      sx={{ borderRadius: `${R.md}px`, "&:before": { display: "none" } }}
    >
      <AccordionSummary expandIcon={<ExpandMoreRounded />}>
        <Box sx={{ minWidth: 0 }}>
          {title}
          {subtitle && (
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.25 }}>
              {subtitle}
            </Typography>
          )}
        </Box>
      </AccordionSummary>
      <AccordionDetails sx={{ display: "grid", gap: 2.5, pt: 0 }}>{children}</AccordionDetails>
    </Accordion>
  );
}

function CodeTitle({ children }: { children: string }) {
  return (
    <Typography variant="body2" sx={{ fontFamily: "monospace", fontWeight: 700, wordBreak: "break-all" }}>
      {children}
    </Typography>
  );
}

function CloseFooter({ onClose }: { onClose: () => void }) {
  return (
    <Button variant="outlined" color="inherit" onClick={onClose} sx={{ borderColor: "divider" }}>{t("Cerrar")}</Button>
  );
}

/**
 * «Documentación» de una credencial de entrada: qué puede llamar el socio con ella, alcance por
 * alcance, con la petición y la respuesta de cada operación.
 *
 * Muestra los alcances que **tiene** la credencial, no el catálogo entero: es la hoja que se le
 * pasa al socio que va a usarla. El clientId es el real (es la mitad pública); el secreto nunca —
 * solo se ve una vez, al emitirlo o rotarlo.
 */
export function CredentialDocsDrawer({ client, onClose }: { client: IntegrationClientView; onClose: () => void }) {
  const baseUrl = integrationBaseUrl();
  const authorization = `Bearer ${client.clientId}.<${t("secreto")}>`;
  const scopes = orderedByCatalogue(client.scopes, SCOPE_ORDER);

  return (
    <FormDrawer
      open
      icon={<MenuBookRounded />}
      title={t("Documentación")}
      subtitle={client.name}
      size="xl"
      onClose={onClose}
      footer={<CloseFooter onClose={onClose} />}
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        {!client.active && (
          <Alert severity="warning">{t("Esta credencial está revocada: cualquier llamada con ella responde 401.")}</Alert>
        )}

        <KeyFacts columns={2} items={[
          { label: t("URL base"), value: <CodeTitle>{baseUrl}</CodeTitle>, span: true },
          { label: t("Autenticación"), value: <CodeTitle>{`Authorization: ${authorization}`}</CodeTitle>, span: true },
          { label: t("Formato"), value: "application/json" },
          { label: t("Idempotencia"), value: t("Idempotency-Key opcional, 8 a 128 caracteres") },
        ]} />

        <DetailSection title={t("Conexión")}>
          <DocAccordion title={<Typography variant="body2" sx={{ fontWeight: 700 }}>{t("Comprobar la credencial")}</Typography>}>
            <ExchangeView exchange={pingExchange(client.clientId, client.name, client.scopes)} baseUrl={baseUrl} authorization={authorization} />
          </DocAccordion>
        </DetailSection>

        <DetailSection title={t("Alcances")}>
          <Box sx={{ display: "grid", gap: 1 }}>
            {scopes.map((code, index) => {
              const doc = scopeDoc(code);
              return (
                <DocAccordion
                  key={code}
                  title={<CodeTitle>{code}</CodeTitle>}
                  subtitle={doc ? t(doc.summary) : undefined}
                  defaultExpanded={index === 0}
                >
                  {doc
                    ? doc.exchanges.map((exchange) => (
                      <ExchangeView key={exchange.key} exchange={exchange} baseUrl={baseUrl} authorization={authorization} />
                    ))
                    : <Alert severity="info">{t("Sin ejemplo documentado para este alcance todavía.")}</Alert>}
                </DocAccordion>
              );
            })}
          </Box>
        </DetailSection>

        <DetailSection title={t("Errores comunes")}>
          <DocAccordion
            title={<Typography variant="body2" sx={{ fontWeight: 700 }}>{t("Formato de error (RFC 9457)")}</Typography>}
            subtitle={t("Decide por code, nunca por detail. 400, 403, 404 y 409 no se reintentan; 5xx sí, con la misma Idempotency-Key.")}
          >
            {COMMON_ERRORS.map((error) => (
              <CodeBlock key={error.status} label={responseLabel(error)} text={renderJson(error.body)} />
            ))}
          </DocAccordion>
        </DetailSection>
      </Box>
    </FormDrawer>
  );
}

const SAMPLE_EVENT_ID = "3f0a0e2c-9c4b-4b1e-9a6f-0f6b1c2d3e4a";

const SIGNATURE_CHECK = `// X-TMS-Signature: t=<segundos unix>,v1=<hex>
firma = hex(HMAC_SHA256(secreto, t + "." + cuerpoCrudo))

1. Lee t y v1 de la cabecera.
2. Rechaza si t se aleja más de 5 minutos de tu reloj.
3. Compara firma con v1 en tiempo constante.
4. Solo entonces parsea el JSON.`;

/**
 * «Documentación» de una suscripción de salida: qué le llega a su URL por cada evento que tiene
 * seleccionado — la petición exacta que hace TMS, la respuesta que espera — y qué tiene que consultar
 * el receptor después, porque el sobre no lleva detalle de negocio a propósito.
 */
export function SubscriptionDocsDrawer({ subscription, onClose }: {
  subscription: WebhookSubscriptionView;
  onClose: () => void;
}) {
  const integrationUrl = integrationBaseUrl();
  const followUpAuthorization = `Bearer tmsc_<${t("clientId")}>.<${t("secreto")}>`;
  const events = orderedByCatalogue(subscription.eventTypes, EVENT_ORDER);

  return (
    <FormDrawer
      open
      icon={<MenuBookRounded />}
      title={t("Documentación")}
      subtitle={subscription.name}
      size="xl"
      onClose={onClose}
      footer={<CloseFooter onClose={onClose} />}
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        <KeyFacts columns={2} items={[
          { label: t("URL destino"), value: <CodeTitle>{subscription.targetUrl}</CodeTitle>, span: true },
          { label: t("Método"), value: "POST" },
          { label: t("Firma"), value: <CodeTitle>X-TMS-Signature: t=…,v1=…</CodeTitle> },
          { label: t("Deduplicación"), value: t("Por X-TMS-Event-Id: la entrega es al menos una vez") },
          { label: t("Secreto"), value: <CodeTitle>{`···${subscription.secretHint}`}</CodeTitle> },
        ]} />

        <DetailSection title={t("Eventos")}>
          <Box sx={{ display: "grid", gap: 1 }}>
            {events.map((type, index) => {
              const doc = eventDoc(type);
              return (
                <DocAccordion
                  key={type}
                  title={<CodeTitle>{type}</CodeTitle>}
                  subtitle={doc ? t(doc.summary) : undefined}
                  defaultExpanded={index === 0}
                >
                  {doc ? (
                    <>
                      <Notes notes={doc.notes} />
                      <CodeBlock
                        label={t("Lo que envía TMS")}
                        text={renderRequest(
                          "POST", subscription.targetUrl, webhookHeaders(doc.type, SAMPLE_EVENT_ID),
                          webhookEnvelope(doc, SAMPLE_EVENT_ID),
                        )}
                      />
                      <CodeBlock label={t("Respuesta esperada")} text={t("HTTP/1.1 200 OK\n\n(cualquier 2xx; el cuerpo se descarta)")} />
                      {doc.followUp && (
                        <Box sx={{ display: "grid", gap: 1 }}>
                          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                            <Typography variant="caption" sx={{ fontWeight: 700, color: "text.secondary" }}>
                              {t("Después, el receptor consulta (API de integración, alcance)")}
                            </Typography>
                            <Chip size="small" variant="outlined" label={doc.followUp.scope} sx={{ fontFamily: "monospace" }} />
                          </Box>
                          <ExchangeView exchange={doc.followUp.exchange} baseUrl={integrationUrl} authorization={followUpAuthorization} />
                        </Box>
                      )}
                    </>
                  ) : (
                    <Alert severity="info">{t("Sin ejemplo documentado para este evento todavía.")}</Alert>
                  )}
                </DocAccordion>
              );
            })}
          </Box>
        </DetailSection>

        <DetailSection title={t("Firma y reintentos")}>
          <Box sx={{ display: "grid", gap: 1 }}>
            <DocAccordion title={<Typography variant="body2" sx={{ fontWeight: 700 }}>{t("Verificar la firma")}</Typography>}>
              <CodeBlock label={t("HMAC-SHA-256 sobre el cuerpo tal como llegó")} text={t(SIGNATURE_CHECK)} />
            </DocAccordion>
            <DocAccordion title={<Typography variant="body2" sx={{ fontWeight: 700 }}>{t("Qué hace TMS con tu respuesta")}</Typography>}>
              <Box sx={{ display: "grid", gap: 0.75 }}>
                {WEBHOOK_RESPONSE_RULES.map((rule) => (
                  <Box key={rule.response} sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "260px 1fr" }, gap: 1 }}>
                    <Typography variant="body2" sx={{ fontFamily: "monospace", fontWeight: 600 }}>{t(rule.response)}</Typography>
                    <Typography variant="body2" color="text.secondary">{t(rule.outcome)}</Typography>
                  </Box>
                ))}
              </Box>
            </DocAccordion>
          </Box>
        </DetailSection>
      </Box>
    </FormDrawer>
  );
}
