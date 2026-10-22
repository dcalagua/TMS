import { useQuery } from "@tanstack/react-query";
import { useRef, useState, type FormEvent } from "react";
import {
  Alert, Box, Button, MenuItem, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from "@mui/material";
import {
  AttachFileRounded, BlockRounded, CheckRounded, DownloadRounded, EditRounded, GavelRounded, SendRounded, TaskAltRounded,
} from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import {
  attachClaimEvidence, cancelClaim, closeClaim, decideClaim, downloadClaimEvidence, fetchClaim, submitClaim,
  RECOVERY_METHODS, type ClaimDetail, type ClaimEvidenceView, type ClaimResolution, type RecoveryMethod,
} from "../../shared/api/claimsApi";
import { fetchInvoices } from "../../shared/api/settlementApi";
import { saveDownloadedFile, type ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { DetailSection, FormDrawer, KeyFacts, StatusChip } from "../../shared/ui/components";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal } from "../../lib/locale";
import { Money } from "../settlement/Money";
import { CLAIM_STATUS_TONE, ageLabel } from "./claimUi";

type Mode = "none" | "decide" | "close";

interface ClaimDetailDrawerProps {
  companyId: string;
  claimId: string;
  onClose: () => void;
  /** Algo cambió: la lista y los indicadores se refrescan. */
  onChanged: () => void;
  onEdit: (claim: ClaimDetail) => void;
}

/**
 * Un reclamo de carga (V71): lo que se reclama, la evidencia, la decisión y cómo se recuperó.
 *
 * Las acciones salen de `allowedActions` (el estado) y de las capabilities (quién); los cuatro ojos
 * los dice el servidor y la pantalla los adelanta: quien abrió el reclamo ve por qué no puede
 * aceptarlo antes de intentarlo.
 */
export function ClaimDetailDrawer({ companyId, claimId, onClose, onChanged, onEdit }: ClaimDetailDrawerProps) {
  const { hasCapability } = useCompany();
  const canManage = hasCapability(CAPABILITY.CLAIMS_MANAGE);
  const canDecide = hasCapability(CAPABILITY.CLAIMS_DECIDE);
  const [mode, setMode] = useState<Mode>("none");
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const claimQuery = useQuery({
    queryKey: ["freight-claim", companyId, claimId],
    queryFn: ({ signal }) => fetchClaim(companyId, claimId, signal),
  });
  const claim = claimQuery.data;
  const allowed = (action: ClaimDetail["allowedActions"][number]) => claim?.allowedActions.includes(action) ?? false;

  async function run(action: () => Promise<ClaimDetail>, success: string): Promise<boolean> {
    setBusy(true);
    try {
      await action();
      notifySuccess(success, claim?.summary.claimNumber);
      await claimQuery.refetch();
      onChanged();
      return true;
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function submit() {
    const confirmed = await confirmDialog({
      title: t("¿Enviar el reclamo a revisión?"),
      text: t("Desde aquí queda congelado hasta que otra persona lo decida."),
      confirmLabel: t("Enviar a revisión"),
    });
    if (confirmed) await run(() => submitClaim(companyId, claimId), t("Reclamo enviado a revisión"));
  }

  async function cancel() {
    const reason = await promptDialog({
      title: t("¿Anular el reclamo?"),
      text: t("Un reclamo anulado no se puede reabrir."),
      inputLabel: t("Motivo"),
      required: true,
      maxLength: 500,
      confirmLabel: t("Anular reclamo"),
      dangerous: true,
    });
    if (reason !== null) await run(() => cancelClaim(companyId, claimId, reason), t("Reclamo anulado"));
  }

  async function attach(file: File | undefined) {
    if (!file) return;
    await run(() => attachClaimEvidence(companyId, claimId, file), t("Evidencia adjuntada"));
    if (fileInput.current) fileInput.current.value = "";
  }

  async function download(evidence: ClaimEvidenceView) {
    try {
      const file = await downloadClaimEvidence(companyId, claimId, evidence.id);
      saveDownloadedFile(file, evidence.originalFilename ?? `evidencia-${evidence.id}`);
    } catch (error) {
      notifyError(t("No se pudo descargar"), describeApiError(error as ApiError));
    }
  }

  const summary = claim?.summary;
  return (
    <FormDrawer
      open
      icon={<GavelRounded />}
      title={summary ? summary.claimNumber : t("Reclamo")}
      subtitle={summary ? [summary.carrierName, summary.shipmentNumber, summary.orderNumber].filter(Boolean).join(" · ") : undefined}
      titleAdornment={summary ? (
        <StatusChip label={enumLabel("claimStatus", summary.status)} tone={CLAIM_STATUS_TONE[summary.status]} />
      ) : undefined}
      size="lg"
      loading={claimQuery.isPending}
      onClose={onClose}
      footer={
        <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", justifyContent: "flex-end", width: "100%" }}>
          {claim && canManage && allowed("UPDATE") && (
            <Button startIcon={<EditRounded />} disabled={busy} onClick={() => onEdit(claim)}>{t("Corregir")}</Button>
          )}
          {claim && canManage && allowed("CANCEL") && (
            <Button color="error" startIcon={<BlockRounded />} disabled={busy} onClick={() => void cancel()}>{t("Anular")}</Button>
          )}
          {claim && canManage && allowed("ATTACH_EVIDENCE") && (
            <Button startIcon={<AttachFileRounded />} disabled={busy} onClick={() => fileInput.current?.click()}>
              {t("Adjuntar evidencia")}
            </Button>
          )}
          {claim && canManage && allowed("SUBMIT") && (
            <Button variant="contained" startIcon={<SendRounded />} disabled={busy} onClick={() => void submit()}>
              {t("Enviar a revisión")}
            </Button>
          )}
          {claim && canDecide && allowed("REJECT") && mode !== "decide" && (
            <Button variant="contained" startIcon={<GavelRounded />} disabled={busy} onClick={() => setMode("decide")}>
              {t("Decidir")}
            </Button>
          )}
          {claim && canManage && allowed("CLOSE") && mode !== "close" && (
            <Button variant="contained" startIcon={<TaskAltRounded />} disabled={busy} onClick={() => setMode("close")}>
              {t("Cerrar reclamo")}
            </Button>
          )}
        </Box>
      }
    >
      <input
        ref={fileInput} type="file" hidden accept="image/*,application/pdf" aria-label={t("Archivo de evidencia")}
        onChange={(event) => void attach(event.target.files?.[0])}
      />
      {claimQuery.isError && <Alert severity="error">{describeApiError(claimQuery.error as ApiError)}</Alert>}
      {claim && summary && (
        <Box sx={{ display: "grid", gap: 2.5 }}>
          <Alert severity="info" variant="outlined">
            {t("TMS no mueve dinero: un reclamo aceptado se recupera con una penalidad en la factura del transportista o con la nota de crédito que él envíe.")}
          </Alert>

          <KeyFacts columns={4} items={[
            { label: t("Tipo"), value: enumLabel("claimType", summary.claimType) },
            { label: t("Fecha del incidente"), value: fmtDate(summary.incidentDate) },
            { label: t("Abierto"), value: fmtDateTime(summary.openedAt), sub: ageLabel(summary.ageDays) },
            { label: t("Abrió"), value: claim.openedByMe ? t("Tú") : t("Otra persona") },
            { label: t("Reclamado"), value: <Money value={summary.claimedAmount} currency={summary.currency} strong /> },
            {
              label: t("Aceptado"),
              value: summary.acceptedAmount === null ? null : <Money value={summary.acceptedAmount} currency={summary.currency} strong />,
            },
            { label: t("Recuperación"), value: summary.recoveryMethod ? enumLabel("recoveryMethod", summary.recoveryMethod) : null },
            {
              label: t("Decisión"),
              value: summary.resolution ? enumLabel("claimResolution", summary.resolution) : null,
              sub: claim.decidedAt ? fmtDateTime(claim.decidedAt) : undefined,
            },
          ]} />

          <DetailSection title={t("Descripción")}>
            <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{claim.description}</Typography>
            {claim.decisionReason && (
              <Typography variant="body2" sx={{ mt: 1 }}>
                <strong>{t("Motivo de la decisión")}:</strong> {claim.decisionReason}
              </Typography>
            )}
            {claim.cancellationReason && (
              <Typography variant="body2" color="error.main" sx={{ mt: 1 }}>
                <strong>{t("Motivo de la anulación")}:</strong> {claim.cancellationReason}
              </Typography>
            )}
            {claim.closeNotes && (
              <Typography variant="body2" sx={{ mt: 1 }}><strong>{t("Al cerrar")}:</strong> {claim.closeNotes}</Typography>
            )}
          </DetailSection>

          {mode === "decide" && canDecide && (
            <DecisionForm
              claim={claim} busy={busy}
              onCancel={() => setMode("none")}
              onSubmit={async (input) => {
                const ok = await run(() => decideClaim(companyId, claimId, input), t("Decisión registrada"));
                if (ok) setMode("none");
              }}
            />
          )}

          {mode === "close" && canManage && (
            <CloseForm
              companyId={companyId} claim={claim} busy={busy}
              onCancel={() => setMode("none")}
              onSubmit={async (input) => {
                const ok = await run(() => closeClaim(companyId, claimId, input), t("Reclamo cerrado"));
                if (ok) setMode("none");
              }}
            />
          )}

          <DetailSection title={t("Líneas")}>
            {claim.lines.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t("El reclamo no detalla líneas.")}</Typography>
            ) : (
              <TableContainer>
                <Table size="small" aria-label={t("Líneas del reclamo")}>
                  <TableHead>
                    <TableRow>
                      <TableCell>#</TableCell>
                      <TableCell>{t("Concepto")}</TableCell>
                      <TableCell>{t("Material")}</TableCell>
                      <TableCell align="right">{t("Cantidad")}</TableCell>
                      <TableCell align="right">{t("Importe")}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {claim.lines.map((line) => (
                      <TableRow key={line.id}>
                        <TableCell>{line.lineNumber}</TableCell>
                        <TableCell>{line.description}</TableCell>
                        <TableCell>{line.materialCode ?? "—"}</TableCell>
                        <TableCell align="right" sx={{ fontVariantNumeric: "tabular-nums" }}>
                          {line.quantity === null ? "—" : `${fmtDecimal(line.quantity, 3)} ${line.uom ?? ""}`.trim()}
                        </TableCell>
                        <TableCell align="right"><Money value={line.amount} currency={summary.currency} /></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </DetailSection>

          <DetailSection title={t("Evidencias")}>
            {claim.evidence.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t("Sin fotos ni documentos adjuntos.")}</Typography>
            ) : (
              <Box sx={{ display: "grid", gap: 0.75 }}>
                {claim.evidence.map((evidence) => (
                  <Box key={evidence.id} sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
                    <Button size="small" startIcon={<DownloadRounded />} onClick={() => void download(evidence)}>
                      {evidence.originalFilename ?? t("Evidencia")}
                    </Button>
                    <Typography variant="caption" color="text.secondary">
                      {[evidence.description, fmtDateTime(evidence.createdAt)].filter(Boolean).join(" · ")}
                    </Typography>
                  </Box>
                ))}
              </Box>
            )}
          </DetailSection>

          <DetailSection title={t("Recuperación")}>
            {claim.penalties.length === 0 && !claim.settlementInvoiceNumber ? (
              <Typography variant="body2" color="text.secondary">
                {summary.recoveryMethod === "PENALTY"
                  ? t("Se propondrá como penalidad al registrar la próxima factura de este transportista.")
                  : summary.recoveryMethod === "CREDIT_NOTE"
                    ? t("Pendiente de la nota de crédito del transportista.")
                    : t("Nada que recuperar todavía.")}
              </Typography>
            ) : (
              <Box sx={{ display: "grid", gap: 0.5 }}>
                {claim.penalties.map((penalty) => (
                  <Typography key={penalty.lineId} variant="body2">
                    {t("Penalidad en la factura {{number}} ({{status}})", {
                      number: penalty.invoiceNumber, status: enumLabel("invoiceStatus", penalty.invoiceStatus),
                    })}: <Money value={penalty.amount} currency={summary.currency} />
                  </Typography>
                ))}
                {claim.settlementInvoiceNumber && (
                  <Typography variant="body2" sx={{ fontWeight: 700 }}>
                    {t("Cerrado con el documento {{number}}", { number: claim.settlementInvoiceNumber })}
                  </Typography>
                )}
              </Box>
            )}
          </DetailSection>
        </Box>
      )}
    </FormDrawer>
  );
}

function DecisionForm({ claim, busy, onCancel, onSubmit }: {
  claim: ClaimDetail;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: { decision: ClaimResolution; acceptedAmount?: number | null; recoveryMethod?: RecoveryMethod | null; reason?: string | null }) => Promise<void>;
}) {
  const [decision, setDecision] = useState<ClaimResolution>(claim.openedByMe ? "REJECTED" : "ACCEPTED");
  const [method, setMethod] = useState<RecoveryMethod>("PENALTY");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const accepting = decision !== "REJECTED";

  async function handle(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (decision === "PARTIALLY_ACCEPTED") {
      const value = Number(amount);
      if (!Number.isFinite(value) || value <= 0 || value >= claim.summary.claimedAmount) {
        setError(t("Un acuerdo parcial acepta más que cero y menos que lo reclamado."));
        return;
      }
    }
    if (decision !== "ACCEPTED" && reason.trim() === "") {
      setError(t("Esta decisión dice por qué."));
      return;
    }
    await onSubmit({
      decision,
      acceptedAmount: decision === "PARTIALLY_ACCEPTED" ? Number(amount) : null,
      recoveryMethod: accepting ? method : null,
      reason: reason.trim() || null,
    });
  }

  return (
    <DetailSection title={t("Decisión")}>
      <Box component="form" onSubmit={(event) => void handle(event)} sx={{ display: "grid", gap: 1.5 }} noValidate>
        {claim.openedByMe && (
          <Alert severity="warning">
            {t("Abriste este reclamo: puedes rechazarlo, pero aceptarlo le corresponde a otra persona.")}
          </Alert>
        )}
        {error && <Alert severity="error">{error}</Alert>}
        <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
          <TextField
            select size="small" label={t("Decisión")} value={decision} sx={{ minWidth: 200 }}
            onChange={(e) => setDecision(e.target.value as ClaimResolution)}
          >
            {(["ACCEPTED", "PARTIALLY_ACCEPTED", "REJECTED"] as const).map((value) => (
              <MenuItem key={value} value={value} disabled={claim.openedByMe && value !== "REJECTED"}>
                {enumLabel("claimResolution", value)}
              </MenuItem>
            ))}
          </TextField>
          {decision === "PARTIALLY_ACCEPTED" && (
            <TextField
              size="small" type="number" label={t("Importe aceptado ({{c}})", { c: claim.summary.currency })}
              value={amount} onChange={(e) => setAmount(e.target.value)} sx={{ minWidth: 180 }}
            />
          )}
          {accepting && (
            <TextField
              select size="small" label={t("Cómo se recupera")} value={method} sx={{ minWidth: 260 }}
              onChange={(e) => setMethod(e.target.value as RecoveryMethod)}
            >
              {RECOVERY_METHODS.map((value) => (
                <MenuItem key={value} value={value}>{enumLabel("recoveryMethod", value)}</MenuItem>
              ))}
            </TextField>
          )}
        </Box>
        <TextField
          size="small" label={t("Motivo")} value={reason} multiline minRows={2}
          required={decision !== "ACCEPTED"} onChange={(e) => setReason(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 500 } }}
        />
        <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end" }}>
          <Button color="inherit" onClick={onCancel} disabled={busy}>{t("Cancelar")}</Button>
          <Button type="submit" variant="contained" startIcon={<CheckRounded />} disabled={busy}>{t("Registrar decisión")}</Button>
        </Box>
      </Box>
    </DetailSection>
  );
}

function CloseForm({ companyId, claim, busy, onCancel, onSubmit }: {
  companyId: string;
  claim: ClaimDetail;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: { settlementInvoiceId?: string | null; notes?: string | null }) => Promise<void>;
}) {
  const recovers = claim.summary.resolution !== "REJECTED";
  const byNote = claim.summary.recoveryMethod === "CREDIT_NOTE";
  const [invoiceId, setInvoiceId] = useState(claim.penalties[0]?.invoiceId ?? "");
  const [notes, setNotes] = useState("");

  // Las notas de crédito del transportista en la moneda del reclamo: lo que puede cerrarlo.
  const notesQuery = useQuery({
    queryKey: ["claim-credit-notes", companyId, claim.summary.carrierId],
    queryFn: ({ signal }) => fetchInvoices(companyId, { carrierId: claim.summary.carrierId, size: 100 }, signal),
    enabled: recovers && byNote,
  });
  const options = byNote
    ? (notesQuery.data?.content ?? [])
        .filter((doc) => doc.documentKind === "CREDIT_NOTE" && doc.status !== "REJECTED" && doc.currency === claim.summary.currency)
        .map((doc) => ({ id: doc.id, label: `${doc.invoiceNumber} · ${fmtDecimal(doc.totalAmount, 2)} ${doc.currency}` }))
    : claim.penalties.map((penalty) => ({ id: penalty.invoiceId, label: penalty.invoiceNumber }));

  return (
    <DetailSection title={t("Cerrar reclamo")}>
      <Box
        component="form" sx={{ display: "grid", gap: 1.5 }} noValidate
        onSubmit={(event) => {
          event.preventDefault();
          void onSubmit({ settlementInvoiceId: recovers ? invoiceId || null : null, notes: notes.trim() || null });
        }}
      >
        {recovers && options.length === 0 && (
          <Alert severity="warning">
            {byNote
              ? t("Todavía no hay una nota de crédito de este transportista en {{c}}. Regístrala en la auditoría de flete y vuelve aquí.", { c: claim.summary.currency })
              : t("Ninguna factura descuenta todavía este reclamo. Se propone como penalidad al registrar la factura del transportista.")}
          </Alert>
        )}
        {recovers && options.length > 0 && (
          <TextField
            select size="small" label={byNote ? t("Nota de crédito") : t("Factura con la penalidad")} value={invoiceId}
            onChange={(e) => setInvoiceId(e.target.value)} required
          >
            {options.map((option) => <MenuItem key={option.id} value={option.id}>{option.label}</MenuItem>)}
          </TextField>
        )}
        <TextField
          size="small" label={t("Notas")} value={notes} onChange={(e) => setNotes(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 500 } }}
        />
        <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end" }}>
          <Button color="inherit" onClick={onCancel} disabled={busy}>{t("Cancelar")}</Button>
          <Button
            type="submit" variant="contained" startIcon={<TaskAltRounded />}
            disabled={busy || (recovers && invoiceId === "")}
          >
            {t("Cerrar reclamo")}
          </Button>
        </Box>
      </Box>
    </DetailSection>
  );
}
