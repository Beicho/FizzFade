package android.os;
public final class Looper {
 public final String name; boolean quitting;
 private static final Looper MAIN=new Looper("main");
 private static final ThreadLocal<Looper> current=new ThreadLocal<>();
 public Looper(String name){this.name=name;}
 public static Looper getMainLooper(){return MAIN;}
 public static Looper myLooper(){Looper value=current.get();return value==null?MAIN:value;}
 static void enter(Looper looper){current.set(looper);}
}
