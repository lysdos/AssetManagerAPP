import { get } from './api.js';
import { el, fmtTime } from './ui.js';

const PAGE_SIZE = 200;
const MAX_PAGES = 50;

export async function render(root, params) {
  const id = params.id;
  const task = await get(`/tasks/${id}`);

  const wrap = el('div', 'report');
  wrap.style.background = 'var(--color-surface)';
  wrap.style.padding = '24px';
  wrap.style.borderRadius = 'var(--radius)';

  const toolbar = el('div', 'row no-print');
  toolbar.style.marginBottom = '16px';
  const back = el('button', 'btn', '返回任务');
  back.addEventListener('click', () => { window.location.hash = `#/tasks/${id}`; });
  const print = el('button', 'btn primary', '打印 / 另存为 PDF');
  print.addEventListener('click', () => window.print());
  toolbar.appendChild(back);
  toolbar.appendChild(el('div', 'spacer'));
  toolbar.appendChild(print);
  wrap.appendChild(toolbar);

  const head = el('div', 'report-head');
  head.appendChild(el('div', 'report-title', `固定资产盘点结果 — ${task.name || ''}`));
  head.appendChild(el('div', 'report-meta',
    `导出时间：${fmtTime(Date.now())}　·　部门：${task.dept || '不限'}　·　`
    + `应盘 ${task.expected}　已盘 ${task.scanned}　盘亏 ${task.missing}　盘盈 ${task.extra}`));
  wrap.appendChild(head);

  const rows = [];
  let page = 1;
  for (;;) {
    const data = await get(`/tasks/${id}/results`, { tab: 'ALL', page, pageSize: PAGE_SIZE });
    rows.push(...data.items);
    if (page >= data.totalPages || page >= MAX_PAGES) break;
    page += 1;
  }

  const table = document.createElement('table');
  table.innerHTML = `<thead><tr>
    <th>序号</th><th>固资编号</th><th>设备类型</th><th>设备型号</th>
    <th>序列号</th><th>领用部门</th><th>领用人</th><th>盘点时间</th>
  </tr></thead>`;
  const tbody = document.createElement('tbody');
  rows.forEach((a, i) => {
    const tr = document.createElement('tr');
    const cells = [String(i + 1), a.epc, a.assetType, a.assetModel,
      a.serialNumber, a.dept, a.custodian, fmtTime(a.inventoryTime)];
    for (const v of cells) {
      const td = document.createElement('td');
      td.textContent = v == null ? '' : v;
      tr.appendChild(td);
    }
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  wrap.appendChild(table);

  root.appendChild(wrap);
}
