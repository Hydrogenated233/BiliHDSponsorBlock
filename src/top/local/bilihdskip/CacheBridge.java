package top.local.bilihdskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** Module-process cache owner. File descriptors keep response bodies out of Binder parcels. */
final class CacheBridge {
    private static SegmentCache store;
    private static VideoLabelCache labels;
    private static SegmentCache store(Context context)throws IOException{
        if(store==null)store=new SegmentCache(new File(context.getNoBackupFilesDir(),"segment-responses"));
        return store;
    }
    private static VideoLabelCache labels(Context context)throws IOException{
        if(labels==null)labels=new VideoLabelCache(new File(context.getNoBackupFilesDir(),"video-labels"));
        return labels;
    }
    static synchronized Bundle call(Context context,String method,Bundle args)throws IOException{
        SharedPreferences p=SettingsProvider.prefs(context);Bundle result=new Bundle();
        long generation=p.getLong("cache_generation",0);result.putLong("generation",generation);
        if("cacheClear".equals(method)){
            if(!p.edit().putLong("cache_generation",generation+1).commit())throw new IOException("Cannot save cache generation");
            store(context).clear();labels(context).clear();
            result.putBoolean("cleared",true);return result;
        }
        if("cacheStats".equals(method)){SegmentCache.Stats stats=store(context).stats();VideoLabelCache.Stats labelStats=labels(context).stats();result.putInt("count",stats.entryCount);result.putLong("size",stats.sizeUnits);result.putInt("labelsCount",labelStats.entryCount);result.putLong("labelsSize",labelStats.sizeUnits);return result;}
        if(args==null)throw new IllegalArgumentException("Cache scope required");
        String server=args.getString("server",""),key=args.getString("prefix","");
        if(!key.matches("[a-f0-9]{4}"))throw new IllegalArgumentException("Invalid cache prefix");
        if("cacheRemove".equals(method)){store(context).remove(server,key);return result;}
        boolean enabled=Preferences.parse(p.getString("config","{}")).optBoolean("enableCache",true);
        if("cacheLabelsGet".equals(method)){
            String block=enabled?labels(context).get(server,key):null;if(block!=null)body(context,result,block);
        }else if("cacheLabelsPut".equals(method)){
            ParcelFileDescriptor descriptor=args.getParcelable("body");if(descriptor==null)throw new IllegalArgumentException("Response descriptor required");
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){if(enabled&&args.getLong("generation",-1)==generation)result.putBoolean("stored",labels(context).put(server,key,read(in)));}
        }else if("cacheLabelsRemove".equals(method)){labels(context).remove(server,key);
        }else if("cacheGet".equals(method)){
            SegmentCache.Response response=enabled?store(context).get(server,key):null;
            if(response!=null){
                result.putInt("status",response.status);result.putBoolean("ok",response.ok);result.putString("retryAfter",response.retryAfter);
                body(context,result,response.responseText);
            }
        }else if("cachePut".equals(method)){
            ParcelFileDescriptor descriptor=args.getParcelable("body");
            if(descriptor==null)throw new IllegalArgumentException("Response descriptor required");
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){
                if(enabled&&args.getLong("generation",-1)==generation){
                    String body=read(in);int status=args.getInt("status");
                    result.putBoolean("stored",store(context).put(server,key,new SegmentCache.Response(body,status,args.getBoolean("ok"),args.getString("retryAfter"))));
                }
            }
        }else throw new IllegalArgumentException("Unknown cache operation");
        return result;
    }
    private static void body(Context context,Bundle result,String text)throws IOException{
        File temporary=File.createTempFile("response-",".tmp",context.getCacheDir());
        try{try(OutputStream out=new FileOutputStream(temporary)){out.write(text.getBytes("UTF-8"));}result.putParcelable("body",ParcelFileDescriptor.open(temporary,ParcelFileDescriptor.MODE_READ_ONLY));}
        finally{if(!temporary.delete())temporary.deleteOnExit();}
    }
    static String read(InputStream in)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1){if(bytes.size()+count>2097152)throw new IOException("Response too large");bytes.write(buffer,0,count);}
        return bytes.toString("UTF-8");
    }
}
