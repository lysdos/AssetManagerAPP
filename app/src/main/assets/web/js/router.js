const routes = [];

export function register(pattern, render) {
  routes.push({ segments: split(pattern), render });
}

function split(hash) {
  const clean = (hash || '').replace(/^#\/?/, '').replace(/\/$/, '');
  return clean ? clean.split('/') : [];
}

function match(pattern, actual) {
  if (pattern.length !== actual.length) return null;
  const params = {};
  for (let i = 0; i < pattern.length; i++) {
    if (pattern[i].startsWith(':')) {
      params[pattern[i].slice(1)] = decodeURIComponent(actual[i]);
    } else if (pattern[i] !== actual[i]) {
      return null;
    }
  }
  return params;
}

let container = null;

async function dispatch() {
  const actual = split(window.location.hash);
  for (const r of routes) {
    const params = match(r.segments, actual);
    if (!params) continue;
    document.querySelectorAll('.nav a').forEach((a) => {
      const target = a.getAttribute('href').replace(/^#\//, '');
      a.classList.toggle('active', target === actual[0]);
    });
    container.innerHTML = '';
    try {
      await r.render(container, params);
    } catch (e) {
      container.innerHTML = '';
      const box = document.createElement('div');
      box.className = 'empty';
      box.textContent = `加载失败：${e.message}`;
      container.appendChild(box);
    }
    return;
  }
  window.location.hash = '#/dashboard';
}

export function start(el) {
  container = el;
  window.addEventListener('hashchange', dispatch);
  dispatch();
}
