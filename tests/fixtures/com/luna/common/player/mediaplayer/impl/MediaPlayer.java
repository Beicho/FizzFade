package com.luna.common.player.mediaplayer.impl;
import com.luna.common.player.kit.impl.TTMediaPlayer;import com.luna.common.player.kit.api.AVInfo;import com.luna.common.player.queue.api.IPlayable;import com.luna.common.player.mediaplayer.*;import io.github.qishui.automix.TestRuntime;
public final class MediaPlayer {
 public final TTMediaPlayer player;public final IPlayable item;public final AVInfo info;public boolean prepared=true;public MediaResType reported;public Integer cachedPosition;
 /** As in 21.6.0: set only by the fresh-start path; innerPause pauses the engine only when it is set. */
 public boolean C;
 /** The play intent the fresh start sets (N3), cleared on reset. */
 public boolean A;
 public MediaPlayer(TTMediaPlayer p,IPlayable i,MediaResType reported,MediaResType source){player=p;item=i;this.reported=reported;info=new AVInfo(source);}
 public TTMediaPlayer k3(){return player;} public boolean isPrepared(){return prepared;}public IPlayable getPlayable(){return item;}public MediaResType getRealMediaResType(){return reported;}
 public AVInfo getAVInfo(PlayerSubType t){return info;} public int getDuration(){return player.e!=null?player.e.duration:player.b.getDuration();}public int getPlaybackTime(){return cachedPosition!=null?cachedPosition:player.b.getCurrentPlaybackTime();} public boolean isPlaying(){return player.b.playing;}
 /** Host order: already playing returns; an engine in PAUSED state is resumed (e4, flag untouched); otherwise the fresh start (g4) sets C. */
 public boolean play(PlayReason r){boolean result=playOriginal(r);TestRuntime.mediaPlayed(this);return result;}
 private boolean playOriginal(PlayReason r){if(isPlaying())return true;if(player.b.getPlaybackState()==2){player.b.play();return true;}A=true;C=true;player.b.play();return true;}
 /** Host order: returns true even when C is unset and the engine keeps playing. */
 public boolean pause(PauseReason r){TestRuntime.mediaPause(this);if(isPlaying()&&C)player.b.pause();return true;}
}
