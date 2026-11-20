package io.github.qishui.automix;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Process;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private boolean installed;
    private final HostDiagnostics diagnostics = new HostDiagnostics();

    @Override public void initZygote(StartupParam param) {
        diagnostics.event("module_loaded", "module=" + HostDiagnostics.VERSION + " api=" + Build.VERSION.SDK_INT
            + " framework_api=" + XposedBridge.getXposedVersion() + " system_server=" + param.startsSystemServer);
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.luna.music".equals(param.packageName)) return;
        diagnostics.event("load_package", "module=" + HostDiagnostics.VERSION + " package=" + param.packageName
            + " process=" + param.processName + " pid=" + Process.myPid() + " api=" + Build.VERSION.SDK_INT
            + " framework_api=" + XposedBridge.getXposedVersion() + " first=" + param.isFirstApplication
            + " loader=" + (param.classLoader == null ? "null" : param.classLoader.getClass().getName()));
        if (!param.packageName.equals(param.processName)) {
            diagnostics.event("process_skip", "仅在汽水主进程安装播放器 hook");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    enter((Context) p.args[0], "application_attach", param.classLoader);
                }
            });
            diagnostics.event("boot_hook", "Application.attach 已安装");
        } catch (Throwable error) { diagnostics.error("boot_hook_attach_failed", error); }
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        enter((Application) p.args[0], "application_oncreate", param.classLoader);
                    }
                });
            diagnostics.event("boot_hook", "Instrumentation.callApplicationOnCreate 备用入口已安装");
        } catch (Throwable error) { diagnostics.error("boot_hook_oncreate_failed", error); }
        try {
            Application current = android.app.AndroidAppHelper.currentApplication();
            if (current != null) enter(current, "application_already_created", param.classLoader);
        } catch (Throwable error) { diagnostics.error("boot_current_application_failed", error); }
    }

    private synchronized void enter(Context context, String entry, ClassLoader fallback) {
        if (installed || context == null) return;
        try {
            if (!"com.luna.music".equals(context.getPackageName())) return;
            installed = true;
            diagnostics.start(context);
            ClassLoader loader = context.getClassLoader();
            diagnostics.event(entry, "context=" + context.getClass().getName()
                + " uid=" + Process.myUid() + " loader=" + (loader == null ? "null" : loader.getClass().getName()));
            setup(context, loader == null ? fallback : loader);
        } catch (Throwable error) {
            diagnostics.error("boot_enter_failed", error);
            diagnostics.status("启动入口失败，详见完整日志", 0);
        }
    }

    private void setup(Context context, ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        MixManager manager = null;
        String stage = "package_identity";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.luna.music", PackageManager.GET_SIGNING_CERTIFICATES);
            diagnostics.event(stage, "version=" + info.versionName + " code=" + info.getLongVersionCode());
            stage = "signature_check";
            if (!officialSignature(info)) {
                diagnostics.event("signature_rejected", "官网签名不匹配，未安装播放 hook");
                diagnostics.status("签名与分析用官网 APK 不一致，未启用播放 hook", 0);
                return;
            }
            diagnostics.event(stage, "官网证书匹配");
            // The UI hooks match members by shape, so they do not need the exact host version; they
            // own their hooks and failures and cannot take the mix or transport hooks down.
            try { LyricsHook.install(context, loader, diagnostics); }
            catch (Throwable error) { diagnostics.error("lyrics_install_failed", error); }
            try { LyricsShortHook.install(context, loader, diagnostics); }
            catch (Throwable error) { diagnostics.error("short_lyrics_install_failed", error); }
            try { PlayerHook.install(context, loader, diagnostics); }
            catch (Throwable error) { diagnostics.error("player_install_failed", error); }
            try { TabHook.install(context, loader, diagnostics); }
            catch (Throwable error) { diagnostics.error("tab_install_failed", error); }
            try { SmoothHook.install(context, loader, diagnostics); }
            catch (Throwable error) { diagnostics.error("refresh_install_failed", error); }
            if (!HostBridge.VERSION.equals(info.versionName) || info.getLongVersionCode() != HostBridge.VERSION_CODE) {
                diagnostics.event("version_rejected", "不是已适配版本，未安装播放 hook");
                diagnostics.status("未适配汽水 " + info.versionName + " / " + info.getLongVersionCode(), 0);
                diagnostics.pollConfig();
                return;
            }
            stage = "host_contract";
            HostBridge bridge = new HostBridge(loader);
            diagnostics.event(stage, "宿主反射接口检查通过");
            manager = new MixManager(bridge, diagnostics);
            MixManager mix = manager;
            ThreadLocal<WeakReference<Object>> constructing = new ThreadLocal<>();
            stage = "factory_binding_hooks";
            hooks.add(XposedBridge.hookMethod(bridge.factoryCreate, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (bridge.mainFactory(p.thisObject)) constructing.set(new WeakReference<>(p.args[0]));
                    } catch (Exception error) { mix.reportError(error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { constructing.remove(); }
            }));
            hooks.addAll(XposedBridge.hookAllConstructors(bridge.wrapperClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    WeakReference<Object> ref = constructing.get();
                    if (ref != null && ref.get() != null) mix.bind(p.thisObject, ref.get());
                }
            }));
            diagnostics.event(stage, "已安装 " + hooks.size() + " 个 hook");
            stage = "progress_hooks";
            for (String name : new String[] {"onPlaybackTimeChanged", "onPlaybackTimeChangedFast", "onRenderStart"}) {
                int before = hooks.size();
                hooks.addAll(XposedBridge.hookAllMethods(bridge.wrapperClass, name, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { mix.progress(p.thisObject); }
                }));
                diagnostics.event(stage, name + " count=" + (hooks.size() - before));
            }
            stage = "cancel_hooks";
            for (String name : new String[] {"pause", "stop", "seekTo", "seekToTime", "destroy", "setPlaybackSpeed", "handlePlayerError", "onAudioFocusChange", "onPlaySourceWillChange", "onQueueLoopModeChanged"}) {
                final String action = name;
                hooks.addAll(XposedBridge.hookAllMethods(bridge.wrapperClass, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { mix.cancelFor(p.thisObject, action); }
                }));
            }
            for (String name : new String[] {"pause", "play"}) {
                final String action = name;
                hooks.addAll(XposedBridge.hookAllMethods(bridge.innerClass, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try { mix.innerTransport(p.thisObject, action); } catch (Throwable error) { diagnostics.error("transport_diagnostics_failed", error); }
                    }
                }));
            }
            for (String name : new String[] {"pause", "play"}) {
                final String action = name;
                hooks.addAll(XposedBridge.hookAllMethods(bridge.wrapperClass, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try { mix.transport(p.thisObject, action); } catch (Throwable error) { diagnostics.error("transport_diagnostics_failed", error); }
                    }
                }));
            }
            // The host's MediaPlayer.pause skips the engine unless its start flag is set; the
            // resume path that promotes an analysed (paused) pre-roll never sets it. Not gated on
            // the mix switch: an engine left in that state must stay pausable after disabling.
            hooks.add(XposedBridge.hookMethod(bridge.mediaPlay, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try { mix.restoreStart(p.thisObject); } catch (Throwable error) { diagnostics.errorThrottled("start_restore_failed", error, 15000); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(bridge.mediaPause, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { mix.repairPause(p.thisObject); } catch (Throwable error) { diagnostics.errorThrottled("pause_repair_failed", error, 15000); }
                }
            }));
            diagnostics.event(stage, "累计 hook=" + hooks.size());
            stage = "automatic_routing_hooks";
            hooks.add(XposedBridge.hookMethod(bridge.automaticPageRoute, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object result = mix.redirectPageRouting();
                    if (result != null) p.setResult(result);
                }
            }));
            hooks.addAll(XposedBridge.hookAllMethods(bridge.innerClass, "changeToNextQueueItem", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { mix.queueAdvance(p.thisObject, p.args); }
            }));
            diagnostics.event(stage, "已隔离混音事务中的完成翻页路由，并记录队列推进来源");
            stage = "volume_lifecycle_hooks";
            for (String name : new String[] {"playNext", "playPrev", "changePlaySource", "setSelectedSleepTime", "setSleepAfterPlayEnd"}) {
                final String action = name;
                hooks.addAll(XposedBridge.hookAllMethods(bridge.innerClass, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { mix.cancelInner(p.thisObject, action); }
                }));
            }
            hooks.add(XposedBridge.hookMethod(bridge.setVolume, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { mix.volume(p.thisObject, p.args); }
            }));
            for (String name : new String[] {"pause", "stop", "release", "releaseAsync"}) {
                hooks.addAll(XposedBridge.hookAllMethods(bridge.engineClass, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (mix.defer(p.thisObject, (Method) p.method)) p.setResult(null);
                    }
                }));
            }
            for (Method method : bridge.engineClass.getDeclaredMethods()) {
                String name = method.getName();
                if (name.startsWith("setDirect") || name.equals("setDataSource") || name.equals("setVideoModel")
                    || name.equals("setLocalURL") || name.equals("seekTo")) {
                    hooks.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) { mix.sourceChange(p.thisObject, name); }
                    }));
                }
            }
            hooks.add(XposedBridge.hookMethod(HostBridge.method(bridge.engineClass, "setIsMute", boolean.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.args[0])) mix.sourceChange(p.thisObject,"setIsMute");
                }
            }));
            diagnostics.event(stage, "累计 hook=" + hooks.size());
            stage = "prerender_hooks";
            for (String name : new String[] {"onPreRenderStart", "onPreRenderEnd", "onPreRenderDiscard", "onPreRenderPlayed"}) {
                final String event = name;
                hooks.addAll(XposedBridge.hookAllMethods(bridge.wrapperClass, name, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        mix.preRenderEvent(p.thisObject, event, p.args);
                    }
                }));
            }
            Class<?> streamSource = Class.forName("com.luna.common.player.datasource.StreamMediaDataSource", false, loader);
            hooks.add(XposedBridge.hookMethod(HostBridge.method(streamSource, "close"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (mix.deferSourceClose(p.thisObject)) p.setResult(null);
                }
            }));
            // Native pre-render checks for cache, entitlement and media type remain in force.
            Class<?> preRenderConfig = Class.forName("com.luna.biz.playing.player.bach.prerender.PreRenderSettingsConfig", false, loader);
            hooks.add(XposedBridge.hookMethod(HostBridge.method(preRenderConfig, "F"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (mix.config().enabled && mix.config().preRender) p.setResult(true);
                }
            }));
            hooks.add(XposedBridge.hookMethod(HostBridge.method(bridge.factoryClass, "d"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (mix.config().enabled && mix.config().preRender && bridge.mainFactory(p.thisObject)) p.setResult(true);
                    } catch (Exception error) { mix.reportError(error); }
                }
            }));
            stage = "optional_audio_observer";
            // Optional: AudioX/offload may bypass the Java processor completely.
            List<XC_MethodHook.Unhook> audioHooks = new ArrayList<>();
            try {
                Class<?> processor = Class.forName("com.ss.ttm.player.AudioProcessor", false, loader);
                Class<?> effect = Class.forName("com.luna.audio.effect.AudioEffectProcessor", false, loader);
                audioHooks.add(XposedBridge.hookMethod(HostBridge.method(bridge.engineClass, "setAudioProcessor", processor), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!p.hasThrowable() && p.args[0] != null && effect.isInstance(p.args[0])) {
                            try { mix.audio.bind(p.thisObject, p.args[0]); }
                            catch (Throwable error) { diagnostics.errorThrottled("pcm_bind_failed", error, 15000); }
                        }
                    }
                }));
                audioHooks.add(XposedBridge.hookMethod(HostBridge.method(effect, "audioOpen", int.class, int.class, int.class, int.class), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!p.hasThrowable()) try { mix.audio.open(p.thisObject, p.args); }
                        catch (Throwable error) { diagnostics.errorThrottled("pcm_open_failed", error, 15000); }
                    }
                }));
                audioHooks.add(XposedBridge.hookMethod(HostBridge.method(effect, "audioProcess", java.nio.ByteBuffer[].class, int.class, long.class), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!p.hasThrowable() && mix.config().enabled && mix.config().adaptive) {
                            try { mix.audio.audio(p.thisObject, p.args); }
                            catch (Throwable error) { diagnostics.errorThrottled("pcm_read_failed", error, 15000); }
                        }
                    }
                }));
                hooks.addAll(audioHooks);
                diagnostics.event("pcm_observer_ready", "只读旁听宿主现有音效处理器；AudioX/直通路径使用普通过渡");
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : audioHooks) hook.unhook();
                diagnostics.error("pcm_observer_unavailable", error);
            }
            try { mix.audio.nativePcm.install(loader, hooks); }
            catch (Throwable error) { diagnostics.error("native_pcm_unavailable", error); }
            hooks.addAll(XposedBridge.hookAllMethods(bridge.engineClass, "setPlaybackParams", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (mix.playbackParams(p.thisObject,p.args[0])) p.setResult(null);
                }
            }));
            mix.start();
            diagnostics.event("adapter_ready", "播放 hook 安装完成 count=" + hooks.size());
        } catch (Throwable error) {
            diagnostics.error("setup_failed/" + stage, error);
            for (XC_MethodHook.Unhook hook : hooks) {
                try { hook.unhook(); }
                catch (Throwable cleanup) { diagnostics.error("unhook_failed", cleanup); }
            }
            if (manager != null) manager.shutdown();
            diagnostics.pollConfig();
            diagnostics.status("接口检查失败（" + stage + "）：" + error.getClass().getSimpleName() + "；详见日志", 0);
        }
    }

    private static boolean officialSignature(PackageInfo info) throws Exception {
        if (info.signingInfo == null) return false;
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers.length != 1) return false;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return "6491ec50293dd3dee7a03b74800e09af118fdd0f008725d08b911f850b7d79f4".equals(hex.toString());
    }
}
