import { get, download } from './api.js';
import { empty, paginator, el, fmtTime } from './ui.js';

export async function render(root) {
  root.appendChild(el('div', 'empty', '加载中…'));
  const filters = await get('/assets/filters');
  root.innerHTML = '';
  const state = { keyword: '', dept: '', type: '', page: 1, pageSize: 20 };

  const wrap = el('div');

  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, '资产台账'));
  head.appendChild(el('div', 'spacer'));
  const btnExport = el('button', 'btn primary', '导出台账');
  btnExport.addEventListener('click', () => {
    const q = new URLSearchParams();
    if (state.keyword) q.set('keyword', state.keyword);
    if (state.dept) q.set('dept', state.dept);
    if (state.type) q.set('type', state.type);
    const suffix = q.toString() ? `?${q}` : '';
    download(`/api/export/ledger.xlsx${suffix}`);
  });
  head.appendChild(btnExport);
  wrap.appendChild(head);

  const bar = el('div', 'row');
  bar.style.marginBottom = '12px';

  const search = el('input');
  search.type = 'search';
  search.placeholder = '搜索固资编号 / 型号 / 序列号 / 部门 / 领用人';
  search.style.cssText = 'flex:1;min-width:260px';
  search.addEventListener('input', debounce(() => {
    state.keyword = search.value.trim();
    state.page = 1;
    load();
  }, 300));

  const deptSelect = buildSelect('全部部门', filters.depts);
  deptSelect.addEventListener('change', () => {
    state.dept = deptSelect.value;
    state.page = 1;
    load();
  });

  const typeSelect = buildSelect('全部类型', filters.types);
  typeSelect.addEventListener('change', () => {
    state.type = typeSelect.value;
    state.page = 1;
    load();
  });

  bar.appendChild(search);
  bar.appendChild(deptSelect);
  bar.appendChild(typeSelect);
  wrap.appendChild(bar);

  const tableBox = el('div', 'card');
  const pagerBox = el('div');
  wrap.appendChild(tableBox);
  wrap.appendChild(pagerBox);
  root.appendChild(wrap);

  async function load() {
    const data = await get('/assets', {
      keyword: state.keyword,
      dept: state.dept,
      type: state.type,
      page: state.page,
      pageSize: state.pageSize,
    });
    tableBox.innerHTML = '';
    if (!data.items.length) {
      empty(tableBox, '没有匹配的资产');
    } else {
      tableBox.appendChild(buildTable(data.items));
    }
    pagerBox.innerHTML = '';
    paginator(pagerBox, { page: data.page, totalPages: data.totalPages }, (p) => {
      state.page = p;
      load();
    });
  }

  await load();
}

function buildSelect(allLabel, options) {
  const select = document.createElement('select');
  const optAll = document.createElement('option');
  optAll.value = '';
  optAll.textContent = allLabel;
  select.appendChild(optAll);
  for (const o of options) {
    const opt = document.createElement('option');
    opt.value = o.name;
    opt.textContent = `${o.name}（${o.count}）`;
    select.appendChild(opt);
  }
  return select;
}

function buildTable(items) {
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

function debounce(fn, ms) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}
