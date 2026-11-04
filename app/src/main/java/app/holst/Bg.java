package app.holst;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Подложка под рисунками и запись настоящих обоев экрана блокировки. */
final class Bg {
    private static final String TAG = "Holst";
    static final int SHEET = 0xFF16161B;

    private Bg() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "bg.jpg");
    }

    /** Реальный размер экрана в портретной ориентации. */
    static int[] screen(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        Display d = c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(dm);
        return new int[] {Math.min(dm.widthPixels, dm.heightPixels), Math.max(dm.widthPixels, dm.heightPixels)};
    }

    /**
     * Размер картинки для обоев с учётом мощности телефона. Пропорции экрана сохраняются, система
     * сама растянет картинку, а памяти и работы нужно гораздо меньше.
     */
    static int[] targetSize(Context c) {
        int[] s = screen(c);
        int maxWidth = Device.get(c).maxWidth;
        if (s[0] <= maxWidth) return s;
        return new int[] {maxWidth, Math.round(s[1] * (float) maxWidth / s[0])};
    }

    /** Выбор картинки из галереи. */
    static Intent pickIntent() {
        return Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
    }

    static void useBlankSheet(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    static boolean hasPicture(Context c) {
        return file(c).exists();
    }

    /** Обрезает выбранную картинку под экран и сохраняет себе: исходник может потом исчезнуть. */
    static boolean importPicture(Context c, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    orientation = new ExifInterface(in).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, "no exif: " + e);
            }
            int[] size = targetSize(c);
            int sample = 1;
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (longSide / (sample * 2) >= size[1]) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap src;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in, null, opts);
            }
            if (src == null) return false;
            src = rotate(src, orientation);
            Bitmap out = cropTo(src, size[0], size[1]);
            try (FileOutputStream fos = new FileOutputStream(file(c))) {
                out.compress(Bitmap.CompressFormat.JPEG, 94, fos);
            }
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "import failed", e);
            return false;
        }
    }

    private static Bitmap rotate(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            default:
                return src;
        }
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private static Bitmap cropTo(Bitmap src, int w, int h) {
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int cw = Math.round(w / scale);
        int ch = Math.round(h / scale);
        int left = (src.getWidth() - cw) / 2;
        int top = (src.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new Rect(left, top, left + cw, top + ch),
                new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    /** Подложка нужного размера или null, если выбран чистый лист. */
    static Bitmap load(Context c, int w, int h) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            Bitmap src = BitmapFactory.decodeFile(f.getPath());
            if (src == null) return null;
            return src.getWidth() == w && src.getHeight() == h ? src : cropTo(src, w, h);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "bg load failed", e);
            return null;
        }
    }

    /** Подложка и рисунки на одной картинке — ровно то, что должно быть на обоях. */
    static Bitmap compose(Context c, List<Stroke> strokes, int w, int h) {
        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Renderer.drawAll(new Canvas(layer), strokes, w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Bitmap bg = load(c, w, h);
        if (bg != null) canvas.drawBitmap(bg, 0, 0, null);
        else canvas.drawColor(SHEET);
        canvas.drawBitmap(layer, 0, 0, null);
        layer.recycle();
        return out;
    }

    /** Кладёт картинку в настоящие обои экрана блокировки. Обои рабочего стола не трогает. */
    static boolean writeLockWallpaper(Context c, List<Stroke> strokes) {
        try {
            int[] size = targetSize(c);
            Bitmap out = compose(c, strokes, size[0], size[1]);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
            out.compress(Bitmap.CompressFormat.JPEG, 93, bytes);
            out.recycle();
            WallpaperManager.getInstance(c).setStream(new ByteArrayInputStream(bytes.toByteArray()),
                    null, true, WallpaperManager.FLAG_LOCK);
            Log.d(TAG, "lock wallpaper updated, strokes=" + strokes.size());
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "wallpaper write failed", e);
            return false;
        }
    }

    /** Экран блокировки снова показывает обои рабочего стола. */
    static void restoreLockWallpaper(Context c) {
        try {
            WallpaperManager.getInstance(c).clear(WallpaperManager.FLAG_LOCK);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "wallpaper restore failed", e);
        }
    }
}
