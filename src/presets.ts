import { FIGHT_CLUB, NEUTRAL, type Params } from './filter'

export interface Preset {
  name: string
  params: Params
  builtin?: boolean
}

const KEY = 'emulsion-presets-v1'

// Teintes : 0 rouge · 0.08 orange · 0.17 jaune · 0.33 vert · 0.5 cyan · 0.67 bleu · 0.83 magenta · 0.93 rose
// Looks « inspirés de » chaque film, réglés à l'œil : ce ne sont pas les vraies courbes d'étalonnage.
const film = (name: string, p: Partial<Params>): Preset => ({ name, params: { ...NEUTRAL, ...p }, builtin: true })

export const BUILTIN: Preset[] = [
  { name: 'Fight Club', params: FIGHT_CLUB, builtin: true },
  film('Se7en', {
    exposure: -0.3, contrast: 0.35, saturation: -0.3, bleach: 0.7, fade: 0.1, temperature: 0.05,
    shadowHue: 0.25, shadowAmt: 0.3, highlightHue: 0.1, highlightAmt: 0.25, grain: 0.55, vignette: 0.5, sharpen: 0.35,
  }),
  film('Matrix', {
    exposure: -0.15, contrast: 0.35, saturation: -0.25, temperature: -0.1, tint: -0.4,
    shadowHue: 0.36, shadowAmt: 0.5, highlightHue: 0.3, highlightAmt: 0.35, grain: 0.2, vignette: 0.35, sharpen: 0.3,
  }),
  film('Blade Runner 2049', {
    exposure: -0.1, contrast: 0.15, saturation: -0.2, temperature: 0.15, fade: 0.25,
    shadowHue: 0.5, shadowAmt: 0.45, highlightHue: 0.07, highlightAmt: 0.6, grain: 0.25, vignette: 0.4, sharpen: 0.2,
  }),
  film('Mad Max', {
    contrast: 0.4, saturation: 0.15, temperature: 0.12,
    shadowHue: 0.52, shadowAmt: 0.7, highlightHue: 0.08, highlightAmt: 0.6, grain: 0.15, vignette: 0.3, sharpen: 0.5,
  }),
  film('Grand Budapest', {
    exposure: 0.2, contrast: -0.1, saturation: 0.1, temperature: 0.2, tint: 0.25, fade: 0.45,
    shadowHue: 0.88, shadowAmt: 0.25, highlightHue: 0.93, highlightAmt: 0.35, grain: 0.1, vignette: 0.15, sharpen: 0.2,
  }),
  film('Amélie', {
    exposure: -0.05, contrast: 0.25, saturation: 0.2, temperature: 0.15, tint: -0.1, fade: 0.1,
    shadowHue: 0.3, shadowAmt: 0.5, highlightHue: 0.12, highlightAmt: 0.4, grain: 0.2, vignette: 0.45, sharpen: 0.3,
  }),
  film('Dune', {
    exposure: 0.05, contrast: 0.3, temperature: 0.25, fade: 0.1, mono: 0.8,
    shadowHue: 0.06, shadowAmt: 0.4, highlightHue: 0.08, highlightAmt: 0.6, grain: 0.3, vignette: 0.35, sharpen: 0.3,
  }),
  film('Raging Bull', { exposure: -0.05, contrast: 0.45, fade: 0.05, mono: 1, grain: 0.6, vignette: 0.5, sharpen: 0.3 }),
  // Sin City : numérique converti en noir et blanc très contrasté, avec une couleur isolée (le rouge)
  film('Sin City', { exposure: 0.05, contrast: 0.8, sharpen: 0.6, grain: 0.12, vignette: 0.2, mono: 1, keepHue: 0, keepAmt: 1 }),
  // John Wick 3 : noirs profonds, couleurs néon, ombres bleu-teal, lumières magenta/orange
  film('John Wick 3', {
    exposure: -0.2, contrast: 0.4, saturation: 0.2, temperature: -0.05,
    shadowHue: 0.58, shadowAmt: 0.5, highlightHue: 0.9, highlightAmt: 0.45, grain: 0.1, vignette: 0.35, sharpen: 0.4,
  }),
  { name: 'Neutre', params: NEUTRAL, builtin: true },
]

// Stockage local uniquement ; try/catch car localStorage peut être bloqué (navigation privée).
// Les préréglages enregistrés avant l'ajout des champs avancés sont complétés avec des zéros.
export function loadCustom(): Preset[] {
  try {
    const list: Preset[] = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return list.map(p => ({ name: p.name, params: { ...NEUTRAL, ...p.params } }))
  } catch {
    return []
  }
}

export function saveCustom(list: Preset[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list))
  } catch {
    /* quota ou stockage bloqué : on ignore */
  }
}
