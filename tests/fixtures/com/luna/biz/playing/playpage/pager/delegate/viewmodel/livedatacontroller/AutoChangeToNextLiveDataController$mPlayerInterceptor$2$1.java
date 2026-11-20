package com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller;
import com.luna.common.player.queue.api.IPlayable;
import com.luna.common.player.mediaplayer.api.AutoChangeToNextInterceptResult;
import io.github.qishui.automix.TestRuntime;
/** Same ordering as the analyzed page router: dispatch first, return consumed afterward. */
public final class AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1 {
 public Runnable dispatch; public int pageChanges;
 public AutoChangeToNextInterceptResult onInterceptAutoChangeToNextPlayable(IPlayable item){
  Object redirect=TestRuntime.redirectPage();if(redirect!=null)return (AutoChangeToNextInterceptResult)redirect;
  pageChanges++;if(dispatch!=null)dispatch.run();return new AutoChangeToNextInterceptResult(true,true,2100224);
 }
}
