package app.wraith.client;
import android.app.job.*;
import android.content.*;
import java.util.concurrent.Future;
public final class UpdateJob extends JobService {
    private Future<?> work;
    public static void schedule(Context c){try{JobInfo job=new JobInfo.Builder(310,new ComponentName(c,UpdateJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(60*60*1000L).setPersisted(true).build();c.getSystemService(JobScheduler.class).schedule(job);}catch(Exception e){AppLog.add("Планировщик: "+e.getMessage());}}
    @Override public boolean onStartJob(JobParameters p){work=WraithApp.IO.submit(()->{try{if(Store.get(this).settings().optBoolean("autoUpdate",true))Subscriptions.updateAll(this,true);}catch(Exception e){AppLog.add("Фоновое обновление: "+Subscriptions.safeError(e));}finally{jobFinished(p,false);}});return true;}
    @Override public boolean onStopJob(JobParameters p){if(work!=null)work.cancel(true);return true;}
}
