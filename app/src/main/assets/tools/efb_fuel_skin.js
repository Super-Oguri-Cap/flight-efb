/* __EFB_FUEL_SKIN__ ----------------------------------------------------------
 * 油量计算器：机型 / 规章两个原生 <select> 的弹层美化。
 *
 * 为什么放在独立资源里而不是直接改 HTML：
 *   两个网页在 assets 里保持字节级原样，所有改动都由 App 在内存里按锚点注入。
 *   这样以后换新版 HTML 时，这个皮肤会自动重放，不用重新手改一遍页面。
 *
 * 做法：不动 <select> 本身（它仍是唯一的数据源，calc()/applyAircraft()/URL 参数
 * 全部照旧），只在它旁边插一个同尺寸的触发按钮，再自己画一个底部弹层。
 * 选中后把值写回 select 并派发 change，页面原有逻辑一行都不用改。
 * ------------------------------------------------------------------------- */

(function () {
  'use strict';

  if (window.__EFB_FUEL_SKIN__) return;
  window.__EFB_FUEL_SKIN__ = true;

  var CSS = [
    /* 原生 select 只留作数据源，不再参与布局 */
    '.pk-native{position:absolute!important;width:0!important;height:0!important;',
    'opacity:0!important;pointer-events:none!important;margin:0!important;padding:0!important;border:0!important}',

    /* 触发按钮：尺寸与原来那个 select 一致，视觉上也一致 */
    '.pk-trigger{width:100%;display:flex;align-items:center;gap:10px;',
    'padding:9px 11px;border-radius:9px;background:var(--panel2);',
    'border:1px solid var(--line);color:var(--txt);font-size:14px;',
    'font-family:inherit;line-height:1.4;text-align:left;cursor:pointer;',
    'transition:border-color .15s, box-shadow .15s;-webkit-tap-highlight-color:transparent}',
    '.pk-trigger:active,.pk-trigger:focus-visible{border-color:rgba(34,211,238,.7);',
    'box-shadow:0 0 0 3px rgba(34,211,238,.13);outline:none}',
    '.pk-trigger .pk-txt{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.pk-trigger .pk-chev{width:14px;height:14px;flex:0 0 auto;opacity:.65}',

    /* 底部弹层 */
    '.pk-root{position:fixed;inset:0;z-index:9999;display:none}',
    '.pk-root.on{display:block}',
    '.pk-backdrop{position:absolute;inset:0;background:rgba(4,8,13,.66);',
    'opacity:0;transition:opacity .22s ease}',
    '.pk-root.on .pk-backdrop{opacity:1}',
    '.pk-panel{position:absolute;left:0;right:0;bottom:0;max-height:72vh;',
    'display:flex;flex-direction:column;',
    'background:linear-gradient(180deg,var(--panel2) 0%,var(--panel) 100%);',
    'border-top:1px solid rgba(34,211,238,.34);',
    'border-radius:18px 18px 0 0;',
    'box-shadow:0 -18px 48px rgba(0,0,0,.62);',
    'transform:translateY(100%);transition:transform .26s cubic-bezier(.22,1,.36,1);',
    'padding-bottom:env(safe-area-inset-bottom,0)}',
    '.pk-root.on .pk-panel{transform:translateY(0)}',
    '.pk-head{display:flex;align-items:center;gap:12px;padding:14px 18px 10px;',
    'border-bottom:1px solid var(--line)}',
    '.pk-head .pk-title{flex:1;min-width:0;font-size:14px;font-weight:700;',
    'letter-spacing:.02em;color:var(--txt)}',
    '.pk-head .pk-sub{display:block;margin-top:2px;font-size:11px;font-weight:400;',
    'color:var(--muted);letter-spacing:0}',
    '.pk-close{border:1px solid var(--line);background:var(--panel2);color:var(--muted);',
    'font-size:12px;font-family:inherit;padding:6px 12px;border-radius:8px;cursor:pointer}',
    '.pk-close:active{color:var(--txt);border-color:rgba(34,211,238,.5)}',
    '.pk-list{overflow-y:auto;-webkit-overflow-scrolling:touch;padding:6px 0 12px}',

    /* 分组标题（机型按制造商分组）。底色用不透明变量：
       V7 起页面支持日间模式，渐变里写死深色会在浅色主题下露出脏边 */
    '.pk-group{position:sticky;top:0;z-index:1;padding:12px 18px 6px;',
    'font-size:11px;font-weight:700;letter-spacing:.10em;text-transform:uppercase;',
    'color:var(--accent);background:var(--panel2)}',

    /* 选项行 */
    '.pk-item{display:flex;align-items:center;gap:12px;width:100%;',
    'padding:12px 18px;border:0;background:transparent;color:var(--txt);',
    'font-size:14px;font-family:inherit;line-height:1.35;text-align:left;cursor:pointer;',
    '-webkit-tap-highlight-color:transparent}',
    '.pk-item:active{background:rgba(34,211,238,.10)}',
    '.pk-item .pk-opt{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.pk-item .pk-dot{flex:0 0 auto;width:18px;height:18px;border-radius:50%;',
    'border:1.5px solid var(--line);display:flex;align-items:center;justify-content:center;',
    'font-size:11px;line-height:1;color:transparent}',
    '.pk-item.sel{color:#a5f3fc;font-weight:600}',
    '.pk-item.sel .pk-dot{border-color:rgba(34,211,238,.75);background:rgba(34,211,238,.16);color:var(--accent)}',

    /* 日间模式：V7 页面用 html[data-theme="light"] 覆盖 CSS 变量，
       弹层主体已经跟着变量走了，这里只补几处写死的强调色 */
    'html[data-theme="light"] .pk-backdrop{background:rgba(15,23,32,.42)}',
    'html[data-theme="light"] .pk-panel{border-top-color:rgba(14,148,168,.42);',
    'box-shadow:0 -18px 48px rgba(15,23,32,.20)}',
    'html[data-theme="light"] .pk-trigger:active,',
    'html[data-theme="light"] .pk-trigger:focus-visible{border-color:rgba(14,148,168,.7);',
    'box-shadow:0 0 0 3px rgba(14,148,168,.15)}',
    'html[data-theme="light"] .pk-item:active{background:rgba(14,148,168,.10)}',
    'html[data-theme="light"] .pk-item.sel{color:#0e7490}',
    'html[data-theme="light"] .pk-item.sel .pk-dot{border-color:rgba(14,148,168,.75);',
    'background:rgba(14,148,168,.14)}',
    'html[data-theme="light"] .pk-close:active{border-color:rgba(14,148,168,.5)}'
  ].join('');

  var CHEV = '<svg class="pk-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>';

  var root = null;      // 弹层根节点（懒建）
  var current = null;   // 当前打开的 select
  var savedOverflow = '';

  function injectCss() {
    var st = document.createElement('style');
    st.id = 'efb-fuel-skin-style';
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  function buildRoot() {
    var div = document.createElement('div');
    div.className = 'pk-root';
    div.innerHTML =
      '<div class="pk-backdrop"></div>' +
      '<div class="pk-panel" role="dialog" aria-modal="true">' +
        '<div class="pk-head">' +
          '<div class="pk-title"><span class="pk-name"></span>' +
          '<span class="pk-sub">点一下选中，立刻重算</span></div>' +
          '<button type="button" class="pk-close">关闭</button>' +
        '</div>' +
        '<div class="pk-list" role="listbox"></div>' +
      '</div>';
    document.body.appendChild(div);

    div.querySelector('.pk-backdrop').addEventListener('click', close);
    div.querySelector('.pk-close').addEventListener('click', close);
    return div;
  }

  function titleOf(sel) {
    var field = sel.closest ? sel.closest('.field') : null;
    var span = field ? field.querySelector('span') : null;
    var text = span ? String(span.textContent || '').trim() : '';
    return text || '请选择';
  }

  function appendOptions(list, opts, value) {
    for (var i = 0; i < opts.length; i++) {
      var o = opts[i];
      if (o.tagName !== 'OPTION') continue;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'pk-item' + (String(o.value) === value ? ' sel' : '');
      btn.setAttribute('role', 'option');
      btn.setAttribute('aria-selected', String(o.value) === value ? 'true' : 'false');
      var txt = document.createElement('span');
      txt.className = 'pk-opt';
      txt.textContent = String(o.textContent || '');
      var dot = document.createElement('span');
      dot.className = 'pk-dot';
      dot.textContent = '✓';
      btn.appendChild(txt);
      btn.appendChild(dot);
      btn.addEventListener('click', function (val) {
        return function () { commit(val); };
      }(String(o.value)));
      list.appendChild(btn);
    }
  }

  function render(sel) {
    var list = root.querySelector('.pk-list');
    list.innerHTML = '';
    var value = String(sel.value);

    var groups = sel.querySelectorAll('optgroup');
    if (groups.length) {
      for (var i = 0; i < groups.length; i++) {
        var g = document.createElement('div');
        g.className = 'pk-group';
        g.textContent = String(groups[i].label || '');
        list.appendChild(g);
        appendOptions(list, groups[i].children, value);
      }
    } else {
      appendOptions(list, sel.options, value);
    }

    root.querySelector('.pk-name').textContent = titleOf(sel);
  }

  function open(sel) {
    if (!root) root = buildRoot();
    current = sel;
    render(sel);
    savedOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    root.classList.add('on');
    // 打开后把选中项滚进视野，机型有几十条，默认停在最上面要翻很久
    var chosen = root.querySelector('.pk-item.sel');
    if (chosen && chosen.scrollIntoView) {
      chosen.scrollIntoView({ block: 'center' });
    }
  }

  function close() {
    if (!root) return;
    root.classList.remove('on');
    document.body.style.overflow = savedOverflow;
    current = null;
  }

  function commit(value) {
    var sel = current;
    if (!sel) return;
    close();
    if (String(sel.value) === String(value)) return;
    sel.value = value;
    // 页面原来就会监听 change（机型 → applyAircraft，规章 → calc），
    // 派发一次就等于用户自己用原生下拉选了一下
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    refreshAll();
  }

  function refresh(sel, btn) {
    var opt = sel.options[sel.selectedIndex];
    btn.querySelector('.pk-txt').textContent = opt ? String(opt.textContent || '') : '未选择';
  }

  function refreshAll() {
    var triggers = document.querySelectorAll('.pk-trigger');
    for (var i = 0; i < triggers.length; i++) {
      var btn = triggers[i];
      var sel = document.getElementById(btn.getAttribute('data-for'));
      if (sel) refresh(sel, btn);
    }
  }

  function enhance(sel) {
    if (!sel || sel.getAttribute('data-pk') === '1') return false;
    sel.setAttribute('data-pk', '1');
    sel.classList.add('pk-native');

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'pk-trigger';
    btn.setAttribute('data-for', sel.id);
    btn.innerHTML = '<span class="pk-txt"></span>' + CHEV;
    // 必须挡住默认行为：它躺在 <label> 里，不拦的话点击会转发给 select
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      open(sel);
    });
    // button 是 interactive content，label 不会再转发，但保险起见仍按上面处理

    sel.parentNode.insertBefore(btn, sel.nextSibling);
    refresh(sel, btn);
    // 页面自己改值（URL 参数、恢复默认）时标签也要跟着变
    sel.addEventListener('change', function () { refresh(sel, btn); });
    return true;
  }

  function boot() {
    // V9 起页面自带「cselect」跨平台自定义下拉（视觉与配色就是照本皮肤做的），
    // 再套一层 pk-trigger 会出现两个下拉按钮。检测到页面自绘下拉就直接让位。
    if (document.querySelector('.cselect')) {
      console.info('[EFB] 页面已自带自定义下拉，油量皮肤让位');
      return;
    }
    injectCss();
    var ok = false;
    ok = enhance(document.getElementById('regime')) || ok;
    ok = enhance(document.getElementById('aircraft')) || ok;
    // 机型列表是在 DOMContentLoaded 里现填的，标签要等它填完再同步一次
    setTimeout(refreshAll, 0);
    if (ok) console.info('[EFB] 油量页选择器皮肤已加载');
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { setTimeout(boot, 0); });
  } else {
    setTimeout(boot, 0);
  }
})();
