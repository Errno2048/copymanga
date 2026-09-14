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
        },
        // 设置页注入开关：夜间模式（开关）、黑白漫画反色（三态循环）。幂等，可重复调用。
        installSettingRows: function () {
            var groups = document.getElementsByClassName("van-cell-group");
            if (!groups.length) return;
            var self = this;
            var rows = [
                { id: "cm-night-cell", kind: "switch", label: "夜间模式" },
                { id: "cm-invert-cell", kind: "cycle", label: "黑白漫画反色" }
            ];
            for (var i = 0; i < rows.length; i++) {
                if (document.getElementById(rows[i].id)) continue;
                var row = rows[i];
                var wrap = document.createElement("div");
                wrap.className = "van-cell-group";
                var right = row.kind === "switch"
                    ? '<i id="cm-night-switch" class="cm-switch"></i>'
                    : '<span id="cm-invert-value" style="color:#d8d8d8"></span>';
                wrap.innerHTML = '<div class="van-cell" id="' + row.id + '">'
                    + '<div class="van-cell__title"><span>' + row.label + '</span></div>'
                    + '<div class="van-cell__value">' + right + '</div></div>';
                (function (r) {
                    wrap.addEventListener("click", function (e) {
                        e.stopPropagation();
                        e.preventDefault();
                        if (r.kind === "switch") self.setNight(!self.nightOn()); else self.cycleInvert();
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
            }, settings.tickMs);
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