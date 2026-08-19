import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useCompany } from '../company/CompanyContext'
import type { NavigationKey } from '../i18n/keys'
import { HOME_NAV, NAV_GROUPS, type NavLeaf } from './navConfig'

interface Entry {
  item: NavLeaf
  /** The group the entry belongs to, so two similarly named screens stay distinguishable. */
  groupKey?: NavigationKey
}

/**
 * Jump straight to a screen by typing its name.
 *
 * This is navigation, not search over data: with twenty-odd screens behind six groups, a
 * planner who knows they want Frecuencias should not have to remember it lives under Maestros.
 * It searches the same `navConfig` the sidebar renders and respects the same capability gating,
 * so it can never offer a screen the menu is hiding.
 *
 * Deliberately not a global data search. Promising one in the chrome and then only matching
 * screen names would be worse than not offering it.
 */
export function NavSearch() {
  const { t } = useTranslation('navigation')
  const navigate = useNavigate()
  const { hasCapability, status } = useCompany()
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const listId = useId()

  const entries = useMemo<Entry[]>(() => {
    const all: Entry[] = [{ item: HOME_NAV }]
    for (const group of NAV_GROUPS) {
      if (group.capability && status === 'ready' && !hasCapability(group.capability)) {
        continue
      }
      for (const item of group.items) {
        all.push({ item, groupKey: group.labelKey })
      }
    }
    return all
  }, [hasCapability, status])

  const matches = useMemo(() => {
    const needle = normalise(query)
    if (needle === '') {
      return []
    }
    return entries.filter((entry) => normalise(t(entry.item.labelKey)).includes(needle)).slice(0, 6)
  }, [entries, query, t])

  // Clicking anywhere else dismisses the list; the input keeps whatever was typed so a
  // mis-click does not throw the query away.
  useEffect(() => {
    if (!open) {
      return
    }
    function onPointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])

  function go(entry: Entry) {
    setOpen(false)
    setQuery('')
    navigate(entry.item.to)
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      setOpen(false)
      return
    }
    if (matches.length === 0) {
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setOpen(true)
      setActiveIndex((current) => (current + 1) % matches.length)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setOpen(true)
      setActiveIndex((current) => (current - 1 + matches.length) % matches.length)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const entry = matches[Math.min(activeIndex, matches.length - 1)]
      if (entry) {
        go(entry)
      }
    }
  }

  const expanded = open && query.trim() !== ''

  return (
    <div className="tms-navsearch" ref={containerRef}>
      <i className="bi bi-search tms-navsearch-icon" aria-hidden="true" />
      <input
        type="text"
        role="combobox"
        className="tms-navsearch-input"
        placeholder={t('search.placeholder')}
        aria-label={t('search.label')}
        aria-expanded={expanded}
        aria-controls={listId}
        aria-autocomplete="list"
        autoComplete="off"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value)
          setActiveIndex(0)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
      />

      {expanded && (
        <ul className="tms-navsearch-list" id={listId} role="listbox" aria-label={t('search.results')}>
          {matches.length === 0 && <li className="tms-navsearch-empty">{t('search.empty')}</li>}
          {matches.map((entry, index) => (
            <li key={entry.item.to}>
              <button
                type="button"
                role="option"
                aria-selected={index === activeIndex}
                className={`tms-navsearch-option${index === activeIndex ? ' active' : ''}`}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => go(entry)}
              >
                <i className={`bi ${entry.item.icon}`} aria-hidden="true" />
                <span className="tms-truncate">{t(entry.item.labelKey)}</span>
                {entry.groupKey && <span className="tms-navsearch-group">{t(entry.groupKey)}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Case- and accent-insensitive, so "frecuencias" matches when the user types "frec". */
function normalise(value: string): string {
  return value
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .trim()
}
