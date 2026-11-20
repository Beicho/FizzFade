package de.robv.android.xposed;
import java.lang.reflect.*;
public final class XposedBridge {
 public static boolean original;
 public static class Unused { }
 private static final java.util.Map<Member,XC_MethodHook> hooks=new java.util.HashMap<>();
 public static XC_MethodHook.Unhook hookMethod(Member method,XC_MethodHook hook){hooks.put(method,hook);return hook.new Unhook();}
 public static Object invokeHooked(Method method,Object receiver,Object[] args)throws Throwable {
  XC_MethodHook hook=hooks.get(method);
  XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();param.thisObject=receiver;param.args=args;
  if(hook!=null)hook.beforeHookedMethod(param);
  try{param.result=method.invoke(receiver,param.args);}catch(InvocationTargetException e){param.error=e.getCause();}
  if(hook!=null)hook.afterHookedMethod(param);
  if(param.error!=null)throw param.error;return param.result;
 }
 public static Object invokeOriginalMethod(Method m,Object receiver,Object[]args)throws Throwable {boolean prior=original;original=true;try{return m.invoke(receiver,args);}catch(InvocationTargetException e){throw e.getCause();}finally{original=prior;}}
}
