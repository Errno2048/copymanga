javascript:
if (typeof (loaded) == "undefined") {
    var loaded = true;
    var settings = {
        // true  = 显示官方 web 端的「輕小說」入口。
        showNovel: true,
        // 视为小说页面的 URL 片段（命中即交给 web 页面自行渲染，不启动漫画阅读器）。
        novelMarkers: ["/novel", "detailsNovel", "/ranobe"],
        // 关闭「遮罩弹层」的重试次数与间隔(ms)。
        popupRetries: 8,
        popupRetryMs: 400,
        // 同一章节在此时间内重复触发只拉起一次阅读器(Avoid double-tap double-open)。
        relaunchGuardMs: 1500,
        // 夜间模式偏好键（与站点自身设置区分开）。
        nightKey: "cm_night",
        // 站点小说阅读页自带的夜间参数键。
        novelStyleKey: "novelSteing",
        // 黑白漫画反色开关键。
        invertKey: "cm_invert",
        // 反色方式：value = 只反转亮度（保持色相）、rgb = 按通道直接反色。
        invertStyleKey: "cm_invert_style",
        tickMs: 800
    };
    var NIGHT_CSS = ""
        + "html,body{background:#121212 !important;color:#d8d8d8 !important;}"
        + "html,body,:root{color-scheme:dark !important;}"
        + ".van-cell,.van-cell-group,.van-cell-group__title{background:#1c1c1c !important;color:#d8d8d8 !important;}"
        + ".van-cell::after{border-color:#2a2a2a !important;}"
        + ".van-cell__title,.van-cell__value,.van-cell__label,.van-cell__text{color:#d8d8d8 !important;}"
        + ".van-nav-bar,.van-nav-bar__title,.van-nav-bar .van-icon{background:#1c1c1c !important;color:#d8d8d8 !important;}"
        + ".van-tabbar{background:#1c1c1c !important;}"
        + ".van-tabbar-item{color:#9a9a9a !important;}"
        + ".van-tabbar-item--active{color:#5b9dff !important;}"
        + ".van-tabs__nav,.van-tabs__wrap{background:#121212 !important;}"
        + ".van-tab{color:#bbbbbb !important;}"
        + ".van-tab--active{color:#5b9dff !important;}"
        + ".van-grid-item__content{background:#1c1c1c !important;color:#d8d8d8 !important;}"
        + ".copyApp,#app,.van-pull-refresh,.van-list{background:#121212 !important;}"
        + ".van-skeleton,.van-skeleton__row,.van-skeleton__title,.van-skeleton__avatar{background:#1c1c1c !important;}"
        + ".van-skeleton .van-skeleton__row,.van-skeleton__row.van-skeleton__row,."
        + "van-skeleton__title.van-skeleton__title,.van-skeleton__avatar.van-skeleton__avatar"
        + "{background:#1c1c1c !important;}"
        + ".van-grid-item__text,.van-grid-item__content span,.chapterItem,.chapterItem span,.van-ellipsis{color:#d8d8d8 !important;}"
        + ".van-card,.van-panel,.van-popup,.van-dialog,.van-action-sheet,.van-toast{background:#1c1c1c !important;color:#d8d8d8 !important;}"
        + ".van-dialog__message,.van-dialog__header{color:#d8d8d8 !important;}"
        + ".van-search,.van-search__content{background:#1c1c1c !important;}"
        + ".van-field__control,.van-field__label{background:transparent !important;color:#d8d8d8 !important;}"
        + ".van-button--default{background:#262626 !important;color:#d8d8d8 !important;border-color:#333333 !important;}"
        + ".van-divider,.van-empty__description,.van-loading__text{color:#9a9a9a !important;}"
        + ".cm-switch{display:inline-block;width:44px;height:24px;border-radius:12px;background:#555555;position:relative;vertical-align:middle;transition:background .2s;}"
        + ".cm-switch::after{content:'';position:absolute;top:2px;left:2px;width:20px;height:20px;border-radius:50%;background:#ffffff;transition:left .2s;}"
        + ".cm-switch.on{background:#1989fa;}"
        + ".cm-switch.on::after{left:22px;}";
    var invoke = {
        preUrl: "",
        launchedUrl: "",
        lastLaunch: { url: "", at: 0 },

        // ---------- Vue 实例定位 ----------
        vueRoot: function () {
            var vm = null;
            var el = document.getElementById("app");
            if (el && el.__vue__) vm = el.__vue__;
            if (!vm) {
                var all = document.body ? document.body.getElementsByTagName("*") : [];
                for (var i = 0; i < all.length; i++) {
                    if (all[i].__vue__) { vm = all[i].__vue__; break; }
                }
            }
            if (!vm) return null;
            try { return vm.$root || vm; } catch (e) { return vm; }
        },
        clearNotIos: function (vm, depth, budget) {
            if (!vm || depth > 15 || budget.n > 5000) return;
            budget.n++;
            try {
                if (vm._data && Object.prototype.hasOwnProperty.call(vm._data, "notIos") && vm.notIos !== false) {
                    vm.notIos = false;
                }
            } catch (e) {}
            var ch = vm.$children || [];
            for (var i = 0; i < ch.length; i++) this.clearNotIos(ch[i], depth + 1, budget);
        },
        // 站点用组件 data 中的 notIos 决定是否弹「安裝APP後可瀏覽完整內容」。
        // 置为 false 即走正常分支，小说全部卷与阅读页跨卷翻页都不再被拦。
        allowNovel: function () {
            var root = this.vueRoot();
            if (root) this.clearNotIos(root, 0, { n: 0 });
        },
        // ---------- 夜间模式 ----------
        _lastNight: null,
        _lastInvert: null,
        nightOn: function () {
            try { return localStorage.getItem(settings.nightKey) === "1"; } catch (e) { return false; }
        },
        isSettingUrl: function (url) {
            return url.replace(/^https?:\/\/[^\/]+/, "").indexOf("/setting") >= 0;
        },
        // 运行时把浅色背景改暗。站点大量使用 #fff 作为卡片/容器底色，
        // 且部分规则带更高特异性的 !important，逐类名枚举不可靠，
        // 因此在 DOM 上统一处理（带缓存标记，可逆）。
        _sumOf: function (c) {
            var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(c || "");
            if (!m) return null;
            return { sum: (+m[1]) + (+m[2]) + (+m[3]), alpha: m[4] === undefined ? 1 : parseFloat(m[4]),
                     spread: Math.max(+m[1], +m[2], +m[3]) - Math.min(+m[1], +m[2], +m[3]) };
        },
        _darkSurface: function (el) {
            var n = el;
            while (n && n.nodeType === 1) {
                var v = invoke._sumOf(getComputedStyle(n).backgroundColor);
                if (v && v.alpha > 0.05) return v.sum < 320;
                n = n.parentElement;
            }
            return false;
        },
        _kebab: function (s) {
            return s.replace(/[A-Z]/g, function (m) { return "-" + m.toLowerCase(); });
        },
        paintDark: function () {
            var all = document.getElementsByTagName("*");
            for (var i = 0; i < all.length; i++) {
                var el = all[i];
                var tag = el.tagName;
                if (tag === "IMG" || tag === "VIDEO" || tag === "CANVAS" || tag === "SVG" || tag === "PATH") continue;
                var cs = getComputedStyle(el);
                if (!el.__cmDark) {
                    var v = invoke._sumOf(cs.backgroundColor);
                    if (v && v.alpha > 0.05 && v.sum > 600 && v.spread <= 28) {
                        el.style.setProperty("background-color", "#1c1c1c", "important");
                        if (cs.backgroundImage && cs.backgroundImage !== "none") {
                            el.style.setProperty("background-image", "none", "important");
                        }
                        el.__cmDark = true;
                    }
                }
                // 站点用「与背景同色的粗边框」充当区块间距（例如 border-bottom:20px 白色），
                // 浅色主题下看不见，暗色主题下就成了白带，需要一并改暗。
                if (!el.__cmBrd) {
                    var sides = ["borderTopColor", "borderRightColor", "borderBottomColor", "borderLeftColor"];
                    var changed = false;
                    for (var k = 0; k < sides.length; k++) {
                        var wKey = sides[k].replace("Color", "Width");
                        if ((parseFloat(cs[wKey]) || 0) <= 0) continue;
                        var bv = invoke._sumOf(cs[sides[k]]);
                        if (bv && bv.alpha > 0.05 && bv.sum > 560 && bv.spread <= 28) {
                            el.style.setProperty(invoke._kebab(sides[k]), "#121212", "important");
                            changed = true;
                        }
                    }
                    if (changed) el.__cmBrd = true;
                }
                if (!el.__cmText) {
                    var t = invoke._sumOf(cs.color);
                    if (t && t.sum < 260 && invoke._darkSurface(el)) {
                        el.style.setProperty("color", "#d8d8d8", "important");
                        el.__cmText = true;
                    }
                }
            }
        },
        unpaint: function () {
            var all = document.getElementsByTagName("*");
            for (var i = 0; i < all.length; i++) {
                var el = all[i];
                if (el.__cmDark) {
                    el.style.removeProperty("background-color");
                    el.style.removeProperty("background-image");
                    el.__cmDark = false;
                }
                if (el.__cmText) { el.style.removeProperty("color"); el.__cmText = false; }
                if (el.__cmBrd) {
                    ["border-top-color", "border-right-color", "border-bottom-color", "border-left-color"].forEach(function (k) {
                        el.style.removeProperty(k);
                    });
                    el.__cmBrd = false;
                }
            }
        },
        applyNight: function () {
            var on = this.nightOn();
            var el = document.getElementById("cm-night-style");
            if (on && !el) {
                el = document.createElement("style");
                el.id = "cm-night-style";
                el.textContent = NIGHT_CSS;
                (document.head || document.documentElement).appendChild(el);
            } else if (!on && el && el.parentNode) {
                el.parentNode.removeChild(el);
            }
            if (on) this.paintDark(); else this.unpaint();
            this.applyToggles();
            if (this._lastNight !== on) {
                this._lastNight = on;
                try { if (typeof GM.setNightMode === "function") GM.setNightMode(on); } catch (e) {}
            }
        },
        setNight: function (on) {
            try { localStorage.setItem(settings.nightKey, on ? "1" : "0"); } catch (e) {}
            // 站点小说阅读页自带夜间参数，合并写入以免覆盖字号等设置
            try {
                var st = JSON.parse(localStorage.getItem(settings.novelStyleKey) || "{}");
                st.night = !!on;
                st.backgroundColor = on ? "#121212" : "#fff2cc";
                localStorage.setItem(settings.novelStyleKey, JSON.stringify(st));
            } catch (e) {}
            this.applyNight();
        },
        invertMode: function () {
            try {
                var v = localStorage.getItem(settings.invertKey);
                if (v === "auto") return "auto";
                if (v === "1" || v === "on") return "on";
                return "off";
            } catch (e) { return "off"; }
        },
        invertModeLabel: function (mode) {
            return mode === "on" ? "开启" : (mode === "auto" ? "自动识别黑白页" : "关闭");
        },
        cycleInvert: function () {
            var order = ["off", "auto", "on"];
            var next = order[(order.indexOf(this.invertMode()) + 1) % order.length];
            try { localStorage.setItem(settings.invertKey, next); } catch (e) {}
            this.applyToggles();
        },
        invertStyle: function () {
            try {
                return localStorage.getItem(settings.invertStyleKey) === "rgb" ? "rgb" : "value";
            } catch (e) { return "value"; }
        },
        invertStyleLabel: function (style) {
            return style === "rgb" ? "直接反色" : "只反转亮度（保色相）";
        },
        cycleInvertStyle: function () {
            var next = this.invertStyle() === "rgb" ? "value" : "rgb";
            try { localStorage.setItem(settings.invertStyleKey, next); } catch (e) {}
            this.applyToggles();
        },
        // 同步设置页两行的外观，并把反色模式回传给原生端。
        applyToggles: function () {
            var nightSw = document.getElementById("cm-night-switch");
            if (nightSw) nightSw.className = this.nightOn() ? "cm-switch on" : "cm-switch";
            var mode = this.invertMode();
            var val = document.getElementById("cm-invert-value");
            if (val) val.textContent = this.invertModeLabel(mode);
            if (this._lastInvert !== mode) {
                this._lastInvert = mode;
                try { if (typeof GM.setInvertMode === "function") GM.setInvertMode(mode); } catch (e) {}
            }
            var style = this.invertStyle();
            var sval = document.getElementById("cm-invert-style-value");
            if (sval) sval.textContent = this.invertStyleLabel(style);
            if (this._lastInvertStyle !== style) {
                this._lastInvertStyle = style;
                try { if (typeof GM.setInvertStyle === "function") GM.setInvertStyle(style); } catch (e) {}
            }
        },
        // 设置页注入开关：夜间模式（开关）、黑白漫画反色（三态循环）。幂等，可重复调用。
        installSettingRows: function () {
            var groups = document.getElementsByClassName("van-cell-group");
            if (!groups.length) return;
            var self = this;
            var rows = [
                { id: "cm-night-cell", kind: "switch", label: "夜间模式" },
                { id: "cm-invert-cell", kind: "cycle", label: "黑白漫画反色", valueId: "cm-invert-value" },
                { id: "cm-invert-style-cell", kind: "cycle", label: "反色方式", valueId: "cm-invert-style-value" }
            ];
            for (var i = 0; i < rows.length; i++) {
                if (document.getElementById(rows[i].id)) continue;
                var row = rows[i];
                var wrap = document.createElement("div");
                wrap.className = "van-cell-group";
                var right = row.kind === "switch"
                    ? '<i id="cm-night-switch" class="cm-switch"></i>'
                    : '<span id="' + row.valueId + '" style="color:#d8d8d8"></span>';
                wrap.innerHTML = '<div class="van-cell" id="' + row.id + '">'
                    + '<div class="van-cell__title"><span>' + row.label + '</span></div>'
                    + '<div class="van-cell__value">' + right + '</div></div>';
                (function (r) {
                    wrap.addEventListener("click", function (e) {
                        e.stopPropagation();
                        e.preventDefault();
                        if (r.kind === "switch") self.setNight(!self.nightOn());
                        else if (r.id === "cm-invert-style-cell") self.cycleInvertStyle();
                        else self.cycleInvert();
                    });
                })(row);
                groups[0].parentNode.insertBefore(wrap, groups[0].nextSibling);
            }
            self.applyNight();
            self.applyToggles();
        },
        // 统一定时兜底：
        // - 小说页面组件可能懒加载/重建，晚于路由变化与一次性触发点，需要重试；
        // - 夜间模式样式与设置页开关需要在 SPA 页面切换后重新应用/注入。
        startTick: function () {
            if (this.tickTimer) return;
            var self = this;
            this.tickTimer = setInterval(function () {
                if (document.hidden) return;
                var url = location.href;
                if (self.isNovelUrl(url)) self.allowNovel();
                self.applyNight();
                if (self.isSettingUrl(url)) self.installSettingRows();
                if (self.isNovelDetailUrl(url)) {
                    self.loadNovelBook();
                    self.installNovelDownloadButton();
                }
                self.installNovelVolumeHook();
                self.installPersonalHooks();
                self.fixPersonalTab();
                self.installRouterGuard();
                // 未登录却还留着上一次的身份/缓存时兜底清理
                if (self.loggedIn()) self.accountCleared = false;
                else if (!self.accountCleared) {
                    self.accountCleared = true;
                    self.clearAccount();
                }
                self.backToPersonalIfNeeded();
                self.correctPersonalTabLanding();
            }, settings.tickMs);
        },

        // ---------- 个人页：取消“必须先登录才能进入”的限制 ----------
        //
        // 站点本身允许未登录渲染 /personal（会显示登录引导），底部栏却在未登录时
        // 把「個人」换成 to:"/login" 的「去登陸」，导致从界面上根本进不去个人页。
        // 另外站点的登出（deleteToken）只清了 token 与 localStorage.user，
        // 没有清 Vuex 里缓存的身份与书架/浏览记录，会残留上一次登录的信息。

        PERSONAL_TEXTS: ["個人資料", "留言專區", "書架", "瀏覽記錄",
                         "个人资料", "留言专区", "书架", "浏览记录"],
        LOGIN_TAB_TEXTS: ["去登陸", "去登陆", "去登录"],
        PERSONAL_TAB_LABEL: { "去登陸": "個人", "去登陆": "个人", "去登录": "个人" },
        LOGOUT_TEXTS: ["登出", "退出登錄", "退出登录"],
        personalHookInstalled: false,
        accountCleared: false,

        isPersonalUrl: function (url) {
            var path = url.replace(/^https?:\/\/[^\/]+/, "");
            return /(^|\/)personal(\/|$|\?)/.test(path) && path.indexOf("/personal/") !== 0
                || /^\/(h5\/)?personal(\/|$|\?)/.test(path);
        },
        storeOf: function () {
            try {
                var root = this.vueRoot();
                return root && root.$store ? root.$store : null;
            } catch (e) { return null; }
        },
        loggedIn: function () {
            var store = this.storeOf();
            return !!(store && store.state && store.state.token);
        },
        // 清掉上一次登录遗留的身份与缓存（登出后站点没清干净）
        clearAccount: function () {
            var store = this.storeOf();
            if (!store) return;
            try { store.commit("deleteToken"); } catch (e) {}
            try {
                var st = store.state;
                st.personalData = {};
                st.bookRack = {};
                st.bookrack = {};
                st.personal = {};
                if (st.cache) {
                    var cache = Object.assign({}, st.cache);
                    delete cache.bookrack;
                    delete cache.personalRecord;
                    delete cache.details;
                    st.cache = cache;
                }
            } catch (e) {}
            try { localStorage.removeItem("user"); } catch (e) {}
        },
        // 未登录时底部栏那一项是「去登陸」，改成「個人」并标记，点击时进个人页
        fixPersonalTab: function () {
            var items = document.getElementsByClassName("van-tabbar-item");
            for (var i = 0; i < items.length; i++) {
                var item = items[i];
                var textEl = item.getElementsByClassName("van-tabbar-item__text")[0];
                if (!textEl) continue;
                var text = (textEl.innerText || "").trim();
                if (this.LOGIN_TAB_TEXTS.indexOf(text) < 0) continue;
                item.setAttribute("data-cm-personal", "1");
                var label = this.PERSONAL_TAB_LABEL[text];
                var span = textEl.getElementsByTagName("span")[0] || textEl;
                if (label && span.textContent !== label) span.textContent = label;
                this.fixPersonalIcon(item);
            }
        },
        // 图标也要从「登录」换成「个人」
        fixPersonalIcon: function (item) {
            var iconEl = item.getElementsByClassName("van-tabbar-item__icon")[0];
            if (!iconEl) return;
            var use = iconEl.getElementsByTagName("use")[0];
            if (use) {
                if (use.getAttribute("xlink:href") !== "#icontab_btn_nor_my-2") {
                    use.setAttribute("xlink:href", "#icontab_btn_nor_my-2");
                    use.setAttributeNS("http://www.w3.org/1999/xlink", "xlink:href", "#icontab_btn_nor_my-2");
                }
                return;
            }
            if (iconEl.getAttribute("data-cm-icon") === "1") return;
            iconEl.setAttribute("data-cm-icon", "1");
            iconEl.textContent = "";
            var svgNs = "http://www.w3.org/2000/svg";
            var svg = document.createElementNS(svgNs, "svg");
            svg.setAttribute("class", "icon");
            svg.setAttribute("aria-hidden", "true");
            var u = document.createElementNS(svgNs, "use");
            u.setAttributeNS("http://www.w3.org/1999/xlink", "xlink:href", "#icontab_btn_nor_my-2");
            svg.appendChild(u);
            iconEl.appendChild(svg);
        },
        // 点了底部栏的个人入口却落到登录页时，改去个人页
        correctPersonalTabLanding: function () {
            var flag = false;
            try { flag = sessionStorage.getItem("cm_login_tab_click") === "1"; } catch (e) {}
            if (!flag) return;
            if (this.isPersonalUrl(location.href)) {
                try { sessionStorage.removeItem("cm_login_tab_click"); } catch (e) {}
                return;
            }
            var path = location.href.replace(/^https?:\/\/[^\/]+/, "");
            if (/\/login(\/|$|\?)/.test(path)) {
                try { sessionStorage.removeItem("cm_login_tab_click"); } catch (e) {}
                if (!this.loggedIn()) {
                    try { this.vueRoot().$router.replace("/personal"); } catch (e) {}
                }
            } else {
                try { sessionStorage.removeItem("cm_login_tab_click"); } catch (e) {}
            }
        },
        goLogin: function () {
            try { sessionStorage.setItem("cm_back_personal", "1"); } catch (e) {}
            try { this.vueRoot().$router.push("/login"); } catch (e) {}
        },
        // 登录成功后回到个人页（站点登录后默认跳首页）
        backToPersonalIfNeeded: function () {
            var pending = false;
            try { pending = sessionStorage.getItem("cm_back_personal") === "1"; } catch (e) {}
            if (!pending || !this.loggedIn()) return;
            try { sessionStorage.removeItem("cm_back_personal"); } catch (e) {}
            if (this.isPersonalUrl(location.href)) return;
            var self = this;
            setTimeout(function () {
                try { self.vueRoot().$router.replace("/personal"); } catch (e) {}
            }, 400);
        },
        installPersonalHooks: function () {
            if (this.personalHookInstalled) return;
            this.personalHookInstalled = true;
            var self = this;
            document.addEventListener("click", function (e) {
                var text = (e.target && e.target.innerText ? e.target.innerText : "").trim();
                // 登出：让站点自己发请求，之后再兜底清一次（含 Vuex 缓存）
                if (text.length <= 8 && self.LOGOUT_TEXTS.indexOf(text) >= 0) {
                    setTimeout(function () { self.clearAccount(); }, 1500);
                }
                // 1) 底部栏「去登陸」-> 个人页
                // 注意：不能用 indexOf("van-tabbar-item")，它会先匹配到
                // van-tabbar-item__icon（图标容器），必须精确匹配类名
                var node = e.target;
                while (node && node !== document.body &&
                       !(node.classList && node.classList.contains("van-tabbar-item"))) {
                    node = node.parentElement;
                }
                if (node && node !== document.body &&
                    node.getAttribute("data-cm-personal") === "1") {
                    e.stopPropagation();
                    e.preventDefault();
                    // 站点自己的处理器在真实触摸下仍可能把地址推到 /login，
                    // 这里留个标记，落地时再纠正一次（见 correctPersonalTabLanding）
                    try { sessionStorage.setItem("cm_login_tab_click", "1"); } catch (err) {}
                    try { self.vueRoot().$router.push("/personal"); } catch (err) {}
                    return;
                }
                // 2) 未登录时，需登录的功能 -> 登录页
                if (!self.isPersonalUrl(location.href) || self.loggedIn()) return;
                var el = e.target;
                while (el && el !== document.body) {
                    var t = (el.innerText || "").trim();
                    if (t && t.length <= 8 && self.PERSONAL_TEXTS.indexOf(t) >= 0) {
                        e.stopPropagation();
                        e.preventDefault();
                        self.goLogin();
                        return;
                    }
                    el = el.parentElement;
                }
            }, true);
        },

        // ---------- 小说：下载与原生阅读器 ----------

        novelBook: null,
        novelHookInstalled: false,

        isNovelDetailUrl: function (url) {
            return url.replace(/^https?:\/\/[^\/]+/, "").indexOf("/detailsNovel/") >= 0;
        },
        // 站点网页自身的接口主机就是把 www 换成 api
        apiBaseOf: function () {
            return location.origin.replace("://www.", "://api.").replace("://copy-", "://api.copy-");
        },
        novelPathWord: function () {
            var m = /detailsNovel\/([^\/?#]+)/.exec(location.href);
            return m ? m[1] : null;
        },
        loadNovelBook: function (cb) {
            var self = this;
            var pw = self.novelPathWord();
            if (!pw) return;
            if (self.novelBook && self.novelBook.pathWord === pw) { if (cb) cb(self.novelBook); return; }
            var api = self.apiBaseOf();
            fetch(api + "/api/v3/book/" + pw)
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    var book = j && j.results && j.results.book;
                    return fetch(api + "/api/v3/book/" + pw + "/volumes").then(function (r) { return r.json(); })
                        .then(function (vj) {
                            var list = (vj && vj.results && vj.results.list) || [];
                            self.novelBook = {
                                pathWord: pw,
                                name: (book && book.name) || document.title,
                                apiBase: api,
                                volumes: list.map(function (v) { return { id: String(v.id), name: v.name }; })
                            };
                            if (cb) cb(self.novelBook);
                        });
                })
                .catch(function () {});
        },
        // 卷详情 -> 传给原生的约定结构
        novelRequestOf: function (book, volumeId, cb) {
            fetch(book.apiBase + "/api/v3/book/" + book.pathWord + "/volume/" + volumeId)
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    var v = j && j.results && j.results.volume;
                    if (!v) return;
                    cb({
                        pathWord: book.pathWord,
                        name: book.name,
                        apiBase: book.apiBase,
                        volumes: book.volumes,
                        volume: {
                            id: String(v.id),
                            name: v.name,
                            index: v.index,
                            txtAddr: v.txt_addr,
                            encoding: v.txt_encoding,
                            prev: v.prev == null ? null : String(v.prev),
                            next: v.next == null ? null : String(v.next),
                            // content_type=1 正文（带行区间）、=2 插图（content 即原图链接）
                            chapters: (v.contents || []).map(function (c) {
                                return {
                                    name: (c.name || "").trim(),
                                    start: c.start_lines,
                                    end: c.end_lines,
                                    type: c.content_type || 1,
                                    imageUrl: c.content || ""
                                };
                            })
                        }
                    });
                })
                .catch(function () {});
        },
        openNovelVolume: function (book, volumeId) {
            var self = this;
            self.novelRequestOf(book, volumeId, function (req) {
                try { GM.openNovelReader(JSON.stringify(req)); } catch (e) {}
            });
        },
        downloadNovel: function () {
            var self = this;
            var book = self.novelBook;
            if (!book) { self.loadNovelBook(function () { self.downloadNovel(); }); return; }
            var st = document.getElementById("cm-novel-dl-state");
            if (st) st.textContent = "已提交";
            var req = { pathWord: book.pathWord, name: book.name, apiBase: book.apiBase, volumes: book.volumes, volume: null };
            try { GM.downloadNovel(JSON.stringify(req)); } catch (e) {}
        },
        installNovelDownloadButton: function () {
            if (document.getElementById("cm-novel-dl")) return;
            var host = document.querySelector("main") || document.body;
            if (!host) return;
            var self = this;
            var wrap = document.createElement("div");
            wrap.className = "van-cell-group";
            wrap.innerHTML = '<div class="van-cell" id="cm-novel-dl">'
                + '<div class="van-cell__title"><span>下载整本小说</span></div>'
                + '<div class="van-cell__value"><span id="cm-novel-dl-state"></span></div></div>';
            wrap.addEventListener("click", function (e) {
                e.stopPropagation();
                e.preventDefault();
                self.downloadNovel();
            });
            host.insertBefore(wrap, host.firstChild);
            self.applyNight();
        },
        // 卷列表按 DOM 顺序与接口顺序一一对应；点击卷一律交给原生阅读器
        installNovelVolumeHook: function () {
            if (this.novelHookInstalled) return;
            this.novelHookInstalled = true;
            var self = this;
            document.addEventListener("click", function (e) {
                if (!self.isNovelDetailUrl(location.href)) return;
                var book = self.novelBook;
                if (!book) return;
                var el = e.target;
                while (el && el !== document.body && String(el.className || "").indexOf("chapterItem") < 0) {
                    el = el.parentElement;
                }
                if (!el || el === document.body) return;
                var items = Array.prototype.slice.call(document.getElementsByClassName("chapterItem"));
                var idx = items.indexOf(el);
                if (idx < 0 || !book.volumes[idx]) return;
                var volumeId = book.volumes[idx].id;
                // 不再要求“已下载”：一律用内嵌阅读器打开，未下载的卷由它在线取正文
                e.stopPropagation();
                e.preventDefault();
                self.openNovelVolume(book, volumeId);
            }, true);
        },

        // ---------- 漫画：详情页直接拉起阅读器，不显示中间的内容页 ----------
        installRouterGuard: function () {
            var root = this.vueRoot();
            var router = null;
            try { router = root && (root.$router || (root.$root && root.$root.$router)); } catch (e) {}
            if (!router || router.__cmGuarded) return;
            router.__cmGuarded = true;
            var self = this;
            router.beforeEach(function (to, from, next) {
                try {
                    var fp = (to && (to.fullPath || to.path)) || "";
                    // 小说内容页：详情页的「續看」等按钮走的是路由而不是卷列表点击，
                    // 这里一并拦下，交给内嵌阅读器
                    var mv = /novelContent\/([^\/]+)\/([^\/?#]+)/.exec(fp);
                    if (mv) {
                        var nb = self.novelBook;
                        if (nb && nb.pathWord === mv[1]) {
                            self.openNovelVolume(nb, mv[2]);
                            next(false);
                            return;
                        }
                    }
                    var m = /comicContent\/([^\/]+)\/([^\/?#]+)/.exec(fp);
                    if (m) {
                        var url = "https://cm.local/comicContent/" + m[1] + "/" + m[2];
                        var now = Date.now();
                        if (self.lastLaunch.url !== url || now - self.lastLaunch.at > settings.relaunchGuardMs) {
                            self.lastLaunch = { url: url, at: now };
                            var fn = (typeof GM.loadComicDirect === "function") ? GM.loadComicDirect : GM.loadComic;
                            fn.call(GM, url);
                        }
                        next(false);   // 取消导航：可见 WebView 留在详情页
                        return;
                    }
                    setTimeout(function () { self.allowNovel(); }, 300);
                } catch (e) {}
                next();
            });
            router.afterEach(function () { setTimeout(function () { self.allowNovel(); }, 300); });
        },

        // ---------- 原有逻辑 ----------
        hideRanobeTab: function () {
            var tabs = document.getElementsByClassName("van-tabbar-item");
            for (var i = 0; i < tabs.length; i++) {
                if (tabs[i].innerText == "輕小說") tabs[i].style.display = "none";
            }
        },
        hideRanobeRack: function () {
            var tabs = document.getElementsByClassName("van-tabs van-tabs--line");
            if (tabs.length) tabs[0].hidden = true;
        },
        pinTitle: function () {
            var game = document.getElementsByName("exchange");
            if (game.length) game[0].hidden = true;
        },
        notCallGM: function (url) {
            if (this.preUrl == url) return false;
            this.preUrl = url;
            return true;
        },
        resetPreUrl: function () { this.preUrl = ""; },
        isNovelUrl: function (url) {
            var path = url.replace(/^https?:\/\/[^\/]+/, "");
            for (var i = 0; i < settings.novelMarkers.length; i++) {
                if (path.indexOf(settings.novelMarkers[i]) >= 0) return true;
            }
            return false;
        },
        clickClass: function (name, index) {
            var els = document.getElementsByClassName(name);
            if (!els || els.length <= index) return false;
            try { els[index].click(); return true; } catch (e) { return false; }
        },
        clickClassCenter: function (name, index) {
            var els = document.getElementsByClassName(name);
            if (!els || els.length <= index) return false;
            try {
                var ev = document.createEvent('HTMLEvents');
                ev.clientX = innerWidth / 2;
                ev.clientY = innerHeight / 2;
                ev.initEvent('click', false, true);
                els[index].dispatchEvent(ev);
                return true;
            } catch (e) { return false; }
        },
        dismissPopup: function (remaining) {
            if (this.clickClassCenter("comicContentPopupImageItem", 0)) return;
            if (remaining > 0) {
                var self = this;
                setTimeout(function () { self.dismissPopup(remaining - 1); }, settings.popupRetryMs);
            }
        },
        // 兜底：若路由守卫未生效（例如老版本无 loadComicDirect），仍在内容页拉起阅读器。
        loadChapter: function () {
            var self = this;
            var url = location.href;
            if (self.launchedUrl === url) return;
            self.launchedUrl = url;
            GM.loadComic(url);
            self.dismissPopup(settings.popupRetries);
        },
        urlChangeListener: function (todo) {
            setInterval(function () { if (invoke.notCallGM(location.href)) { todo(); } }, 1000);
        }
    };
    function modify() {
        var url = location.href;
        GM.hideFab();
        invoke.applyNight();
        if (invoke.isNovelUrl(url)) invoke.allowNovel();
        if (url.indexOf("/comicContent/") < 0) invoke.launchedUrl = "";
        if (invoke.isNovelUrl(url)) return;
        if (url.endsWith("/index")) {
            invoke.pinTitle();
            if (!settings.showNovel) invoke.hideRanobeTab();
        }
        else if (url.endsWith("/bookrack")) {
            if (!settings.showNovel) { invoke.hideRanobeTab(); invoke.hideRanobeRack(); }
        }
        else if (url.indexOf("/searchContent") > 0) {
            if (!settings.showNovel) invoke.hideRanobeRack();
        }
        else if (url.indexOf("/comicContent/") > 0) invoke.loadChapter();
        else if (url.indexOf("/details/comic/") > 0) GM.loadComic(url);
        else if (url.indexOf("/personal") > 0) {
            if (!settings.showNovel) invoke.hideRanobeTab();
            GM.enterProfile();
        }
    }
    invoke.preUrl = location.href;
    invoke.installRouterGuard();
    invoke.allowNovel();
    modify();
    invoke.urlChangeListener(modify);
    setTimeout(function () { invoke.installRouterGuard(); invoke.allowNovel(); }, 800);
    invoke.applyNight();
    invoke.startTick();
    setTimeout(function () { invoke.allowNovel(); }, 2500);
} else {
    setTimeout(modify, 1280);
}