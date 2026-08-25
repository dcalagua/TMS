#!/usr/bin/env node
/*
 * Locale keys for the read-only audit trail screen (Seguridad -> Auditoría).
 *
 * Two vocabularies and one screen. The vocabularies - `auditAggregateType` and `auditAction` -
 * are enum labels, and `enums.test.ts` asserts every value of both has one in both languages;
 * they are here rather than hand-written for the reason every other upsert script exists: two
 * bundles must gain exactly the same key paths, and doing that by hand is how a key ends up in
 * one language only.
 *
 *     node scripts/i18n/upsert_locale_keys_audit_read.js
 *
 * Idempotent: an existing key keeps its current translation and is never overwritten.
 */

const fs = require('fs')
const path = require('path')

const LOCALES = path.join(__dirname, '..', '..', 'frontend', 'tms-web', 'src', 'shared', 'i18n', 'locales')

const KEYS = {
  security: {
    'audit.title': { es: 'Auditoría', en: 'Audit trail' },
    'audit.description': {
      es: 'Quién cambió qué y cuándo, dentro de esta empresa. Es un registro de solo lectura: no puede editarse ni borrarse.',
      en: 'Who changed what, and when, within this company. A read-only record: it cannot be edited or deleted.',
    },
    'audit.noActor': { es: 'Sin actor registrado', en: 'No actor recorded' },
    'audit.machineActor': { es: 'Credencial de integración', en: 'Integration credential' },
    'audit.noDetail': { es: 'Ver', en: 'View' },
    'audit.noDetailHint': {
      es: 'Esta entrada no registró detalles adicionales. La acción y el recurso siguen siendo el hecho.',
      en: 'This entry recorded no further detail. The action and the resource are still the fact.',
    },
    'audit.openDetailNamed': {
      es: 'Ver el detalle de {{action}} del {{when}}',
      en: 'View the detail of {{action}} on {{when}}',
    },
    'audit.columns.when': { es: 'Fecha y hora', en: 'When' },
    'audit.columns.actor': { es: 'Usuario', en: 'Actor' },
    'audit.columns.action': { es: 'Acción', en: 'Action' },
    'audit.columns.resource': { es: 'Recurso', en: 'Resource' },
    'audit.columns.identifier': { es: 'Identificador', en: 'Identifier' },
    'audit.columns.detail': { es: 'Detalle', en: 'Detail' },
    'audit.filters.from': { es: 'Desde', en: 'From' },
    'audit.filters.to': { es: 'Hasta', en: 'To' },
    'audit.filters.allResources': { es: 'Todos los recursos', en: 'All resources' },
    'audit.filters.allActions': { es: 'Todas las acciones', en: 'All actions' },
    'audit.filters.identifierHint': { es: 'Id exacto del recurso', en: 'Exact resource id' },
    'audit.filters.correlationId': { es: 'Correlación', en: 'Correlation' },
    'audit.filters.correlationHint': { es: 'Id de la petición', en: 'Request id' },
    'audit.empty.title': { es: 'Sin movimientos', en: 'Nothing recorded' },
    'audit.empty.message': {
      es: 'No hay entradas para estos filtros. Amplía el rango de fechas o quita algún filtro.',
      en: 'No entries match these filters. Widen the date range or remove a filter.',
    },
  },

  common: {
    'states.readOnly': {
      es: 'Este registro es de solo lectura y no puede modificarse.',
      en: 'This record is read-only and cannot be changed.',
    },
  },

  navigation: {
    'items.audit': { es: 'Auditoría', en: 'Audit trail' },
  },

  statuses: {
    'auditAggregateType.LOCATION': { es: 'Ubicación', en: 'Location' },
    'auditAggregateType.CARRIER': { es: 'Transportista', en: 'Carrier' },
    'auditAggregateType.VEHICLE': { es: 'Vehículo', en: 'Vehicle' },
    'auditAggregateType.DRIVER': { es: 'Conductor', en: 'Driver' },
    'auditAggregateType.TRANSPORT_ORDER': { es: 'Pedido', en: 'Order' },
    'auditAggregateType.TRIP': { es: 'Viaje', en: 'Trip' },
    'auditAggregateType.PLANNING_RUN': { es: 'Corrida de planificación', en: 'Planning run' },
    'auditAggregateType.INTEGRATION_CLIENT': { es: 'Credencial de integración', en: 'Integration credential' },
    'auditAggregateType.MASTER_DATA_IMPORT_BATCH': { es: 'Importación de maestros', en: 'Master data import' },
    'auditAggregateType.ORDER_IMPORT_BATCH': { es: 'Importación de pedidos', en: 'Order import' },
    'auditAggregateType.SHIPMENT': { es: 'Envío', en: 'Shipment' },
    'auditAggregateType.RATE_CARD': { es: 'Tarifa', en: 'Rate card' },
    'auditAggregateType.TRIP_COST': { es: 'Costo del viaje', en: 'Trip cost' },
    'auditAggregateType.COMPANY': { es: 'Empresa', en: 'Company' },
    'auditAggregateType.APP_USER': { es: 'Usuario', en: 'User' },
    'auditAggregateType.MEMBERSHIP': { es: 'Acceso', en: 'Membership' },

    'auditAction.CREATE': { es: 'Creación', en: 'Created' },
    'auditAction.UPDATE': { es: 'Modificación', en: 'Updated' },
    'auditAction.ACTIVATE': { es: 'Activación', en: 'Activated' },
    'auditAction.DEACTIVATE': { es: 'Desactivación', en: 'Deactivated' },
    'auditAction.ASSIGN_ORDER': { es: 'Pedido asignado', en: 'Order assigned' },
    'auditAction.REMOVE_ORDER': { es: 'Pedido retirado', en: 'Order removed' },
    'auditAction.MOVE_ORDER': { es: 'Pedido movido', en: 'Order moved' },
    'auditAction.VEHICLE_CHANGE': { es: 'Cambio de vehículo', en: 'Vehicle changed' },
    'auditAction.DRIVER_CHANGE': { es: 'Cambio de conductor', en: 'Driver changed' },
    'auditAction.CONFIRM': { es: 'Confirmación', en: 'Confirmed' },
    'auditAction.CANCEL': { es: 'Cancelación', en: 'Cancelled' },
    'auditAction.CREDENTIAL_CREATE': { es: 'Credencial emitida', en: 'Credential issued' },
    'auditAction.CREDENTIAL_ROTATE': { es: 'Credencial rotada', en: 'Credential rotated' },
    'auditAction.CREDENTIAL_REVOKE': { es: 'Credencial revocada', en: 'Credential revoked' },
    'auditAction.AUTO_PLAN': { es: 'Planificación automática', en: 'Automatic planning' },
    'auditAction.IMPORT_EXECUTED': { es: 'Importación ejecutada', en: 'Import executed' },
    'auditAction.SHIPMENT_CONFIRMED': { es: 'Envío confirmado', en: 'Shipment confirmed' },
    'auditAction.SHIPMENT_READY': { es: 'Envío listo para despacho', en: 'Shipment ready for dispatch' },
    'auditAction.SHIPMENT_DISPATCHED': { es: 'Envío despachado', en: 'Shipment dispatched' },
    'auditAction.SHIPMENT_COMPLETED': { es: 'Envío completado', en: 'Shipment completed' },
    'auditAction.SHIPMENT_CANCELLED': { es: 'Envío cancelado', en: 'Shipment cancelled' },
    'auditAction.DELIVERY_RESULT_RECORDED': { es: 'Entrega registrada', en: 'Delivery recorded' },
    'auditAction.COST_ESTIMATED': { es: 'Costo estimado', en: 'Cost estimated' },
    'auditAction.COST_ACTUAL_RECORDED': { es: 'Costo real registrado', en: 'Actual cost recorded' },
    'auditAction.COST_CLOSED': { es: 'Costo cerrado', en: 'Cost closed' },
    'auditAction.COST_REOPENED': { es: 'Costo reabierto', en: 'Cost reopened' },
    'auditAction.TENDER_SENT': { es: 'Oferta enviada', en: 'Tender sent' },
    'auditAction.TENDER_ACCEPTED': { es: 'Oferta aceptada', en: 'Tender accepted' },
    'auditAction.TENDER_REJECTED': { es: 'Oferta rechazada', en: 'Tender rejected' },
    'auditAction.TENDER_EXPIRED': { es: 'Oferta vencida', en: 'Tender expired' },
    'auditAction.TENDER_CANCELLED': { es: 'Oferta retirada', en: 'Tender withdrawn' },
  },
}

function setDeep(target, keyPath, value) {
  const parts = keyPath.split('.')
  let node = target
  for (const part of parts.slice(0, -1)) {
    if (typeof node[part] !== 'object' || node[part] === null) {
      node[part] = {}
    }
    node = node[part]
  }
  const leaf = parts[parts.length - 1]
  if (node[leaf] === undefined) {
    node[leaf] = value
    return true
  }
  return false
}

function sorted(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return value
  }
  const out = {}
  for (const key of Object.keys(value).sort()) {
    out[key] = sorted(value[key])
  }
  return out
}

let added = 0
for (const [namespace, keys] of Object.entries(KEYS)) {
  for (const language of ['es', 'en']) {
    const file = path.join(LOCALES, language, `${namespace}.json`)
    const bundle = JSON.parse(fs.readFileSync(file, 'utf8'))
    for (const [keyPath, translations] of Object.entries(keys)) {
      if (setDeep(bundle, keyPath, translations[language])) {
        added++
      }
    }
    fs.writeFileSync(file, `${JSON.stringify(sorted(bundle), null, 2)}\n`, 'utf8')
  }
}

console.log(`upsert_locale_keys_audit_read: ${added} key(s) added across es/en`)
