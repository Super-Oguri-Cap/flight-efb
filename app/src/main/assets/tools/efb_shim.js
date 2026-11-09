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
    /* v8.6：直升机场 / 水上基地被排除在 airportByIcao 之外（选了会提示不支持），
       这里报「可选机场数」，和页面顶部计数保持一致；total 另给一个，方便排查。 */
    var usable = ds.airportByIcao ? ds.airportByIcao.size : (ds.airports || []).length;
    return {
      airports: usable,
      airportsTotal: (ds.airports || []).length,
      special: ds.specialByIcao ? ds.specialByIcao.size : 0,
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

  /* ---------------- 「选择数据文件夹」入口 ----------------
   * 用户不用再记「earth_fix / earth_awy / earth_aptmeta 各在哪」：
   * 指到导航包解压后的那一层，App 扫描目录、按文件名分角色、自动挑齐。
   * 纯附加按钮：App 没实现 pickFolder 时退回原来的多选文件。 */
  function addFolderButton(paneId, kind, text) {
    var id = 'efbFolder_' + kind;
    if (document.getElementById(id)) return true;
    var pane = document.getElementById(paneId);
    if (!pane) return false;
    var drop = pane.querySelector('.drop');
    if (!drop) return false;
    var b = document.createElement('button');
    b.id = id;
    b.type = 'button';
    b.className = 'primary';
    b.style.cssText = 'width:100%;margin-top:8px';
    b.textContent = text;
    b.addEventListener('click', function () {
      if (window.Android && window.Android.pickFolder) { window.Android.pickFolder(kind); return; }
      var el = document.getElementById(kind === 'aip' ? 'fileAip' : 'fileNav');
      if (el) el.click();
    });
    drop.parentNode.insertBefore(b, drop.nextSibling);
    return true;
  }

  function injectFolderButtons() {
    addFolderButton('pane-nav', 'nav', '📁 选择数据文件夹（自动找 earth_fix / awy / nav / aptmeta）');
    addFolderButton('pane-aip', 'aip', '📁 选择 AIP 文件夹（自动找 CSV / zip）');
  }

  /* ---------------- 顶部工具栏适配 ----------------
   * v8.6 起页面自己带了 @media(max-width:640px) 的窄屏规则：顶栏换行、品牌独占一行、
   * 数据标签省略、按钮压缩。1.3.6 打的那段 900px 补丁已经没用了 ——
   * 留着反而会覆盖页面新写的 order/font-size，把版本号藏起来、把标签挤到按钮前面。
   * 所以这里不再注入任何 CSS，等页面自己的媒体查询生效。 */

  /* ---------------- 主题：App 固定深色，页面默认跟随系统 ----------------
   * v8.6 新增白天模式，storedTheme() 先读 localStorage（本 WebView 关了 DOM Storage，
   * 读不到），再退回 prefers-color-scheme。手机是浅色模式时，工具页会白底打开，
   * 和整个 App 的深色外壳撞在一起。
   * 这里在首屏后把页面切回深色 —— 走页面自己的 #btnTheme，变量 / 图标 / title 一起变，
   * 不写死任何颜色；用户当次仍可点 ☀️ 换白天，不拦。 */
  var themeTries = 0;

  function forceDarkTheme() {
    var el = document.documentElement;
    if (!el) return;
    if (el.getAttribute('data-theme') !== 'light') return;   // 已经是深色，什么都不做
    var b = document.getElementById('btnTheme');
    if (!b) return;
    themeTries++;
    b.click();                                               // 页面自己的 toggleTheme
    if (el.getAttribute('data-theme') === 'light' && themeTries < 6) {
      setTimeout(forceDarkTheme, 250);                       // 监听还没挂上，稍后再试
    }
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
   * @param keepSelection 应用后是否把起降机场填回去。
   *   页面的 UI.applyData 会 resetSelection()（清空输入、置灰「生成航路」），
   *   这对「换导航数据」是合理的，但 AIP 航路表只是给同一份数据加一层官方航路，
   *   用户刚选好的机场被清掉，看起来就像「导完 AIP 不能算了」。所以 AIP 走这条要保留。
   */
  function finishApply(rec, okMessage, persist, keepSelection) {
    var before = keepSelection ? readAirportInputs() : null;
    return UI.applyData(rec, {}).then(function (ok) {
      if (ok) {
        lastPack = rec.pack;
        ctxCaac = rec.caac;
        ctxPriority = rec.priority;
        if (before) restoreAirportInputs(before);
        if (persist !== false) exportPackChunks();
      }
      notifyApplied(!!ok, ok ? (okMessage || '') : '数据不可用');
    }).catch(function (e) {
      notifyApplied(false, '应用失败：' + (e && e.message ? e.message : e));
    });
  }

  /* ---------------- 起降机场输入框的存取 ----------------
   * 页面没有对外暴露 sel，只能操作 DOM：填值 + 派发 input，
   * 让页面自己的 validate() 重新按新数据集校验并点亮「生成航路」。 */
  function readAirportInputs() {
    var f = document.getElementById('fromInput');
    var t = document.getElementById('toInput');
    return { from: f ? String(f.value || '') : '', to: t ? String(t.value || '') : '' };
  }

  function restoreAirportInputs(v) {
    if (!v) return;
    var pairs = [['fromInput', v.from], ['toInput', v.to]];
    for (var i = 0; i < pairs.length; i++) {
      var el = document.getElementById(pairs[i][0]);
      if (!el || !pairs[i][1]) continue;
      el.value = pairs[i][1];
      try {
        el.dispatchEvent(new Event('input', { bubbles: true }));
      } catch (e) { /* 老 WebView 不支持构造 Event 时忽略 */ }
    }
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

  /* ① 导航数据：earth_fix / earth_awy / earth_nav / apt.dat / earth_aptmeta */
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

    var warn = (parsed.warnings || []).slice();
    if (!airportsTxt) {
      /* 没有机场坐标 = 起降机场输不进去 = 整页算不了。必须说清楚缺哪个文件。 */
      warn.unshift('没有机场坐标：请补 earth_aptmeta.dat 或 apt.dat，' +
        '否则输入 ZBAA 会提示「数据库里没有」，无法生成航路');
    }
    /* v8.6 起页面自己解析/合并 aptmeta 与 apt.dat，还会在两者都缺时从进离场点推算机场，
       所以不再过滤含 "apt.dat" 的告警 —— 那些现在是有效信息，不是误报。 */
    finishApply(rec, warn.join('；'));
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

    /* CAAC 的压缩包里同时放着同名的 PDF 报表。PDF 进 CSV 解析只会得到
       「表头里没有 Fly along 列」这种看不懂的告警，直接在这里挑掉；
       zip 由 App 端解压后按条目送进来，走到这里已经不是 zip 了。 */
    var usable = [];
    var skipped = [];
    for (var i = 0; i < files.length; i++) {
      var n = String(files[i].name || '');
      if (/\.(pdf|zip)$/i.test(n)) { skipped.push(n); continue; }
      usable.push(files[i]);
    }
    if (!usable.length) {
      notifyApplied(false, 'AIP 入口只认 .csv / .json。PDF 请放进航图库；' +
        'zip 请用「选择数据文件夹」，或解压后选那四张 CSV。');
      return;
    }

    console.info('[EFB] 解析 AIP 航路表 ' + usable.length + ' 个文件' +
      (skipped.length ? '（跳过 ' + skipped.length + ' 个 PDF/压缩包）' : ''));
    var parsed = AipParser.parse(usable);

    if (!parsed.stats.routes) {
      notifyApplied(false, '没解析出任何航路：' +
        ((parsed.warnings || []).join('；') || '检查表头里是否有 "Fly along" 列'));
      return;
    }

    var pack = lastPack || PackCodec.encode({
      nodes: ds.nodes, segments: ds.segments, airports: ds.airports
    });

    var rec = {
      source: 'aip',
      label: 'AIP 航路表 · ' + usable.length + ' 个文件',
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
    /* 第四个参数 = 保留起降机场：AIP 只是加一层官方航路，没有换数据集 */
    finishApply(rec, stat + (warn ? '（' + warn + '）' : ''), true, true);
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

  // 必须等 DOM 就绪：面板里的三个 file input 在 bindDataPanel() 前后才有意义
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      injectFolderButtons();
      hookFileInputs();
      watchPlans();
      forceDarkTheme();
    });
  } else {
    injectFolderButtons();
    hookFileInputs();
    watchPlans();
    forceDarkTheme();
  }

  window.__EFB__ = api;
  console.info('[EFB] 桥已就绪 bridge=' + BRIDGE_VERSION + ' tool=' + TOOL_VERSION);
})();
