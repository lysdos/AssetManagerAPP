import { register, start } from './router.js';
import * as dashboard from './dashboard.js';
import * as tasks from './tasks.js';
import * as assets from './assets.js';
import * as importer from './import.js';
import * as report from './report.js';

const THEME_KEY = 'asset-theme';

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  const btn = document.getElementById('themeToggle');
  if (btn) btn.textContent = theme === 'dark' ? '☀️' : '🌙';
  localStorage.setItem(THEME_KEY, theme);
}

applyTheme(localStorage.getItem(THEME_KEY) || 'light');

document.getElementById('themeToggle').addEventListener('click', () => {
  const current = document.documentElement.getAttribute('data-theme');
  applyTheme(current === 'dark' ? 'light' : 'dark');
});

register('/dashboard', dashboard.render);
register('/', dashboard.render);
register('/tasks', tasks.renderList);
register('/tasks/:id', tasks.renderDetail);
register('/assets', assets.render);
register('/import', importer.render);
register('/report/:id', report.render);

start(document.getElementById('view'));
