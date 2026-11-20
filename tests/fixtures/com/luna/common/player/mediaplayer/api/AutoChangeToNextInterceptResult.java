package com.luna.common.player.mediaplayer.api;
public final class AutoChangeToNextInterceptResult {
 public boolean blocked,willPlay;public Integer errorCode;
 public AutoChangeToNextInterceptResult(){} public AutoChangeToNextInterceptResult(boolean blocked,boolean willPlay,Integer errorCode){this.blocked=blocked;this.willPlay=willPlay;this.errorCode=errorCode;}
 public boolean isIntercepted(){return blocked;}public boolean getWillPlay(){return willPlay;}public Integer getErrorCode(){return errorCode;}
}
