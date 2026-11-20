package de.robv.android.xposed;
import java.lang.reflect.*;
public final class XposedHelpers {
 public static Object callMethod(Object receiver,String name,Object...args){
  try{for(Method m:receiver.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(receiver,args);}throw new NoSuchMethodException(name);}
  catch(Throwable e){throw new RuntimeException(e);}
 }
}
