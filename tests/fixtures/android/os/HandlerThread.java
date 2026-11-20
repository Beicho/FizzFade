package android.os;
public final class HandlerThread {
 private final Looper looper;private boolean started;
 public HandlerThread(String name){looper=new Looper(name);}
 public void start(){if(started)throw new IllegalStateException("already started");started=true;}
 public Looper getLooper(){if(!started)throw new IllegalStateException("not started");return looper;}
 public boolean quitSafely(){looper.quitting=true;return true;}
}
