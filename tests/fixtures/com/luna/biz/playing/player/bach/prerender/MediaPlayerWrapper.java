package com.luna.biz.playing.player.bach.prerender;
import com.luna.common.player.mediaplayer.impl.MediaPlayer;import com.luna.common.player.mediaplayer.*;import com.luna.common.player.queue.api.IPlayable;import com.luna.common.player.prerender.PreRenderTrigger;import io.github.qishui.automix.TestRuntime;
public final class MediaPlayerWrapper {
 public MediaPlayer current,next;public final Provider z=new Provider();public int requests;public boolean reject;
 public MediaPlayer H2(){return current;}public MediaPlayer I2(){return next;}
 public boolean c3(PreRenderTrigger trigger,IPlayable item){requests++;return !reject;}
 public void onPlaybackTimeChanged(IPlayable i,long t){} public void onPlaybackTimeChangedFast(IPlayable i,long t){}
 public boolean play(PlayReason r){return current.play(r);}public boolean isPlaying(){return current.isPlaying();}public float getPlaybackSpeed(){return 1;}
 public final class Provider {
  public void A0(com.luna.common.player.mediaplayer.api.PreRenderDiscardReason reason){if(next!=null){next.player.destroy();next=null;}}
 }
 public void pause(){TestRuntime.cancel(this,"pause");current.pause(PauseReason.PlayPageScreen.a);}
}
