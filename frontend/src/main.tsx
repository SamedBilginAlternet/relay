import '@fontsource-variable/inter';
import '@fontsource/jetbrains-mono/400.css';
import './styles/global.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

const container = document.getElementById('root');

if (!container) {
  // Should never happen — but never leave a blank page without an explanation.
  document.body.innerHTML =
    '<div class="boot-fallback">Relay başlatılamadı: #root elemanı bulunamadı.</div>';
} else {
  createRoot(container).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
