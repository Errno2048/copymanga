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
        // 跨页面失效标记（同源共享 localStorage，多实例可实时收到 storage 事件）
        dirtyKey: "cm_dirty",
        // 是否记住各页面的滚动位置（设置页可关）
        scrollMemKey: "cm_scroll_memory",
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
            var mval = document.getElementById("cm-scrollmem-value");
            if (mval) mval.textContent = this.scrollMemoryLabel();
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
                { id: "cm-invert-style-cell", kind: "cycle", label: "反色方式", valueId: "cm-invert-style-value" },
                { id: "cm-scrollmem-cell", kind: "cycle", label: "记住滚动位置", valueId: "cm-scrollmem-value" }
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
                        else if (r.id === "cm-scrollmem-cell") self.cycleScrollMemory();
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
                self.collectComicMeta();
                self.installNoticeObserver();
                self.installNoticeFilter();
                self.installContinueButton();
                self.watchLogin();
                self.installShelfHook();
                self.installNovelVolumeHook();
                self.installPersonalHooks();
                self.fixPersonalTab();
                self.installRouterGuard();
                // 本域没有凭证但备份里有 -> 先种回去（跨镜像域不掉登录）
                self.restoreCredential();
                // 未登录却还留着上一次的身份/缓存时兜底清理（只清缓存，不删凭证）
                if (self.loggedIn()) self.accountCleared = false;
                else if (!self.accountCleared) {
                    self.accountCleared = true;
                    self.clearAccount();
                }
                self.syncCredential();
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
        // 持久化登录凭证：站点把它存在 localStorage.user 里，是唯一事实来源
        storedCredential: function () {
            try { return localStorage.getItem("user") || ""; } catch (e) { return ""; }
        },
        loggedIn: function () {
            // 必须以持久化凭证为准：站点只在 getInfo/postInfo 这类组件里才把 token 灌进 Vuex，
            // 其它页面（以及任何新文档加载的早期）store 里是空的，据此判定会把好登录清掉。
            if (this.storedCredential()) return true;
            var store = this.storeOf();
            return !!(store && store.state && store.state.token);
        },
        // 本域没有凭证但原生备份里有（例如切到了另一个镜像域）：种回去。纯本地，不发请求。
        restoreCredential: function () {
            if (this.storedCredential()) return false;
            var saved = "", from = "";
            try {
                saved = (typeof GM.savedCredential === "function") ? GM.savedCredential() : "";
                from = (typeof GM.savedCredentialOrigin === "function") ? GM.savedCredentialOrigin() : "";
            } catch (e) {}
            // 只在「备份来自另一个镜像域」时还原：同域下凭证消失就是真的登出，不能顶回去
            if (!saved || !from || from === location.origin) return false;
            try { localStorage.setItem("user", saved); } catch (e) { return false; }
            try {
                var store = this.storeOf();
                var u = JSON.parse(saved);
                if (store && store.state && u && u.token) {
                    store.state.token = u.token;
                    if (u.userId) store.state.userId = u.userId;
                }
            } catch (e) {}
            return true;
        },
        // 凭证变化时同步到原生备份；连续几次为空才当作登出（避免写入过程中的瞬时为空）
        syncCredential: function () {
            var cur = this.storedCredential();
            if (cur) {
                this._emptyTicks = 0;
                if (this._lastCred !== cur) {
                    this._lastCred = cur;
                    try { if (typeof GM.rememberCredential === "function") GM.rememberCredential(cur, location.origin); } catch (e) {}
                }
                return;
            }
            if (this._lastCred !== undefined && this._lastCred !== "") {
                // 站点只在登录时写、登出时删，不存在「瞬时为空」，所以空了就是登出
                this._lastCred = "";
                try { if (typeof GM.forgetCredential === "function") GM.forgetCredential(); } catch (e) {}
            }
        },
        // 清掉上一次登录遗留的身份与缓存（登出后站点没清干净）
        clearAccount: function (explicitLogout) {
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
            // 只有显式登出（或凭证确实已经不在了）才允许删持久化凭证。
            // 自动兜底清理只负责清 Vuex 残留缓存，绝不能把有效登录删掉。
            if (explicitLogout || !this.storedCredential()) {
                try { localStorage.removeItem("user"); } catch (e) {}
            }
            // 显式登出：立刻丢弃备份，避免下次加载时被「还原」回来
            if (explicitLogout) {
                try { if (typeof GM.forgetCredential === "function") GM.forgetCredential(); } catch (e) {}
            }
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
                    setTimeout(function () { self.clearAccount(true); }, 1500);
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
                                // 封面/作者：供「我的下载」按书架样式展示（下载时落盘）
                                cover: self.coverFromDom(pw) || self.coverUrlOf(book && book.cover),
                                author: self.authorTextOf(book && book.author),
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
                        cover: book.cover || "",
                        author: book.author || "",
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
            var req = {
                pathWord: book.pathWord, name: book.name, apiBase: book.apiBase,
                cover: book.cover || "", author: book.author || "",
                volumes: book.volumes, volume: null
            };
            try { GM.downloadNovel(JSON.stringify(req)); } catch (e) {}
        },
        // ---------------- 页面栈协作（滚动位置 + 原地返回 + 失效刷新） ----------------
        // 页面标识含查询串：同一 path 的不同筛选（若有）各记各的位置
        pageUrl: function () { return location.origin + location.pathname + (location.search || ""); },
        // 站点把内容放在内层容器里滚（首页是 .homeTemplate 等），取最深的可滚动容器
        scroller: function () {
            var best = null;
            var all = document.querySelectorAll("div");
            for (var i = 0; i < all.length; i++) {
                var e = all[i];
                try {
                    var st = getComputedStyle(e);
                    if ((st.overflowY === "auto" || st.overflowY === "scroll")
                        && e.scrollHeight > e.clientHeight + 60) {
                        if (!best || e.scrollHeight > best.scrollHeight) best = e;
                    }
                } catch (x) {}
            }
            return best;
        },
        // 滚动目标：优先内层可滚动容器，找不到就用文档本身
        // （排行榜、分类列表这类页面是文档在滚，不能只认内层容器）
        scrollTarget: function () {
            var el = this.scroller();
            if (el) return { el: el, win: false };
            return { el: document.scrollingElement || document.documentElement, win: true };
        },
        scrollTop: function () {
            var t = this.scrollTarget();
            if (t.win) return Math.round(window.scrollY || t.el.scrollTop || 0);
            return Math.round(t.el.scrollTop);
        },
        /** 恢复滚动位置：内容可能是异步渲染的，逐帧重试直到内容足够高 */
        // 设置项：记住滚动位置（默认开）
        scrollMemoryOn: function () {
            try { return localStorage.getItem(settings.scrollMemKey) !== "0"; } catch (e) { return true; }
        },
        scrollMemoryLabel: function () { return this.scrollMemoryOn() ? "开" : "关"; },
        cycleScrollMemory: function () {
            try { localStorage.setItem(settings.scrollMemKey, this.scrollMemoryOn() ? "0" : "1"); } catch (e) {}
            this.applyToggles();
        },
        // 恢复期间先把内容藏起来（只改不透明度，保留布局与滚动高度），
        // 落地后再淡入，避免「先看到顶部、过一会儿突然跳走」
        hideForRestore: function () {
            var root = document.getElementById("app");
            if (!root || this._hiddenForRestore) return;
            this._hiddenForRestore = true;
            root.style.transition = "none";
            root.style.opacity = "0";
            var self = this;
            setTimeout(function () { self.showAfterRestore(); }, 1200);   // 兜底，不能一直藏着
        },
        showAfterRestore: function () {
            var root = document.getElementById("app");
            if (!root || !this._hiddenForRestore) return;
            this._hiddenForRestore = false;
            root.style.transition = "opacity .12s linear";
            root.style.opacity = "1";
        },
        restoreScroll: function (y) {
            if (!(y > 0)) { this.showAfterRestore(); return; }
            if (!this.scrollMemoryOn()) { this.showAfterRestore(); return; }
            var self = this;
            var rounds = 0;
            var lastMax = -1;
            var stagnant = 0;
            (function step() {
                var t = self.scrollTarget();
                var max = t.win ? (t.el.scrollHeight - window.innerHeight)
                                : (t.el.scrollHeight - t.el.clientHeight);
                if (max + 40 >= y) {                    // 内容够了：精确落位
                    if (t.win) window.scrollTo(0, Math.min(y, max));
                    else t.el.scrollTop = Math.min(y, max);
                    self.reportPage();
                    self.showAfterRestore();
                    return;
                }
                // 内容还不够高：说明这是增量加载的列表。滚到当前底部去触发站点加载下一页；
                // 每轮留间隔、最多几轮，避免制造请求突发；高度不再增长就放弃。
                if (max > lastMax + 20) stagnant = 0; else stagnant++;
                lastMax = max;
                if (t.win) window.scrollTo(0, Math.max(0, max));
                else t.el.scrollTop = Math.max(0, max);
                if (rounds++ < 10 && stagnant < 4) { setTimeout(step, 400); return; }
                self.reportPage();                      // 到不了目标：停在能够到的位置
                self.showAfterRestore();
            })();
        },
        reportPage: function () {
            try {
                if (typeof GM.reportPage === "function") GM.reportPage(this.pageUrl(), this.scrollTop());
            } catch (e) {}
        },
        // ---------------- 跨页面失效：写入方打标记，读方在显示时刷新 ----------------
        dirtyMap: function () {
            try { return JSON.parse(localStorage.getItem(settings.dirtyKey) || "{}"); } catch (e) { return {}; }
        },
        markDirty: function (group) {
            try {
                var m = this.dirtyMap();
                m[group] = Date.now();
                localStorage.setItem(settings.dirtyKey, JSON.stringify(m));
            } catch (e) {}
        },
        routeGroup: function (p) {
            var q = String(p || location.pathname);
            if (q.indexOf("/bookrack") >= 0) return "bookrack";
            if (q.indexOf("/personal") >= 0 || q.indexOf("/personalRecord") >= 0
                || q.indexOf("/messageList") >= 0 || q.indexOf("/messageboard") >= 0) return "personal";
            return "";
        },
        /** 当前页面若被标记为失效，就刷新一次（刷新前先清标记，避免循环） */
        checkDirty: function () {
            var g = this.routeGroup();
            if (!g) return false;
            var m = this.dirtyMap();
            if (!m[g]) return false;
            try {
                delete m[g];
                localStorage.setItem(settings.dirtyKey, JSON.stringify(m));
            } catch (e) {}
            // 整页重新加载：这是真正意义上的「刷新」（站点的列表数据缓存在内存里，
            // 只做路由跳转不会重新拉取）
            try { if (typeof GM.forgetScroll === 'function') GM.forgetScroll(this.pageUrl()); } catch (e) {}
            try { location.replace(this.pageUrl()); } catch (e) {}
            return true;
        },
        /** 监听登录态变化：登录/登出会影响书架、个人、浏览记录 */
        watchLogin: function () {
            var cur = "";
            try { cur = localStorage.getItem("user") || ""; } catch (e) {}
            if (this._lastUser === undefined) { this._lastUser = cur; return; }
            if (this._lastUser === cur) return;
            this._lastUser = cur;
            this.markDirty("bookrack");
            this.markDirty("personal");
        },
        /** 加入/移除书架会改变书架内容，给书架页打失效标记 */
        installShelfHook: function () {
            if (this._shelfHooked) return;
            this._shelfHooked = true;
            var self = this;
            document.addEventListener("click", function (e) {
                var el = e.target;
                if (!el || !el.closest) return;
                var btn = el.closest("button,.van-button");
                if (!btn) return;
                var t = (btn.textContent || "").trim();
                if (/加入書架|加入书架|移除書架|移除书架|已加入/.test(t)) {
                    self.markDirty("bookrack");
                }
            }, true);
        },

        // 详情页路径（漫画 /details/<type>/<pw>，小说 /detailsNovel/<pw>）
        isDetailPath: function (p) {
            if (!p) return false;
            var q = String(p).split("?")[0].split("#")[0];
            return q.indexOf("/detailsNovel/") === 0 || q.indexOf("/details/") === 0;
        },

        // ---------------- 详情页「續看」 ----------------
        // 详情页的主按钮文本改成最近一次读的卷/话，点击直接回到那个位置。
        detailKind: function () {
            var p = location.pathname;
            if (p.indexOf("/detailsNovel/") >= 0) return "novel";
            if (/\/details\/[^\/]+\/[^\/?#]+\/?$/.test(p)) return "comic";
            return "";
        },
        comicPathWord: function () {
            var m = /\/details\/[^\/]+\/([^\/?#]+)/.exec(location.href);
            return m ? m[1] : "";
        },
        detailActionButton: function () {
            var bs = document.querySelectorAll("button.van-button, .van-button");
            for (var i = 0; i < bs.length; i++) {
                var t = (bs[i].textContent || "").trim();
                if (/^(開始|开始|續看|续看|續讀)/.test(t)) return bs[i];
            }
            return null;
        },
        installContinueButton: function () {
            var kind = this.detailKind();
            if (!kind) return;
            var self = this;
            if (!self._continueHooked) {
                self._continueHooked = true;
                // 捕获阶段拦截：先于站点自己的点击处理，避免它按服务端进度跳走
                document.addEventListener("click", function (e) {
                    var el = e.target && e.target.closest ? e.target.closest("[data-cm-continue]") : null;
                    if (!el) return;
                    var info = self._continueInfo;
                    if (!info) return;
                    e.preventDefault();
                    e.stopPropagation();
                    self.openContinue(info);
                }, true);
            }
            if (kind === "novel") {
                var book = self.novelBook;
                if (!book) { self.loadNovelBook(); return; }
                var raw = "";
                try { if (typeof GM.lastNovelVolume === "function") raw = GM.lastNovelVolume(book.name); } catch (e) {}
                if (!raw) return;
                var info;
                try { info = JSON.parse(raw); } catch (e) { return; }
                if (!info || !info.volumeId) return;
                info.kind = "novel";
                info.name = info.volumeName || "";
                self.applyContinueButton(info);
                return;
            }
            var pw = self.comicPathWord();
            if (!pw) return;
            self._comicInfos = self._comicInfos || {};
            if (self._comicInfos[pw]) { self.applyContinueButton(self._comicInfos[pw]); return; }
            // 先按已知的漫画名查一次（没有就走在线进度），名字取到后再查一次本地下载的记录
            var tryInfo = function (name) {
                if (self._comicInfos[pw]) return;
                var raw = "";
                try { if (typeof GM.lastComicChapter === "function") raw = GM.lastComicChapter(pw, name || ""); } catch (e) {}
                if (!raw) return;
                var info;
                try { info = JSON.parse(raw); } catch (e) { return; }
                if (!info || !info.chapterId) return;
                info.kind = "comic";
                info.pathWord = pw;
                info.comicName = name || "";
                info.name = info.chapterName || "";
                self._comicInfos[pw] = info;
                self.applyContinueButton(info);
            };
            // 注意：这里绝不能请求 /api/v3/comic2/<pw>。该接口对未登录客户端返回 210
            // （反破解风控），而注入是每次进详情页都会跑的，密集的未授权请求会触发封禁。
            // 漫画名交给原生侧在本地下载记录里解析。
            tryInfo("");
        },
        applyContinueButton: function (info) {
            var btn = this.detailActionButton();
            if (!btn) return;
            var label = "續看 " + (info.name || "");
            if ((btn.textContent || "").trim() !== label) btn.textContent = label;
            btn.setAttribute("data-cm-continue", "1");
            this._continueInfo = info;
        },
        openContinue: function (info) {
            if (info.kind === "novel") {
                var book = this.novelBook;
                if (!book) return;
                this.openNovelVolume(book, info.volumeId);
                return;
            }
            if (info.local) {
                try {
                    if (typeof GM.openLocalComic === "function"
                        && GM.openLocalComic(info.comicName, info.chapterId)) return;
                } catch (e) {}
            }
            try {
                GM.loadComicDirect("https://cm.local/comicContent/" + info.pathWord + "/" + info.chapterId);
            } catch (e) {}
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
                    // 详情页现在走页内导航：滚动位置由页面栈记住/恢复，
                    // 返回键由 MainActivity 的页面栈统一处理，不再需要独立页面
                    // 目标页面有记住的位置：在内容渲染前就藏起来，
                    // 这样不会先看到顶部、再突然跳走（落地后由 restoreScroll 显示回来）
                    try {
                        var target = location.origin + "/h5" + fp;
                        if (self.scrollMemoryOn()
                            && typeof GM.rememberedScroll === "function"
                            && GM.rememberedScroll(target) > 0) {
                            self.hideForRestore();
                        }
                    } catch (e) {}
                    setTimeout(function () { self.allowNovel(); }, 300);
                } catch (e) {}
                next();
            });
            router.afterEach(function () {
                setTimeout(function () { self.allowNovel(); }, 300);
                setTimeout(function () {
                    // 先记下这次路由变化的时间：切换后页面会重新渲染并把滚动归零，
                    // 滚动监听要忽略这段时间内的变化
                    self.routeChangedAt = Date.now();
                    if (self.checkDirty()) return;      // 该页要刷新：不恢复旧位置
                    // 前进导航到访问过的页面（例如切换底部 tab）：恢复上次的滚动位置
                    var y = 0;
                    try {
                        y = (typeof GM.rememberedScroll === 'function')
                            ? GM.rememberedScroll(self.pageUrl()) : 0;
                    } catch (e) {}
                    if (y > 0) {
                        self.hideForRestore();          // 恢复期间先藏起来，落地再显示
                        self.restoreScroll(y);          // 内部会按恢复后的位置上报
                    } else {
                        self.reportPage();              // 首次访问：记录当前位置
                    }
                }, 400);
            });
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
        // ---------- 漫画元信息（封面 / 作者）：下载任意章节时一起存到本地 ----------
        comicMetaSent: {},
        /** 封面字段可能是相对路径，缩略图还带 .328x422.jpg 后缀：能拿到页面上的真实 src 最好 */
        coverFromDom: function (pw) {
            var imgs = document.getElementsByTagName("img");
            for (var i = 0; i < imgs.length; i++) {
                var s = imgs[i].currentSrc || imgs[i].src || "";
                if (s && pw && s.indexOf(pw) >= 0 && /cover/i.test(s)) {
                    return s.replace(/\.\d+x\d+\.(jpg|jpeg|png|webp)$/i, "");
                }
            }
            return "";
        },
        coverUrlOf: function (cover) {
            if (!cover) return "";
            var c = String(cover);
            if (/^https?:/i.test(c)) return c.replace(/\.\d+x\d+\.(jpg|jpeg|png|webp)$/i, "");
            var host = "";
            var imgs = document.getElementsByTagName("img");
            for (var i = 0; i < imgs.length; i++) {
                var s = imgs[i].currentSrc || imgs[i].src || "";
                var m = s.match(/^(https?:\/\/[^\/]+)\//);
                if (m && /mangafun/i.test(m[1])) { host = m[1]; break; }
            }
            if (!host) host = "https://s3.mangafunb.fun";
            c = c.replace(/\.\d+x\d+\.(jpg|jpeg|png|webp)$/i, "").replace(/^\/+/, "");
            // 站点里的 cover 字段就是相对图片 CDN 根目录的路径（如 <pathWord>/cover/xxx.jpg）
            return host + "/" + c;
        },
        /** 详情页的 pathWord（路由是 /details/comic/<pathWord>，兼容两段写法） */
        detailPathWord: function () {
            var m = location.pathname.match(/\/details\/comic\/(?:[^\/]+\/)?([A-Za-z0-9_-]+)/);
            return m ? m[1] : "";
        },
        /**
         * 从详情页头部 DOM 取元信息：网络不好时详情接口可能失败（store 里没有数据），
         * 但页面头部的标题 / 封面 / 作者照样渲染出来了，所以 DOM 是更可靠的一手来源。
         */
        detailMetaFromDom: function (pw) {
            var out = { name: "", cover: "", author: "" };
            var root = document.querySelector(".headerContent") || document;
            var t = root.querySelector(".headerContentTitle");
            if (t) out.name = (t.innerText || t.textContent || "").trim();
            var img = root.querySelector(".headerContentImage img");
            if (img) out.cover = (img.currentSrc || img.src || "").trim();
            if (!out.cover && pw) out.cover = this.coverFromDom(pw);
            var item = root.querySelector(".headerContentTextItem.author");
            if (item) {
                var node = item.querySelector("p") || item;
                var parts = [];
                var kids = node.children;
                if (kids && kids.length) {
                    for (var i = 0; i < kids.length; i++) {
                        var s = (kids[i].innerText || kids[i].textContent || "").trim();
                        if (s) parts.push(s);
                    }
                } else {
                    var one = (node.innerText || node.textContent || "")
                        .replace(/^作者[:：]?/, "").trim();
                    if (one) parts.push(one);
                }
                out.author = parts.join(" / ");
            }
            if (out.cover) out.cover = out.cover.replace(/\.\d+x\d+\.(jpg|jpeg|png|webp)$/i, "");
            return out;
        },
        authorTextOf: function (a) {
            if (!a) return "";
            if (typeof a === "string") return a;
            if (a.length) {
                return a.map(function (x) { return (x && (x.name || x)) || ""; })
                    .filter(function (s) { return !!s; }).join(" / ");
            }
            return "";
        },
        /** 在 store / 组件数据里按 path_word 找漫画条目（列表缓存里就有封面与作者） */
        findComicInfo: function (pw) {
            var found = null;
            var budget = { n: 0 };
            var seen = [];
            var walk = function (o, depth) {
                if (!o || typeof o !== "object" || depth > 5 || budget.n > 4000 || found) return;
                budget.n++;
                for (var i = 0; i < seen.length; i++) if (seen[i] === o) return;
                seen.push(o);
                if ((o.path_word || o.pathWord) === pw && (o.cover || o.name)) { found = o; return; }
                for (var k in o) {
                    if (found) return;
                    if (k === "$el" || k === "$parent" || k === "$root" || k === "$children") continue;
                    var v;
                    try { v = o[k]; } catch (e) { continue; }
                    if (v && typeof v === "object") walk(v, depth + 1);
                }
            };
            var store = this.storeOf();
            if (store) walk(store.state, 0);
            if (!found) {
                var root = this.vueRoot();
                if (root) walk(root, 0);
            }
            return found;
        },
        /**
         * 漫画详情页：把封面 / 作者 / 分类交给原生（下载章节时随 info.bin 一起落盘）。
         * 每个 pathWord 只报一次；拿不到封面就不报，避免存下空元信息。
         */
        collectComicMeta: function () {
            // 详情页路由是 /details/comic/<pathWord>（单段）；兼容历史上出现过的
            // /details/comic/<type>/<pathWord> 两段写法
            var m = location.pathname.match(/\/details\/comic\/(?:[^\/]+\/)?([A-Za-z0-9_-]+)/);
            if (!m) return;
            var pw = m[1];
            // 详情页数据是异步到的：先只有封面（DOM 渲染出来就有），名字/作者要等接口回来。
            // 因此报过不等于报全，缺项就继续重试，直到齐全或超过尝试上限。
            var st = this.comicMetaSent[pw] || { n: 0, done: false };
            if (st.done) return;
            var dom = this.detailMetaFromDom(pw);
            var info = this.findComicInfo(pw) || {};
            var name = dom.name || info.name || "";
            if (!name) {
                // 详情页的 document.title 只是「詳情」这类通用标题，不能当名字用
                var h = document.getElementsByTagName("h6")[0];
                var t = (h && (h.title || h.innerText)) || "";
                if (!t) {
                    var dt = (document.title || "").trim();
                    t = /^(詳情|详情|拷貝漫畫|拷贝漫画|comic|novel)$/i.test(dt) ? "" : dt.split(/[-|]/)[0].trim();
                }
                name = t;
            }
            var cover = dom.cover || this.coverUrlOf(info.cover);
            var author = dom.author || this.authorTextOf(info.author);
            if (!name && !cover) return;
            st.n++;
            if ((name && cover) || st.n >= 40) st.done = true;
            this.comicMetaSent[pw] = st;
            try {
                GM.rememberComicMeta(JSON.stringify({
                    pathWord: pw, name: name, cover: cover, author: author
                }));
            } catch (e) {}
        },
        /**
         * 站点的两类打扰：
         * 1) 进入时的系统公告弹窗（#systemConfirm）：隐藏遮罩与弹窗，并代点「我知道了」——
         *    只隐藏不点，遮罩会留着挡住页面操作；
         * 2) 网络异常时的 toast（超时/连接失败/请求失败…）：只在文案命中网络类关键词时隐藏，
         *    避免误伤「收藏成功」这类正常提示。页面加载不出来本身已经说明网络有问题，
         *    不需要再弹一次提示。
         */
        noticeToastRe: /超时|超時|逾時|timeout|连接失败|連接失敗|連線失敗|网络异常|網路異常|网络错误|網路錯誤|网络连接|網路連接|无法连接|無法連接|请求失败|請求失敗|请求异常|請求異常|加载失败|載入失敗|重新连接|重新連接|重新加载|重新載入|服务器错误|服務器錯誤|请检查网络|請檢查網絡|网络不佳|網路不佳/i,
        // 网络类「弹窗」的文案（站点的连接超时提示：系統提示 / 【連接超時】-客官請【下拉】頁面刷新）
        noticeDialogRe: /超時|超时|逾時|timeout|連接失敗|连接失败|連線失敗|网络异常|網路異常|网络错误|網路錯誤|伺服器|服务器|下拉[^。]{0,8}刷新|請[^。]{0,8}刷新/i,
        /**
         * 站点的打扰有两类，都要处理：
         * - 弹窗（van-dialog）：进入时的系统公告、以及网络不好时的「【連接超時】-客官請【下拉】頁面刷新」。
         *   隐藏遮罩与弹窗后**代点确认按钮**——只隐藏不点，遮罩会留着挡住操作，站点状态也不会复位；
         * - 轻提示（van-toast / van-notify）：只在文案命中网络关键词时隐藏，不误伤「收藏成功」这类正常提示。
         */
        installNoticeFilter: function () {
            var self = this;
            var hideOverlays = function () {
                var ov = document.querySelectorAll(".van-overlay, .van-popup__overlay");
                for (var k = 0; k < ov.length; k++) {
                    if (ov[k].style.display !== "none") ov[k].style.display = "none";
                }
            };
            // 1) 进入时的系统公告弹窗（有固定 id）
            var sys = document.getElementById("systemConfirm");
            if (sys && sys.style.display !== "none") {
                hideOverlays();
                sys.style.display = "none";
                var sb = sys.querySelector("button, a");
                if (sb && !sb.__cmNoticeClicked) {
                    sb.__cmNoticeClicked = true;
                    try { sb.click(); } catch (e) {}
                }
            }
            // 2) 网络类弹窗：按文案匹配（连接超时等）
            var dlg = document.querySelectorAll(".van-dialog");
            for (var i = 0; i < dlg.length; i++) {
                var d = dlg[i];
                if (d.style.display === "none") continue;
                var t = (d.innerText || d.textContent || "").trim();
                if (!t || !self.noticeDialogRe.test(t)) continue;
                hideOverlays();
                d.style.display = "none";
                var b = d.querySelector("button, a");
                if (b && !b.__cmNoticeClicked) {
                    b.__cmNoticeClicked = true;
                    try { b.click(); } catch (e) {}
                }
            }
            // 3) 网络类轻提示
            var toasts = document.querySelectorAll(".van-toast, .van-notify");
            for (var j = 0; j < toasts.length; j++) {
                var el = toasts[j];
                if (el.style.display === "none") continue;
                var txt = (el.innerText || el.textContent || "").trim();
                if (txt && self.noticeToastRe.test(txt)) el.style.display = "none";
            }
        },
        /**
         * 弹窗/提示是随时冒出来的，定时兜底最快也要等 ~800ms（会闪一下）。
         * 监听 body 上新增的相关节点，一出现就立刻处理。
         */
        installNoticeObserver: function () {
            var self = this;
            if (this.noticeObserver || typeof MutationObserver === "undefined") return;
            if (!document.body) return;
            try {
                this.noticeObserver = new MutationObserver(function (list) {
                    for (var i = 0; i < list.length; i++) {
                        var added = list[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            var n = added[j];
                            if (!n || n.nodeType !== 1) continue;
                            var cls = String(n.className || "");
                            if (cls.indexOf("van-dialog") >= 0 || cls.indexOf("van-toast") >= 0 ||
                                cls.indexOf("van-notify") >= 0 || cls.indexOf("van-overlay") >= 0) {
                                self.installNoticeFilter();
                                return;
                            }
                        }
                    }
                });
                this.noticeObserver.observe(document.body, { childList: true, subtree: true });
            } catch (e) {}
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
    // 公告/网络弹窗要尽早挡住：先装节点监听，再在启动后 7 秒内密集兜底
    try { invoke.installNoticeObserver(); } catch (e) {}
    (function () {
        var n = 0;
        var t = setInterval(function () {
            try { window.invoke.installNoticeFilter(); } catch (e) {}
            if (++n > 58) clearInterval(t);
        }, 120);
    })();
    try { invoke.installNoticeFilter(); } catch (e) {}
    invoke.startTick();
    setTimeout(function () { invoke.allowNovel(); }, 2500);
} else {
    setTimeout(modify, 1280);
}

// ---------------- 与页面栈/失效刷新配合的原生入口 ----------------
(function () {
    var lastReport = 0;
    // 内层容器的 scroll 不冒泡，用捕获阶段监听全部滚动
    document.addEventListener('scroll', function () {
        var now = Date.now();
        if (now - lastReport < 500) return;
        // 路由切换后页面会重新渲染、把滚动归零，那不是用户行为，
        // 不能把它当成「上次读到的位置」记下来
        try {
            if (window.invoke && window.invoke.routeChangedAt
                && now - window.invoke.routeChangedAt < 700) return;
        } catch (e) {}
        lastReport = now;
        try { if (window.invoke && window.invoke.reportPage) window.invoke.reportPage(); } catch (e) {}
    }, true);
    // 返回键弹回本实例内的上一个页面：原地跳转并恢复滚动位置
    window.__cmNavigateTo = function (url, scrollY) {
        try {
            var self = window.invoke;
            var app = document.getElementById('app');
            var vm = app && (app.__vue__ || (app.__vue_app__ && app.__vue_app__._instance));
            var R = vm && vm.$root && vm.$root.$router;
            if (!self || !R) return false;
            var path = String(url).replace(location.origin, '');
            if (path.indexOf('/h5') === 0) path = path.slice(3) || '/';
            R.replace(path);
            setTimeout(function () { self.restoreScroll(scrollY); }, 350);
            return true;
        } catch (e) { return false; }
    };
    // 页面重新可见：上报当前位置；被重建时恢复滚动；必要时刷新失效页面
    window.__cmOnShow = function (scrollHint) {
        try {
            var self = window.invoke;
            if (!self) return;
            if (self.checkDirty && self.checkDirty()) return;
            if (scrollHint >= 0 && self.restoreScroll) self.restoreScroll(scrollHint);
            if (self.reportPage) self.reportPage();
        } catch (e) {}
    };
    // 同源其它实例写了失效标记：如果正是当前页面，立刻刷新
    window.addEventListener('storage', function (e) {
        try {
            if (e.key !== 'cm_dirty') return;
            if (window.invoke && window.invoke.checkDirty) window.invoke.checkDirty();
        } catch (x) {}
    });
})();
