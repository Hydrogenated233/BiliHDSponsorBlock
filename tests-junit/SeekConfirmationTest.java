package top.local.bilihdskip;
import org.junit.Test;
import static org.junit.Assert.*;
public class SeekConfirmationTest {
    @Test public void completedPositionSurvivesFourTimesPollingDrift(){SeekConfirmation c=new SeekConfirmation();long token=c.begin(60000);c.completed(token,60005);assertTrue(c.reached(61005));}
    @Test public void unconfirmedPlaybackCannotCrossFarTarget(){SeekConfirmation c=new SeekConfirmation();c.begin(60000);assertFalse(c.reached(61005));assertFalse(c.reached(30000));}
    @Test public void staleCompletionCannotConfirmReplacement(){SeekConfirmation c=new SeekConfirmation();long old=c.begin(60000);c.begin(100000);c.completed(old,100000);assertFalse(c.reached(110000));}
    @Test public void wrongArrivalDoesNotConfirm(){SeekConfirmation c=new SeekConfirmation();long token=c.begin(60000);c.completed(token,30000);assertFalse(c.reached(61005));}
    @Test public void clearInvalidatesCallbackAndPoll(){SeekConfirmation c=new SeekConfirmation();long token=c.begin(60000);c.clear();c.completed(token,60000);assertFalse(c.reached(60000));}
    @Test public void pausedOrMissingObserverRetainsClosePositionFallback(){SeekConfirmation c=new SeekConfirmation();c.begin(60000);assertTrue(c.reached(60000));assertTrue(c.reached(60750));assertFalse(c.reached(60751));}
}
