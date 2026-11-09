/* __EFB_BRIDGE__ ------------------------------------------------------------
 * EFB App 在内存里插入的桥接层。磁盘上的 route_gen.html 是字节级原样，
 * 不包含本文件的任何内容；App 读成 String 后按 efb_patches.json 的锚点插入，
 * 再交给 WebView —— 不写磁盘、不跑构建脚本。
 *
 * 为什么独立成一个 script 块而不是插进主脚本：
 *   主脚本里任一错误会中止整段，DOMContentLoaded 注册不上、UI.init() 不跑、整页废掉。
 *   独立块里出错只炸这座桥，主页面照常。
 *
 * 为什么用裸名 Codec/UI 而不是 window.UI：
 *   7 个对象都是顶层 const，只活在经典脚本的全局词法环境里，
 *   window.UI === undefined 恒成立。经典脚本之间共享词法环境，裸名可取。
 *
 * 不做任何 typeof 探测 / 能力降级：7 个 const 改名的风险由出包期
 * ToolAssetsContractTest 逐条断言（任一改名 → 构建红），运行期不兜底。
 * ------------------------------------------------------------------------- */

(function () {
  'use strict';

  var BRIDGE_VERSION = '1';

  /* 版本串只进 Logcat：从 <title> 读，界面不显示、App 运行期不比对。
     出包期比对在 ToolAssetsContractTest 里做。 */
  var TOOL_VERSION = '';
  try {
    var m = String(document.title || '').match(/v(\d+\.\d+(?:\.\d+)?)/i);
    if (m) TOOL_VERSION = 'v' + m[1];
  } catch (e) { /* 读不到就算了，不影响桥 */ }

  var api = {
    bridgeVersion: BRIDGE_VERSION,
    toolVersion: TOOL_VERSION,
    degraded: false,
    exportHooked: !!window.__EFB_EXPORT_HOOKED__,
    capabilities: [
      'navImport', 'airportMerge', 'packEncode',
      'applyData', 'getPlans', 'exportJson', 'stepLevel'
    ],
    domContract: {
      plan: '.plan',
      dist: '.pdist',
      copy: '.pcopy[data-copy]',
      route: 'pre.proute',
      overview: '.overview .rt'
    }
  };

  /* 单个对象字面量：7 个里任一改名都会在这里抛 ReferenceError，整座桥建不起来。
     这是设计上的 fail-loud —— 出包期测试会先把这种改动拦在构建里。 */
  try {
    api.Codec = Codec;
    api.DatasetLoader = DatasetLoader;
    api.Geo = Geo;
    api.PackCodec = PackCodec;
    api.DataStore = DataStore;
    api.NavParser = NavParser;
    api.UI = UI;
  } catch (err) {
    console.error('[EFB] 桥接失败：顶层 const 缺失或改名 → ' + (err && err.message));
    throw err;
  }

  /* ---------------- 数据集读数 ---------------- */

  function datasetInfo() {
    var ds = window.__DATASET__;
    if (!ds) return { airports: 0, nodes: 0, segments: 0, airways: 0 };
    var awy = {};
    var segs = ds.segments || [];
    for (var i = 0; i < segs.length; i++) {
      if (segs[i] && segs[i].awy) awy[segs[i].awy] = 1;
    }
    return {
      airports: (ds.airports || []).length,
      nodes: (ds.nodes || []).length,
      segments: segs.length,
      airways: Object.keys(awy).length
    };
  }

  /* ---------------- 航路方案读取（DOM 收敛在这里，Kotlin 不刮 DOM） ----------------
   * key 取 .pcopy 的 data-copy，不用 data-plan：后者在 offbad 卡片上也有，
   * 且与复制键不是同一套编号。没有 .pcopy 的卡片（高度越界的官方航路）直接跳过。 */

  function getPlans() {
    var out = { from: '', to: '', plans: [] };
    var rt = document.querySelector('.overview .rt');
    if (rt) {
      var t = String(rt.textContent || '').replace(/\s+/g, ' ').trim();
      var pair = t.match(/([A-Z0-9]{4})\s*\u2192\s*([A-Z0-9]{4})/);
      if (pair) { out.from = pair[1]; out.to = pair[2]; }
    }
    var cards = document.querySelectorAll('.plan');
    for (var i = 0; i < cards.length; i++) {
      var el = cards[i];
      var btn = el.querySelector('.pcopy[data-copy]');
      var pre = el.querySelector('pre.proute');
      if (!btn || !pre) continue;
      var distEl = el.querySelector('.pdist');
      var dist = distEl ? parseFloat(String(distEl.textContent || '').replace(/[^0-9.]/g, '')) : NaN;
      out.plans.push({
        key: String(btn.getAttribute('data-copy') || ''),
        kind: el.classList.contains('official') ? 'official' : 'engine',
        distNM: isFinite(dist) ? Math.round(dist) : 0,
        route: String(pre.textContent || '').replace(/\s+/g, ' ').trim()
      });
    }
    return out;
  }

  function getPlansJson() {
    return JSON.stringify(getPlans());
  }

  /* ---------------- 导出 ---------------- */

  function exportJson(filename, obj) {
    var text = (typeof obj === 'string') ? obj : JSON.stringify(obj);
    if (window.Android && window.Android.exportJson) {
      window.Android.exportJson(String(filename || 'export.json'), text);
      return true;
    }
    return false;
  }

  /* ---------------- 高度层滚轮 ---------------- */

  function stepLevel(delta) {
    var w = UI && UI.LevelWheel;
    if (!w) return false;
    try {
      if (delta < 0 && typeof w.down === 'function') { w.down(); return true; }
      if (delta > 0 && typeof w.up === 'function') { w.up(); return true; }
    } catch (e) {
      console.warn('[EFB] 高度层操作失败', e);
    }
    return false;
  }

  /* ---------------- 导入管线 ----------------
   * Binder 单次约 1 MB，App 侧按 Kotlin String 字符分块（<=192K 字符）逐块送进来，
   * 这里只做拼接，全部到齐后才解析。不用 base64：徒增 33% 体积。
   * 解析是同步的重活（25 MB 文本），放到 setTimeout 里让 App 先把进度条画出来。 */

  var pending = null;      // { kind: 'nav'|'aip'|'pack', files: [ {name, parts: [], text} ], chars }
  var lastPack = null;     // 上一次成功应用的数据包，用于「只补机场坐标」时合并
  var ctxCaac = null;      // 上一次生效的 CAAC 官方航路表，换数据时沿用
  var ctxPriority = null;  // 同上，航路优先级

  function importBegin(kind) {
    pending = { kind: String(kind || 'nav'), files: [], chars: 0 };
    return true;
  }

  function importAppend(name, chunk) {
    if (!pending) pending = { files: [], chars: 0 };
    var n = String(name || 'file.dat');
    var last = pending.files.length ? pending.files[pending.files.length - 1] : null;
    if (!last || last.name !== n || last.text != null) {
      last = { name: n, parts: [], text: null };
      pending.files.push(last);
    }
    last.parts.push(String(chunk == null ? '' : chunk));
    pending.chars += chunk ? chunk.length : 0;
    return pending.chars;
  }

  function importFileEnd() {
    if (!pending || !pending.files.length) return 0;
    var last = pending.files[pending.files.length - 1];
    if (last.text == null) {
      last.text = last.parts.join('');
      last.parts = null;
    }
    return last.text.length;
  }

  function importState() {
    if (!pending) return { files: 0, chars: 0 };
    return { files: pending.files.length, chars: pending.chars };
  }

  function importReset() {
    pending = null;
    return true;
  }

  /* earth_aptmeta.dat：X-Plane 12 的机场元数据，列序是
     ICAO 区域 纬度 经度 标高 ...。导航数据包里通常没有 apt.dat，
     缺的就是这一份坐标；有它才能选起降机场。 */
  function collectAptMeta(files) {
    var out = [];
    var seen = {};
    for (var i = 0; i < files.length; i++) {
      if (!/aptmeta/i.test(files[i].name || '')) continue;
      var lines = String(files[i].text || '').split('\n');
      for (var j = 0; j < lines.length; j++) {
        var line = lines[j].replace(/\r$/, '').trim();
        if (!line || line.charAt(0) === 'I' || /^\d/.test(line)) continue;
        var f = line.split(/\s+/);
        if (f.length < 5) continue;
        var icao = f[0];
        if (!/^[A-Z0-9]{3,4}$/.test(icao) || seen[icao]) continue;
        var lat = Number(f[2]);
        var lon = Number(f[3]);
        var elev = Number(f[4]);
        /* 全量范围校验放在 encode 之前：坏行绝不能进 PackCodec，
           手工拼 AIRPORTS_GLOBAL 会静默写出 (-90,-180) 这种错坐标。 */
        if (!isFinite(lat) || !isFinite(lon)) continue;
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
        if (lat === 0 && lon === 0) continue;
        seen[icao] = 1;
        out.push({ icao: icao, lat: lat, lon: lon, elev: isFinite(elev) ? elev : 0 });
      }
    }
    return out;
  }

  function notifyApplied(ok, message, info) {
    var payload = JSON.stringify({
      ok: !!ok,
      message: String(message || ''),
      info: info || datasetInfo()
    });
    console.info('[EFB] 导入结果 ' + payload);
    // 导入是从「数据管理」面板里点进来的，成功后顺手把面板收掉，
    // 否则用户会盯着一个「已选 0 个文件」的列表以为没导进去
    if (ok) closeDataPanel();
    if (window.Android && window.Android.onDataApplied) {
      window.Android.onDataApplied(payload);
    }
  }

  function closeDataPanel() {
    try {
      var m = document.getElementById('dmodal');
      if (m) m.classList.remove('on');
      return true;
    } catch (e) { return false; }
  }

  /* ---------------- 网页自带的文件选择 ----------------
   * 面板里三个「拖入 / 点击选择文件」最终都是对应 input 的 .click()。
   * WebView 没有 onShowFileChooser 回调，点它什么都不会发生 —— 这正是
   * 「点导入数据没反应」的原因。这里按 kind 转交给 App 的 SAF 选择器，
   * 选完仍然回到同一条分块注入管线，解析依旧在页面里做。 */
  var FILE_KINDS = { fileNav: 'nav', fileAip: 'aip', filePack: 'pack' };

  function hookFileInputs() {
    var ok = false;
    for (var id in FILE_KINDS) {
      if (!FILE_KINDS.hasOwnProperty(id)) continue;
      var el = document.getElementById(id);
      if (!el || el.__efbHooked) continue;
      el.__efbHooked = true;
      ok = true;
      (function (input, kind) {
        input.click = function () {
          if (window.Android && window.Android.pickFiles) {
            console.info('[EFB] 转交 App 选择文件 kind=' + kind);
            window.Android.pickFiles(kind);
            return;
          }
          HTMLInputElement.prototype.click.call(input);
        };
      }(el, FILE_KINDS[id]));
    }
    return ok;
  }

  /* ---------------- 顶部工具栏适配 ----------------
   * 原页面 #toolbar 是固定 50px、不换行的单行布局，900px 断点只处理了 #main，
   * 没管工具栏。手机上它会被挤成两行（实测 brand 高达 71px）后被 50px 的
   * 高度裁掉，同时 nowrap 的 #dataTag 把整行撑宽 —— 就是「字显示不全、页面溢出」。
   * 这里补一段窄屏规则：标题独占一行、副标题隐藏、标签可省略、按钮不压缩。 */
  var TOOLBAR_CSS = [
    '@media (max-width:900px){',
    '#toolbar{height:auto;min-height:50px;flex-wrap:wrap;row-gap:8px;padding:8px 12px}',
    '#toolbar .brand{flex:1 1 100%;margin-right:0;min-width:0;flex-wrap:wrap;row-gap:2px}',
    '#toolbar .brand small{display:none}',
    '#dataTag{order:2;flex:1 1 64px;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '#toolbar button{order:3;flex:0 0 auto;height:34px;padding:0 12px;font-size:12px}',
    '#toolbar #btnGen{flex:1 1 auto}',
    '}'
  ].join('');

  function injectToolbarFix() {
    if (document.getElementById('efb-toolbar-fix')) return;
    var st = document.createElement('style');
    st.id = 'efb-toolbar-fix';
    st.textContent = TOOLBAR_CSS;
    (document.head || document.documentElement).appendChild(st);
  }

  /* ---------------- 方案变化回调 ----------------
   * 页面生成 / 展开方案后只改 DOM，不对外发事件。这里盯住结果区，
   * 稳定 400ms 后叫 App 读一次方案 —— 用户不必再点「读取方案」。
   * 用 MutationObserver 而不是定时轮询：页面空闲时零开销。 */
  var plansTimer = null;

  function watchPlans() {
    var target = document.getElementById('results');
    if (!target || typeof MutationObserver === 'undefined' || target.__efbWatched) return false;
    target.__efbWatched = true;
    var mo = new MutationObserver(function () {
      if (plansTimer) clearTimeout(plansTimer);
      plansTimer = setTimeout(function () {
        plansTimer = null;
        try {
          if (window.Android && window.Android.onPlansChanged) window.Android.onPlansChanged();
        } catch (e) { /* 通知失败不影响页面 */ }
      }, 400);
    });
    mo.observe(target, { childList: true, subtree: true, characterData: true });
    return true;
  }

  /* 把缓冲区的分块拼成 {name, text} 列表 */
  function drainFiles() {
    if (!pending) return [];
    var files = [];
    for (var i = 0; i < pending.files.length; i++) {
      var f = pending.files[i];
      var text = f.text != null ? f.text : (f.parts ? f.parts.join('') : '');
      if (text) files.push({ name: f.name, text: text });
    }
    return files;
  }

  function importCommit() {
    if (!pending || !pending.files.length) {
      notifyApplied(false, '没有待导入的文件');
      return false;
    }
    var kind = pending.kind || 'nav';
    var files = drainFiles();
    pending = null;
    if (!files.length) { notifyApplied(false, '没有可解析的内容'); return false; }

    /* 重活丢到下一轮事件循环，App 的「解析中」提示先画出来 */
    setTimeout(function () {
      try {
        // restore = 从 App 的私有缓存读回来的同一份 pack，不必再回写缓存
        if (kind === 'restore') { files[0].fromCache = true; applyPack(files); return; }
        if (kind === 'pack') { applyPack(files); return; }
        if (kind === 'aip') { applyAip(files); return; }
        applyNav(files);
      } catch (e) {
        console.error('[EFB] 导入失败', e);
        notifyApplied(false, (e && e.message) ? e.message : String(e));
      }
    }, 0);
    return true;
  }

  /**
   * 三个入口共用：应用成功后记住 pack / CAAC / 优先级，供下一次切换数据时沿用。
   *
   * @param persist 成功后是否把数据集回传给 App 落盘缓存。
   *   真正的导入传 true；从缓存恢复传 false —— 没必要把刚读出来的东西再写一遍。
   */
  function finishApply(rec, okMessage, persist) {
    return UI.applyData(rec, {}).then(function (ok) {
      if (ok) {
        lastPack = rec.pack;
        ctxCaac = rec.caac;
        ctxPriority = rec.priority;
        if (persist !== false) exportPackChunks();
      }
      notifyApplied(!!ok, ok ? (okMessage || '') : '数据不可用');
    }).catch(function (e) {
      notifyApplied(false, '应用失败：' + (e && e.message ? e.message : e));
    });
  }

  /* ---------------- 数据集回传给 App 缓存 ----------------
   * 分块走桥：整个 pack 有几 MB，一次性从 evaluateJavascript 返回会撞 Binder 上限。
   * 每块 180K 字符，App 侧拼起来写进自己的私有目录，下次启动直接读回来。 */
  var PACK_CHUNK = 180000;

  function exportPackChunks() {
    if (!lastPack) return false;
    if (!(window.Android && window.Android.packBegin && window.Android.packChunk && window.Android.packDone)) {
      return false;
    }
    var rec = {
      NODES_GLOBAL: lastPack.NODES_GLOBAL || '',
      SEGMENTS_GLOBAL: lastPack.SEGMENTS_GLOBAL || '',
      AIRPORTS_GLOBAL: lastPack.AIRPORTS_GLOBAL || '',
      label: '本机缓存',
      caac: ctxCaac || { routes: [], index: {} },
      priority: ctxPriority || {}
    };
    var text = JSON.stringify(rec);
    window.Android.packBegin(text.length);
    for (var i = 0; i < text.length; i += PACK_CHUNK) {
      window.Android.packChunk(text.substr(i, PACK_CHUNK));
    }
    window.Android.packDone();
    console.info('[EFB] 数据集已交给 App 缓存：' + text.length + ' 字符');
    return true;
  }

  /* ① 导航数据：earth_fix / earth_awy / earth_nav / earth_aptmeta */
  function applyNav(files) {
    console.info('[EFB] 解析导航数据 ' + files.length + ' 个文件');
    var parsed = NavParser.parse(files);
    var pack = parsed.pack || {};
    var airportsTxt = String(pack.AIRPORTS_GLOBAL || '');

    if (!airportsTxt) {
      var extra = collectAptMeta(files);
      if (extra.length) {
        /* 坐标 → 机场行必须走 PackCodec.encode，绝不手工拼字符串 */
        airportsTxt = String(PackCodec.encode({ airports: extra }).AIRPORTS_GLOBAL || '');
        console.info('[EFB] 用 aptmeta 补机场坐标 ' + extra.length + ' 个');
      }
    }

    var rec = {
      source: 'app',
      label: '导入数据 · ' + files.length + ' 个文件',
      createdAt: Date.now(),
      pack: {
        NODES_GLOBAL: String(pack.NODES_GLOBAL || ''),
        SEGMENTS_GLOBAL: String(pack.SEGMENTS_GLOBAL || ''),
        AIRPORTS_GLOBAL: airportsTxt
      },
      caac: ctxCaac || { routes: [], index: {} },
      domestic: { corridors: [] },
      priority: ctxPriority || {}
    };
    finishApply(rec, (parsed.warnings || []).join('；'));
  }

  /* ② AIP 航路表（CAAC 四张表的 CSV，或 caac_official.json）
     页面自己的 btnApplyAip 取的是 UI 内部变量 current.pack，外部拿不到；
     这里用上一次成功应用的 lastPack，没有再退化成把当前数据集重新编码，
     与页面内那条路径等价。 */
  function applyAip(files) {
    if (typeof AipParser === 'undefined') {
      notifyApplied(false, '这个版本的页面没有 AIP 解析器');
      return;
    }
    var ds = window.__DATASET__;
    if (!ds || !(ds.nodes || []).length) {
      notifyApplied(false, '还没有导航数据，请先在「① 导航数据」里导入 earth_fix / earth_awy');
      return;
    }
    console.info('[EFB] 解析 AIP 航路表 ' + files.length + ' 个文件');
    var parsed = AipParser.parse(files);
    var pack = lastPack || PackCodec.encode({
      nodes: ds.nodes, segments: ds.segments, airports: ds.airports
    });

    var rec = {
      source: 'aip',
      label: 'AIP 航路表 · ' + files.length + ' 个文件',
      createdAt: Date.now(),
      pack: pack,
      caac: parsed.official,
      priority: parsed.priority,
      domestic: null
    };
    var warn = (parsed.warnings || []).join('；');
    var stat = parsed.stats
      ? ('航路 ' + parsed.stats.routes + ' 条 / 机场 ' + parsed.stats.airports + ' 个')
      : '';
    finishApply(rec, stat + (warn ? '（' + warn + '）' : ''));
  }

  /* ③ 数据包（本工具导出的 routegen-pack.json） */
  function applyPack(files) {
    console.info('[EFB] 读取数据包 ' + files[0].name);
    var raw = JSON.parse(files[0].text);
    var pack = {
      NODES_GLOBAL: raw.NODES_GLOBAL || '',
      SEGMENTS_GLOBAL: raw.SEGMENTS_GLOBAL || '',
      AIRPORTS_GLOBAL: raw.AIRPORTS_GLOBAL || ''
    };
    if (!pack.NODES_GLOBAL && !pack.AIRPORTS_GLOBAL) {
      throw new Error('缺少 NODES_GLOBAL / AIRPORTS_GLOBAL 字段');
    }
    var rec = {
      source: 'pack',
      label: raw.label || raw._label || files[0].name,
      createdAt: Date.now(),
      pack: pack,
      // 缓存文件里带着上次生效的 CAAC 航路表与优先级，恢复时要一并还原
      caac: (raw.caac && raw.caac.routes) ? raw.caac : (ctxCaac || { routes: [], index: {} }),
      priority: raw.priority || ctxPriority || {},
      domestic: null
    };
    // 从缓存恢复时不回写缓存
    finishApply(rec, '数据已恢复', files[0].fromCache !== true);
  }

  api.datasetInfo = datasetInfo;
  api.getPlans = getPlans;
  api.getPlansJson = getPlansJson;
  api.exportJson = exportJson;
  api.stepLevel = stepLevel;
  api.importBegin = importBegin;
  api.importAppend = importAppend;
  api.importFileEnd = importFileEnd;
  api.importState = importState;
  api.importReset = importReset;
  api.importCommit = importCommit;
  api.closeDataPanel = closeDataPanel;

  // 工具栏适配是纯 CSS，head 一存在就能插，不必等 DOM
  injectToolbarFix();

  // 必须等 DOM 就绪：面板里的三个 file input 在 bindDataPanel() 前后才有意义
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      injectToolbarFix();
      hookFileInputs();
      watchPlans();
    });
  } else {
    hookFileInputs();
    watchPlans();
  }

  window.__EFB__ = api;
  console.info('[EFB] 桥已就绪 bridge=' + BRIDGE_VERSION + ' tool=' + TOOL_VERSION);
})();
