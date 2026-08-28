import { configureAxe } from 'vitest-axe'

/**
 * Un axe configurado una vez, para que todas las pruebas de accesibilidad juzguen igual (JOB 26).
 *
 * <h2>Qué comprueba y qué no</h2>
 * axe automatiza aproximadamente **un tercio** de los criterios de la WCAG. Encuentra un botón sin
 * nombre accesible, un campo sin etiqueta, un encabezado saltado y un contraste insuficiente. **No**
 * encuentra un orden de tabulación absurdo, un mensaje de error que nadie anuncia, un foco que se
 * pierde al cerrar un panel, ni una tabla cuyo significado depende del color.
 *
 * Por eso lo que este proyecto tiene es una **base**, no accesibilidad: la deuda D9 sigue abierta y
 * el documento `docs/frontend/ACCESSIBILITY.md` dice exactamente qué falta.
 */
export const axe = configureAxe({
  rules: {
    // Un componente montado suelto no es una página: no tiene <main>, ni encabezado de nivel 1, ni
    // landmarks. Comprobarlos aquí produciría fallos que sólo dicen "esto es un fragmento", y las
    // reglas de página se comprueban donde corresponde — en los tests E2E, sobre la página real.
    region: { enabled: false },
    'page-has-heading-one': { enabled: false },
    'landmark-one-main': { enabled: false },
  },
})
