import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Box, Button, Chip, MenuItem, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, TextField, Typography, Paper,
} from "@mui/material";
import {
  AddRounded, UploadRounded, EditRounded, BlockRounded, CheckCircleRounded,
  PlaceRounded, TripOriginRounded, PinDropRounded, MyLocationRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  activateLocation, deactivateLocation, fetchLocations, LOCATION_IMPORT_BASE_PATH,
  LOCATION_ROLES, LOCATION_TYPES,
  type LocationImportPreview, type LocationRole, type LocationType, type LocationView,
} from "../../shared/api/locationsApi";
import { fetchZones } from "../../shared/api/zonesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, FilterChips, ImportDrawer, ImportOutcomeChip,
  PageHeader, Pagination, Toolbar, dataTableSx,
  type DataTableColumn, type FilterChip,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { GeofenceDrawer } from "./GeofenceDrawer";
import { LocationFormDrawer } from "./LocationFormDrawer";

const DEFAULT_PAGE_SIZE = 25;

interface AppliedFilters {
  search: string;
  type: LocationType | "";
  role: LocationRole | "";
  zoneId: string;
  active: ActiveFilter;
}

type ModalState = { mode: "create" } | { mode: "edit"; location: LocationView } | null;

interface LocationsPageProps {
  /**
   * Qué pantalla es esta. Sin valor, es Ubicaciones: el maestro completo. Con valor, es Orígenes
   * o Destinos — el mismo maestro con el filtro de uso operacional clavado, el control de ese
   * filtro escondido porque es la identidad de la pantalla y no una elección, y el drawer
   * abriéndose con ese uso ya marcado.
   */
  view?: LocationRole;
}

/**
 * La pantalla de maestros de lugares físicos y —con `view` puesto— también las de Orígenes y
 * Destinos.
 *
 * Esas tres entradas de menú son un solo componente a propósito. Una tienda que recibe entregas
 * y despacha sus propias devoluciones es un lugar, una dirección, un par de coordenadas;
 * "orígenes" y "destinos" son dos preguntas que se le hacen a ese maestro, no dos maestros.
 * Construirlas como pantallas separadas es lo que produjo los registros duplicados que este
 * cambio de dominio eliminó, y una segunda copia de este fichero volvería a separarlas en una
 * versión.
 *
 * Un solo cuadro de búsqueda en lugar de filtros separados de código y nombre: este es el
 * maestro donde un operador busca algo, y lo busca por lo que recuerde —código, nombre o
 * referencia externa— que es exactamente lo que abarca el parámetro `search` del backend.
 */
export function LocationsPage({ view }: LocationsPageProps = {}) {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("masterdata.location:manage");
  const queryClient = useQueryClient();

  // El rol de la vista es parte del estado en reposo, no algo que aplicó el operador, así que
  // nunca se ofrece como chip que se pueda quitar y reset() lo repone en vez de limpiarlo.
  const defaultFilters: AppliedFilters = {
    search: "", type: "", role: view ?? "", zoneId: "", active: "active",
  };

  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [draft, setDraft] = useState<AppliedFilters>(defaultFilters);
  const [filters, setFilters] = useState<AppliedFilters>(defaultFilters);
  const [modal, setModal] = useState<ModalState>(null);
  const [geofenceFor, setGeofenceFor] = useState<LocationView | null>(null);
  const [showImport, setShowImport] = useState(false);

  const scope = view === "ORIGIN" ? "origins" : view === "DESTINATION" ? "destinations" : "locations";
  const COPY = {
    locations: {
      title: "Ubicaciones",
      description: "Lugares físicos utilizados por la operación: tiendas, almacenes, plantas, hubs y puntos de entrega.",
      neu: "Nueva ubicación",
      emptyTitle: "Sin ubicaciones",
      emptyMessage: "Crea una ubicación o ajusta los filtros.",
      icon: <PlaceRounded />, tint: ICON_TINTS["/masters/locations"],
    },
    origins: {
      title: "Orígenes",
      description: "Ubicaciones habilitadas para despachar. Es la misma ficha de Ubicaciones, filtrada por uso operacional.",
      neu: "Nuevo origen",
      emptyTitle: "Sin orígenes",
      emptyMessage: "Marca «Puede utilizarse como origen» en una ubicación o crea una nueva.",
      icon: <TripOriginRounded />, tint: ICON_TINTS["/masters/origins"],
    },
    destinations: {
      title: "Destinos",
      description: "Ubicaciones habilitadas para recibir entregas. Es la misma ficha de Ubicaciones, filtrada por uso operacional.",
      neu: "Nuevo destino",
      emptyTitle: "Sin destinos",
      emptyMessage: "Marca «Puede utilizarse como destino» en una ubicación o crea una nueva.",
      icon: <PinDropRounded />, tint: ICON_TINTS["/masters/destinations"],
    },
  }[scope];

  const locationsQuery = useQuery({
    // companyId se queda el segundo: refresh() invalida el prefijo ['locations', companyId], y
    // una clave que pusiera la vista antes ya no casaría con ese prefijo — la lista seguiría
    // enseñando lo que enseñaba antes de guardar.
    queryKey: ["locations", companyId, view ?? "all", page, pageSize, filters],
    queryFn: ({ signal }) =>
      fetchLocations({
        companyId,
        page,
        size: pageSize,
        sort: "code,asc",
        search: filters.search || undefined,
        type: filters.type || undefined,
        role: filters.role || undefined,
        zoneId: filters.zoneId || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const zonesQuery = useQuery({
    queryKey: ["zones-for-filter", companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  const zones = zonesQuery.data?.content ?? [];

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(defaultFilters); setFilters(defaultFilters); setPage(0); }

  function clearOne(patch: Partial<AppliedFilters>) {
    const next = { ...filters, ...patch };
    setDraft(next);
    setFilters(next);
    setPage(0);
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["locations", companyId] });
    // Las proyecciones también cambiaron, así que cualquier lista de Orígenes/Destinos que ya
    // esté en la caché está obsoleta. Invalidarlas aquí es lo que impide que las tres pantallas
    // discrepen después de una edición.
    void queryClient.invalidateQueries({ queryKey: ["origins", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["destinations", companyId] });
  }

  async function toggleActive(location: LocationView) {
    const changed = await toggleActiveRecord({
      name: location.name,
      active: location.active,
      activate: () => activateLocation(companyId, location.id),
      deactivate: () => deactivateLocation(companyId, location.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<LocationView>[] = [
    { key: "code", header: t("Código"), render: (l) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{l.code}</Typography> },
    {
      key: "name",
      header: t("Nombre"),
      // Nombre y dirección en una celda: son un solo hecho —qué lugar es este— y la columna de
      // dirección estaría casi siempre vacía por su cuenta.
      render: (l) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.3 }}>{l.name}</Typography>
          {l.address && <Typography variant="caption" color="text.secondary">{l.address}</Typography>}
        </Box>
      ),
    },
    { key: "type", header: t("Tipo"), render: (l) => enumLabel("locationType", l.type) },
    {
      // Una columna, no dos. La pantalla antigua ponía "Roles" al lado de "Utilizable como", que
      // eran el mismo hecho contado dos veces.
      key: "use",
      header: t("Uso operacional"),
      render: (l) =>
        l.roles.length === 0 ? (
          <Typography variant="caption" color="text.disabled">{t("Sin uso definido")}</Typography>
        ) : (
          <Box sx={{ display: "inline-flex", flexWrap: "wrap", gap: 0.5 }}>
            {l.roles.map((role) => (
              <Chip key={role} size="small" variant="outlined" label={enumLabel("locationRole", role)} sx={{ fontWeight: 500 }} />
            ))}
          </Box>
        ),
    },
    { key: "zone", header: t("Zona"), render: (l) => l.zoneName ?? "-" },
    { key: "active", header: t("Estado"), render: (l) => <ActiveBadge active={l.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (location) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", location }) },
            {
              key: "geofence",
              label: t("Geocerco"),
              icon: <MyLocationRounded />,
              onSelect: () => setGeofenceFor(location),
            },
            {
              key: "active",
              label: location.active ? t("Desactivar") : t("Activar"),
              icon: location.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: location.active,
              onSelect: () => void toggleActive(location),
            },
          ]}
        />
      ),
    });
  }

  const pageData = locationsQuery.data;

  /* Qué está estrechando la lista ahora mismo. `active` solo cuenta cuando no es el valor por
     defecto: "Activos" es el estado en reposo de la pantalla, no un filtro que alguien eligió. */
  const activeChips: FilterChip[] = [
    filters.search && {
      key: "search", label: t("Buscar"), value: filters.search,
      onClear: () => clearOne({ search: "" }),
    },
    filters.type && {
      key: "type", label: t("Tipo"), value: enumLabel("locationType", filters.type),
      onClear: () => clearOne({ type: "" }),
    },
    !view && filters.role && {
      key: "role", label: t("Uso operacional"), value: enumLabel("locationRole", filters.role),
      onClear: () => clearOne({ role: "" }),
    },
    filters.zoneId && {
      key: "zone", label: t("Zona"),
      value: zones.find((zone) => zone.id === filters.zoneId)?.name ?? filters.zoneId,
      onClear: () => clearOne({ zoneId: "" }),
    },
    filters.active !== defaultFilters.active && {
      key: "active", label: t("Estado"),
      value: filters.active === "all" ? t("Todos") : t("Inactivos"),
      onClear: () => clearOne({ active: defaultFilters.active }),
    },
  ].filter(Boolean) as FilterChip[];

  const isFiltered = activeChips.length > 0;

  return (
    <>
      <PageHeader
        icon={COPY.icon}
        tint={COPY.tint}
        title={t(COPY.title)}
        subtitle={t(COPY.description)}
        onRefresh={refresh}
        refreshing={locationsQuery.isFetching}
        actions={canManage && (
          <>
            <Button variant="outlined" startIcon={<UploadRounded />} onClick={() => setShowImport(true)}>
              {t("Importar")}
            </Button>
            <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
              {t(COPY.neu)}
            </Button>
          </>
        )}
      />

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        activeFilterCount={activeChips.length}
        filters={
          <>
            <TextField
              size="small" type="search" label={t("Buscar")}
              placeholder={t("Código, nombre o referencia externa")}
              value={draft.search}
              onChange={(e) => setDraft({ ...draft, search: e.target.value })}
              sx={{ minWidth: 240, flex: 1 }}
            />
            <TextField
              select size="small" label={t("Tipo")} value={draft.type}
              onChange={(e) => setDraft({ ...draft, type: e.target.value as LocationType | "" })}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">{t("Todos los tipos")}</MenuItem>
              {LOCATION_TYPES.map((type) => (
                <MenuItem key={type} value={type}>{enumLabel("locationType", type)}</MenuItem>
              ))}
            </TextField>
            {!view && (
              <TextField
                select size="small" label={t("Uso operacional")} value={draft.role}
                onChange={(e) => setDraft({ ...draft, role: e.target.value as LocationRole | "" })}
                sx={{ minWidth: 180 }}
              >
                <MenuItem value="">{t("Cualquier uso")}</MenuItem>
                {LOCATION_ROLES.map((role) => (
                  <MenuItem key={role} value={role}>{enumLabel("locationRole", role)}</MenuItem>
                ))}
              </TextField>
            )}
            <TextField
              select size="small" label={t("Zona")} value={draft.zoneId}
              onChange={(e) => setDraft({ ...draft, zoneId: e.target.value })}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">{t("Todas las zonas")}</MenuItem>
              {zones.map((zone) => (
                <MenuItem key={zone.id} value={zone.id}>{zone.name}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Estado")} value={draft.active}
              onChange={(e) => setDraft({ ...draft, active: e.target.value as ActiveFilter })}
              sx={{ minWidth: 150 }}
            >
              {ACTIVE_FILTER_OPTIONS.map((option) => (
                <MenuItem key={option.value} value={option.value}>{t(option.label)}</MenuItem>
              ))}
            </TextField>
          </>
        }
      />

      <FilterChips chips={activeChips} onClearAll={resetFilters} />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(location) => location.id}
        isLoading={locationsQuery.isPending}
        error={locationsQuery.isError ? describeApiError(locationsQuery.error as ApiError) : null}
        onRetry={() => void locationsQuery.refetch()}
        /* Dos situaciones distintas, dos salidas distintas: aquí nunca se creó nada, o los
           filtros han estrechado hasta dejarlo todo fuera. */
        emptyTitle={isFiltered ? t("Sin resultados") : t(COPY.emptyTitle)}
        emptyMessage={isFiltered ? t("Ningun registro coincide con los filtros seleccionados.") : t(COPY.emptyMessage)}
        emptyAction={
          isFiltered ? (
            <Button size="small" variant="outlined" onClick={resetFilters}>{t("Limpiar")}</Button>
          ) : canManage ? (
            <Button size="small" variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
              {t(COPY.neu)}
            </Button>
          ) : undefined
        }
        footer={pageData ? (
          <Pagination
            page={pageData}
            onPageChange={setPage}
            onPageSizeChange={(size) => {
              setPageSize(size);
              // De vuelta a la primera página: la página 4 del tamaño anterior puede no existir
              // con el nuevo.
              setPage(0);
            }}
          />
        ) : undefined}
      />

      {modal && (
        <LocationFormDrawer
          companyId={companyId}
          location={modal.mode === "edit" ? modal.location : null}
          presetRole={view}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySaved(wasEdit);
            refresh();
          }}
        />
      )}

      {geofenceFor && (
        <GeofenceDrawer
          companyId={companyId}
          location={geofenceFor}
          onClose={() => setGeofenceFor(null)}
          onSaved={() => { setGeofenceFor(null); refresh(); }}
        />
      )}

      {showImport && (
        <ImportDrawer<LocationImportPreview>
          open
          apiBasePath={LOCATION_IMPORT_BASE_PATH}
          companyId={companyId}
          title={t("Importar ubicaciones")}
          subtitle={t("Alta masiva desde una plantilla .xlsx o .csv.")}
          onClose={() => setShowImport(false)}
          onImported={refresh}
          renderItems={(items, outcomeLabel) => (
            <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 340 }}>
              <Table size="small" stickyHeader sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Estado")}</TableCell>
                    <TableCell>{t("Código")}</TableCell>
                    <TableCell>{t("Nombre")}</TableCell>
                    <TableCell>{t("Tipo")}</TableCell>
                    <TableCell>{t("Uso operacional")}</TableCell>
                    <TableCell>{t("Zona")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((item, index) => (
                    <TableRow key={`${item.code}-${index}`}>
                      <TableCell><ImportOutcomeChip outcome={item.outcome} label={outcomeLabel(item.outcome)} /></TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>{item.code}</TableCell>
                      <TableCell>{item.name}</TableCell>
                      <TableCell>{enumLabel("locationType", item.type)}</TableCell>
                      <TableCell>{item.roles.map((role) => enumLabel("locationRole", role)).join(", ")}</TableCell>
                      <TableCell>{item.zoneCode ?? "-"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        />
      )}
    </>
  );
}
