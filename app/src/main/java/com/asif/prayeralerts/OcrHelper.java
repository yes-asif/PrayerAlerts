package com.asif.prayeralerts;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Loads an image/PDF and runs Google ML Kit text recognition on the device (no internet). */
public final class OcrHelper {
    private OcrHelper() {}

    public interface Callback {
        void onResult(TimetableParser.Result result);
        void onError(Exception e);
    }

    /** Decodes the picked file into an upright bitmap large enough for OCR. Call off the main thread. */
    public static Bitmap load(Context c, Uri uri) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String type = cr.getType(uri);
        if (type != null && type.contains("pdf")) return renderPdf(cr, uri);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth <= 0) throw new IOException("Not an image");
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o); }
        if (bmp == null) throw new IOException("Could not decode image");

        int rotate = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
        } catch (Exception ignored) { }

        Matrix m = new Matrix();
        if (rotate != 0) m.postRotate(rotate);
        // Small screenshots (like a 600px-wide timetable) read far better when enlarged.
        int longSide = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longSide < 2000) { float s = 2000f / longSide; m.postScale(s, s); }
        if (!m.isIdentity()) {
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (t != bmp) bmp.recycle();
            bmp = t;
        }
        return bmp;
    }

    private static Bitmap renderPdf(ContentResolver cr, Uri uri) throws IOException {
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("Cannot open PDF");
            try (PdfRenderer r = new PdfRenderer(pfd); PdfRenderer.Page p = r.openPage(0)) {
                int w = 2200;
                int h = Math.round(w * (float) p.getHeight() / p.getWidth());
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            }
        }
    }

    public static void recognize(Bitmap bmp, Callback cb) {
        TextRecognizer rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        rec.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> {
                    try {
                        cb.onResult(TimetableParser.parse(toTokens(text), text.getText()));
                    } catch (Exception e) {
                        cb.onError(e);
                    } finally {
                        closeQuietly(rec);
                    }
                })
                .addOnFailureListener(e -> { closeQuietly(rec); cb.onError(e); });
    }

    private static void closeQuietly(TextRecognizer rec) {
        try { rec.close(); } catch (Exception ignored) { }
    }

    static List<OcrToken> toTokens(Text text) {
        List<OcrToken> out = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element el : line.getElements()) {
                    Rect r = el.getBoundingBox();
                    if (r == null) continue;
                    Point[] p = el.getCornerPoints();
                    float angle = 0, height = r.height(), width = r.width();
                    float cx = r.exactCenterX(), cy = r.exactCenterY();
                    if (p != null && p.length == 4) {
                        // Corner order: top-left, top-right, bottom-right, bottom-left.
                        angle = (float) Math.toDegrees(Math.atan2(p[1].y - p[0].y, p[1].x - p[0].x));
                        height = (float) Math.hypot(p[3].x - p[0].x, p[3].y - p[0].y);
                        width = (float) Math.hypot(p[1].x - p[0].x, p[1].y - p[0].y);
                        cx = (p[0].x + p[1].x + p[2].x + p[3].x) / 4f;
                        cy = (p[0].y + p[1].y + p[2].y + p[3].y) / 4f;
                    }
                    out.add(new OcrToken(el.getText(), cx, cy, width, height, angle));
                }
            }
        }
        return out;
    }
}
