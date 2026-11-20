package io.github.qishui.automix;

import android.media.AudioTrack;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Observe the host's output without replacing AudioX or altering PCM. */
final class NativePcm {
    private final Map<Object, Owner> owners=new WeakHashMap<>();
    private final HostDiagnostics diagnostics;
    private final AudioSignals signals;
    NativePcm(AudioSignals signals,HostDiagnostics diagnostics) { this.signals=signals;this.diagnostics=diagnostics; }

    private static final Map<Class<?>, Map<String, Field>> fields = new java.util.concurrent.ConcurrentHashMap<>();
    static Object field(Object object,String name) throws Exception {
        Map<String, Field> byName = fields.computeIfAbsent(object.getClass(), ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        Field known = byName.get(name);
        if (known != null) return known.get(object);
        for (Class<?> c=object.getClass();c!=null;c=c.getSuperclass()) {
            try {
                Field f=c.getDeclaredField(name);f.setAccessible(true);byName.put(name,f);return f.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    void watch(Object engine,long position) {
        try {
            // APK: TTVideoEngine option 700 forwards to MediaPlayer option 961 (GET_SESSIONID).
            // AJVoice's mNativeObject is an aj_voice.cpp object, not the TTPlayer pointer.
            int session=((Number)HostBridge.call(engine,"getIntOption",700)).intValue();
            long now=SystemClock.elapsedRealtime();
            synchronized (owners) {
                owners.entrySet().removeIf(e->e.getValue().engine.get()==null || now-e.getValue().at>3000);
                if (session<=0) {
                    owners.remove(engine);
                    diagnostics.throttled("native_pcm_session_pending","engine="+System.identityHashCode(engine)
                        +" session="+session+" option=700",15000);
                    return;
                }
                Owner owner=owners.get(engine);
                if (owner==null || owner.session!=session) {
                    owner=new Owner(engine,session);owners.put(engine,owner);
                    diagnostics.event("native_pcm_owner","engine="+System.identityHashCode(engine)
                        +" session="+session+" via=TTVideoEngine.getIntOption(700)");
                }
                owner.position=position;owner.at=now;
            }
        } catch (Exception error) { diagnostics.errorThrottled("native_pcm_mapping_unavailable",error,30000); }
    }

    void install(ClassLoader loader,List<XC_MethodHook.Unhook> hooks) throws Exception {
        Class<?> voice=Class.forName("com.ss.ttm.player.AJVoice",false,loader);
        // Validate the exact fields used on the audio thread before installing any write hook.
        HostBridge.method(Class.forName("com.ss.ttvideoengine.TTVideoEngine",false,loader),"getIntOption",int.class);
        for (String n:new String[]{"mAudioTrack","mSampleRate","mChannels","mAudioFormat","mWrittenPcmBytes","mPCMFrameBuffer"}) voice.getDeclaredField(n);
        for (java.lang.reflect.Method method:voice.getDeclaredMethods()) {
            if (!method.getName().equals("write")) continue;
            Class<?>[] p=method.getParameterTypes();
            boolean bytes=(p.length==4 || p.length==5) && p[0]==byte[].class;
            boolean direct=p.length==3 && p[0]==int.class;
            if (!bytes && !direct) continue;
            hooks.add(XposedBridge.hookMethod(method,new XC_MethodHook() {
                private final ThreadLocal<Packet> packet=new ThreadLocal<>();
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try { packet.set(capture(param.thisObject,param.args,bytes)); }
                    catch (Throwable error) { diagnostics.errorThrottled("native_pcm_capture_failed",error,30000); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Packet data=packet.get();packet.remove();
                    if (data==null || param.hasThrowable() || !(param.getResult() instanceof Integer)) return;
                    int written=(Integer)param.getResult();
                    if (written<=0) return;
                    try {
                        if (field(param.thisObject,"mAudioTrack")!=data.track) return;
                        data.buffer.limit(data.buffer.position()+Math.min(written,data.buffer.remaining()));
                        int frames=data.buffer.remaining()/data.frameBytes;
                        signals.nativeAudio(data.engine,data.buffer,frames,data.rate,data.channels,data.format,data.pts);
                    } catch (Throwable error) { diagnostics.errorThrottled("native_pcm_read_failed",error,30000); }
                }
            }));
        }
        diagnostics.event("native_pcm_ready","AJVoice 输出旁听已安装；使用宿主音频 session 唯一归属，不替换音效处理器");
    }

    private Packet capture(Object voice,Object[] args,boolean bytes) throws Exception {
        AudioTrack track=(AudioTrack)field(voice,"mAudioTrack");
        if (track==null) return null;
        int session=track.getAudioSessionId();
        if (session<=0) return null;
        Owner owner=null;long now=SystemClock.elapsedRealtime();int known;
        synchronized (owners) {
            known=owners.size();
            for (Owner candidate:owners.values()) {
                if (candidate.session!=session || candidate.engine.get()==null || now-candidate.at>600) continue;
                if (owner!=null) {
                    diagnostics.throttled("native_pcm_session_ambiguous","session="+session
                        +" 多个活跃引擎共用音频会话，跳过节拍采样",15000);return null;
                }
                owner=candidate;
            }
        }
        if (owner==null) {
            diagnostics.throttled("native_pcm_unmapped","session="+session+" known_owners="+known,15000);return null;
        }
        Object engine=owner.engine.get();
        if (engine==null) return null;
        int rate=(Integer)field(voice,"mSampleRate"),channels=(Integer)field(voice,"mChannels"),encoding=(Integer)field(voice,"mAudioFormat");
        int format=encoding==2?1:encoding==3?0:encoding==4?3:-1;
        int frameBytes=channels*(format==1?2:format==0?1:4);
        if (format<0 || channels<1 || channels>8 || rate<8000 || rate>192000) return null;
        ByteBuffer buffer;
        if (bytes) {
            byte[] array=(byte[])args[0];int offset=(Integer)args[1],length=(Integer)args[2];
            if (array==null || offset<0 || length<=0 || offset>array.length-length) return null;
            buffer=ByteBuffer.wrap(array,offset,length).slice();
        } else {
            ByteBuffer original=(ByteBuffer)field(voice,"mPCMFrameBuffer");
            int length=(Integer)args[1];
            if (original==null || length<=0 || length>original.remaining()) return null;
            buffer=original.duplicate();buffer.limit(buffer.position()+length);
        }
        long pts;
        if (args.length==5) pts=((Number)args[4]).longValue();
        else {
            long written=((Number)field(voice,"mWrittenPcmBytes")).longValue()/frameBytes;
            long played=track.getPlaybackHeadPosition()&0xffffffffL;
            long buffered=written-played;
            if (buffered<0 || buffered>rate*2L) return null;
            pts=owner.position+(now-owner.at)+buffered*1000/rate;
        }
        return new Packet(engine,track,buffer,rate,channels,format,frameBytes,pts);
    }
    private static final class Owner {
        final WeakReference<Object> engine;final int session;
        volatile long position,at;
        Owner(Object engine,int session) { this.engine=new WeakReference<>(engine);this.session=session; }
    }
    private static final class Packet {
        final Object engine;final AudioTrack track;final ByteBuffer buffer;final int rate,channels,format,frameBytes;final long pts;
        Packet(Object engine,AudioTrack track,ByteBuffer buffer,int rate,int channels,int format,int frameBytes,long pts) {
            this.engine=engine;this.track=track;this.buffer=buffer;this.rate=rate;this.channels=channels;this.format=format;this.frameBytes=frameBytes;this.pts=pts;
        }
    }
}
