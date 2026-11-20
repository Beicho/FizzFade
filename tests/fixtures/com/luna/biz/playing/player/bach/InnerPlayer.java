package com.luna.biz.playing.player.bach;
import com.luna.common.player.CompositePlayerInterceptor;import com.luna.common.player.bach.IInnerPlayerController;import com.luna.common.player.executor.IPlayerThreadExecutor;import com.luna.common.player.mediaplayer.*;import com.luna.common.player.queue.api.*;import com.luna.common.player.queue.mode.QueueLoopMode;import com.luna.biz.playing.player.bach.prerender.MediaPlayerWrapper;
public final class InnerPlayer implements IInnerPlayerController {
 public final android.os.Looper playerLooper=new android.os.Looper("host-player");
 private final android.os.Handler handler=new android.os.Handler(playerLooper);
 public IPlayerThreadExecutor executor=r->{if(android.os.Looper.myLooper()==playerLooper)r.run();else handler.post(r);};
 public int realPlayingChecks;

 public CompositePlayerInterceptor j=new CompositePlayerInterceptor(); public final MediaPlayerWrapper wrapper;public int changes;public boolean refuse;public QueueLoopMode loop=QueueLoopMode.LIST;public IQueueItem queuedOverride;public Long sleepTime;
 public InnerPlayer(MediaPlayerWrapper w){wrapper=w;} public IPlayerThreadExecutor getExecutor(){return executor;}public IQueueItem getNextQueueItem(){return queuedOverride!=null?queuedOverride:wrapper.next==null?null:wrapper.next.item;}
 public boolean isRealPlaying(){if(android.os.Looper.myLooper()!=playerLooper)throw new AssertionError("evaluation outside host player looper");realPlayingChecks++;return wrapper.isPlaying();}public QueueLoopMode getCurrentQueueLoopMode(){return loop;}public Long getSelectedSleepTimeS(){return sleepTime;}public Object getLeftSleepTimeData(){return null;}
 public boolean changeToNextQueueItem(PlayReason r){changes++;if(refuse)return false;wrapper.current.player.destroy();wrapper.current=wrapper.next;wrapper.current.player.b.setPlaybackParams(new com.ss.ttm.player.PlaybackParams().setSpeed(1));wrapper.next=null;return true;}public boolean play(PlayReason r){return wrapper.play(r);}
}
