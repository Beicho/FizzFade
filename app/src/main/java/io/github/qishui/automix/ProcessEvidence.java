package io.github.qishui.automix;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

/** Small local journal plus Android's own exit record; does not replace crash handlers. */
final class ProcessEvidence {
    private final SharedPreferences prefs;
    private final HostDiagnostics diagnostics;
    private volatile String stage="idle";
    ProcessEvidence(Context context,HostDiagnostics diagnostics) {
        this.diagnostics=diagnostics;
        prefs=context.getSharedPreferences("qishui_automix_process",Context.MODE_PRIVATE);
        int previous=prefs.getInt("pid",0);
        String old=prefs.getString("stage","none");
        if (previous!=0 && previous!=Process.myPid() && !old.equals("idle")) {
            diagnostics.event("previous_mix_interrupted","pid="+previous+" stage="+old+" at="+prefs.getLong("at",0));
        }
        if (Build.VERSION.SDK_INT>=30) try {
            ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager!=null) for (ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,5)) {
                if (!context.getPackageName().equals(exit.getProcessName())) continue;
                diagnostics.event("host_exit_history","pid="+exit.getPid()+" reason="+exit.getReason()
                    +" status="+exit.getStatus()+" at="+exit.getTimestamp()+" description="+DiagnosticText.safe(exit.getDescription(),800));
            }
        } catch (Throwable error) { diagnostics.error("host_exit_history_unavailable",error); }
        mark("idle");
    }
    synchronized void mark(String value) {
        if (value.equals(stage) && prefs.getInt("pid",0)==Process.myPid()) return;
        stage=value;
        if (!prefs.edit().putInt("pid",Process.myPid()).putString("stage",value)
            .putLong("at",System.currentTimeMillis()).commit()) diagnostics.event("process_journal_failed",value);
    }
}
