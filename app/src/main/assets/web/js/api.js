const BASE = '/api';

async function request(path, { method = 'GET', body = null, headers = {} } = {}) {
  const res = await fetch(BASE + path, { method, body, headers });
  const type = res.headers.get('content-type') || '';
  if (!res.ok) {
    let payload = null;
    if (type.includes('application/json')) {
      try { payload = await res.json(); } catch (_) { payload = null; }
    }
    const info = payload && payload.error ? payload.error : null;
    const err = new Error(info ? info.message : `HTTP ${res.status}`);
    err.code = info ? info.code : `HTTP_${res.status}`;
    err.status = res.status;
    throw err;
  }
  return type.includes('application/json') ? res.json() : res;
}

function qs(params) {
  if (!params) return '';
  const parts = [];
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

export function get(path, params) {
  return request(path + qs(params));
}

export function post(path, payload) {
  return request(path, {
    method: 'POST',
    body: JSON.stringify(payload || {}),
    headers: { 'Content-Type': 'application/json' },
  });
}

export function del(path) {
  return request(path, { method: 'DELETE' });
}

export function uploadXlsx(path, filename, arrayBuffer) {
  return request(`${path}?filename=${encodeURIComponent(filename)}`, {
    method: 'POST',
    body: arrayBuffer,
    headers: { 'Content-Type': 'application/octet-stream' },
  });
}

export function download(url) {
  window.location.href = url;
}
