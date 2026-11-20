package io.github.qishui.automix;
import de.robv.android.xposed.XposedBridge;
import com.ss.ttvideoengine.TTVideoEngine;
public final class TestRuntime {
 public static Object redirectPage(){return manager==null?null:manager.redirectPageRouting();}

 static MixManager manager;
 public static boolean lifecycle(Object engine,String name){try{return !XposedBridge.original && manager!=null && manager.defer(engine,TTVideoEngine.class.getDeclaredMethod(name));}catch(Exception e){throw new RuntimeException(e);}}
 public static boolean sourceClose(Object source){return manager!=null && manager.deferSourceClose(source);}
 public static void volume(Object engine,Object[]args){if(!XposedBridge.original&&manager!=null)manager.volume(engine,args);}
 public static boolean playbackParams(Object engine,Object params){return !XposedBridge.original&&manager!=null&&manager.playbackParams(engine,params);}
 public static void cancel(Object wrapper,String why){if(manager!=null)manager.cancelFor(wrapper,why);}
 /** The before hook on MediaPlayer.pause; false reproduces 0.5.x, which had no such hook. */
 public static boolean pauseRepair=true;
 public static void mediaPlayed(Object media){if(pauseRepair&&manager!=null)try{manager.restoreStart(media);}catch(Exception e){throw new RuntimeException(e);}}
 public static void mediaPause(Object media){if(pauseRepair&&manager!=null)try{manager.repairPause(media);}catch(Exception e){throw new RuntimeException(e);}}
}
