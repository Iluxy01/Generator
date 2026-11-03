package app.holst;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackTest {
    private static LiveChunk chunk(int seq, int a, int b, int... pts) {
        return new LiveChunk("x", 1, 100, false, seq, pts, a, b);
    }

    private static void run(Playback p, int ms, long now) {
        for (int t = 0; t < ms; t += 16) p.advance(16, now + t);
    }

    @Test
    public void revealsGraduallyAndHoldsAtEndOfData() {
        Playback p = new Playback("x", 1, 100, false);
        p.addChunk(chunk(0, 0, 200, 0, 0, 100, 0, 200, 0), 0);
        run(p, 180, 0);
        assertEquals("задержка на старте", 0, p.visibleIndex());
        run(p, 100, 0);
        assertTrue(p.tailX() > 0 && p.tailX() < 200);
        run(p, 2000, 0);
        assertEquals(200f, p.tailX(), 0.01f);
        assertFalse("без готового штриха проигрывание ждёт данные", p.isFinished());
    }

    @Test
    public void outOfOrderChunksAreReordered() {
        Playback p = new Playback("x", 1, 100, false);
        p.addChunk(chunk(1, 100, 200, 100, 0, 200, 0), 0);
        assertFalse("второй кусок ждёт первый", p.hasPoints());
        p.addChunk(chunk(0, 0, 100, 0, 0, 100, 0), 10);
        assertTrue(p.hasPoints());
        run(p, 2000, 10);
        assertEquals(200f, p.tailX(), 0.01f);
    }

    @Test
    public void lostChunkDoesNotBlockForever() {
        Playback p = new Playback("x", 1, 100, false);
        p.addChunk(chunk(0, 0, 100, 0, 0, 100, 0), 0);
        p.addChunk(chunk(2, 200, 300, 200, 0, 300, 0), 10);
        run(p, 1000, 1000);
        assertEquals(300f, p.tailX(), 0.01f);
    }

    @Test
    public void finalStrokeCompletesEvenWithGaps() {
        Playback p = new Playback("x", 1, 100, false);
        p.addChunk(chunk(0, 0, 100, 0, 0, 100, 0), 0);
        run(p, 400, 0);
        p.setFinal(new int[] {0, 0, 100, 0, 200, 0, 300, 0}, 400, 500);
        run(p, 3000, 500);
        assertTrue(p.isFinished());
        assertEquals(3, p.visibleIndex());
    }

    @Test
    public void strokeWithoutLiveChunksPlaysQuickly() {
        Playback p = new Playback("x", 1, 100, false);
        p.setFinal(new int[] {0, 0, 100, 0, 200, 0}, 20000, 0);
        run(p, 8000, 0);
        assertTrue("длинный штрих, пришедший целиком, не тянется 20 секунд", p.isFinished());
    }

    @Test
    public void singleDotFinishes() {
        Playback p = new Playback("x", 1, 100, false);
        p.setFinal(new int[] {5, 5}, 0, 0);
        run(p, 200, 0);
        assertTrue(p.isFinished());
    }
}
