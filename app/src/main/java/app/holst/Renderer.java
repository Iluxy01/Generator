package app.holst;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import java.util.List;

/**
 * Рисует штрихи на любом размере экрана. Масштаб считается только по ширине, а начало отсчёта по
 * высоте стоит в центре: на телефонах с другими пропорциями круг остаётся кругом.
 */
final class Renderer {
    static final float Q = 10000f;
    private static final PorterDuffXfermode CLEAR = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    private Renderer() {}

    static int qx(float x, float w) {
        return Math.round(x / w * Q);
    }

    static int qy(float y, float w, float h) {
        return Math.round((y - h / 2f) / w * Q);
    }

    static Paint newPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    static void draw(Canvas c, int[] pts, int count, int color, int width, boolean erase,
                     float w, float h, Paint p) {
        if (count < 2) return;
        p.setColor(color | 0xFF000000);
        p.setStrokeWidth(Math.max(1f, width / Q * w));
        p.setXfermode(erase ? CLEAR : null);
        float k = w / Q;
        float cy = h / 2f;
        if (count < 4) {
            c.drawPoint(pts[0] * k, pts[1] * k + cy, p);
            return;
        }
        Path path = new Path();
        float px = pts[0] * k;
        float py = pts[1] * k + cy;
        path.moveTo(px, py);
        for (int i = 2; i + 1 < count; i += 2) {
            float x = pts[i] * k;
            float y = pts[i + 1] * k + cy;
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x;
            py = y;
        }
        path.lineTo(px, py);
        c.drawPath(path, p);
    }

    static void draw(Canvas c, Stroke s, float w, float h, Paint p) {
        draw(c, s.pts, s.pts.length, s.color, s.width, s.erase, w, h, p);
    }

    static void drawAll(Canvas c, List<Stroke> strokes, float w, float h) {
        Paint p = newPaint();
        for (Stroke s : strokes) draw(c, s, w, h, p);
    }
}
