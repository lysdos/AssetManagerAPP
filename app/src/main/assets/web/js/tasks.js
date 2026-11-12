import { get, post, del, download } from './api.js';
import { toast, confirmDialog, empty, paginator, el, fmtTime, pct } from './ui.js';

const STATUS_LABEL = { IN_PROGRESS: '进行中', PAUSED: '已暂停', COMPLETED: '已完成' };
const STATUS_CLASS = { IN_PROGRESS: 'running', PAUSED: 'paused', COMPLETED: 'done' };

function notifySessionChange(res) {
  if (res && res.sessionChanged) {
    const prev = res.previousTaskId == null ? '无' : res.previousTaskId;
    toast(`已切换 PDA 当前扫描任务（原任务 ${prev}）`);
  }
}

export async function renderList(root) {
  // 先给占位再 await —— 否则数据回来前整页空白，慢网下像卡死
  root.appendChild(el('div', 'empty', '加载中…'));
  const data = await get('/tasks');
  root.innerHTML = '';
  const wrap = el('div');

  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, '盘点任务'));
  head.appendChild(el('div', 'spacer'));

  const btnClear = el('button', 'btn danger', '清除已完成');
  btnClear.addEventListener('click', async () => {
    if (!(await confirmDialog('清除全部已完成任务？资产会退回库存池。'))) return;
    try {
      await del('/tasks/completed');
      toast('已清除');
      rerender(root);
    } catch (e) {
      toast(e.message);
    }
  });

  const btnNew = el('button', 'btn primary', '新建任务');
  btnNew.addEventListener('click', () => openCreateDialog(root));

  head.appendChild(btnClear);
  head.appendChild(btnNew);
  wrap.appendChild(head);

  if (!data.items.length) {
    empty(wrap, '还没有任务，点「新建任务」开始');
    root.appendChild(wrap);
    return;
  }

  for (const t of data.items) {
    wrap.appendChild(taskCard(root, t));
  }
  root.appendChild(wrap);
}

function taskCard(root, t) {
  const card = el('div', 'card');
  card.style.marginBottom = '12px';

  const top = el('div', 'row');
  top.appendChild(el('b', null, t.name || '未命名任务'));
  top.appendChild(el('span', `tag ${STATUS_CLASS[t.status] || 'done'}`,
    STATUS_LABEL[t.status] || t.status));
  if (t.dept) top.appendChild(el('span', 'subtitle', `部门：${t.dept}`));
  top.appendChild(el('div', 'spacer'));
  top.appendChild(el('span', 'subtitle num', `${t.scanned} / ${t.expected} · ${pct(t.progress)}`));
  card.appendChild(top);

  const bar = el('div', 'trk');
  const fill = el('i', t.status === 'PAUSED' ? 'paused'
    : t.status === 'COMPLETED' ? 'done' : '');
  fill.style.width = `${Math.max(1, Math.round((t.progress || 0) * 100))}%`;
  bar.appendChild(fill);
  const trk = el('div', 'task-line');
  trk.style.marginTop = '10px';
  trk.appendChild(bar);
  card.appendChild(trk);

  const actions = el('div', 'row');
  actions.style.marginTop = '12px';

  if (t.status === 'IN_PROGRESS') {
    actions.appendChild(actionBtn('暂停', () => post(`/tasks/${t.id}/pause`), root));
  }
  if (t.status === 'PAUSED') {
    actions.appendChild(actionBtn('恢复', () => post(`/tasks/${t.id}/resume`), root));
  }
  if (t.status !== 'COMPLETED') {
    actions.appendChild(actionBtn('完成任务', () => post(`/tasks/${t.id}/complete`), root, true));
  }
  actions.appendChild(actionBtn('查看详情', null, root, false,
    () => { window.location.hash = `#/tasks/${t.id}`; }));
  actions.appendChild(actionBtn('导出报表', null, root, false,
    () => download(`/api/export/task/${t.id}.xlsx`)));
  actions.appendChild(actionBtn('删除', async () => {
    if (!(await confirmDialog(`删除任务「${t.name}」？资产会退回库存池。`))) return null;
    return del(`/tasks/${t.id}`);
  }, root, true));

  card.appendChild(actions);
  return card;
}

function actionBtn(label, fn, root, danger, override) {
  const b = el('button', `btn ${danger ? 'danger' : ''}`, label);
  b.addEventListener('click', async () => {
    if (override) { override(); return; }
    if (!fn) return;
    b.disabled = true;
    try {
      const res = await fn();
      if (res === null) { b.disabled = false; return; }
      notifySessionChange(res);
      toast('操作成功');
      rerender(root);
    } catch (e) {
      toast(e.message);
      b.disabled = false;
    }
  });
  return b;
}

function rerender(root) {
  root.innerHTML = '';
  renderList(root).catch((e) => {
    empty(root, `加载失败：${e.message}`);
  });
}

async function openCreateDialog(root) {
  const depts = await get('/tasks/depts');
  const mask = el('div');
  mask.style.cssText = 'position:fixed;inset:0;background:rgba(15,23,42,.45);'
    + 'display:flex;align-items:center;justify-content:center;z-index:500';

  const box = el('div', 'card');
  box.style.cssText = 'width:420px;max-width:92vw';
  box.innerHTML = '<h2 style="margin-bottom:16px">新建盘点任务</h2>';

  const nameInput = el('input');
  nameInput.type = 'text';
  nameInput.placeholder = '任务名称，如 2026Q4 行政部盘点';
  nameInput.style.cssText = 'width:100%;margin-bottom:12px';

  const deptSelect = document.createElement('select');
  deptSelect.style.cssText = 'width:100%;margin-bottom:12px';
  const optAll = document.createElement('option');
  optAll.value = '';
  optAll.textContent = '不限部门（盘点整个库存池）';
  deptSelect.appendChild(optAll);
  for (const d of depts) {
    const o = document.createElement('option');
    o.value = d;
    o.textContent = d;
    deptSelect.appendChild(o);
  }

  const remarkInput = el('input');
  remarkInput.type = 'text';
  remarkInput.placeholder = '备注（可选）';
  remarkInput.style.cssText = 'width:100%;margin-bottom:16px';

  const actions = el('div', 'row');
  actions.style.justifyContent = 'flex-end';
  const cancel = el('button', 'btn', '取消');
  const ok = el('button', 'btn primary', '创建');

  cancel.addEventListener('click', () => mask.remove());
  ok.addEventListener('click', async () => {
    ok.disabled = true;
    try {
      const res = await post('/tasks', {
        name: nameInput.value,
        dept: deptSelect.value,
        remark: remarkInput.value,
      });
      mask.remove();
      toast(`已创建，划拨 ${res.assigned} 台资产`);
      rerender(root);
    } catch (e) {
      toast(e.message);
      ok.disabled = false;
    }
  });

  actions.appendChild(cancel);
  actions.appendChild(ok);
  box.appendChild(nameInput);
  box.appendChild(deptSelect);
  box.appendChild(remarkInput);
  box.appendChild(actions);
  mask.appendChild(box);
  document.body.appendChild(mask);
  nameInput.focus();
}

export async function renderDetail(root, params) {
  const id = params.id;
  root.appendChild(el('div', 'empty', '加载中…'));
  const task = await get(`/tasks/${id}`);
  root.innerHTML = '';

  const wrap = el('div');
  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, task.name || '未命名任务'));
  head.appendChild(el('span', `tag ${STATUS_CLASS[task.status] || 'done'}`,
    STATUS_LABEL[task.status] || task.status));
  head.appendChild(el('div', 'spacer'));

  const btnReport = el('button', 'btn', '打印报表');
  btnReport.addEventListener('click', () => { window.location.hash = `#/report/${id}`; });
  const btnExport = el('button', 'btn primary', '导出报表');
  btnExport.addEventListener('click', () => download(`/api/export/task/${id}.xlsx`));
  head.appendChild(btnReport);
  head.appendChild(btnExport);
  wrap.appendChild(head);

  const kpis = el('div', 'kpis');
  kpis.style.marginBottom = '16px';
  for (const c of [
    { v: task.expected, l: '应盘' },
    { v: task.scanned, l: '已盘' },
    { v: task.missing, l: '盘亏' },
    { v: task.extra, l: '盘盈' },
  ]) {
    const k = el('div', 'kpi');
    k.appendChild(el('div', 'v', String(c.v)));
    k.appendChild(el('div', 'l', c.l));
    kpis.appendChild(k);
  }
  wrap.appendChild(kpis);

  const tabs = el('div', 'row');
  tabs.style.marginBottom = '12px';
  const tableBox = el('div', 'card');
  const pagerBox = el('div');
  const state = { tab: 'ALL', page: 1 };

  const loadTab = async () => {
    const data = await get(`/tasks/${id}/results`, {
      tab: state.tab, page: state.page, pageSize: 20,
    });
    tableBox.innerHTML = '';
    if (!data.items.length) {
      empty(tableBox, '暂无数据');
    } else {
      tableBox.appendChild(state.tab === 'EXTRA'
        ? extraTable(data.items) : assetTable(data.items));
    }
    paginator(pagerBox, { page: data.page, totalPages: data.totalPages }, (p) => {
      state.page = p;
      loadTab();
    });
  };

  for (const [key, label] of [['ALL', '全部'], ['FOUND', '已盘'],
    ['MISSING', '盘亏'], ['EXTRA', '盘盈']]) {
    const b = el('button', 'btn', label);
    b.addEventListener('click', () => {
      state.tab = key;
      state.page = 1;
      tabs.querySelectorAll('button').forEach((x) => x.classList.remove('primary'));
      b.classList.add('primary');
      loadTab();
    });
    if (key === 'ALL') b.classList.add('primary');
    tabs.appendChild(b);
  }

  wrap.appendChild(tabs);
  wrap.appendChild(tableBox);
  wrap.appendChild(pagerBox);
  root.appendChild(wrap);
  await loadTab();
}

function assetTable(items) {
  const table = document.createElement('table');
  table.innerHTML = `<thead><tr>
    <th>固资编号</th><th>设备类型</th><th>设备型号</th>
    <th>序列号</th><th>领用部门</th><th>领用人</th><th>盘点时间</th>
  </tr></thead>`;
  const tbody = document.createElement('tbody');
  for (const a of items) {
    const tr = document.createElement('tr');
    for (const v of [a.epc, a.assetType, a.assetModel, a.serialNumber, a.dept, a.custodian]) {
      const td = document.createElement('td');
      td.textContent = v == null ? '' : v;
      tr.appendChild(td);
    }
    const tdTime = document.createElement('td');
    tdTime.className = 'num';
    tdTime.textContent = fmtTime(a.inventoryTime);
    tr.appendChild(tdTime);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  return table;
}

function extraTable(items) {
  const table = document.createElement('table');
  table.innerHTML = '<thead><tr><th>固资编号</th><th>扫描时间</th><th>RSSI</th></tr></thead>';
  const tbody = document.createElement('tbody');
  for (const s of items) {
    const tr = document.createElement('tr');
    const tdEpc = document.createElement('td');
    tdEpc.textContent = s.epc || '';
    const tdTime = document.createElement('td');
    tdTime.className = 'num';
    tdTime.textContent = fmtTime(s.scannedAt);
    const tdRssi = document.createElement('td');
    tdRssi.textContent = s.rssi || '';
    tr.appendChild(tdEpc);
    tr.appendChild(tdTime);
    tr.appendChild(tdRssi);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  return table;
}
