import top.local.bilihdskip.Logic;
import java.util.*;
public class LogicTest {
 static int checks;
 static void eq(Object a,Object b){checks++;if(!a.equals(b))throw new AssertionError("expected "+b+" got "+a);}
 public static void main(String[] args)throws Exception{
  eq(Logic.bvid(170001),"BV17x411w7KC");eq(Logic.bvid(455017605),"BV1Q541167Qg");
  String body="[{\"videoID\":\"BV17x411w7KC\",\"segments\":["+
   "{\"cid\":123,\"actionType\":\"skip\",\"category\":\"sponsor\",\"segment\":[10,20]},"+
   "{\"cid\":\"123\",\"actionType\":\"skip\",\"category\":\"sponsor\",\"segment\":[18,25]},"+
   "{\"cid\":123,\"actionType\":\"poi\",\"category\":\"sponsor\",\"segment\":[5,8]},"+
   "{\"cid\":999,\"actionType\":\"skip\",\"category\":\"sponsor\",\"segment\":[2,9]},"+
   "{\"cid\":123,\"actionType\":\"skip\",\"votes\":-1,\"category\":\"sponsor\",\"segment\":[1,8]},"+
   "{\"cid\":123,\"actionType\":\"skip\",\"category\":\"sponsor\",\"segment\":[-1,8]},"+
   "{\"cid\":123,\"actionType\":\"skip\",\"category\":\"sponsor\",\"segment\":[9,3]}]},"+
   "{\"videoID\":\"COLLISION\",\"segments\":[{\"cid\":123,\"actionType\":\"skip\",\"category\":\"sponsor\",\"segment\":[0,999]}]}]";
  List<Logic.Segment> s=Logic.parse(body,"BV17x411w7KC",123);
  Set<String> cats=new HashSet<String>(Arrays.asList("sponsor"));
  eq(s.size(),2);eq(Logic.target(s,cats,10000,100000),25000L);
  eq(Logic.target(s,cats,25000,100000),-1L);eq(Logic.target(s,cats,9999,100000),-1L);
  eq(Logic.target(s,Collections.<String>emptySet(),12000,100000),-1L);
  eq(Logic.target(s,cats,12000,5000),-1L);eq(Logic.target(s,cats,12000,0),-1L);
  eq(Logic.parse(body,"BV17x411w7KC",999).size(),1);eq(Logic.parse(body,"NOT_FOUND",123).size(),0);
  eq(Logic.target(s,cats,12000,24900),24900L);
  eq(Logic.parse("[]","BV17x411w7KC",123).size(),0);
  System.out.println("PASS "+checks+" protocol and playback checks");
 }
}
