import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import {
  Alert, Box, Button, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from "@mui/material";
import {
  CheckRounded, CloseRounded, EventRounded, PauseCircleOutlineRounded, PaymentsRounded, PlayCircleOutlineRounded,
  PostAddRounded,
} from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import {
  confirmPaymentRequest, fetchPayable, holdPayable, rejectPaymentRequest, releasePayable, requestPayment,
  schedulePayable,
  type PayableView, type PaymentRequestInput, type PaymentRequestView,
} from "../../shared/api/payablesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError, describePaymentError } from "../../shared/api/problemMessages";
import { DetailSection, FormDrawer, KeyFacts, StatusChip } from "../../shared/ui/components";
import { INVOICE_STATUS_TONE } from "../../shared/ui/statusTones";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal } from "../../lib/locale";
import { Money } from "./Money";
import { dueLabel } from "./payableUi";

/** Hoy como `YYYY-MM-DD` en el reloj del navegador: sólo un valor inicial del formulario. */
function today(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}

/** Un importe que puede no existir: el guion es la respuesta honesta, no un cero. */
function optionalMoney(value: number | null, currency: string) {
  return value === null ? null : <Money value={value} currency={currency} />;
}

/** Quién: el id de usuario tal cual. Un pago de la integración no tiene persona: lo dice su origen. */
function who(id: string | null): string {
  return id ?? "—";
}

type Mode = "none" | "schedule" | "payment";

interface PayableDrawerProps {
  companyId: string;
  invoiceId: string;
  onClose: () => void;
  onChanged: () => void;
}

/**
 * Una cuenta por pagar: cuánto se debe, cuándo vence, qué se pagó y qué espera confirmación.
 *
 * TMS no mueve dinero. Lo pagado llega del ERP por integración, o lo transcribe una persona; en
 * ese caso es una **solicitud** que no cuenta hasta que otra persona la confirma. Las acciones se
 * ofrecen sólo si el servidor las permite (`allowedActions`) **y** quien mira tiene el permiso;
 * esconder es UX, el servidor vuelve a comprobarlo todo.
 */
export function PayableDrawer({ companyId, invoiceId, onClose, onChanged }: PayableDrawerProps) {
  const { hasCapability } = useCompany();
  const canRecord = hasCapability(CAPABILITY.PAYMENT_RECORD);
  const canConfirm = hasCapability(CAPABILITY.PAYMENT_CONFIRM);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<Mode>("none");

  const payableQuery = useQuery({
    queryKey: ["settlement-payable", companyId, invoiceId],
    queryFn: ({ signal }) => fetchPayable(companyId, invoiceId, signal),
  });
  const payable = payableQuery.data;
  const allowed = (action: PayableView["allowedActions"][number]) =>
    canRecord && Boolean(payable?.allowedActions.includes(action));

  /** Corre una acción; un rechazo del servidor se muestra con su `detail`, que dice qué hacer. */
  async function run(action: () => Promise<PayableView>, success: string): Promise<boolean> {
    setBusy(true);
    try {
      await action();
      notifySuccess(success);
      await payableQuery.refetch();
      onChanged();
      return true;
    } catch (error) {
      notifyError(t("No se pudo completar"), describePaymentError(error as ApiError));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function hold() {
    const reason = await promptDialog({
      title: t("¿Retener el pago de esta factura?"),
      text: t("La factura queda en espera y no aparece como pagable hasta que alguien la libere. Queda tu motivo."),
      inputLabel: t("Motivo"), required: true, maxLength: 500, confirmLabel: t("Retener"), dangerous: true,
    });
    if (reason === null) return;
    await run(() => holdPayable(companyId, invoiceId, reason), t("Factura retenida"));
  }

  async function release() {
    const reason = await promptDialog({
      title: t("¿Liberar la retención?"),
      text: t("La factura vuelve a estar pagable. Queda tu motivo."),
      inputLabel: t("Motivo"), required: true, maxLength: 500, confirmLabel: t("Liberar"),
    });
    if (reason === null) return;
    await run(() => releasePayable(companyId, invoiceId, reason), t("Retención liberada"));
  }

  async function confirmRequest(request: PaymentRequestView) {
    const ok = await confirmDialog({
      title: t("¿Confirmar el pago?"),
      text: t("Se aplica el pago {{reference}} de {{amount}} {{currency}} del {{date}}. Confirmas que el ERP lo pagó así; quien lo registró no puede confirmarlo.", {
        reference: request.paymentReference, amount: fmtDecimal(request.amount, 2),
        currency: request.currency, date: fmtDate(request.paidOn),
      }),
      confirmLabel: t("Confirmar pago"),
    });
    if (!ok) return;
    await run(() => confirmPaymentRequest(companyId, invoiceId, request.id), t("Pago confirmado"));
  }

  async function rejectRequest(request: PaymentRequestView) {
    const comment = await promptDialog({
      title: t("¿Rechazar la solicitud de pago?"),
      text: t("El pago {{reference}} no se aplica. Quien lo registró verá tu comentario.", { reference: request.paymentReference }),
      inputLabel: t("Comentario"), required: true, maxLength: 500, confirmLabel: t("Rechazar"), dangerous: true,
    });
    if (comment === null) return;
    await run(() => rejectPaymentRequest(companyId, invoiceId, request.id, comment), t("Solicitud rechazada"));
  }

  return (
    <FormDrawer
      open
      size="lg"
      icon={<PaymentsRounded />}
      title={payable ? t("Factura {{number}}", { number: payable.invoiceNumber }) : t("Cuenta por pagar")}
      subtitle={payable?.carrierName ?? undefined}
      titleAdornment={payable
        ? <StatusChip label={enumLabel("invoiceStatus", payable.status)} tone={INVOICE_STATUS_TONE[payable.status]} variant="solid" />
        : undefined}
      loading={payableQuery.isLoading}
      onClose={onClose}
      footer={
        <>
          <Button variant="outlined" color="inherit" onClick={onClose} disabled={busy} sx={{ borderColor: "divider" }}>
            {t("Cerrar")}
          </Button>
          <Box sx={{ flex: 1 }} />
          {allowed("HOLD") && (
            <Button variant="outlined" color="warning" startIcon={<PauseCircleOutlineRounded />} disabled={busy}
              onClick={() => void hold()}>
              {t("Retener")}
            </Button>
          )}
          {allowed("RELEASE") && (
            <Button variant="outlined" startIcon={<PlayCircleOutlineRounded />} disabled={busy}
              onClick={() => void release()}>
              {t("Liberar")}
            </Button>
          )}
          {allowed("SCHEDULE") && (
            <Button variant="outlined" startIcon={<EventRounded />} disabled={busy}
              onClick={() => setMode(mode === "schedule" ? "none" : "schedule")}>
              {t("Programar")}
            </Button>
          )}
          {allowed("REQUEST_PAYMENT") && (
            <Button variant="contained" startIcon={<PostAddRounded />} disabled={busy}
              onClick={() => setMode(mode === "payment" ? "none" : "payment")}>
              {t("Registrar pago")}
            </Button>
          )}
        </>
      }
    >
      {payableQuery.isError && <Alert severity="error">{describeApiError(payableQuery.error as ApiError)}</Alert>}
      {payable && (
        <Box sx={{ display: "grid", gap: 2.5 }}>
          <Alert severity="info" variant="outlined">
            {t("TMS registra lo que el ERP pagó; no mueve dinero.")}
          </Alert>

          {payable.status === "ON_HOLD" && (
            <Alert severity="warning">
              {t("Pago retenido")}{payable.onHoldReason ? `: ${payable.onHoldReason}` : ""}
            </Alert>
          )}

          <KeyFacts columns={4} items={[
            {
              label: t("Vencimiento"),
              value: payable.dueDate ? fmtDate(payable.dueDate) : t("Sin vencimiento"),
              sub: payable.daysOverdue !== null && payable.daysOverdue > 0
                ? <Typography component="span" sx={{ fontSize: "inherit", color: "error.main", fontWeight: 700 }}>
                    {dueLabel(-payable.daysOverdue)}
                  </Typography>
                : payable.paymentTermsDays !== null
                  ? t("Plazo de {{n}} días", { n: payable.paymentTermsDays })
                  : undefined,
            },
            { label: t("Pago programado"), value: payable.scheduledPaymentDate ? fmtDate(payable.scheduledPaymentDate) : null },
            { label: t("Fecha de factura"), value: fmtDate(payable.invoiceDate) },
            { label: t("Tipo"), value: enumLabel("documentType", payable.documentType) },
            { label: t("Neto"), value: <Money value={payable.netAmount} currency={payable.currency} /> },
            {
              label: t("Detracción"),
              value: optionalMoney(payable.detractionAmount, payable.currency),
              sub: payable.detractionAmount === null ? undefined
                : payable.detractionConstancyNumber
                  ? [t("Constancia {{number}}", { number: payable.detractionConstancyNumber }),
                    payable.detractionConstancyDate ? fmtDate(payable.detractionConstancyDate) : null]
                    .filter(Boolean).join(" · ")
                  : t("Constancia pendiente"),
            },
            { label: t("Retención"), value: optionalMoney(payable.retentionAmount, payable.currency) },
            { label: t("A pagar"), value: <Money value={payable.payableAmount} currency={payable.currency} strong /> },
            { label: t("Pagado"), value: <Money value={payable.paidAmount} currency={payable.currency} /> },
            { label: t("Saldo"), value: <Money value={payable.openBalance} currency={payable.currency} strong /> },
          ]} />

          {mode === "schedule" && allowed("SCHEDULE") && (
            <ScheduleForm
              initial={payable.scheduledPaymentDate ?? payable.dueDate ?? today()}
              busy={busy}
              onCancel={() => setMode("none")}
              onSubmit={async (date) => {
                if (await run(() => schedulePayable(companyId, invoiceId, date), t("Pago programado"))) setMode("none");
              }}
            />
          )}

          {mode === "payment" && allowed("REQUEST_PAYMENT") && (
            <PaymentRequestForm
              payable={payable}
              onCancel={() => setMode("none")}
              onSubmit={async (input) => {
                await requestPayment(companyId, invoiceId, input);
                notifySuccess(t("Pago registrado"), t("Queda por confirmar por otra persona."));
                setMode("none");
                await payableQuery.refetch();
                onChanged();
              }}
            />
          )}

          <DetailSection title={t("Pagos")}>
            {payable.payments.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t("Todavía no hay pagos aplicados.")}</Typography>
            ) : (
              <TableContainer>
                <Table size="small" aria-label={t("Pagos")}>
                  <TableHead>
                    <TableRow>
                      <TableCell>{t("Fecha")}</TableCell>
                      <TableCell>{t("Referencia")}</TableCell>
                      <TableCell align="right">{t("Monto")}</TableCell>
                      <TableCell>{t("Detracción")}</TableCell>
                      <TableCell align="right">{t("Retención")}</TableCell>
                      <TableCell>{t("Origen")}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {payable.payments.map((payment) => (
                      <TableRow key={payment.id}>
                        <TableCell>{fmtDate(payment.paidOn)}</TableCell>
                        <TableCell>{payment.paymentReference}</TableCell>
                        <TableCell align="right"><Money value={payment.amount} currency={payment.currency} /></TableCell>
                        <TableCell>
                          {payment.detractionAmount === null ? "—" : `${fmtDecimal(payment.detractionAmount, 2)} ${payment.currency}`}
                          {payment.detractionConstancyNumber && (
                            <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                              {t("Constancia {{number}}", { number: payment.detractionConstancyNumber })}
                              {payment.detractionConstancyDate ? ` · ${fmtDate(payment.detractionConstancyDate)}` : ""}
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell align="right">
                          {payment.retentionAmount === null ? "—" : <Money value={payment.retentionAmount} currency={payment.currency} />}
                        </TableCell>
                        <TableCell>
                          {enumLabel("paymentSource", payment.source)}
                          {payment.source === "OPERATOR" && (
                            <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                              {t("Registró {{a}} · confirmó {{b}}", { a: who(payment.recordedBy), b: who(payment.confirmedBy) })}
                            </Typography>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </DetailSection>

          <DetailSection title={t("Solicitudes de pago")}>
            {payable.requests.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t("Ningún pago manual registrado.")}</Typography>
            ) : (
              <TableContainer>
                <Table size="small" aria-label={t("Solicitudes de pago")}>
                  <TableHead>
                    <TableRow>
                      <TableCell>{t("Fecha de pago")}</TableCell>
                      <TableCell>{t("Referencia")}</TableCell>
                      <TableCell align="right">{t("Monto")}</TableCell>
                      <TableCell>{t("Estado")}</TableCell>
                      <TableCell>{t("Solicitó")}</TableCell>
                      <TableCell>{t("Decisión")}</TableCell>
                      {canConfirm && <TableCell />}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {payable.requests.map((request) => (
                      <TableRow key={request.id}>
                        <TableCell>{fmtDate(request.paidOn)}</TableCell>
                        <TableCell>{request.paymentReference}</TableCell>
                        <TableCell align="right"><Money value={request.amount} currency={request.currency} /></TableCell>
                        <TableCell>
                          <StatusChip
                            label={enumLabel("paymentRequestStatus", request.status)}
                            tone={request.status === "PENDING" ? "inProgress" : request.status === "CONFIRMED" ? "done" : "cancelled"}
                          />
                        </TableCell>
                        <TableCell>
                          {who(request.requestedBy)}
                          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                            {fmtDateTime(request.requestedAt)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          {request.decidedBy ? who(request.decidedBy) : "—"}
                          {request.decidedAt && (
                            <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                              {fmtDateTime(request.decidedAt)}
                            </Typography>
                          )}
                          {request.decisionComment && (
                            <Typography variant="caption" sx={{ display: "block" }}>{request.decisionComment}</Typography>
                          )}
                        </TableCell>
                        {canConfirm && (
                          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                            {request.status === "PENDING" && (
                              <>
                                <Button size="small" color="success" startIcon={<CheckRounded />} disabled={busy}
                                  onClick={() => void confirmRequest(request)}>
                                  {t("Confirmar")}
                                </Button>
                                <Button size="small" color="error" startIcon={<CloseRounded />} disabled={busy}
                                  onClick={() => void rejectRequest(request)}>
                                  {t("Rechazar")}
                                </Button>
                              </>
                            )}
                          </TableCell>
                        )}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </DetailSection>
        </Box>
      )}
    </FormDrawer>
  );
}

function ScheduleForm({ initial, busy, onCancel, onSubmit }: {
  initial: string; busy: boolean; onCancel: () => void; onSubmit: (date: string) => Promise<void>;
}) {
  const [date, setDate] = useState(initial);
  return (
    <DetailSection title={t("Programar pago")}>
      <Box sx={{ display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
        <TextField
          size="small" type="date" label={t("Fecha de pago programada")} value={date} required
          onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }}
        />
        <Button onClick={onCancel} disabled={busy}>{t("Cancelar")}</Button>
        <Button variant="contained" disabled={busy || date === ""} onClick={() => void onSubmit(date)}>
          {t("Guardar fecha")}
        </Button>
      </Box>
    </DetailSection>
  );
}

interface PaymentFormValues {
  paymentReference: string;
  paidOn: string;
  amount: string;
  detractionConstancyNumber: string;
  detractionConstancyDate: string;
  detractionAmount: string;
  retentionAmount: string;
}

/** Un número opcional del formulario: vacío es «no aplica», nunca cero. */
function optionalNumber(value: string): number | null {
  return value.trim() === "" ? null : Number(value);
}

/**
 * El «hacedor» de un pago manual con cuatro ojos: transcribe lo que el ERP pagó. No paga - queda
 * como solicitud hasta que otra persona la confirme. La moneda es la de la factura y no se elige.
 */
export function PaymentRequestForm({ payable, onCancel, onSubmit }: {
  payable: PayableView; onCancel: () => void; onSubmit: (input: PaymentRequestInput) => Promise<void>;
}) {
  const [values, setValues] = useState<PaymentFormValues>({
    paymentReference: "",
    paidOn: today(),
    amount: payable.openBalance > 0 ? String(payable.openBalance) : "",
    detractionConstancyNumber: "",
    detractionConstancyDate: "",
    detractionAmount: "",
    retentionAmount: "",
  });
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const change = (next: Partial<PaymentFormValues>) => setValues((current) => ({ ...current, ...next }));

  const amount = Number(values.amount);
  const optionalInvalid = [values.detractionAmount, values.retentionAmount]
    .some((value) => value.trim() !== "" && !(Number(value) >= 0));
  const valid = values.paymentReference.trim() !== "" && values.paidOn !== ""
    && values.amount.trim() !== "" && Number.isFinite(amount) && amount > 0 && !optionalInvalid;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    setFormError(null);
    setSubmitting(true);
    try {
      await onSubmit({
        paymentReference: values.paymentReference.trim(),
        paidOn: values.paidOn,
        amount,
        currency: payable.currency,
        detractionConstancyNumber: values.detractionConstancyNumber.trim() || null,
        detractionConstancyDate: values.detractionConstancyDate || null,
        detractionAmount: optionalNumber(values.detractionAmount),
        retentionAmount: optionalNumber(values.retentionAmount),
      });
    } catch (error) {
      // El servidor dice la cifra exacta: el saldo que se excede, la moneda que no casa.
      setFormError(describePaymentError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DetailSection title={t("Registrar pago")}>
      <Box component="form" aria-label={t("Registrar pago")} onSubmit={(e) => void submit(e)} noValidate
        sx={{ display: "grid", gap: 1.5 }}>
        <Alert severity="info">
          {t("Transcribe el pago que hizo el ERP. Queda como solicitud: no cuenta hasta que otra persona con permiso de confirmar lo revise.")}
        </Alert>
        {formError && <Alert severity="error">{formError}</Alert>}
        <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", md: "repeat(4, minmax(0, 1fr))" } }}>
          <TextField size="small" required label={t("Referencia de pago")} value={values.paymentReference}
            onChange={(e) => change({ paymentReference: e.target.value })} slotProps={{ htmlInput: { maxLength: 100 } }} />
          <TextField size="small" required type="date" label={t("Fecha de pago")} value={values.paidOn}
            onChange={(e) => change({ paidOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" required type="number" label={t("Monto")} value={values.amount}
            onChange={(e) => change({ amount: e.target.value })}
            helperText={t("Saldo: {{amount}} {{currency}}", { amount: fmtDecimal(payable.openBalance, 2), currency: payable.currency })}
            slotProps={{ htmlInput: { min: 0, step: "0.01" } }} />
          <TextField size="small" label={t("Moneda")} value={payable.currency}
            helperText={t("La de la factura")} slotProps={{ input: { readOnly: true } }} />
          <TextField size="small" label={t("Constancia de detracción nº")} value={values.detractionConstancyNumber}
            onChange={(e) => change({ detractionConstancyNumber: e.target.value })} slotProps={{ htmlInput: { maxLength: 40 } }} />
          <TextField size="small" type="date" label={t("Fecha de constancia")} value={values.detractionConstancyDate}
            onChange={(e) => change({ detractionConstancyDate: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="number" label={t("Monto de detracción")} value={values.detractionAmount}
            onChange={(e) => change({ detractionAmount: e.target.value })} slotProps={{ htmlInput: { min: 0, step: "0.01" } }} />
          <TextField size="small" type="number" label={t("Retención")} value={values.retentionAmount}
            onChange={(e) => change({ retentionAmount: e.target.value })} slotProps={{ htmlInput: { min: 0, step: "0.01" } }} />
        </Box>
        <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end" }}>
          <Button onClick={onCancel} disabled={submitting}>{t("Cancelar")}</Button>
          <Button type="submit" variant="contained" disabled={!valid || submitting}>
            {submitting ? t("Guardando...") : t("Enviar a confirmar")}
          </Button>
        </Box>
      </Box>
    </DetailSection>
  );
}
