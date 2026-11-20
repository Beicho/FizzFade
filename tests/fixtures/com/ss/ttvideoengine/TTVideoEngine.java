package com.ss.ttvideoengine;
import android.os.SystemClock;import io.github.qishui.automix.TestRuntime;import com.luna.common.player.datasource.StreamMediaDataSource;
public final class TTVideoEngine {
 public final EngineImpl mVideoEngine=new EngineImpl();
 public static final class EngineImpl {public final MediaPlayerWrapper mMediaPlayer=new MediaPlayerWrapper();}
 public Runnable releaseCallback;
 public float tempo=1,pitch=1;public boolean seekSuccess=true; public int duration=180000,position,releases,pauses;public long started;public boolean playing,mute,paused;public float volume=1;public StreamMediaDataSource source;
 public void play(){if(!playing){playing=true;started=SystemClock.now;}paused=false;}
 public int getPlaybackState(){return playing?1:paused?2:0;}
 public int getCurrentPlaybackTime(){return position+(playing?(int)((SystemClock.now-started)*tempo):0);}public int getDuration(){return duration;}
 public int getIntOption(int option){return option==700?mVideoEngine.mMediaPlayer.getIntOption(961,-1):-1;}
 public float getVolume(){return volume;}public void setVolume(float l,float r){Object[] a={l,r};TestRuntime.volume(this,a);volume=(Float)a[0];}
 public boolean isMute(){return mute;}public void pause(){if(TestRuntime.lifecycle(this,"pause"))return;position=getCurrentPlaybackTime();playing=false;paused=true;pauses++;}
 public void stop(){if(TestRuntime.lifecycle(this,"stop"))return;pause();paused=false;}public void release(){if(TestRuntime.lifecycle(this,"release"))return;playing=false;paused=false;releases++;
 if(releaseCallback!=null){Thread callback=new Thread(releaseCallback);callback.start();try{callback.join(1000);}catch(InterruptedException e){throw new RuntimeException(e);}if(callback.isAlive())throw new AssertionError("native callback blocked by transaction monitor");}
 if(source!=null)source.close();}
 public void seekTo(int ms,SeekCompletionListener callback){if(seekSuccess){position=ms;started=SystemClock.now;}callback.onCompletion(seekSuccess);}
 public void setPlaybackParams(com.ss.ttm.player.PlaybackParams params){if(TestRuntime.playbackParams(this,params))return;if(params!=null&&params.speed!=-1){position=getCurrentPlaybackTime();started=SystemClock.now;tempo=params.speed;}}
 public void releaseAsync(){if(TestRuntime.lifecycle(this,"releaseAsync"))return;release();}
}
