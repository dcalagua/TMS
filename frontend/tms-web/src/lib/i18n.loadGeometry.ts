// ── Geometría de carga (V62, ADR-022): «Ocupa», apilables/anidables, piso y alto ──
// Su propio mapa, como los demás módulos recientes; `i18n.ts` solo lo funde. La clave es el español.
export const EN_LOAD_GEOMETRY: Record<string, string> = {
  // «Ocupa» del pedido y de la línea
  "Ocupa": "Occupies",
  "{{count}} pila": "{{count}} stack",
  "{{count}} pilas": "{{count}} stacks",
  "{{area}} de piso": "{{area}} of floor",
  "alto {{height}}": "height {{height}}",
  "{{effective}} efectivos": "{{effective}} effective",
  "{{declared}} declarados": "{{declared}} declared",
  "{{effective}} efectivos · {{declared}} declarados": "{{effective}} effective · {{declared}} declared",
  "Ahorra {{saved}} ({{percent}} %)": "Saves {{saved}} ({{percent}}%)",
  "Por volumen declarado": "By declared volume",
  "{{count}} línea sin medir": "{{count}} line not measured",
  "{{count}} líneas sin medir": "{{count}} lines not measured",
  "{{count}} línea no se pudo medir: cuenta con su volumen declarado.":
    "{{count}} line could not be measured: it counts with its declared volume.",
  "{{count}} líneas no se pudieron medir: cuentan con su volumen declarado.":
    "{{count}} lines could not be measured: they count with their declared volume.",
  "Ninguna línea trae medidas ni perfil de material: el pedido se planifica por su volumen declarado.":
    "No line carries dimensions or a material profile: the order is planned by its declared volume.",
  "Volumen de planificación": "Planning volume",
  "Piso": "Floor",
  "Pila más alta": "Tallest stack",
  "Sin medir": "Not measured",
  "unidad más alta {{height}}": "tallest unit {{height}}",
  "Alto de referencia": "Reference height",
  "con el que se apilaron las líneas": "the lines were stacked against",
  "perfil del almacén": "warehouse profile",
  "medidas del ERP": "ERP dimensions",
  "Sin perfil físico: cuenta su volumen declarado": "No physical profile: its declared volume counts",
  // Motivos de una línea sin geometría
  "Falta el largo de la unidad": "The unit's length is missing",
  "Falta el ancho de la unidad": "The unit's width is missing",
  "Falta el alto de la unidad": "The unit's height is missing",
  "No se declaró si se apila, se anida o ninguna": "Not declared whether it stacks, nests or neither",
  "Falta el incremento de anidado": "The nesting increment is missing",
  "Falta el peso unitario (hace falta para la carga máxima encima)":
    "The unit weight is missing (needed for the maximum top load)",
  "La unidad de la línea no es un conteo (kg, m³…): no se convierte":
    "The line's unit is not a count (kg, m³…): it is not converted",
  "La cantidad no es entera": "The quantity is not a whole number",
  "Sin pila máxima ni alto de referencia: la pila no tiene tope":
    "No maximum stack and no reference height: the stack has no cap",
  "Una unidad es más alta que el alto de referencia": "One unit is taller than the reference height",
  // Capacidad: cinco dimensiones
  "Alto de pila": "Stack height",
  "El vehículo no declara largo y ancho": "The vehicle does not declare length and width",
  "El vehículo no declara alto": "The vehicle does not declare height",
  "Lo llena: {{dimension}}": "Filled by: {{dimension}}",
  "lo que llena el camión": "what fills the truck",
  "Piso {{p}}%": "Floor {{p}}%",
  "Alto {{p}}%": "Height {{p}}%",
  "de piso": "of floor",
  // Rechazos de capacidad
  "El pedido {{order}} no cabe en el viaje {{trip}}.": "Order {{order}} does not fit trip {{trip}}.",
  "El vehículo {{vehicle}} no puede llevar lo ya planificado en el viaje {{trip}}.":
    "Vehicle {{vehicle}} cannot take what is already planned on trip {{trip}}.",
  "{{what}} no se puede confirmar.": "{{what}} cannot be confirmed.",
  "El viaje {{trip}} del plan {{plan}} no se puede confirmar.": "Trip {{trip}} of plan {{plan}} cannot be confirmed.",
  "El peso ({{used}}) supera la capacidad del vehículo ({{limit}}).":
    "The weight ({{used}}) exceeds the vehicle's capacity ({{limit}}).",
  "El volumen de planificación ({{used}}) supera la capacidad del vehículo ({{limit}}).":
    "The planning volume ({{used}}) exceeds the vehicle's capacity ({{limit}}).",
  "Los pallets ({{used}}) superan la capacidad del vehículo ({{limit}}).":
    "The pallets ({{used}}) exceed the vehicle's capacity ({{limit}}).",
  "El piso ocupado ({{used}}) supera el piso útil del vehículo ({{limit}}).":
    "The floor taken ({{used}}) exceeds the vehicle's usable floor ({{limit}}).",
  "La pila más alta ({{used}}) supera el alto útil del vehículo ({{limit}}).":
    "The tallest stack ({{used}}) exceeds the vehicle's usable height ({{limit}}).",
  "Una unidad mide {{unit}} de alto y el vehículo admite {{vehicle}}: no entra aunque se reparta o se reapile. Hace falta un vehículo más alto.":
    "One unit is {{unit}} tall and the vehicle takes {{vehicle}}: it does not fit however it is split or re-stacked. A taller vehicle is needed.",
  "Una unidad es más alta que cualquier vehículo disponible. Hace falta un vehículo más alto: repartir el pedido no lo resuelve.":
    "One unit is taller than every available vehicle. A taller vehicle is needed: splitting the order does not solve it.",
  // Reparto
  "Piso (m²)": "Floor (m²)",
  "El tramo no se reapila: indica el piso que ocupa en este viaje.":
    "The slice is not re-stacked: state the floor it takes on this trip.",
  // Tipos de vehículo
  "Factor de llenado de piso": "Floor fill factor",
  "Vacío: el de la empresa": "Empty: the company's",
  "Vacío: el de la empresa ({{factor}})": "Empty: the company's ({{factor}})",
  "Debe ser mayor que 0 y como máximo 1": "Must be greater than 0 and at most 1",
  "Piso útil: sin límite (falta largo o ancho)": "Usable floor: unlimited (length or width missing)",
  "Piso útil: {{area}}": "Usable floor: {{area}}",
  "Alto útil: sin límite (falta el alto)": "Usable height: unlimited (height missing)",
  "Alto útil para las pilas: {{height}}": "Usable height for stacks: {{height}}",
  "Medidas útiles de la caja. Opcionales: sin largo y ancho el piso no limita; sin alto, la altura de las pilas tampoco.":
    "Usable dimensions of the body. Optional: without length and width the floor does not limit; without height, neither does stack height.",
  "Caja útil (L × A × H)": "Usable body (L × W × H)",
  "Sin medidas": "No dimensions",
  "Factor de piso: el de la empresa": "Floor factor: the company's",
  "Factor de piso: {{factor}}": "Floor factor: {{factor}}",
  // Configuración de la empresa
  "Geometría de carga": "Load geometry",
  "Con estas dos cifras se calculan las pilas de los productos apilables y anidables: el alto de referencia es contra el que se apilan las líneas al grabar un pedido, y el factor de piso es la parte del largo × ancho de la caja que una carga puede cubrir cuando el tipo de vehículo no declara el suyo.":
    "These two figures drive the stacks of stackable and nestable goods: the reference height is what an order's lines are stacked against when it is saved, and the floor factor is the share of the body's length × width a load can cover when the vehicle type does not declare its own.",
  "Valor propio de la empresa": "The company's own value",
  "Valor por defecto de la instalación": "The installation default",
  "Solo lectura: estos valores todavía no se editan desde esta pantalla. El factor de piso de cada tipo de vehículo sí se edita en Flota › Tipos de vehículo.":
    "Read-only: these values cannot be edited from this screen yet. Each vehicle type's floor factor is edited under Fleet › Vehicle types.",
};
