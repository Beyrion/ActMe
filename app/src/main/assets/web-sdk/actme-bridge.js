/**
 * actme-bridge.js — wraps @JavascriptInterface flat methods into ActMe.* namespace.
 * Injected inline by CardRenderer into every plugin WebView page.
 */
(function () {
    function wrapJson(fn) {
        return function () {
            var a = arguments;
            return new Promise(function (res, rej) {
                try { res(JSON.parse(fn.apply(null, a))); } catch (e) { rej(e); }
            });
        };
    }
    function wrapBool(fn) {
        return function () {
            var a = arguments;
            return new Promise(function (res, rej) {
                try { res(fn.apply(null, a)); } catch (e) { rej(e); }
            });
        };
    }

    window.ActMe = {
        /** Per-plugin key-value storage (plugin_items table). */
        storage: {
            getAll: wrapJson(function ()     { return ActMeBridge.storageGetAll(); }),
            get:    wrapJson(function (k)    { return ActMeBridge.storageGet(k); }),
            set:    wrapBool(function (k, d) { return ActMeBridge.storageSet(k, JSON.stringify(d)); }),
            delete: wrapBool(function (k)    { return ActMeBridge.storageDelete(k); })
        },

        /**
         * Generic one-shot or recurring alarm (AlarmManager-backed).
         * set(key, triggerMs, title, body, repeat?)
         *   repeat: {type:'DAILY'|'WEEKLY'|'MONTHLY'|'NONE', time:'HH:mm', days:[1,3], day:15}
         */
        alarm: {
            set: wrapBool(function (key, ms, title, body, repeat) {
                var r = JSON.stringify(repeat || {type: 'NONE'});
                return ActMeBridge.alarmSet(key, ms, title, body, r);
            }),
            cancel: wrapBool(function (key) { return ActMeBridge.alarmCancel(key); })
        },

        /**
         * Call the plugin's own execute_script tools from the management page.
         * (Only wired in management-page WebViews, not execution WebViews.)
         */
        execute: wrapJson(function (toolName, args) {
            return ActMeBridge.execute(toolName, JSON.stringify(args || {}));
        }),

        permission: {
            isGranted: wrapBool(function (id) { return ActMeBridge.permissionIsGranted(id); })
        },

        notify: function (title, body) { try { ActMeBridge.notify(title, body); } catch (e) {} },
        back:   function ()            { try { ActMeBridge.back(); } catch (e) {} }
    };
})();
