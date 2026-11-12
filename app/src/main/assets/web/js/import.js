import { uploadXlsx, download } from './api.js';
import { toast, el } from './ui.js';

const DROP_IDLE = '<div style="font-size:32px">📄</div>'
  + '<div style="margin-top:8px">拖拽 Excel 文件到这里，或点击选择</div>'
  + '<div class="subtitle" style="margin-top:6px">支持 .xlsx，导入后进入库存池</div>';

export async function render(root) {
  const wrap = el('div');
  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, '导入资产'));
  head.appendChild(el('div', 'spacer'));
  const btnTpl = el('button', 'btn', '下载导入模板');
  btnTpl.addEventListener('click', () => download('/api/template.xlsx'));
  head.appendChild(btnTpl);
  wrap.appendChild(head);

  const drop = el('div', 'drop');
  drop.innerHTML = DROP_IDLE;

  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.xlsx';
  input.style.display = 'none';

  const result = el('div', 'card');
  result.style.marginTop = '16px';
  result.hidden = true;

  drop.addEventListener('click', () => input.click());
  drop.addEventListener('dragover', (e) => {
    e.preventDefault();
    drop.classList.add('over');
  });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => {
    e.preventDefault();
    drop.classList.remove('over');
    if (e.dataTransfer.files.length) handleFile(e.dataTransfer.files[0]);
  });
  input.addEventListener('change', () => {
    if (input.files.length) handleFile(input.files[0]);
  });

  wrap.appendChild(drop);
  wrap.appendChild(input);
  wrap.appendChild(result);
  root.appendChild(wrap);

  async function handleFile(file) {
    if (!file.name.toLowerCase().endsWith('.xlsx')) {
      toast('请选择 .xlsx 文件');
      return;
    }
    drop.innerHTML = `<div class="subtitle">正在上传 ${file.name} …</div>`;
    try {
      const buffer = await file.arrayBuffer();
      const res = await uploadXlsx('/import', file.name, buffer);
      drop.innerHTML = DROP_IDLE;
      showResult(res);
      toast(`导入完成：成功 ${res.success} 条`);
    } catch (e) {
      drop.innerHTML = DROP_IDLE;
      toast(e.message);
    }
  }

  function showResult(res) {
    result.hidden = false;
    result.innerHTML = `
      <h3 style="font-size:15px;margin-bottom:12px">导入结果</h3>
      <div class="row">
        <span class="tag running">成功 ${res.success}</span>
        <span class="tag done">覆盖更新 ${res.updated}</span>
        <span class="tag done">新增 ${res.added}</span>
        <span class="tag ${res.failed ? 'paused' : 'done'}">失败 ${res.failed}</span>
      </div>`;
    if (res.errors && res.errors.length) {
      const list = el('div');
      list.style.cssText = 'margin-top:12px;font-size:13px;color:var(--color-bad);'
        + 'max-height:240px;overflow:auto';
      for (const msg of res.errors) {
        const line = el('div', null, msg);
        line.style.marginBottom = '4px';
        list.appendChild(line);
      }
      if (res.truncated) {
        list.appendChild(el('div', 'subtitle', '（错误过多，仅显示前 50 条）'));
      }
      result.appendChild(list);
    }
  }
}
