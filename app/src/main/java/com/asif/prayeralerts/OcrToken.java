package com.asif.prayeralerts;

/** One word recognised by OCR, with its position in the image. Pure Java (no Android types) so it can be unit tested. */
public final class OcrToken {
    public final String text;
    public final float cx, cy, width, height;
    /** Baseline angle in degrees (image coordinates, y down). */
    public final float angleDeg;

    public OcrToken(String text, float cx, float cy, float width, float height, float angleDeg) {
        this.text = text;
        this.cx = cx;
        this.cy = cy;
        this.width = width;
        this.height = height;
        this.angleDeg = angleDeg;
    }
}
