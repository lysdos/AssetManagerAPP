import { get } from './api.js';
import { el } from './ui.js';

const POLL_MS = 5000;

export async function render(root) {
  root.appendChild(buildSkeleton());

  let timer = null;
  const load = async () => {
    if (!root.isConnected) {
      clearInterval(timer);
      return;
    }
    try {
      paint(await get('/dashboard'));
    } catch (e) {
      setStatus(`连接中断：${e.message}`);
    }
  };

  await load();
  timer = setInterval(load, POLL_MS);
  window.addEventListener('hashchange', () => clearInterval(timer), { once: true });
}

function buildSkeleton() {
  const wrap = el('div', 'dash');

  const kpis = el('div', 'kpis');
  kpis.id = 'kpis';
  wrap.appendChild(kpis);

  const main = el('div', 'dash-main');
  main.innerHTML = `
    <div class="dash-col">
      <div class="panel" id="activeTask"></div>
      <div class="panel" id="taskList"></div>
    </div>
    <div class="dash-col" style="grid-template-rows: 1fr 1fr;">
      <div class="panel" id="deptChart"></div>
      <div class="panel" id="typeChart"></div>
    </div>`;
  wrap.appendChild(main);

  const foot = el('div', 'dash-foot');
  foot.innerHTML = '<span>数据来源：本机 PDA 数据库 · 每 5 秒自动刷新</span>'
    + '<span id="dashStatus">正在连接…</span>';
  wrap.appendChild(foot);

  return wrap;
}

function setStatus(text) {
  const s = document.getElementById('dashStatus');
  if (s) s.textContent = text;
}

function paint(data) {
  paintKpis(data);
  paintActiveTask(data.activeTask);
  paintTaskList(data);
  paintBars('deptChart', '部门分布', data.deptDistribution);
  paintBars('typeChart', '设备类型分布', data.typeDistribution);
  setStatus(`最后更新 ${new Date(data.updatedAt).toLocaleTimeString('zh-CN')}`);
}

function paintKpis(data) {
  const box = document.getElementById('kpis');
  if (!box) return;
  const counts = data.taskCounts || {};
  const items = [
    { v: data.ledger.total, l: '资产总数', cls: 'hi' },
    { v: data.ledger.deptCount, l: '领用部门' },
    { v: data.ledger.typeCount, l: '设备类型' },
    { v: counts.inProgress || 0, l: '进行中任务', cls: 'warn' },
    { v: counts.completed || 0, l: '已完成任务', cls: 'ok' },
  ];
  box.innerHTML = '';
  for (const it of items) {
    const k = el('div', `kpi ${it.cls === 'hi' ? 'hi' : ''}`);
    k.appendChild(el('div', `v ${it.cls === 'warn' ? 'warn' : it.cls === 'ok' ? 'ok' : ''}`,
      String(it.v)));
    k.appendChild(el('div', 'l', it.l));
    box.appendChild(k);
  }
}

function paintActiveTask(task) {
  const box = document.getElementById('activeTask');
  if (!box) return;
  if (!task) {
    box.innerHTML = '<h3>当前任务</h3><div class="empty">PDA 上尚未选择任务</div>';
    return;
  }
  const percent = Math.round((task.progress || 0) * 100);
  box.innerHTML = `
    <h3>当前任务 <span>${escapeHtml(task.name || '')}</span></h3>
    <div class="ring-row">
      <div class="ring" style="--pct:${percent}%">
        <i><span class="p">${percent}%</span>
            <span class="s">${task.scanned}/${task.expected}</span></i>
      </div>
      <div class="stats">
        <div class="r"><span>已盘</span><b style="color:var(--color-ok)">${task.scanned}</b></div>
        <div class="r"><span>盘亏</span><b style="color:var(--color-bad)">${task.missing}</b></div>
        <div class="r"><span>盘盈</span><b style="color:var(--color-warn)">${task.extra}</b></div>
      </div>
    </div>`;
}

function paintTaskList(data) {
  const box = document.getElementById('taskList');
  if (!box) return;
  const counts = data.taskCounts || {};
  box.innerHTML = '<h3>任务概览 <span>进行中 ' + (counts.inProgress || 0)
    + ' · 已完成 ' + (counts.completed || 0) + '</span></h3>';
  const list = el('div');
  list.style.flex = '1';
  list.style.display = 'flex';
  list.style.flexDirection = 'column';
  list.style.justifyContent = 'space-around';
  box.appendChild(list);

  const tasks = data.tasks || [];
  if (!tasks.length) {
    list.appendChild(el('div', 'empty', '暂无进行中的任务'));
    return;
  }
  const activeId = data.activeTask ? data.activeTask.id : null;
  for (const t of tasks) {
    const kind = t.status === 'PAUSED' ? 'paused' : 'running';
    const line = taskLine(t.name, t.progress, kind);
    if (t.id === activeId) {
      line.querySelector('.nm').textContent += ' ·当前';
      line.querySelector('.nm').style.fontWeight = '600';
    }
    list.appendChild(line);
  }
}

function taskLine(name, progress, kind) {
  const wrap = el('div', 'task-line');
  const top = el('div', 'top');
  top.appendChild(el('span', 'nm', name || '未命名任务'));
  top.appendChild(el('span', 'pc', `${Math.round((progress || 0) * 100)}%`));
  wrap.appendChild(top);
  const trk = el('div', 'trk');
  const fill = el('i', kind === 'paused' ? 'paused' : '');
  fill.style.width = `${Math.max(1, Math.round((progress || 0) * 100))}%`;
  trk.appendChild(fill);
  wrap.appendChild(trk);
  return wrap;
}

function paintBars(containerId, title, rows) {
  const box = document.getElementById(containerId);
  if (!box) return;
  box.innerHTML = `<h3>${title} <span>按资产数量 · Top 10</span></h3>`;
  const chart = el('div', 'barchart');
  box.appendChild(chart);

  if (!rows || rows.length === 0) {
    chart.appendChild(el('div', 'empty', '暂无数据'));
    return;
  }
  const max = Math.max(...rows.map((r) => r.count), 1);
  for (const r of rows) {
    const row = el('div', 'brow');
    row.appendChild(el('span', 'n', r.name));
    const trk = el('span', 'trk');
    const fill = el('i');
    fill.style.width = `${Math.max(2, Math.round((r.count / max) * 100))}%`;
    trk.appendChild(fill);
    row.appendChild(trk);
    row.appendChild(el('span', 'c', String(r.count)));
    chart.appendChild(row);
  }
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
