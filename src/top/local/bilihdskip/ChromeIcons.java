package top.local.bilihdskip;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.Xml;
import androidx.core.graphics.PathParser;
import org.xmlpull.v1.XmlPullParser;
import java.io.InputStream;
import java.util.*;

/** Draws the unmodified, path-only SVGs shipped by Chrome extension 0.17.1. */
final class ChromeIcons {
    static final String INFO="PlayerInfoIconSponsorBlocker.svg", START="PlayerStartIconSponsorBlocker.svg",
        STOP="PlayerStopIconSponsorBlocker.svg", UPLOAD="PlayerUploadIconSponsorBlocker.svg",
        DELETE="PlayerDeleteIconSponsorBlocker.svg", CANCEL="PlayerCancelSegmentIconSponsorBlocker.svg";
    private static final Map<String,List<Path>> paths=new HashMap<>();

    static Drawable create(Context host,String name){
        List<Path> shape=paths.get(name);
        if(shape==null){
            if(!Arrays.asList(INFO,START,STOP,UPLOAD,DELETE,CANCEL).contains(name))throw new IllegalArgumentException("Unknown player icon");
            shape=new ArrayList<>();
            try(InputStream input=host.createPackageContext("top.local.bilihdskip",0).getAssets().open("chrome-icons/"+name)){
                XmlPullParser parser=Xml.newPullParser();parser.setInput(input,"UTF-8");
                for(int event=parser.next();event!=XmlPullParser.END_DOCUMENT;event=parser.next()){
                    if(event!=XmlPullParser.START_TAG)continue;
                    if("svg".equals(parser.getName())&&!"4 4 80 80".equals(parser.getAttributeValue(null,"viewBox")))throw new IllegalArgumentException("Unexpected icon viewport");
                    if("path".equals(parser.getName())){
                        Path path=PathParser.createPathFromPathData(parser.getAttributeValue(null,"d"));
                        if(path==null)throw new IllegalArgumentException("Invalid icon path");
                        path.setFillType("evenodd".equals(parser.getAttributeValue(null,"fill-rule"))?Path.FillType.EVEN_ODD:Path.FillType.WINDING);shape.add(path);
                    }
                }
                if(shape.isEmpty())throw new IllegalArgumentException("Empty player icon");
            }catch(Exception error){throw new IllegalStateException("Unable to load bundled Chrome icon",error);}
            paths.put(name,shape);
        }
        final List<Path> immutableShape=shape;
        return new Drawable(){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            {paint.setColor(Color.WHITE);}
            @Override public void draw(Canvas canvas){Rect bounds=getBounds();int saved=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/80f,bounds.height()/80f);canvas.translate(-4,-4);for(Path path:immutableShape)canvas.drawPath(path,paint);canvas.restoreToCount(saved);}
            @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
            @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
            @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
            @Override public int getIntrinsicWidth(){return 80;}
            @Override public int getIntrinsicHeight(){return 80;}
        };
    }
}
