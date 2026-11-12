export function toast(message, ms = 2200) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = message;
  el.hidden = false;
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.hidden = true; }, ms);
}

export function confirmDialog(message) {
  return Promise.resolve(window.confirm(message));
}

export function empty(container, text = '暂无数据') {
  const node = document.createElement('div');
  node.className = 'empty';
  node.textContent = text;
  container.appendChild(node);
}

export function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function pct(v) {
  return `${Math.round((v || 0) * 100)}%`;
}

/**
 * 渲染分页器。
 * @param {HTMLElement} target 容器
 * @param {{page:number,totalPages:number}} state
 * @param {(page:number)=>void} onPage
 */
export function paginator(target, state, onPage) {
  target.innerHTML = '';
  target.className = 'pager';
  if (!state.totalPages || state.totalPages <= 1) return;

  const mk = (label, page, disabled) => {
    const b = document.createElement('button');
    b.className = 'btn';
    b.textContent = label;
    b.disabled = disabled;
    b.addEventListener('click', () => onPage(page));
    target.appendChild(b);
  };

  mk('上一页', state.page - 1, state.page <= 1);
  const info = document.createElement('span');
  info.className = 'subtitle';
  info.textContent = `${state.page} / ${state.totalPages}`;
  target.appendChild(info);
  mk('下一页', state.page + 1, state.page >= state.totalPages);
}

export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}
