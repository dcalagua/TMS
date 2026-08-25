import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Chip, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import { HistoryRounded, PersonRounded, MemoryRounded } from "@mui/icons-material";
import {
  AUDIT_ACTIONS, AUDIT_AGGREGATE_TYPES, fetchAuditEvents,
  type AuditAction, type AuditAggregateType, type AuditEventView,
} from "../../shared/api/auditApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  DataTable, PageHeader, Pagination, Toolbar, type DataTableColumn,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

const PAGE_SIZE = 25;

interface AppliedFilters {
  aggregateType: AuditAggregateType | "";
  action: AuditAction | "";
  aggregateId: string;
  correlationId: string;
  from: string;
  to: string;
}

const DEFAULT_FILTERS: AppliedFilters = {
  aggregateType: "", action: "", aggregateId: "", correlationId: "", from: "", to: "",
};

/**
 * El rastro de auditoría: quién hizo qué, y a qué.
 *
 * Solo lectura, y no por decisión de esta pantalla: las entradas las escribe el backend como
 * efecto colateral de las acciones que describen, y la tabla rechaza UPDATE y DELETE al rol de
 * ejecución. Un cliente capaz de corregir el rastro lo convertiría en evidencia de nada.
 *
 * Detrás de su propio permiso, más estricto que el del resto de Administración: el rastro nombra
 * a colegas, y verlo no es lo mismo que administrar la empresa.
 *
 * El `correlationId` es el filtro que de verdad se usa cuando algo se investiga: es lo que une
 * todo lo que pasó en una misma petición, incluidas las cinco escrituras que provocó una sola
 * importación.
 */
export function AuditPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS);

  const eventsQuery = useQuery({
    queryKey: ["audit-events", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchAuditEvents({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "occurredAt,desc",
        aggregateType: filters.aggregateType || undefined,
        action: filters.action || undefined,
        aggregateId: filters.aggregateId || undefined,
        correlationId: filters.correlationId || undefined,
        // El backend rechaza una ventana que termine antes de empezar; se manda tal cual y se
        // deja que lo diga él, en lugar de duplicar aquí la regla.
        from: filters.from ? new Date(filters.from).toISOString() : undefined,
        to: filters.to ? new Date(filters.to).toISOString() : undefined,
        signal,
      }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
  });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(DEFAULT_FILTERS); setFilters(DEFAULT_FILTERS); setPage(0); }

  const columns: DataTableColumn<AuditEventView>[] = [
    { key: "when", header: t("Cuándo"), render: (event) => fmtDateTime(event.occurredAt) },
    {
      key: "actor",
      header: t("Quién"),
      // Persona o máquina, nunca las dos: si ninguna está, el actor no se registró, que es algo
      // que las entradas antiguas pueden tener.
      render: (event) => {
        if (event.actorEmail) {
          return (
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
              <PersonRounded sx={{ fontSize: 16, color: "text.disabled" }} />
              <Typography variant="body2">{event.actorEmail}</Typography>
            </Box>
          );
        }
        if (event.actorMachineLabel) {
          return (
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
              <MemoryRounded sx={{ fontSize: 16, color: "text.disabled" }} />
              <Typography variant="body2">{event.actorMachineLabel}</Typography>
              <Chip size="small" variant="outlined" label={t("Máquina")} sx={{ height: 19, fontSize: 10 }} />
            </Box>
          );
        }
        return <Typography variant="body2" color="text.disabled">{t("Sin registrar")}</Typography>;
      },
    },
    { key: "action", header: t("Acción"), render: (event) => enumLabel("auditAction", event.action) },
    {
      key: "aggregate",
      header: t("Sobre qué"),
      render: (event) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {enumLabel("auditAggregateType", event.aggregateType)}
          </Typography>
          <Tooltip title={event.aggregateId}>
            <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block", maxWidth: 180, fontFamily: "monospace" }}>
              {event.aggregateId}
            </Typography>
          </Tooltip>
        </Box>
      ),
    },
    {
      key: "metadata",
      header: t("Detalle"),
      // La anotación llega ya parseada; se pinta como pares clave/valor y no como JSON crudo:
      // quien investiga quiere leerla, no descifrarla.
      render: (event) => {
        const entries = Object.entries(event.metadata).filter(([, value]) => value !== null);
        if (entries.length === 0) return <Typography variant="body2" color="text.disabled">-</Typography>;
        return (
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, maxWidth: 380 }}>
            {entries.map(([key, value]) => (
              <Chip
                key={key}
                size="small"
                variant="outlined"
                label={`${key}: ${value}`}
                sx={{ fontSize: 10.5, height: 20, maxWidth: 220 }}
              />
            ))}
          </Box>
        );
      },
    },
    {
      key: "correlation",
      header: t("Correlación"),
      render: (event) => event.correlationId === null ? "-" : (
        <Tooltip title={t("Filtrar por esta correlación")}>
          <Chip
            size="small"
            label={event.correlationId.slice(0, 8)}
            onClick={() => {
              const next = { ...DEFAULT_FILTERS, correlationId: event.correlationId as string };
              setDraft(next);
              setFilters(next);
              setPage(0);
            }}
            sx={{ fontFamily: "monospace", fontSize: 10.5 }}
          />
        </Tooltip>
      ),
    },
  ];

  const pageData = eventsQuery.data;

  return (
    <>
      <PageHeader
        icon={<HistoryRounded />}
        tint={ICON_TINTS["/security/audit"]}
        title={t("Auditoría")}
        subtitle={t("Quién hizo qué, y a qué. Solo lectura: el rastro no se corrige.")}
        onRefresh={() => void eventsQuery.refetch()}
        refreshing={eventsQuery.isFetching}
      />

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        filters={
          <>
            <TextField
              select size="small" label={t("Sobre qué")} value={draft.aggregateType}
              onChange={(e) => setDraft({ ...draft, aggregateType: e.target.value as AuditAggregateType | "" })}
              sx={{ minWidth: 200 }}
            >
              <MenuItem value="">{t("Todos")}</MenuItem>
              {AUDIT_AGGREGATE_TYPES.map((type) => (
                <MenuItem key={type} value={type}>{enumLabel("auditAggregateType", type)}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Acción")} value={draft.action}
              onChange={(e) => setDraft({ ...draft, action: e.target.value as AuditAction | "" })}
              sx={{ minWidth: 200 }}
            >
              <MenuItem value="">{t("Todas")}</MenuItem>
              {AUDIT_ACTIONS.map((action) => (
                <MenuItem key={action} value={action}>{enumLabel("auditAction", action)}</MenuItem>
              ))}
            </TextField>
            <TextField
              size="small" label={t("ID del registro")} value={draft.aggregateId}
              onChange={(e) => setDraft({ ...draft, aggregateId: e.target.value })}
              sx={{ minWidth: 200 }}
            />
            <TextField
              size="small" label={t("Correlación")} value={draft.correlationId}
              onChange={(e) => setDraft({ ...draft, correlationId: e.target.value })}
              sx={{ minWidth: 200 }}
            />
            <TextField
              size="small" type="datetime-local" label={t("Desde")} value={draft.from}
              onChange={(e) => setDraft({ ...draft, from: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ minWidth: 210 }}
            />
            <TextField
              size="small" type="datetime-local" label={t("Hasta")} value={draft.to}
              onChange={(e) => setDraft({ ...draft, to: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ minWidth: 210 }}
            />
          </>
        }
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(event) => event.id}
        isLoading={eventsQuery.isPending}
        error={eventsQuery.isError ? describeApiError(eventsQuery.error as ApiError) : null}
        onRetry={() => void eventsQuery.refetch()}
        emptyTitle={t("Sin eventos")}
        emptyMessage={t("Ningún evento coincide con los filtros seleccionados.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />
    </>
  );
}
