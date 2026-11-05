package top.local.bilihdskip;
import org.junit.Test;
import static org.junit.Assert.*;
public class CardBlurTest {
    @Test public void uniformAndAlphaArePreserved() {
        assertArrayEquals(new int[] {0x80ff0301,0x80ff0301,0x80ff0301},
                CardBlur.blur(new int[] {0x80ff0301,0x80ff0301,0x80ff0301},3,1,4));
    }
    @Test public void edgesClampAndSourceIsUnchanged() {
        int[] original={0xff000000,0xffffffff,0xff000000};
        assertArrayEquals(new int[] {0xff555555,0xff555555,0xff555555},CardBlur.blur(original,3,1,1));
        assertArrayEquals(new int[] {0xff000000,0xffffffff,0xff000000},original);
        assertNotSame(original,CardBlur.blur(original,3,1,0));
    }
    @Test public void boundedDimensionsRejectInvalidSnapshots() {
        try { CardBlur.blur(new int[193],193,1,1); fail(); } catch(IllegalArgumentException expected) {}
        try { CardBlur.blur(new int[2],1,1,1); fail(); } catch(IllegalArgumentException expected) {}
    }
}
