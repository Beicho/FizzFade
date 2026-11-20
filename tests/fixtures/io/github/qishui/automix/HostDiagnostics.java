package io.github.qishui.automix;
import android.os.Bundle;import java.util.*;
final class HostDiagnostics {
 final List<String> events=new ArrayList<>(); final Bundle config=new Bundle(); Runnable monitor; boolean configAvailable=true;
 HostDiagnostics(){config.put("enabled",true);config.put("duration",6000);config.put("smooth",true);config.put("prerender",true);config.put("adaptive",false);}
 void beatCount(int count) {}
 void checkpoint(String stage) {event("checkpoint", stage);}
 Boolean cached; long blocked;
 Bundle cachedConfig(){return (cached!=null?cached:configAvailable)?config:null;} long ipcBlockedMs(){return blocked;}
 Bundle readConfig(){return configAvailable?config:null;} void monitor(Runnable r){monitor=r;} void status(String s,int c){}
 void event(String tag,String s){events.add(tag+" "+s);} void throttled(String t,String s,long ms){event(t,s);}
 void error(String t,Throwable e){event(t,e.toString());} void errorThrottled(String t,Throwable e,long ms){error(t,e);}
}
