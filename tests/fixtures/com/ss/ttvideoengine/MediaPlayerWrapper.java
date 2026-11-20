package com.ss.ttvideoengine;
/** Real APK has a wrapper between TTVideoEngineInternal and TTPlayerClient. */
public final class MediaPlayerWrapper {
 public Object mClient=new com.ss.ttm.player.TTPlayerClient();
 public Object getPlayerClient(){return mClient;}
 public int getIntOption(int option,int fallback){return mClient instanceof com.ss.ttm.player.TTPlayerClient?((com.ss.ttm.player.TTPlayerClient)mClient).getIntOption(option,fallback):fallback;}
}
