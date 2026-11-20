package android.os;
import java.util.*;
public final class Handler {
 private static final List<Task> tasks=new ArrayList<>();
 public static boolean suspendMain;
 private final Looper looper;
 public Handler(Looper l){looper=l;}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){if(looper.quitting)return false;tasks.add(new Task(this,r,SystemClock.now+delay));return true;}
 public void removeCallbacks(Runnable r){tasks.removeIf(t->t.owner==this&&t.r==r);}
 public void removeCallbacksAndMessages(Object token){tasks.removeIf(t->t.owner==this);}
 public static void drain(){int guard=10000;while(guard-->0){Task found=null;for(Task t:tasks)if(t.at<=SystemClock.now&&(!suspendMain||t.owner.looper!=Looper.getMainLooper())&&(found==null||t.at<found.at))found=t;if(found==null)return;tasks.remove(found);if(found.owner.looper.quitting)continue;Looper previous=Looper.myLooper();Looper.enter(found.owner.looper);try{found.r.run();}finally{Looper.enter(previous);}}throw new AssertionError("infinite handler loop");}
 public static void reset(){tasks.clear();suspendMain=false;SystemClock.now=100000;}
 static final class Task {final Handler owner;final Runnable r;final long at;Task(Handler owner,Runnable r,long at){this.owner=owner;this.r=r;this.at=at;}}
}
