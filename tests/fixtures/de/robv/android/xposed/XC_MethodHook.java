package de.robv.android.xposed;
public class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
 protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
 public class Unhook { }
 public static class MethodHookParam {
  public Object thisObject;public Object[] args;Object result;Throwable error;
  public boolean hasThrowable(){return error!=null;}public Object getResult(){return result;}
 }
}
