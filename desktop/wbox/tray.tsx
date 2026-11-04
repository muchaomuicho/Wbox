import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { applyTheme, loadThemePreference, applyAccent, loadAccentPreference } from '@dashboard/app/context';
import { TrayMenu } from '@dashboard/TrayMenu';
import { configurePreferenceStorage } from '@dashboard/lib/storage';
import '@dashboard/styles/globals.css';
import '@dashboard/styles/shared.css';
import { createDesktopHost } from './host';

const host = createDesktopHost(true);
configurePreferenceStorage(host.preferences);
// Apply persisted colors before React creates any visible menu content.
applyTheme(loadThemePreference());
applyAccent(loadAccentPreference());
const position = new URLSearchParams(location.search);
document.documentElement.dataset.trayOpen = 'true';
document.documentElement.dataset.trayCascadeSide = position.get('side') || 'left';
document.documentElement.dataset.trayVerticalAlignment = position.get('align') || 'bottom';
flushSync(() => createRoot(document.getElementById('root')!).render(<StrictMode><TrayMenu desktop={host} /></StrictMode>));
