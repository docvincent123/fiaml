import {createContext, useContext, useEffect, useState, type ReactNode} from 'react';
import {createPortal} from 'react-dom';
import {Sparkles} from 'lucide-react';

type Mode = 'auto' | 'full' | 'eco';
const storageKey = 'quremed.appearance.v1';
const Appearance = createContext<{mode: Mode; setMode: (mode: Mode) => void}>({mode: 'auto', setMode: () => {}});

function initialMode(): Mode {
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved === 'auto' || saved === 'full' || saved === 'eco') return saved;
  } catch { /* Private/restricted storage must not block login. */ }
  return 'auto';
}

export function AppearanceProvider({children}: {children: ReactNode}) {
  const [mode, setMode] = useState<Mode>(initialMode);
  useEffect(() => {
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const root = document.documentElement;
    const update = () => {
      const memory = (navigator as Navigator & {deviceMemory?: number}).deviceMemory;
      const modestDevice = navigator.hardwareConcurrency <= 4 || (memory !== undefined && memory <= 4);
      root.dataset.effects = reduced.matches || mode === 'eco' || (mode === 'auto' && modestDevice) ? 'eco' : 'full';
      root.dataset.motion = document.hidden || !document.hasFocus() ? 'paused' : 'running';
    };
    try { localStorage.setItem(storageKey, mode); } catch { /* Session-only preference. */ }
    update();
    reduced.addEventListener('change', update);
    document.addEventListener('visibilitychange', update);
    window.addEventListener('focus', update);
    window.addEventListener('blur', update);
    return () => {
      reduced.removeEventListener('change', update);
      document.removeEventListener('visibilitychange', update);
      window.removeEventListener('focus', update);
      window.removeEventListener('blur', update);
      delete root.dataset.effects;
      delete root.dataset.motion;
    };
  }, [mode]);
  return <Appearance.Provider value={{mode, setMode}}>{children}<UiPolicy/></Appearance.Provider>;
}

export function AppearanceControl() {
  const {mode, setMode} = useContext(Appearance);
  return <label className="appearance-control" title="Еко вимикає рух і фонові ефекти. Авто враховує доступні характеристики пристрою.">
    <Sparkles size={16} aria-hidden="true"/>
    <select aria-label="Режим візуальних ефектів" value={mode} onChange={event => setMode(event.target.value as Mode)}>
      <option value="auto">Ефекти: авто</option>
      <option value="full">Ефекти: увімкнено</option>
      <option value="eco">Еко · без анімацій</option>
    </select>
  </label>;
}

function UiPolicy() {
  const [settingsGrid, setSettingsGrid] = useState<HTMLElement | null>(null);
  useEffect(() => {
    let lastPath = '';
    let lastRole = '';
    const sync = () => {
      const path = window.location.pathname;
      const roleText = document.querySelector('.user-mini small')?.textContent?.trim() ?? '';
      const role = roleText === 'Реєстратура' ? 'registrar' : roleText ? 'staff' : '';
      if (path !== lastPath) { document.documentElement.dataset.path = path; lastPath = path; }
      if (role !== lastRole) {
        if (role) document.documentElement.dataset.role = role; else delete document.documentElement.dataset.role;
        lastRole = role;
      }
      const next = path === '/settings' ? document.querySelector<HTMLElement>('.settings-grid') : null;
      setSettingsGrid(current => current === next ? current : next);
    };
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(document.body, {subtree: true, childList: true, characterData: true});
    const timer = window.setInterval(sync, 750);
    return () => {
      observer.disconnect();
      window.clearInterval(timer);
      delete document.documentElement.dataset.path;
      delete document.documentElement.dataset.role;
    };
  }, []);
  return settingsGrid ? createPortal(
    <section className="panel padded appearance-settings-card">
      <h2>Візуальні ефекти</h2>
      <p className="muted">Анімації та фонові ефекти налаштовуються тільки тут.</p>
      <AppearanceControl/>
    </section>, settingsGrid
  ) : null;
}

export function AmbientBackground() {
  return <div className="ambient" aria-hidden="true"><i/><i/></div>;
}
