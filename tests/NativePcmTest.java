package io.github.qishui.automix;
import android.os.Handler;
import android.os.SystemClock;
import com.ss.ttvideoengine.TTVideoEngine;
import com.ss.ttm.player.AJVoice;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
public final class NativePcmTest {
 private static int checks;
 private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 public static void main(String[] args)throws Throwable {
  Handler.reset();
  HostDiagnostics diagnostics=new HostDiagnostics();AudioSignals audio=new AudioSignals(diagnostics);
  TTVideoEngine engine=new TTVideoEngine();AJVoice voice=new AJVoice();
  voice.mNativeObject=987654321L; // A voice pointer is deliberately unrelated to its player.
  voice.mAudioTrack.session=engine.getIntOption(700);
  audio.nativePcm.install(NativePcmTest.class.getClassLoader(),new ArrayList<>());
  audio.nativePcm.watch(engine,10000);
  Method capture=NativePcm.class.getDeclaredMethod("capture",Object.class,Object[].class,boolean.class);capture.setAccessible(true);
  byte[] samples=new byte[2000];
  Object packet=capture.invoke(audio.nativePcm,voice,new Object[]{samples,20,1920,1,10040L},true);
  check(packet!=null,"owner uses host audio session through the real wrapper layer, without mPlayer reflection");
  check(NativePcm.field(packet,"engine")==engine,"samples belong only to matching audio session");
  ByteBuffer view=(ByteBuffer)NativePcm.field(packet,"buffer");
  check(view.remaining()==1920 && ((Long)NativePcm.field(packet,"pts"))==10040,"byte slice length and media timestamp preserved");
  view.get(0);check(samples[20]==0,"reading does not mutate PCM");
  voice.mAudioTrack.session++;
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,20,1920,1,10040L},true)==null,"unrelated player output is never guessed");
  voice.mAudioTrack.session--;
  SystemClock.now+=800;
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,20,1920,1,10040L},true)==null,"stale mapping is rejected");
  audio.nativePcm.watch(engine,11000);
  voice.mPCMFrameBuffer.position(100);voice.mPCMFrameBuffer.limit(2100);
  packet=capture.invoke(audio.nativePcm,voice,new Object[]{0,1920,1},false);
  check(packet!=null && voice.mPCMFrameBuffer.position()==100 && voice.mPCMFrameBuffer.limit()==2100,"direct PCM buffer position/limit belong to the host");
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,-1,1920,1,0L},true)==null,"invalid offsets rejected");
  voice.mAudioFormat=99;
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,0,1920,1,0L},true)==null,"compressed or unknown encoding rejected");
  voice.mAudioFormat=2;
  com.ss.ttm.player.TTPlayerClient client=(com.ss.ttm.player.TTPlayerClient)engine.mVideoEngine.mMediaPlayer.getPlayerClient();
  int oldSession=client.session;client.session+=100;
  audio.nativePcm.watch(engine,12000);
  voice.mAudioTrack.session=oldSession;
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,0,1920,1,12000L},true)==null,"replaced audio session is removed immediately");
  voice.mAudioTrack.session=client.session;
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,0,1920,1,12000L},true)!=null,"replacement audio session maps to the same Java engine");
  TTVideoEngine other=new TTVideoEngine();
  ((com.ss.ttm.player.TTPlayerClient)other.mVideoEngine.mMediaPlayer.getPlayerClient()).session=client.session;
  audio.nativePcm.watch(other,100);
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,0,1920,1,12000L},true)==null,"shared audio session never guesses between two active engines");
  check(diagnostics.events.stream().anyMatch(s->s.startsWith("native_pcm_session_ambiguous")),"ambiguous output has a distinct diagnostic");
  SystemClock.now+=650;audio.nativePcm.watch(engine,13000);
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,0,1920,1,13000L},true)!=null,"expired second owner no longer blocks the unique active engine");
  client.session=-1;audio.nativePcm.watch(engine,14000);
  check(capture.invoke(audio.nativePcm,voice,new Object[]{samples,0,1920,1,14000L},true)==null,"invalid session immediately revokes previous ownership");
  check(diagnostics.events.stream().anyMatch(s->s.startsWith("native_pcm_session_pending")),"not-yet-created audio output is diagnosed without reflection errors");
  check(diagnostics.events.stream().anyMatch(s->s.contains("via=TTVideoEngine.getIntOption(700)")),"ownership reports the exact host session API");
  client.session=3333;voice.mAudioTrack.session=3333;voice.result=960;
  audio.nativePcm.watch(engine,15000);
  Method write=AJVoice.class.getDeclaredMethod("write",byte[].class,int.class,int.class,int.class,long.class);
  ByteBuffer.wrap(samples).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(new short[1000]);
  for(int i=0;i<samples.length;i+=2){samples[i]=0;samples[i+1]=40;}
  de.robv.android.xposed.XposedBridge.invokeHooked(write,voice,new Object[]{samples,0,1920,1,15000L});
  check(audio.probe(engine).snapshot(SystemClock.now).fresh,"installed before/after write hooks deliver real PCM into AudioSignals");
  check(((Integer)NativePcm.field(audio.grid(engine),"count"))==1,"only successfully written partial PCM contributes its actual ten milliseconds");
  long observed=audio.probe(engine).snapshot(SystemClock.now).frames;
  for(int result:new int[]{0,-1}){
   voice.result=result;de.robv.android.xposed.XposedBridge.invokeHooked(write,voice,new Object[]{samples,0,1920,1,15010L});
  }
  check(audio.probe(engine).snapshot(SystemClock.now).frames==observed,"failed and empty writes never become audio evidence");
  voice.result=1920;voice.replacement=new android.media.AudioTrack();voice.replacement.session=3333;
  de.robv.android.xposed.XposedBridge.invokeHooked(write,voice,new Object[]{samples,0,1920,1,15010L});
  check(audio.probe(engine).snapshot(SystemClock.now).frames==observed,"track replacement during native write discards that packet");
  voice.replacement=null;voice.result=Integer.MAX_VALUE;audio.grid(engine).reset();audio.probe(engine).reset();
  for(int t=0;t<12000;t+=20){
   long pts=20000+t;
   if(t%200==0)audio.nativePcm.watch(engine,pts);
   ByteBuffer pcm=ByteBuffer.wrap(samples).order(java.nio.ByteOrder.nativeOrder());
   for(int sample=0;sample<960;sample++){
    double phase=(t+sample/48d)%500;
    double amp=0.02+0.65*Math.exp(-phase/18);
    pcm.putShort((short)(amp*30000*Math.sin(2*Math.PI*100*(t/1000d+sample/48000d))));
   }
   de.robv.android.xposed.XposedBridge.invokeHooked(write,voice,new Object[]{samples,0,1920,1,pts});
   SystemClock.now+=20;
  }
  BeatGrid.Result grid=audio.grid(engine).result();
  check(grid.reliable() && Math.abs(grid.bpm-120)<1,"actual write hooks, session ownership and PCM analysis recover 120 BPM without prebuilt beat data");
  voice.mSampleRate=44100;voice.mChannels=2;voice.result=Integer.MAX_VALUE;
  audio.grid(engine).reset();audio.probe(engine).reset();
  int totalFrames=0,packetIndex=0,lastTime=0;long baseClock=SystemClock.now;
  int[] packetFrames={441,1024,256,2048,882};
  while(totalFrames<44100*12){
   int size=Math.min(packetFrames[packetIndex++%packetFrames.length],44100*12-totalFrames);
   int time=(int)Math.round(totalFrames*1000d/44100);
   if(time-lastTime>=200 || totalFrames==0){audio.nativePcm.watch(engine,40000+time);lastTime=time;}
   byte[] stereo=new byte[size*4];ByteBuffer pcm=ByteBuffer.wrap(stereo).order(java.nio.ByteOrder.nativeOrder());
   for(int frame=0;frame<size;frame++){
    double seconds=(totalFrames+frame)/44100d;
    double phase=(seconds*1000)%500;
    double value=(0.02+0.65*Math.exp(-phase/18))*30000*Math.sin(2*Math.PI*100*seconds);
    pcm.putShort((short)value);pcm.putShort((short)(-0.7*value));
   }
   de.robv.android.xposed.XposedBridge.invokeHooked(write,voice,new Object[]{stereo,0,stereo.length,1,40000L+time});
   totalFrames+=size;SystemClock.now=baseClock+Math.round(totalFrames*1000d/44100);
  }
  BeatGrid.Result stereoGrid=audio.grid(engine).result();
  check(stereoGrid.reliable() && Math.abs(stereoGrid.bpm-120)<1,
   "44.1kHz stereo variable-sized writes recover true BPM, including opposite channel polarity");
  check(Math.abs(stereoGrid.endPositionMs-52000)<50,
   "fractional stereo packet durations preserve the song timeline despite rounded native timestamps");
  System.out.println("PASS "+checks+" native PCM ownership/buffer assertions");
 }
}
