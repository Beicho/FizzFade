package com.luna.common.player;
import com.luna.common.player.queue.api.IPlayable;
import com.luna.common.player.mediaplayer.api.AutoChangeToNextInterceptResult;
import com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller.AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1;
public final class CompositePlayerInterceptor {
 public int calls,policyCalls;public final AutoChangeToNextInterceptResult result=new AutoChangeToNextInterceptResult();
 public AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1 pageRouter; public Runnable action;
 public AutoChangeToNextInterceptResult onInterceptAutoChangeToNextPlayable(IPlayable item){
  calls++;if(pageRouter!=null){AutoChangeToNextInterceptResult r=pageRouter.onInterceptAutoChangeToNextPlayable(item);if(r.isIntercepted())return r;}
  policyCalls++;if(action!=null)action.run();return result;
 }
}
