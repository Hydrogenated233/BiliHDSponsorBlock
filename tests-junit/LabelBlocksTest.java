package top.local.bilihdskip;
import org.junit.Test;
import static org.junit.Assert.*;
public class LabelBlocksTest {
    @Test public void collisionIsSelectedByExactBv()throws Exception {String block=LabelBlocks.parse("[{\"videoID\":\"BV14741127BN\",\"segments\":[{\"category\":\"sponsor\"}]},{\"videoID\":\"BV1PL4y1j7Dx\",\"segments\":[{\"category\":\"selfpromo\"}]}]").toString();assertArrayEquals(new String[]{"sponsor"},LabelBlocks.select(block,"BV14741127BN"));assertEquals(0,LabelBlocks.select(block,"BV1111111111").length);}
    @Test public void firstUnrecognisedCategoryDoesNotPromoteSecond()throws Exception {String block=LabelBlocks.parse("[{\"videoID\":\"BV14741127BN\",\"segments\":[{\"category\":\"future\"},{\"category\":\"sponsor\"}]}]").toString();assertArrayEquals(new String[]{"future"},LabelBlocks.select(block,"BV14741127BN"));}
    @Test public void emptyMissingMalformedRowsAreOmitted()throws Exception {assertEquals("{}",LabelBlocks.parse("[{\"videoID\":\"bad\",\"segments\":[{\"category\":\"sponsor\"}]},{\"videoID\":\"BV14741127BN\",\"segments\":[]},null]").toString());}
    @Test public void lastTruthyDuplicateMatchesObjectFromEntries()throws Exception {assertEquals("selfpromo",LabelBlocks.parse("[{\"videoID\":\"BV14741127BN\",\"segments\":[{\"category\":\"sponsor\"}]},{\"videoID\":\"BV14741127BN\",\"segments\":[{\"category\":\"selfpromo\"}]}]").getString("BV14741127BN"));}
}
