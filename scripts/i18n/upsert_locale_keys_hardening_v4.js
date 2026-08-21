#!/usr/bin/env node
/*
 * Upserts the locale keys the Sellable V4 pack's screens referenced but never added.
 *
 * The pack shipped the driver feature (migration V26) across fleet, planning and trips, and the
 * proof that its labels were missing is that `npm run typecheck` refused to compile: the i18n key
 * type is generated from the Spanish bundle, so a `t('drivers.title')` with no such key is a
 * compile error rather than a "drivers.title" rendered on a customer's screen. This script closes
 * that gap, plus the two enum vocabularies the pack added values to.
 *
 * Written as a script, and run rather than hand-edited, for the reason every other
 * upsert_locale_keys_*.js in this directory is: the bundles are sorted, both languages must gain
 * exactly the same key paths, and doing that by hand is how a key ends up in one language only.
 *
 *     node scripts/i18n/upsert_locale_keys_hardening_v4.js
 *
 * Idempotent: an existing key keeps its current translation and is never overwritten.
 */

const fs = require('fs')
const path = require('path')

const LOCALES = path.join(__dirname, '..', '..', 'frontend', 'tms-web', 'src', 'shared', 'i18n', 'locales')

/** namespace -> key path -> { es, en } */
const KEYS = {
  fleet: {
    'drivers.title': { es: 'Conductores', en: 'Drivers' },
    'drivers.description': {
      es: 'Personas habilitadas para conducir. Un conductor con licencia vencida no puede asignarse a un viaje.',
      en: 'The people cleared to drive. A driver whose licence has expired cannot be assigned to a trip.',
    },
    'drivers.new': { es: 'Nuevo conductor', en: 'New driver' },
    'drivers.ownStaff': { es: 'Personal propio', en: 'Own staff' },
    'drivers.licenseNoExpiry': { es: 'Sin fecha', en: 'No date' },
    'drivers.columns.name': { es: 'Conductor', en: 'Driver' },
    'drivers.columns.document': { es: 'Documento', en: 'Document' },
    'drivers.columns.license': { es: 'Licencia', en: 'Licence' },
    'drivers.filters.name': { es: 'Nombre', en: 'Name' },
    'drivers.filters.license': { es: 'Licencia', en: 'Licence' },
    'drivers.filters.allLicenseStatuses': { es: 'Todos los estados', en: 'All statuses' },
    'drivers.empty.title': { es: 'Sin conductores', en: 'No drivers' },
    'drivers.empty.message': {
      es: 'Registra a las personas que conducirán las unidades para poder asignarlas a un viaje.',
      en: 'Register the people who will drive your vehicles so they can be assigned to a trip.',
    },
    'drivers.form.create': { es: 'Nuevo conductor', en: 'New driver' },
    'drivers.form.edit': { es: 'Editar conductor', en: 'Edit driver' },
    'drivers.form.subtitle': {
      es: 'Los datos de licencia se validan contra la fecha del viaje, no contra hoy.',
      en: 'Licence details are checked against the trip date, not against today.',
    },
    'drivers.form.firstName': { es: 'Nombres', en: 'First name' },
    'drivers.form.lastName': { es: 'Apellidos', en: 'Last name' },
    'drivers.form.documentType': { es: 'Tipo de documento', en: 'Document type' },
    'drivers.form.documentNumber': { es: 'Número de documento', en: 'Document number' },
    'drivers.form.sections.license': { es: 'Licencia de conducir', en: 'Driving licence' },
    'drivers.form.sections.employment': { es: 'Vínculo laboral', en: 'Employment' },
    'drivers.form.licenseNumber': { es: 'Número de licencia', en: 'Licence number' },
    'drivers.form.licenseCategory': { es: 'Categoría', en: 'Category' },
    'drivers.form.licenseExpiresOn': { es: 'Vence el', en: 'Expires on' },
    'drivers.form.licenseExpiresHelp': {
      es: 'Se valida contra la fecha de planificación del viaje: una licencia que vence el fin de semana impide planificar el lunes.',
      en: "Checked against the trip's planning date: a licence that lapses over the weekend blocks Monday's plan.",
    },
    'drivers.form.carrier': { es: 'Transportista', en: 'Carrier' },
    'drivers.form.ownStaff': { es: 'Personal propio (sin transportista)', en: 'Own staff (no carrier)' },
  },

  planning: {
    'card.noDriver': { es: 'Sin conductor asignado', en: 'No driver assigned' },
    'drawer.assignDriver': { es: 'Asignar conductor', en: 'Assign driver' },
    'drawer.changeDriver': { es: 'Cambiar conductor', en: 'Change driver' },
    'drawer.driverSaved': { es: 'Conductor actualizado.', en: 'Driver updated.' },
    'drawer.header.driver': { es: 'Conductor', en: 'Driver' },
    'drawer.header.driverLicense': { es: 'Licencia', en: 'Licence' },
    'trip.form.driverTitle': { es: 'Conductor del viaje {{number}}', en: 'Driver for trip {{number}}' },
    'trip.form.driverSubtitle': {
      es: 'Puede cambiarse hasta que la unidad salga. Después, quién conduce deja de ser un plan y pasa a ser un hecho.',
      en: 'Changeable until the vehicle leaves. After that, who is driving stops being a plan and becomes a fact.',
    },
    'trip.form.driver': { es: 'Conductor', en: 'Driver' },
    'trip.form.noDriver': { es: 'Sin conductor', en: 'No driver' },
    'trip.form.submitDriver': { es: 'Guardar conductor', en: 'Save driver' },
    'trip.form.licenseExpiredOption': { es: 'licencia vencida', en: 'licence expired' },
    'trip.form.licenseExpiringOption': { es: 'licencia por vencer', en: 'licence expiring' },
  },

  trips: {
    'columns.driver': { es: 'Conductor', en: 'Driver' },
    'filters.driver': { es: 'Conductor', en: 'Driver' },
    'filters.allDrivers': { es: 'Todos los conductores', en: 'All drivers' },
    'workspace.fields.driver': { es: 'Conductor', en: 'Driver' },
    'workspace.fields.driverPhone': { es: 'Teléfono del conductor', en: 'Driver phone' },
    'workspace.fields.driverLicense': { es: 'Licencia', en: 'Licence' },
    'workspace.actions.assignDriver': { es: 'Asignar conductor', en: 'Assign driver' },
    'workspace.actions.changeDriver': { es: 'Cambiar conductor', en: 'Change driver' },
    'workspace.toasts.driverSaved': { es: 'Conductor actualizado.', en: 'Driver updated.' },
  },

  statuses: {
    'driverLicenseStatus.UNRECORDED': { es: 'Sin registrar', en: 'Not recorded' },
    'driverLicenseStatus.VALID': { es: 'Vigente', en: 'Valid' },
    'driverLicenseStatus.EXPIRING_SOON': { es: 'Por vencer', en: 'Expiring soon' },
    'driverLicenseStatus.EXPIRED': { es: 'Vencida', en: 'Expired' },

    // The fulfilment dimension exposed beside OrderStatus (see OrderFulfillmentStatus): what
    // happened to the goods, which is a different question from whether the order was planned.
    'orderFulfillmentStatus.PENDING': { es: 'Pendiente', en: 'Pending' },
    'orderFulfillmentStatus.DELIVERED': { es: 'Entregado', en: 'Delivered' },
    'orderFulfillmentStatus.PARTIALLY_DELIVERED': { es: 'Entrega parcial', en: 'Partially delivered' },
    'orderFulfillmentStatus.REJECTED': { es: 'Rechazado', en: 'Rejected' },
    'orderFulfillmentStatus.FAILED': { es: 'Fallido', en: 'Failed' },
    'orderFulfillmentStatus.NOT_ATTEMPTED': { es: 'No intentado', en: 'Not attempted' },
  },

  orders: {
    'columns.fulfillment': { es: 'Entrega', en: 'Delivery' },
    'filters.fulfillment': { es: 'Entrega', en: 'Delivery' },
    'filters.allFulfillment': { es: 'Todos los estados de entrega', en: 'All delivery states' },
    'detail.fulfillment': { es: 'Estado de entrega', en: 'Delivery state' },
    'detail.fulfillmentHelp': {
      es: 'Lo que ocurrió con la mercancía en el punto de entrega. Es independiente del estado de planificación.',
      en: 'What happened to the goods at the delivery point. Independent of the planning status.',
    },
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

/** Recursively key-sorted, which is the order the bundles are already kept in. */
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

console.log(`upsert_locale_keys_hardening_v4: ${added} key(s) added across es/en`)
