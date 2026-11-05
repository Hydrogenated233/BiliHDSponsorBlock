package top.local.bilihdskip;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Bounded public GETs, prefix single-flight and module-owned persistent label cache. */
final class LabelRequests implements NativeCards.Source {
    private final Context context;
    private final ExecutorService network=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(64));
    private final Map<String,List<Pending>> flights=new HashMap<>();
    private volatile JSONObject config=Preferences.defaults();
    private volatile long generation;
    private final NativeCards.Diagnostics diagnostics;
    private long requests,hits,errors;
    LabelRequests(Context context,NativeCards.Diagnostics diagnostics){this.context=context;this.diagnostics=diagnostics;}
    void configure(JSONObject next,long cacheGeneration){config=next;generation=cacheGeneration;}
    private static final class Pending {
        final String video;final NativeCards.Result result;
        Pending(String video,NativeCards.Result result){this.video=video;this.result=result;}
    }
    @Override public void lookup(long aid,NativeCards.Result result){
        final String video,server,prefix,key;final boolean cached;final long cacheGeneration=generation;
        try{video=Logic.bvid(aid);if(!video.matches("BV[0-9A-Za-z]{10}"))throw new IllegalArgumentException();JSONObject snapshot=config;server=snapshot.optString("serverAddress","https://bsbsb.top").replaceAll("/+$","");cached=snapshot.optBoolean("enableCache",true);prefix=ApiClient.hashPrefix(video);key=server+":"+prefix+":"+cacheGeneration+":"+cached;}
        catch(Exception e){result.complete(null);return;}
        synchronized(this){List<Pending> existing=flights.get(key);if(existing!=null){if(existing.size()<96)existing.add(new Pending(video,result));else result.complete(null);return;}List<Pending> pending=new ArrayList<>();pending.add(new Pending(video,result));flights.put(key,pending);}
        try{network.execute(()->fetch(key,server,prefix,cached,cacheGeneration));}
        catch(RejectedExecutionException e){finish(key,null);}
    }
    private void fetch(String key,String server,String prefix,boolean cached,long expectedGeneration){
        String block=null;long admitted=-1;Bundle scope=new Bundle();scope.putString("server",server);scope.putString("prefix",prefix);
        try{
            if(cached)try{Bundle found=context.getContentResolver().call(SettingsProvider.URI,"cacheLabelsGet",null,scope);
                if(found!=null&&!found.getBoolean("failed")){admitted=found.getLong("generation",-1);ParcelFileDescriptor descriptor=found.getParcelable("body");if(descriptor!=null)try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){block=CacheBridge.read(in);synchronized(this){hits++;diagnostics.count("label_cache_hits",hits);}}}
            }catch(Exception ignored){}
            if(block==null){synchronized(this){requests++;diagnostics.count("label_requests",requests);}ApiClient.Result response=ApiClient.request(server,"GET","/api/videoLabels/"+prefix,null);
                if(response.status!=200)throw new IOException("Label query failed");block=LabelBlocks.parse(response.body).toString();
                if(cached&&admitted==expectedGeneration)try{File temp=File.createTempFile("sb-label-",".json",context.getCacheDir());try{try(OutputStream out=new FileOutputStream(temp)){out.write(block.getBytes("UTF-8"));}try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY)){Bundle insert=new Bundle(scope);insert.putLong("generation",admitted);insert.putParcelable("body",descriptor);context.getContentResolver().call(SettingsProvider.URI,"cacheLabelsPut",null,insert);}}finally{if(!temp.delete())temp.deleteOnExit();}}catch(Exception ignored){}
            }
        }catch(Exception e){block=null;synchronized(this){errors++;diagnostics.count("label_query_errors",errors);}}
        finish(key,block);
    }
    private void finish(String key,String block){
        List<Pending> pending;synchronized(this){pending=flights.remove(key);}if(pending==null)return;
        for(Pending item:pending){String[] categories=null;try{if(block!=null)categories=LabelBlocks.select(block,item.video);}catch(Exception ignored){}try{item.result.complete(categories);}catch(Exception ignored){}}
    }
}
